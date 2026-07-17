package bookparser

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/net/html"
)

// MaxEpubEntrySize caps the number of bytes we are willing to read from a
// single decompressed entry inside an epub (container.xml, OPF, NCX/nav, or
// manifest XHTML). The outer-file guard MaxEpubSize limits the compressed
// archive size, but a small compressed file can still decompress into a
// multi-GB entry (zip bomb). 16 MiB is well above any legitimate OPF/NCX/XHTML
// payload while keeping worst-case memory bounded.
const MaxEpubEntrySize = 16 * 1024 * 1024

// readCapped reads at most max+1 bytes from r so the caller can detect
// truncation. If the underlying reader yields more than max bytes it returns
// an error wrapping ErrTooLarge instead of allocating the full body.
func readCapped(r io.Reader, max int64) ([]byte, error) {
	limited := io.LimitReader(r, max+1)
	b, err := io.ReadAll(limited)
	if err != nil {
		return nil, err
	}
	if int64(len(b)) > max {
		return nil, fmt.Errorf("%w: epub entry exceeds %d bytes", ErrTooLarge, max)
	}
	return b, nil
}

func parseEpub(path string, info os.FileInfo) (*Book, error) {
	if info.Size() > MaxEpubSize {
		return nil, fmt.Errorf("%w: %d bytes", ErrTooLarge, info.Size())
	}
	zr, err := zip.OpenReader(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidEpub, err)
	}
	defer zr.Close()

	opfPath, err := readContainerOpfPath(&zr.Reader)
	if err != nil {
		return nil, err
	}
	opf, err := readZipFile(zr.File, opfPath)
	if err != nil {
		return nil, fmt.Errorf("%w: missing OPF: %v", ErrInvalidEpub, err)
	}
	opfData, err := parseOpf(opf)
	if err != nil {
		return nil, err
	}
	if len(opfData.spine) == 0 {
		return nil, fmt.Errorf("%w: empty spine", ErrInvalidEpub)
	}

	chapters := make([]Chapter, 0, len(opfData.spine))
	for i, idref := range opfData.spine {
		chapters = append(chapters, Chapter{
			Title:      fmt.Sprintf("第 %d 章", i+1),
			Index:      i,
			ManifestID: idref,
		})
	}

	opfDir := filepath.ToSlash(filepath.Dir(opfPath))
	toc, _ := readToc(&zr.Reader, opfData.tocID, opfData.manifest, opfDir)
	for href, title := range toc {
		for i := range chapters {
			idref := chapters[i].ManifestID
			if h, ok := opfData.manifest[idref]; ok && normalizeHref(h) == normalizeHref(href) {
				chapters[i].Title = title
			}
		}
	}

	book := &Book{
		Path:     path,
		Format:   "epub",
		Title:    opfData.title,
		Chapters: chapters,
		ModTime:  info.ModTime(),
	}
	book.epubManifest = opfData.manifest
	book.epubOpfDir = opfDir
	return book, nil
}

func (b *Book) epubChapterText(idx int) (string, error) {
	c := b.Chapters[idx]
	href, ok := b.epubManifest[c.ManifestID]
	if !ok {
		return "[本章节解析失败]", nil
	}
	fullPath := joinZipPath(b.epubOpfDir, href)
	zr, err := zip.OpenReader(b.Path)
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	defer zr.Close()
	rc, err := zr.Open(fullPath)
	if err != nil {
		return "[本章节解析失败]", nil
	}
	defer rc.Close()
	body, err := readCapped(rc, MaxEpubEntrySize)
	if err != nil {
		return "[本章节解析失败]", nil
	}
	text, imgOnly := extractXhtmlText(body)
	if imgOnly {
		return "[本章节为图片版，暂不支持]", nil
	}
	if text == "" {
		// No text and no images — e.g. a chapter whose body is rendered purely
		// via CSS background-image, or a malformed XHTML the walker couldn't
		// extract anything from. Surface a placeholder instead of returning
		// an empty string (which would render as a blank page in the reader).
		return "[本章节解析失败]", nil
	}
	return text, nil
}

func readContainerOpfPath(zr *zip.Reader) (string, error) {
	f, err := zr.Open("META-INF/container.xml")
	if err != nil {
		return "", fmt.Errorf("%w: missing container.xml: %v", ErrInvalidEpub, err)
	}
	defer f.Close()
	data, _ := readCapped(f, MaxEpubEntrySize)
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		t, err := dec.Token()
		if err != nil {
			return "", fmt.Errorf("%w: malformed container.xml", ErrInvalidEpub)
		}
		se, ok := t.(xml.StartElement)
		if !ok {
			continue
		}
		if se.Name.Local == "rootfile" {
			for _, a := range se.Attr {
				if a.Name.Local == "full-path" {
					return a.Value, nil
				}
			}
		}
	}
}

type opfParsed struct {
	title    string
	manifest map[string]string
	spine    []string
	tocID    string
}

func parseOpf(data []byte) (*opfParsed, error) {
	out := &opfParsed{manifest: map[string]string{}}
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		t, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("%w: malformed OPF", ErrInvalidEpub)
		}
		if se, ok := t.(xml.StartElement); ok {
			switch se.Name.Local {
			case "item":
				var id, href string
				for _, a := range se.Attr {
					if a.Name.Local == "id" {
						id = a.Value
					}
					if a.Name.Local == "href" {
						href = a.Value
					}
				}
				if id != "" {
					out.manifest[id] = href
				}
			case "itemref":
				for _, a := range se.Attr {
					if a.Name.Local == "idref" {
						out.spine = append(out.spine, a.Value)
					}
				}
			case "spine":
				for _, a := range se.Attr {
					if a.Name.Local == "toc" {
						out.tocID = a.Value
					}
				}
			}
		}
	}
	out.title = extractDcTitle(data)
	return out, nil
}

func extractDcTitle(data []byte) string {
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		t, err := dec.Token()
		if err != nil {
			return ""
		}
		if se, ok := t.(xml.StartElement); ok && se.Name.Local == "title" {
			var s struct {
				Chardata string `xml:",chardata"`
			}
			if err := dec.DecodeElement(&s, &se); err == nil {
				return strings.TrimSpace(s.Chardata)
			}
		}
	}
}

func readToc(zr *zip.Reader, ncxID string, manifest map[string]string, opfDir string) (map[string]string, error) {
	for _, href := range manifest {
		full := joinZipPath(opfDir, href)
		if f, err := zr.Open(full); err == nil {
			data, _ := readCapped(f, MaxEpubEntrySize)
			f.Close()
			if toc := parseNavToc(data); len(toc) > 0 {
				return toc, nil
			}
		}
	}
	if ncxID != "" {
		if href, ok := manifest[ncxID]; ok {
			full := joinZipPath(opfDir, href)
			if f, err := zr.Open(full); err == nil {
				data, _ := readCapped(f, MaxEpubEntrySize)
				f.Close()
				return parseNcx(data), nil
			}
		}
	}
	return nil, fmt.Errorf("no toc")
}

func parseNcx(data []byte) map[string]string {
	out := map[string]string{}
	type ncxNav struct {
		Label struct {
			Text string `xml:"text"`
		} `xml:"navLabel"`
		Content struct {
			Src string `xml:"src,attr"`
		} `xml:"content"`
	}
	type ncxRoot struct {
		NavMap struct {
			Points []ncxNav `xml:"navPoint"`
		} `xml:"navMap"`
	}
	var root ncxRoot
	if err := xml.Unmarshal(data, &root); err != nil {
		return out
	}
	for _, p := range root.NavMap.Points {
		out[p.Content.Src] = strings.TrimSpace(p.Label.Text)
	}
	return out
}

func parseNavToc(data []byte) map[string]string {
	out := map[string]string{}
	doc, err := html.Parse(bytes.NewReader(data))
	if err != nil {
		return out
	}
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode && n.Data == "a" {
			href := ""
			for _, a := range n.Attr {
				if a.Key == "href" {
					href = a.Val
					break
				}
			}
			if href != "" {
				out[href] = strings.TrimSpace(textOf(n))
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	return out
}

func textOf(n *html.Node) string {
	var sb strings.Builder
	var w func(*html.Node)
	w = func(node *html.Node) {
		if node.Type == html.TextNode {
			sb.WriteString(node.Data)
		}
		for c := node.FirstChild; c != nil; c = c.NextSibling {
			w(c)
		}
	}
	w(n)
	return sb.String()
}

func extractXhtmlText(data []byte) (string, bool) {
	doc, err := html.Parse(bytes.NewReader(data))
	if err != nil {
		return "[本章节解析失败]", false
	}
	var sb strings.Builder
	imgCount, textCount := 0, 0
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode {
			switch n.Data {
			case "img", "image":
				imgCount++
				sb.WriteString("[图片]")
				return
			case "p", "div", "br", "h1", "h2", "h3":
				if sb.Len() > 0 {
					sb.WriteString("\n\n")
				}
			}
		}
		if n.Type == html.TextNode {
			s := strings.TrimSpace(n.Data)
			if s != "" {
				sb.WriteString(s)
				textCount++
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	if textCount == 0 && imgCount > 0 {
		return "", true
	}
	return strings.TrimSpace(sb.String()), false
}

func readZipFile(files []*zip.File, name string) ([]byte, error) {
	for _, f := range files {
		if f.Name == name {
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			defer rc.Close()
			return readCapped(rc, MaxEpubEntrySize)
		}
	}
	return nil, fmt.Errorf("not found: %s", name)
}

func joinZipPath(dir, href string) string {
	if dir == "" || dir == "." {
		return normalizeHref(href)
	}
	return normalizeHref(filepath.ToSlash(filepath.Join(dir, href)))
}

func normalizeHref(s string) string {
	s = filepath.ToSlash(s)
	if i := strings.IndexByte(s, '#'); i >= 0 {
		s = s[:i]
	}
	return s
}

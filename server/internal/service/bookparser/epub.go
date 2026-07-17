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

// ReadCapped reads at most max+1 bytes from r so the caller can detect
// truncation. If the underlying reader yields more than max bytes it returns
// an error wrapping ErrTooLarge instead of allocating the full body.
func ReadCapped(r io.Reader, max int64) ([]byte, error) {
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
			if h, ok := opfData.manifest[idref]; ok && NormalizeHref(h) == NormalizeHref(href) {
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

// epubChapterBlocks opens the epub zip, reads the XHTML for chapter idx,
// and returns its content as ordered Blocks. Image srcs are raw epub
// hrefs (relative or absolute paths, data: URIs, http(s):// URLs) —
// BookService rewrites relative ones to /api/v1/books/image URLs.
//
// Failures degrade gracefully: missing manifest entry, missing zip entry,
// or read errors all return a single "[本章节解析失败]" text block rather
// than propagating an error, so the reader can still paginate.
func (b *Book) epubChapterBlocks(idx int) ([]Block, error) {
	c := b.Chapters[idx]
	href, ok := b.epubManifest[c.ManifestID]
	if !ok {
		return []Block{{Type: "text", Value: "[本章节解析失败]"}}, nil
	}
	fullPath := JoinZipPath(b.epubOpfDir, href)
	zr, err := zip.OpenReader(b.Path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	defer zr.Close()
	rc, err := zr.Open(fullPath)
	if err != nil {
		return []Block{{Type: "text", Value: "[本章节解析失败]"}}, nil
	}
	defer rc.Close()
	body, err := ReadCapped(rc, MaxEpubEntrySize)
	if err != nil {
		return []Block{{Type: "text", Value: "[本章节解析失败]"}}, nil
	}
	return extractBlocks(body), nil
}

func readContainerOpfPath(zr *zip.Reader) (string, error) {
	f, err := zr.Open("META-INF/container.xml")
	if err != nil {
		return "", fmt.Errorf("%w: missing container.xml: %v", ErrInvalidEpub, err)
	}
	defer f.Close()
	data, _ := ReadCapped(f, MaxEpubEntrySize)
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
		full := JoinZipPath(opfDir, href)
		if f, err := zr.Open(full); err == nil {
			data, _ := ReadCapped(f, MaxEpubEntrySize)
			f.Close()
			if toc := parseNavToc(data); len(toc) > 0 {
				return toc, nil
			}
		}
	}
	if ncxID != "" {
		if href, ok := manifest[ncxID]; ok {
			full := JoinZipPath(opfDir, href)
			if f, err := zr.Open(full); err == nil {
				data, _ := ReadCapped(f, MaxEpubEntrySize)
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

// extractBlocks walks an XHTML byte slice and produces ordered Blocks.
// <img>/<image> flush the current text buffer and push an image Block
// with the raw src. <br> writes '\n' to the buffer. Block-level elements
// (p/div/h1-h6/li/blockquote/section/title) flush on both enter and exit.
// Returns a single "[本章节为空]" placeholder if no content was produced.
func extractBlocks(data []byte) []Block {
	doc, err := html.Parse(bytes.NewReader(data))
	if err != nil {
		return []Block{{Type: "text", Value: "[本章节解析失败]"}}
	}
	var blocks []Block
	var textBuf strings.Builder
	flush := func() {
		if s := strings.TrimSpace(textBuf.String()); s != "" {
			blocks = append(blocks, Block{Type: "text", Value: s})
		}
		textBuf.Reset()
	}
	isBlockElement := func(tagName string) bool {
		switch tagName {
		case "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "title":
			return true
		}
		return false
	}
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode {
			if n.Data == "img" || n.Data == "image" {
				flush()
				if src := extractImgSrc(n); src != "" {
					blocks = append(blocks, Block{Type: "image", Src: src})
				}
				return
			}
			if n.Data == "br" {
				textBuf.WriteByte('\n')
				return
			}
			if isBlockElement(n.Data) {
				flush()
			}
		} else if n.Type == html.TextNode {
			textBuf.WriteString(n.Data)
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
		if n.Type == html.ElementNode && isBlockElement(n.Data) {
			flush()
		}
	}
	walk(doc)
	flush()
	if len(blocks) == 0 {
		return []Block{{Type: "text", Value: "[本章节为空]"}}
	}
	return blocks
}

// extractImgSrc pulls the src (HTML <img>) or xlink:href/href (SVG <image>)
// from a parsed node. Returns "" if no usable attribute is found.
func extractImgSrc(n *html.Node) string {
	for _, a := range n.Attr {
		if a.Key == "src" && a.Val != "" {
			return a.Val
		}
		if (a.Key == "xlink:href" || a.Key == "href" || (a.Namespace == "xlink" && a.Key == "href")) && a.Val != "" {
			return a.Val
		}
	}
	return ""
}

func readZipFile(files []*zip.File, name string) ([]byte, error) {
	for _, f := range files {
		if f.Name == name {
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			defer rc.Close()
			return ReadCapped(rc, MaxEpubEntrySize)
		}
	}
	return nil, fmt.Errorf("not found: %s", name)
}

// JoinZipPath resolves a (possibly relative) href against the OPF directory
// inside an epub zip and strips any fragment. Exported so BookService can
// perform manifest reverse-lookups when rewriting image srcs.
func JoinZipPath(dir, href string) string {
	if dir == "" || dir == "." {
		return NormalizeHref(href)
	}
	return NormalizeHref(filepath.ToSlash(filepath.Join(dir, href)))
}

// NormalizeHref canonicalises an epub href: forward slashes, no fragment.
// Exported so BookService can match manifest entries against toc hrefs.
func NormalizeHref(s string) string {
	s = filepath.ToSlash(s)
	if i := strings.IndexByte(s, '#'); i >= 0 {
		s = s[:i]
	}
	return s
}

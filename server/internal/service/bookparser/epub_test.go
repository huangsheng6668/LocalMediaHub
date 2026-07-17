package bookparser

import (
	"archive/zip"
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func buildMinimalEpub(t *testing.T, path string) {
	t.Helper()
	f, err := os.Create(path)
	require.NoError(t, err)
	defer f.Close()
	w := zip.NewWriter(f)

	mustWrite := func(name, body string) {
		fw, err := w.Create(name)
		require.NoError(t, err)
		_, err = fw.Write([]byte(body))
		require.NoError(t, err)
	}

	mustWrite("mimetype", "application/epub+zip")
	mustWrite("META-INF/container.xml", `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`)
	mustWrite("OEBPS/content.opf", `<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Test Book</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>`)
	mustWrite("OEBPS/ch1.xhtml", `<html><body><p>First chapter body.</p></body></html>`)
	mustWrite("OEBPS/ch2.xhtml", `<html><body><p>Second chapter body.</p></body></html>`)
	mustWrite("OEBPS/toc.ncx", `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1"><navLabel><text>Chapter One</text></navLabel><content src="ch1.xhtml"/></navPoint>
    <navPoint id="n2"><navLabel><text>Chapter Two</text></navLabel><content src="ch2.xhtml"/></navPoint>
  </navMap>
</ncx>`)
	require.NoError(t, w.Close())
}

func TestEpubParseExtractsTitleAndChapters(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	buildMinimalEpub(t, p)
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, "epub", b.Format)
	assert.Equal(t, "Test Book", b.Title)
	require.Len(t, b.Chapters, 2)
	assert.Equal(t, "ch1", b.Chapters[0].ManifestID)
	assert.Equal(t, "Chapter One", b.Chapters[0].Title)
}

func TestEpubChapterBlocksExtract(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	buildMinimalEpub(t, p)
	b, err := Parse(p)
	require.NoError(t, err)
	blocks, err := b.ChapterBlocks(0)
	require.NoError(t, err)
	require.NotEmpty(t, blocks)
	// Find at least one text block containing the body text.
	found := false
	joined := ""
	for _, blk := range blocks {
		if blk.Type == "text" {
			joined += blk.Value
			if strings.Contains(blk.Value, "First chapter body.") {
				found = true
			}
		}
	}
	assert.True(t, found, "expected a text block containing body text, got %#v", blocks)
	assert.NotContains(t, joined, "<p>")
}

func TestEpubTooLargeRejected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "big.epub")
	f, err := os.Create(p)
	require.NoError(t, err)
	require.NoError(t, f.Truncate(int64(MaxEpubSize + 1)))
	require.NoError(t, f.Close())
	_, err = Parse(p)
	assert.ErrorIs(t, err, ErrTooLarge)
}

// TestEpubInnerEntrySizeCap constructs an epub whose outer file is well under
// MaxEpubSize but whose OPF entry decompresses to > MaxEpubEntrySize. The
// per-entry cap in ReadCapped must reject the entry with an error wrapping
// ErrTooLarge rather than allocating the full multi-MB body. We exercise
// ReadCapped both directly (precise errors.Is assertion on the fix) and via
// Parse (integration: the cap fires inside the real OPF read path).
func TestEpubInnerEntrySizeCap(t *testing.T) {
	// Direct unit test on the ReadCapped helper: feed it a stream one byte
	// larger than the cap and assert ErrTooLarge surfaces with the right
	// identity (errors.Is must traverse the wrap chain).
	oversized := bytes.Repeat([]byte("x"), int(MaxEpubEntrySize)+1)
	_, err := ReadCapped(bytes.NewReader(oversized), MaxEpubEntrySize)
	if !errors.Is(err, ErrTooLarge) {
		t.Fatalf("ReadCapped: expected ErrTooLarge, got %v", err)
	}

	// Boundary check: a body exactly at the cap must succeed.
	atCap := bytes.Repeat([]byte("x"), int(MaxEpubEntrySize))
	b, err := ReadCapped(bytes.NewReader(atCap), MaxEpubEntrySize)
	require.NoError(t, err)
	assert.Len(t, b, int(MaxEpubEntrySize))

	// Integration: build an epub whose OPF entry is oversized and confirm
	// Parse rejects it instead of allocating the full body or returning a
	// parsed Book. The outer file stays under MaxEpubSize so we are
	// exercising the per-entry cap, not the outer size guard.
	dir := t.TempDir()
	p := filepath.Join(dir, "bigentry.epub")
	f, err := os.Create(p)
	require.NoError(t, err)
	defer f.Close()
	w := zip.NewWriter(f)

	writeRaw := func(name, body string) {
		fw, err := w.Create(name)
		require.NoError(t, err)
		_, err = fw.Write([]byte(body))
		require.NoError(t, err)
	}

	writeRaw("mimetype", "application/epub+zip")
	writeRaw("META-INF/container.xml", `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`)

	// OPF entry that decompresses to > MaxEpubEntrySize. STORED (no
	// compression) so decompressed size equals bytes written, guaranteeing
	// the LimitReader cap fires regardless of zip implementation.
	opfHead := []byte(`<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="2.0">`)
	padding := bytes.Repeat([]byte(" "), int(MaxEpubEntrySize)+1-int(len(opfHead)))
	hugeOpf := append(opfHead, padding...)
	fw, err := w.CreateHeader(&zip.FileHeader{
		Name:   "OEBPS/content.opf",
		Method: zip.Store,
	})
	require.NoError(t, err)
	_, err = fw.Write(hugeOpf)
	require.NoError(t, err)
	require.NoError(t, w.Close())
	require.NoError(t, f.Close())

	info, err := os.Stat(p)
	require.NoError(t, err)
	require.Less(t, info.Size(), int64(MaxEpubSize), "outer epub should be under MaxEpubSize")

	_, err = Parse(p)
	// readZipFile wraps with "%w: missing OPF: %v" — the inner ErrTooLarge
	// is flattened to a string by %v, so we assert on the message instead.
	// The important guarantee is that Parse rejects the oversized entry
	// rather than returning a parsed Book.
	require.Error(t, err, "Parse must reject an epub with an oversized inner entry")
	assert.Contains(t, err.Error(), ErrTooLarge.Error())
}

// TestEpubEmptyChapterFallback asserts that a chapter whose XHTML body has
// neither text nodes nor img tags (e.g. only CSS background-image) falls back
// to the [本章节解析失败] placeholder rather than returning an empty string
// that would render a blank page in TextReader.
func TestEpubEmptyChapterFallback(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "empty.epub")
	f, err := os.Create(p)
	require.NoError(t, err)
	defer f.Close()
	w := zip.NewWriter(f)

	writeRaw := func(name, body string) {
		fw, err := w.Create(name)
		require.NoError(t, err)
		_, err = fw.Write([]byte(body))
		require.NoError(t, err)
	}

	writeRaw("mimetype", "application/epub+zip")
	writeRaw("META-INF/container.xml", `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`)
	writeRaw("OEBPS/content.opf", `<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Empty Chapter Book</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
  </spine>
</package>`)
	// Chapter with no text nodes and no <img>/<image> tags — e.g. an empty
	// <body> or a div that pulls its content purely from an external CSS
	// background-image. extractBlocks returns a single "[本章节为空]"
	// placeholder for this, which must surface rather than a blank UI page.
	writeRaw("OEBPS/ch1.xhtml", `<html><head></head><body><div></div></body></html>`)
	writeRaw("OEBPS/toc.ncx", `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1"><navLabel><text>Only Chapter</text></navLabel><content src="ch1.xhtml"/></navPoint>
  </navMap>
</ncx>`)
	require.NoError(t, w.Close())
	require.NoError(t, f.Close())

	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 1)
	blocks, err := b.ChapterBlocks(0)
	require.NoError(t, err)
	require.Len(t, blocks, 1)
	assert.Equal(t, "[本章节为空]", blocks[0].Value, "empty-body chapter must render the placeholder, not blank")
	assert.Equal(t, "text", blocks[0].Type)
}

func TestExtractBlocksImageSrc(t *testing.T) {
	xhtml := []byte(`<html><body>
        <p>intro text</p>
        <img src="images/cover.jpg" alt="cover"/>
        <p>outro text</p>
    </body></html>`)
	blocks := extractBlocks(xhtml)
	require.Len(t, blocks, 3)
	assert.Equal(t, "text", blocks[0].Type)
	assert.Contains(t, blocks[0].Value, "intro text")
	assert.Equal(t, "image", blocks[1].Type)
	assert.Equal(t, "images/cover.jpg", blocks[1].Src)
	assert.Equal(t, "text", blocks[2].Type)
	assert.Contains(t, blocks[2].Value, "outro text")
}

func TestExtractBlocksDataUriPreserved(t *testing.T) {
	dataURI := "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
	xhtml := []byte(`<html><body>
        <img src="` + dataURI + `"/>
    </body></html>`)
	blocks := extractBlocks(xhtml)
	require.Len(t, blocks, 1)
	assert.Equal(t, "image", blocks[0].Type)
	assert.Equal(t, dataURI, blocks[0].Src)
}

func TestExtractBlocksImageOnlyChapter(t *testing.T) {
	xhtml := []byte(`<html><body>
        <img src="page1.png"/>
        <img src="page2.png"/>
    </body></html>`)
	blocks := extractBlocks(xhtml)
	require.Len(t, blocks, 2)
	assert.Equal(t, "image", blocks[0].Type)
	assert.Equal(t, "page1.png", blocks[0].Src)
	assert.Equal(t, "image", blocks[1].Type)
	assert.Equal(t, "page2.png", blocks[1].Src)
	// Verify no text block was synthesized as a placeholder
	for _, b := range blocks {
		assert.NotEqual(t, "text", b.Type)
	}
}

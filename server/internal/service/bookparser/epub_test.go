package bookparser

import (
	"archive/zip"
	"os"
	"path/filepath"
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

func TestEpubChapterTextExtract(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	buildMinimalEpub(t, p)
	b, err := Parse(p)
	require.NoError(t, err)
	c0, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.Contains(t, c0, "First chapter body.")
	assert.NotContains(t, c0, "<p>")
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

package service

import (
	"archive/zip"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/localmediahub/server/internal/service/bookparser"
)

func TestBookServiceCachesByMtime(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.txt")
	require.NoError(t, os.WriteFile(p, []byte("hello"), 0644))

	s := NewBookService()
	b1, err := s.GetBook(p)
	require.NoError(t, err)
	b2, err := s.GetBook(p)
	require.NoError(t, err)
	assert.Same(t, b1, b2, "expected identical *Book on cache hit")
}

func TestBookServiceReparseAfterMtimeChange(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.txt")
	require.NoError(t, os.WriteFile(p, []byte("v1"), 0644))

	s := NewBookService()
	b1, _ := s.GetBook(p)
	require.NoError(t, os.Chtimes(p, time.Now().Add(time.Second), time.Now().Add(time.Second)))
	b2, _ := s.GetBook(p)
	assert.NotSame(t, b1, b2, "expected new *Book after mtime change")
}

func TestBookServiceConcurrentCallsDontPanic(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.txt")
	require.NoError(t, os.WriteFile(p, []byte("data"), 0644))

	s := NewBookService()
	done := make(chan struct{})
	for i := 0; i < 10; i++ {
		go func() {
			defer func() { done <- struct{}{} }()
			_, _ = s.GetBook(p)
		}()
	}
	for i := 0; i < 10; i++ {
		<-done
	}
	b, err := s.GetBook(p)
	require.NoError(t, err)
	assert.NotNil(t, b)
}

// buildEpubWithImage writes a minimal epub to a temp file and returns its
// path. The single chapter's XHTML references imgSrc as an <img src="...">,
// and the OPF manifest declares the image as <item id=imgManifestID
// href=imgManifestHref .../>. imgSrc, imgManifestID and imgManifestHref are
// inserted verbatim, so callers control whether the lookup matches.
//
// The epub also contains a (zero-byte) zip entry for the image href so that
// future end-to-end tests of the image endpoint can reuse the helper without
// the zip reader rejecting missing entries.
func buildEpubWithImage(t *testing.T, imgSrc, imgManifestID, imgManifestHref string) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	f, err := os.Create(p)
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
    <dc:title>Image Book</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="`+imgManifestID+`" href="`+imgManifestHref+`" media-type="image/jpeg"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
  </spine>
</package>`)
	mustWrite("OEBPS/ch1.xhtml", `<html><body>
        <p>intro</p>
        <img src="`+imgSrc+`"/>
        <p>outro</p>
    </body></html>`)
	// Zero-byte placeholder entry at the image href's path so the zip is
	// internally consistent. Resolve via NormalizeHref + ToSlash so tests can
	// pass in either forward- or back-slash hrefs.
	imgEntry := strings.TrimPrefix(filepath.ToSlash(filepath.ToSlash(imgManifestHref)), "/")
	// Strip any fragment for the on-disk entry name.
	if i := strings.IndexByte(imgEntry, '#'); i >= 0 {
		imgEntry = imgEntry[:i]
	}
	mustWrite("OEBPS/"+imgEntry, "")
	mustWrite("OEBPS/toc.ncx", `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1"><navLabel><text>Only Chapter</text></navLabel><content src="ch1.xhtml"/></navPoint>
  </navMap>
</ncx>`)
	require.NoError(t, w.Close())
	require.NoError(t, f.Close())
	return p
}

func TestGetChapterBlocksRewritesImageSrc(t *testing.T) {
	p := buildEpubWithImage(t, "images/foo.jpg", "img-foo", "images/foo.jpg")
	svc := NewBookService()
	blocks, err := svc.GetChapterBlocks(t.Context(), p, 0, "127.0.0.1")
	require.NoError(t, err)

	var imgBlock *bookparser.Block
	for i := range blocks {
		if blocks[i].Type == "image" {
			imgBlock = &blocks[i]
			break
		}
	}
	require.NotNil(t, imgBlock, "expected an image block; got %#v", blocks)
	assert.Contains(t, imgBlock.Src, "/api/v1/books/image?path=")
	assert.Contains(t, imgBlock.Src, "manifest=img-foo")
	// path must be URL-encoded so characters like Windows drive colons or
	// spaces survive the round trip.
	encodedPath := url.QueryEscape(p)
	assert.Contains(t, imgBlock.Src, "path="+encodedPath)
}

func TestGetChapterBlocksPreservesDataUri(t *testing.T) {
	dataURI := "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
	// Manifest entry deliberately does NOT match the data: URI, proving we
	// skip rewriting for data: URIs rather than blanking them.
	p := buildEpubWithImage(t, dataURI, "img-foo", "images/foo.jpg")
	svc := NewBookService()
	blocks, err := svc.GetChapterBlocks(t.Context(), p, 0, "127.0.0.1")
	require.NoError(t, err)
	for _, b := range blocks {
		if b.Type == "image" {
			assert.Equal(t, dataURI, b.Src, "data: URI must be passed through unchanged")
			return
		}
	}
	t.Fatal("no image block found")
}

// TestGetChapterBlocksBlanksOnNoManifestMatch verifies that an image Src
// with no matching manifest entry is set to "" (clients render a
// placeholder) rather than leaking the raw relative path to the frontend.
func TestGetChapterBlocksBlanksOnNoManifestMatch(t *testing.T) {
	p := buildEpubWithImage(t, "images/missing.jpg", "img-foo", "images/foo.jpg")
	svc := NewBookService()
	blocks, err := svc.GetChapterBlocks(t.Context(), p, 0, "127.0.0.1")
	require.NoError(t, err)
	for _, b := range blocks {
		if b.Type == "image" {
			assert.Equal(t, "", b.Src, "non-matching src must be blanked")
			return
		}
	}
	t.Fatal("no image block found")
}

func TestReverseLookupManifest(t *testing.T) {
	manifest := map[string]string{
		"img-1": "images/foo.jpg",
		"img-2": "OEBPS/Images/bar.png",
		"img-3": "images/baz.gif#fragment",
	}
	cases := []struct {
		name string
		src  string
		want string
	}{
		{"relative path", "images/foo.jpg", "img-1"},
		{"absolute path", "OEBPS/Images/bar.png", "img-2"},
		{"with fragment", "images/baz.gif#frag", "img-3"},
		{"not found", "images/missing.jpg", ""},
		{"empty src", "", ""},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			assert.Equal(t, tc.want, reverseLookupManifest(manifest, tc.src))
		})
	}

	t.Run("nil manifest", func(t *testing.T) {
		assert.Equal(t, "", reverseLookupManifest(nil, "images/foo.jpg"))
	})
}

// png1x1 is a minimal 1x1 transparent PNG used as test image payload.
var png1x1 = []byte{
	0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
	0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
	0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
	0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4,
	0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
	0x54, 0x78, 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
	0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4, 0x00,
	0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE,
	0x42, 0x60, 0x82,
}

// buildEpubWithImageBytes writes a minimal epub whose manifest image entry
// actually contains the supplied image bytes (unlike buildEpubWithImage, which
// writes a zero-byte placeholder). The image is stored at
// "OEBPS/<imgHref>" and declared as <item id=imgManifestID href=imgHref .../>.
// imgHref must be relative (no leading slash, no fragment).
func buildEpubWithImageBytes(t *testing.T, imgManifestID, imgHref string, imgBytes []byte) string {
	t.Helper()
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	f, err := os.Create(p)
	require.NoError(t, err)
	defer f.Close()
	w := zip.NewWriter(f)

	mustWriteStr := func(name, body string) {
		fw, err := w.Create(name)
		require.NoError(t, err)
		_, err = fw.Write([]byte(body))
		require.NoError(t, err)
	}
	mustWriteBytes := func(name string, body []byte) {
		fw, err := w.Create(name)
		require.NoError(t, err)
		_, err = fw.Write(body)
		require.NoError(t, err)
	}

	mustWriteStr("mimetype", "application/epub+zip")
	mustWriteStr("META-INF/container.xml", `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`)
	mustWriteStr("OEBPS/content.opf", `<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Image Bytes Book</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="`+imgManifestID+`" href="`+imgHref+`" media-type="image/png"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
  </spine>
</package>`)
	mustWriteStr("OEBPS/ch1.xhtml", `<html><body><p>chapter with image</p></body></html>`)
	imgEntry := strings.TrimPrefix(filepath.ToSlash(imgHref), "/")
	if i := strings.IndexByte(imgEntry, '#'); i >= 0 {
		imgEntry = imgEntry[:i]
	}
	mustWriteBytes("OEBPS/"+imgEntry, imgBytes)
	mustWriteStr("OEBPS/toc.ncx", `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1"><navLabel><text>Only Chapter</text></navLabel><content src="ch1.xhtml"/></navPoint>
  </navMap>
</ncx>`)
	require.NoError(t, w.Close())
	require.NoError(t, f.Close())
	return p
}

func TestReadImageBytes(t *testing.T) {
	p := buildEpubWithImageBytes(t, "cover", "Images/test.png", png1x1)
	svc := NewBookService()

	t.Run("success", func(t *testing.T) {
		data, ct, err := svc.ReadImageBytes(p, "cover")
		require.NoError(t, err)
		assert.Equal(t, png1x1, data)
		assert.Equal(t, "image/png", ct)
	})

	t.Run("manifest not found", func(t *testing.T) {
		_, _, err := svc.ReadImageBytes(p, "nonexistent")
		require.Error(t, err)
		assert.ErrorIs(t, err, bookparser.ErrInvalidEpub)
	})

	t.Run("non-epub file rejected", func(t *testing.T) {
		txtPath := filepath.Join(t.TempDir(), "book.txt")
		require.NoError(t, os.WriteFile(txtPath, []byte("hello"), 0644))
		_, _, err := svc.ReadImageBytes(txtPath, "any")
		require.Error(t, err)
		assert.ErrorIs(t, err, bookparser.ErrUnsupported)
	})
}

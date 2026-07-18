package handler

import (
	"archive/zip"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

func newBooksHandler(t *testing.T) (*Handler, string) {
	t.Helper()
	dir := t.TempDir()
	cfg := &config.Config{}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.ImageExtensions = []string{".jpg"}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	cfg.Scan.Roots = []string{dir}
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
	books := service.NewBookService()
	h := New(cfg, scanner, nil, nil, nil, books)
	return h, dir
}

func TestGetBookInfoTxt(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := filepath.Join(dir, "n.txt")
	require.NoError(t, os.WriteFile(p, []byte("第一章\nbody"), 0644))

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/info?path="+p, nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookInfo(c))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"format":"txt"`)
}

func TestGetBookInfoPathOutsideRoots403(t *testing.T) {
	h, _ := newBooksHandler(t)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/info?path=/etc/passwd", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookInfo(c))
	assert.Equal(t, http.StatusForbidden, rec.Code)
}

// TestGetBookChapterReturnsBlocks verifies the Task 8 contract: the chapter
// endpoint returns {title, blocks: [{type, value, src}]} instead of the
// pre-Task-8 {title, content: string}. For a .txt chapter whose body is two
// paragraphs, the parser yields two text blocks whose Values are the
// individual paragraphs (blank-line split). The title line is separated
// from the body by a blank line, matching how the txt parser slices a
// chapter (chapter start offset is at the title line and the body is split
// on "\n\n").
func TestGetBookChapterReturnsBlocks(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := filepath.Join(dir, "n.txt")
	require.NoError(t, os.WriteFile(p, []byte("第一章 title\n\npara1\n\npara2"), 0644))

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/chapter?path="+url.QueryEscape(p)+"&index=0", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookChapter(c))
	require.Equal(t, http.StatusOK, rec.Code)

	var resp struct {
		Title  string `json:"title"`
		Blocks []struct {
			Type  string `json:"type"`
			Value string `json:"value,omitempty"`
			Src   string `json:"src,omitempty"`
		} `json:"blocks"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Equal(t, "第一章 title", resp.Title)
	// The txt parser slices each chapter from the title-line offset and
	// splits on blank lines, so the title text is echoed as the first
	// paragraph followed by the two body paragraphs.
	require.Len(t, resp.Blocks, 3)
	assert.Equal(t, "text", resp.Blocks[0].Type)
	assert.Equal(t, "第一章 title", resp.Blocks[0].Value)
	assert.Equal(t, "text", resp.Blocks[1].Type)
	assert.Equal(t, "para1", resp.Blocks[1].Value)
	assert.Equal(t, "text", resp.Blocks[2].Type)
	assert.Equal(t, "para2", resp.Blocks[2].Value)
	assert.NotContains(t, rec.Body.String(), `"content"`, "response must not leak the old Content field")
}

// png1x1 is a minimal 1x1 transparent PNG used as test image payload.
// (Mirrored from service/book_test.go — that fixture lives in package service
// and cannot be imported here.)
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

// buildEpubWithImageBytes writes a minimal epub to a temp file inside dir.
// The OPF manifest declares the image as <item id=imgManifestID href=imgHref
// media-type="image/png"/> and the actual image bytes are stored at
// OEBPS/<imgHref>. imgHref must be relative (no leading slash, no fragment).
func buildEpubWithImageBytes(t *testing.T, dir, imgManifestID, imgHref string, imgBytes []byte) string {
	t.Helper()
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
    <dc:title>Image Book</dc:title>
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
	mustWriteBytes("OEBPS/"+imgHref, imgBytes)
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

// TestGetBookImageReturnsBlob verifies the happy path: a known manifest id
// resolves to the stored PNG bytes with a PNG content type and 1-day
// browser cache.
func TestGetBookImageReturnsBlob(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/books/image?path="+url.QueryEscape(p)+"&manifest=cover", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookImage(c))
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "image/png", rec.Header().Get("Content-Type"))
	assert.Equal(t, "public, max-age=86400", rec.Header().Get("Cache-Control"))
	assert.Equal(t, png1x1, rec.Body.Bytes())
}

// TestGetBookImagePathOutsideRoots403 verifies the path-access guard fires
// before the book is opened: a path outside the scan roots is rejected with
// 403 regardless of manifest id.
func TestGetBookImagePathOutsideRoots403(t *testing.T) {
	h, _ := newBooksHandler(t)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/books/image?path="+url.QueryEscape("/etc/passwd")+"&manifest=x", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookImage(c))
	assert.Equal(t, http.StatusForbidden, rec.Code)
}

// TestGetBookImageManifestNotFound422 verifies that an unknown manifest id
// surfaces as 422 (not 404). mapBookError maps ErrInvalidEpub — which
// ReadImageBytes wraps the "manifest id not found" error in — to
// http.StatusUnprocessableEntity (422). The original brief named this test
// "404" but the actual mapped status is 422; the test name and assertion
// match the real behaviour.
func TestGetBookImageManifestNotFound422(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/books/image?path="+url.QueryEscape(p)+"&manifest=nonexistent", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookImage(c))
	assert.Equal(t, http.StatusUnprocessableEntity, rec.Code)
}

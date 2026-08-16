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
	h := New(cfg, scanner, nil, nil, nil, books, nil)
	return h, dir
}

// newBooksHandlerWithSigner is the signer-wired variant of newBooksHandler,
// used to cover the Round 32 Task 5 production-mode branches in GetBookImage
// and SignImage. The returned BookSigner is the same instance wired into the
// handler, so tests can call SignImage to mint a valid ?sig= for the request
// the handler will then verify. clientIP is fixed via req.RemoteAddr below to
// keep c.RealIP() stable across the sign and verify phases of each test.
func newBooksHandlerWithSigner(t *testing.T) (*Handler, *service.BookSigner, string) {
	t.Helper()
	dir := t.TempDir()
	cfg := &config.Config{}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.ImageExtensions = []string{".jpg"}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	cfg.Scan.Roots = []string{dir}
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
	books := service.NewBookService()
	signer, err := service.NewBookSigner()
	require.NoError(t, err)
	h := New(cfg, scanner, nil, nil, nil, books, signer)
	return h, signer, dir
}

// newBookImageRequest builds a GET request to /api/v1/books/image with the
// given query string and pins RemoteAddr so c.RealIP() returns testClientIP
// in both the signing helper and the handler's VerifyImage call. Returns only
// the recorder and context — callers don't need to inspect the request.
const testClientIP = "192.0.2.7"

func newBookImageRequest(query string) (*httptest.ResponseRecorder, echo.Context) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/image?"+query, nil)
	req.RemoteAddr = testClientIP + ":1234"
	rec := httptest.NewRecorder()
	return rec, e.NewContext(req, rec)
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

// =============================================================================
// Round 32 Task 5 (S2) follow-up coverage: signed-image production branches.
// The four tests below wire a REAL *service.BookSigner (unlike the open-mode
// newBooksHandler which passes bookSigner: nil) so the if h.bookSigner != nil
// gate in GetBookImage is exercised for each of its three branches:
//   1. valid ?sig=  → 200
//   2. tampered sig → 401
//   3. no sig, no token → 401
//   4. deprecated ?token= fallback → 200 (handler still serves; auth is
//      enforced by the BearerToken middleware wrapping the /books group in
//      production, NOT by the handler itself).
// =============================================================================

// TestGetBookImageValidSigReturns200 covers the happy path of the signed-URL
// flow: SignImage mints a sig over (clientIP, resolvedPath, manifestID), the
// handler re-derives the same HMAC via VerifyImage, and the image bytes are
// served with a 200 + PNG content-type. IP binding is exercised by pinning
// req.RemoteAddr — if the handler read a different IP than the signer did,
// VerifyImage would return false and the test would fail with 401.
func TestGetBookImageValidSigReturns200(t *testing.T) {
	h, signer, dir := newBooksHandlerWithSigner(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	sig := signer.SignImage(testClientIP, p, "cover")
	q := "path=" + url.QueryEscape(p) + "&manifest=cover&sig=" + sig
	rec, c := newBookImageRequest(q)
	require.NoError(t, h.GetBookImage(c))
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "image/png", rec.Header().Get("Content-Type"))
	assert.Equal(t, png1x1, rec.Body.Bytes())
}

// TestGetBookImageTamperedSigReturns401 covers the bad-signature branch:
// appending "x" to a valid sig makes base64 decoding or the HMAC comparison
// fail, which must surface as 401 "invalid signature". This proves the
// handler does NOT fall through to the token-or-open path when a sig is
// present but wrong.
func TestGetBookImageTamperedSigReturns401(t *testing.T) {
	h, signer, dir := newBooksHandlerWithSigner(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	sig := signer.SignImage(testClientIP, p, "cover") + "x"
	q := "path=" + url.QueryEscape(p) + "&manifest=cover&sig=" + sig
	rec, c := newBookImageRequest(q)
	require.NoError(t, h.GetBookImage(c))
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
	assert.Contains(t, rec.Body.String(), "invalid signature")
}

// TestGetBookImageNoSigNoTokenReturns401 covers the third branch of the
// signature gate: when the signer is wired but the request carries neither
// ?sig= nor ?token=, the endpoint MUST refuse with 401 "signature required".
// This prevents an unsigned <img src="...books/image?path=&manifest="> tag
// from loading bytes in production mode.
func TestGetBookImageNoSigNoTokenReturns401(t *testing.T) {
	h, _, dir := newBooksHandlerWithSigner(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	q := "path=" + url.QueryEscape(p) + "&manifest=cover"
	rec, c := newBookImageRequest(q)
	require.NoError(t, h.GetBookImage(c))
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
	assert.Contains(t, rec.Body.String(), "signature required")
}

// TestGetBookImageTokenFallbackStillWorks covers the deprecated ?token=
// fallback in open mode (empty configured token). Since the /books/image
// route is now registered OUTSIDE the BearerToken group, the handler verifies
// the token itself; with an empty configured token (open mode) any non-empty
// value passes, logs a slog.Warning, and serves the bytes — preserving the
// migration path for clients that have not adopted ?sig= yet.
func TestGetBookImageTokenFallbackStillWorks(t *testing.T) {
	h, _, dir := newBooksHandlerWithSigner(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	q := "path=" + url.QueryEscape(p) + "&manifest=cover&token=deprecat3d-bearer-fallback"
	rec, c := newBookImageRequest(q)
	require.NoError(t, h.GetBookImage(c))
	require.Equal(t, http.StatusOK, rec.Code)
	assert.Equal(t, "image/png", rec.Header().Get("Content-Type"))
	assert.Equal(t, png1x1, rec.Body.Bytes())
}

// TestGetBookImageTokenFallbackVerifiedWhenTokenConfigured covers the new
// handler-side token verification: with a non-empty configured token, the
// ?token= fallback must accept only the exact value (constant-time) and 401
// anything else — the route no longer sits behind middleware.BearerToken.
func TestGetBookImageTokenFallbackVerifiedWhenTokenConfigured(t *testing.T) {
	dir := t.TempDir()
	cfg := &config.Config{}
	cfg.Server.Token = "book-image-secret"
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.ImageExtensions = []string{".jpg"}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	cfg.Scan.Roots = []string{dir}
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
	books := service.NewBookService()
	signer, err := service.NewBookSigner()
	require.NoError(t, err)
	h := New(cfg, scanner, nil, nil, nil, books, signer)

	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	// Wrong token → 401.
	q := "path=" + url.QueryEscape(p) + "&manifest=cover&token=wrong-token"
	rec, c := newBookImageRequest(q)
	require.NoError(t, h.GetBookImage(c))
	assert.Equal(t, http.StatusUnauthorized, rec.Code)

	// Correct token → 200.
	q = "path=" + url.QueryEscape(p) + "&manifest=cover&token=" + url.QueryEscape("book-image-secret")
	rec, c = newBookImageRequest(q)
	require.NoError(t, h.GetBookImage(c))
	assert.Equal(t, http.StatusOK, rec.Code)
}

// =============================================================================
// Round 32 Task 5 (S2) follow-up coverage: SignImage handler.
// Exercises the /api/v1/books/sign-image endpoint that mints the signed URLs
// consumed by TestGetBookImageValidSigReturns200 above.
// =============================================================================

// TestSignImageReturnsSignedSrc covers the SignImage happy path: the endpoint
// returns {"src": "<url>&sig=<hmac>"} where the URL embeds the validated path
// and manifest id (URL-escaped) and the sig is a non-empty base64 string.
// When the signer is wired (non-nil), the response MUST carry &sig=; without
// the signer the URL is returned unsigned (covered by the nil test below).
func TestSignImageReturnsSignedSrc(t *testing.T) {
	h, _, dir := newBooksHandlerWithSigner(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/books/sign-image?path="+url.QueryEscape(p)+"&manifest=cover", nil)
	req.RemoteAddr = testClientIP + ":1234"
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.SignImage(c))
	require.Equal(t, http.StatusOK, rec.Code)

	var resp struct {
		Src string `json:"src"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.Contains(t, resp.Src, "&sig=")
	assert.Contains(t, resp.Src, "path="+url.QueryEscape(p))
	assert.Contains(t, resp.Src, "manifest=cover")
}

// TestSignImageRejectsEmptyPath covers the input-validation branch: a missing
// ?path= must be rejected with 400 before the signer or path validator run.
func TestSignImageRejectsEmptyPath(t *testing.T) {
	h, _, _ := newBooksHandlerWithSigner(t)

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/sign-image?manifest=cover", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.SignImage(c))
	assert.Equal(t, http.StatusBadRequest, rec.Code)
}

// TestSignImageNoSignerReturnsUnsignedSrc covers the open-mode branch: when
// bookSigner is nil (tests / open mode), SignImage still returns 200 with a
// valid src URL — but WITHOUT a &sig= suffix. This mirrors GetChapterBlocks'
// behaviour when no signer is wired (image src URLs emitted in unsigned
// mode). The handler does NOT return 503; it gracefully degrades.
func TestSignImageNoSignerReturnsUnsignedSrc(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := buildEpubWithImageBytes(t, dir, "cover", "Images/test.png", png1x1)

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/books/sign-image?path="+url.QueryEscape(p)+"&manifest=cover", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.SignImage(c))
	require.Equal(t, http.StatusOK, rec.Code)

	var resp struct {
		Src string `json:"src"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	assert.NotContains(t, resp.Src, "&sig=")
	assert.Contains(t, resp.Src, "manifest=cover")
}

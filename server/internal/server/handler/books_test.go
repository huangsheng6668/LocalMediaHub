package handler

import (
	"net/http"
	"net/http/httptest"
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

func TestGetBookChapterReturnsJSON(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := filepath.Join(dir, "n.txt")
	require.NoError(t, os.WriteFile(p, []byte("第一章 A\nbody text here"), 0644))

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/chapter?path="+p+"&index=0", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookChapter(c))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"title":`)
	assert.Contains(t, rec.Body.String(), "body text here")
}

package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/require"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func newSystemBrowseHandler(t *testing.T, root string) *Handler {
	t.Helper()
	cfg := &config.Config{}
	cfg.Scan.ImageExtensions = []string{".png", ".jpg"}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.TextExtensions = []string{".txt"}
	cfg.System.AllowedRoots = []string{root}
	return New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions), nil, nil, nil, nil, nil, nil)
}

// TestSystemBrowseSortAndPagination covers the paged load-more contract for
// /api/v1/system/browse: server-side natural sort + deterministic paging so
// consecutive pages append into the same global order as the folder-browse
// endpoint (Android clients reuse the same infinite-scroll path).
func TestSystemBrowseSortAndPagination(t *testing.T) {
	root := t.TempDir()
	require.NoError(t, os.MkdirAll(filepath.Join(root, "sub"), 0o755))
	for _, name := range []string{"10.mp4", "b.mp4", "2.mp4", "a.mp4", "c.mp4"} {
		require.NoError(t, os.WriteFile(filepath.Join(root, name), []byte("x"), 0o644))
	}

	h := newSystemBrowseHandler(t, root)
	e := echo.New()
	e.GET("/api/v1/system/browse", h.SystemBrowse)

	getPage := func(page int) models.BrowseResult {
		t.Helper()
		req := httptest.NewRequest(http.MethodGet,
			"/api/v1/system/browse?path="+filepath.ToSlash(root)+"&sort=name&order=asc&page_size=2&page="+strconv.Itoa(page), nil)
		rec := httptest.NewRecorder()
		e.ServeHTTP(rec, req)
		require.Equal(t, http.StatusOK, rec.Code)
		var result models.BrowseResult
		require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &result))
		return result
	}

	page1 := getPage(1)
	require.Len(t, page1.Files, 2)
	require.Equal(t, "2.mp4", page1.Files[0].Name)
	require.Equal(t, "10.mp4", page1.Files[1].Name)
	require.True(t, page1.HasMore)
	// Folders are always returned in full (they drive navigation).
	require.Len(t, page1.Folders, 1)
	require.Equal(t, "sub", page1.Folders[0].Name)

	page2 := getPage(2)
	require.Len(t, page2.Files, 2)
	require.Equal(t, "a.mp4", page2.Files[0].Name)
	require.Equal(t, "b.mp4", page2.Files[1].Name)
	require.True(t, page2.HasMore)

	page3 := getPage(3)
	require.Len(t, page3.Files, 1)
	require.Equal(t, "c.mp4", page3.Files[0].Name)
	require.False(t, page3.HasMore)

	// desc order reverses the same window.
	req := httptest.NewRequest(http.MethodGet,
		"/api/v1/system/browse?path="+filepath.ToSlash(root)+"&sort=name&order=desc&page=1&page_size=2", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	require.Equal(t, http.StatusOK, rec.Code)
	var pageDesc models.BrowseResult
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &pageDesc))
	require.Len(t, pageDesc.Files, 2)
	require.Equal(t, "c.mp4", pageDesc.Files[0].Name)
	require.Equal(t, "b.mp4", pageDesc.Files[1].Name)
}

package handler

import (
	"encoding/json"
	"image"
	"image/png"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/require"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

func newMediaThumbnailsHandler(t *testing.T, root string) *Handler {
	t.Helper()
	cfg := &config.Config{}
	cfg.Scan.ImageExtensions = []string{".png", ".jpg"}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.Roots = []string{root}
	thumbnail, err := service.NewThumbnailService(t.TempDir(), 64, "jpeg", "")
	require.NoError(t, err)
	return New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions), nil, nil, thumbnail, nil, nil, nil)
}

// writeValidPNG writes a real decodable PNG (the shared png1x1 fixture is an
// intentionally broken CRC — book tests treat it as opaque bytes, but the
// thumbnail pipeline actually decodes images).
func writeValidPNG(t *testing.T, path string) {
	t.Helper()
	f, err := os.Create(path)
	require.NoError(t, err)
	defer f.Close()
	require.NoError(t, png.Encode(f, image.NewRGBA(image.Rect(0, 0, 4, 4))))
}

func postThumbnails(t *testing.T, h *Handler, body string) *httptest.ResponseRecorder {
	e := echo.New()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/media/thumbnails",
		strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	require.NoError(t, h.MediaThumbnails(e.NewContext(req, rec)))
	return rec
}

// thumbnailsBody marshals a paths request via encoding/json so Windows
// backslashes in temp paths are escaped correctly (hand-built JSON strings
// with raw backslashes produce invalid escape sequences).
func thumbnailsBody(t *testing.T, paths []string) string {
	t.Helper()
	b, err := json.Marshal(map[string][]string{"paths": paths})
	require.NoError(t, err)
	return string(b)
}

func TestMediaThumbnailsReturnsBase64AndPerItemErrors(t *testing.T) {
	root := t.TempDir()
	img := filepath.Join(root, "photo.png")
	writeValidPNG(t, img)

	h := newMediaThumbnailsHandler(t, root)

	rec := postThumbnails(t, h, thumbnailsBody(t, []string{img, filepath.Join(root, "missing.png")}))
	require.Equal(t, http.StatusOK, rec.Code)

	var resp struct {
		Items []ThumbnailItem `json:"items"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	require.Len(t, resp.Items, 2)

	require.Equal(t, img, resp.Items[0].Path)
	require.NotEmpty(t, resp.Items[0].Thumbnail)
	require.Empty(t, resp.Items[0].Error)

	// Missing files fail path validation, which the endpoint collapses to
	// the generic access-denied error (matching MediaThumbnail's behavior).
	require.Equal(t, "access denied", resp.Items[1].Error)
	require.Empty(t, resp.Items[1].Thumbnail)
}

func TestMediaThumbnailsRejectsOutsideRoots(t *testing.T) {
	root := t.TempDir()
	h := newMediaThumbnailsHandler(t, root)

	rec := postThumbnails(t, h, thumbnailsBody(t, []string{filepath.Join(t.TempDir(), "elsewhere.png")}))
	require.Equal(t, http.StatusOK, rec.Code)

	var resp struct {
		Items []ThumbnailItem `json:"items"`
	}
	require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
	require.Len(t, resp.Items, 1)
	require.Equal(t, "access denied", resp.Items[0].Error)
}

func TestMediaThumbnailsRejectsEmptyAndOversizedBatches(t *testing.T) {
	root := t.TempDir()
	h := newMediaThumbnailsHandler(t, root)

	rec := postThumbnails(t, h, `{"paths":[]}`)
	require.Equal(t, http.StatusBadRequest, rec.Code)

	paths := make([]string, 0, 65)
	for i := 0; i < 65; i++ {
		paths = append(paths, filepath.Join(root, "x.png"))
	}
	rec = postThumbnails(t, h, thumbnailsBody(t, paths))
	require.Equal(t, http.StatusBadRequest, rec.Code)
}

package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"net/url"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func TestGetFoldersReturnsNavigableRootPaths(t *testing.T) {
	root := t.TempDir()
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}

	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions), nil, nil, nil)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	rec := httptest.NewRecorder()

	if err := h.GetFolders(e.NewContext(req, rec)); err != nil {
		t.Fatalf("GetFolders returned error: %v", err)
	}

	var folders []models.Folder
	if err := json.Unmarshal(rec.Body.Bytes(), &folders); err != nil {
		t.Fatalf("failed to decode folders: %v", err)
	}
	if len(folders) != 1 {
		t.Fatalf("expected one root folder, got %d", len(folders))
	}
	if folders[0].RelativePath != root {
		t.Fatalf("expected relative path to equal root path, got %q", folders[0].RelativePath)
	}
}

func TestGetFoldersUsesDriveLabelForWindowsRoots(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows-specific root label behavior")
	}

	root := filepath.VolumeName(os.TempDir()) + string(filepath.Separator)
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}

	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions), nil, nil, nil)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	rec := httptest.NewRecorder()

	if err := h.GetFolders(e.NewContext(req, rec)); err != nil {
		t.Fatalf("GetFolders returned error: %v", err)
	}

	var folders []models.Folder
	if err := json.Unmarshal(rec.Body.Bytes(), &folders); err != nil {
		t.Fatalf("failed to decode folders: %v", err)
	}
	if len(folders) != 1 {
		t.Fatalf("expected one root folder, got %d", len(folders))
	}
	if folders[0].Name != root {
		t.Fatalf("expected drive root label %q, got %q", root, folders[0].Name)
	}
}

func TestBrowseFolderReturnsAbsoluteNavigablePaths(t *testing.T) {
	root := t.TempDir()
	childDir := filepath.Join(root, "cats")
	if err := os.MkdirAll(childDir, 0o755); err != nil {
		t.Fatalf("failed to create child dir: %v", err)
	}
	filePath := filepath.Join(root, "sample.mp4")
	if err := os.WriteFile(filePath, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create media file: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}

	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions), nil, nil, nil)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders/"+filepath.ToSlash(root)+"/browse", nil)
	rec := httptest.NewRecorder()
	ctx := e.NewContext(req, rec)
	ctx.SetParamNames("*")
	ctx.SetParamValues(filepath.ToSlash(root))

	if err := h.BrowseFolder(ctx); err != nil {
		t.Fatalf("BrowseFolder returned error: %v", err)
	}

	var result models.BrowseResult
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatalf("failed to decode browse result: %v", err)
	}

	if len(result.Folders) != 1 {
		t.Fatalf("expected one child folder, got %d", len(result.Folders))
	}
	if result.Folders[0].RelativePath != childDir {
		t.Fatalf("expected folder relative path %q, got %q", childDir, result.Folders[0].RelativePath)
	}

	if len(result.Files) != 1 {
		t.Fatalf("expected one media file, got %d", len(result.Files))
	}
	if result.Files[0].RelativePath != filePath {
		t.Fatalf("expected file relative path %q, got %q", filePath, result.Files[0].RelativePath)
	}
}

func TestBrowseFolderRouteStripsBrowseSuffix(t *testing.T) {
	root := t.TempDir()
	childDir := filepath.Join(root, "cats")
	if err := os.MkdirAll(childDir, 0o755); err != nil {
		t.Fatalf("failed to create child dir: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}

	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions), nil, nil, nil)
	e := echo.New()
	e.GET("/api/v1/folders/*", h.BrowseFolder)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders/"+filepath.ToSlash(root)+"/browse", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 for routed browse request, got %d with body %s", rec.Code, rec.Body.String())
	}
}

func TestBrowseFolderSerializesEmptyFilesAsArray(t *testing.T) {
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "cats"), 0o755); err != nil {
		t.Fatalf("failed to create child dir: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}

	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions), nil, nil, nil)
	e := echo.New()
	e.GET("/api/v1/folders/*", h.BrowseFolder)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders/"+filepath.ToSlash(root)+"/browse", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d with body %s", rec.Code, rec.Body.String())
	}
	if !strings.Contains(rec.Body.String(), "\"files\":[]") {
		t.Fatalf("expected empty files array in JSON, got %s", rec.Body.String())
	}
}

func TestBrowseFolderRouteDecodesEncodedUnicodePath(t *testing.T) {
	root := t.TempDir()
	childDir := filepath.Join(root, "(同人CG集) [グラスタートル] 某〇〇放送アナウンサー九条愉理子のアイとアナ")
	if err := os.MkdirAll(childDir, 0o755); err != nil {
		t.Fatalf("failed to create unicode child dir: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}

	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions), nil, nil, nil)
	e := echo.New()
	e.GET("/api/v1/folders/*", h.BrowseFolder)

	encodedPath := strings.ReplaceAll(url.PathEscape(filepath.ToSlash(childDir)), "%2F", "/")
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders/"+encodedPath+"/browse", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200 for encoded unicode browse request, got %d with body %s", rec.Code, rec.Body.String())
	}
}

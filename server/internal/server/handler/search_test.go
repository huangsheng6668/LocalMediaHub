package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)

func TestSearchScopesResultsToRequestedPathAndReturnsFolders(t *testing.T) {
	root := t.TempDir()
	catsDir := filepath.Join(root, "cats")
	catFavsDir := filepath.Join(catsDir, "cat-favs")
	dogsDir := filepath.Join(root, "dogs")

	for _, dir := range []string{catFavsDir, dogsDir} {
		if err := os.MkdirAll(dir, 0o755); err != nil {
			t.Fatalf("failed to create dir %q: %v", dir, err)
		}
	}

	catVideo := filepath.Join(catsDir, "cat-home.mp4")
	if err := os.WriteFile(catVideo, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create cat video: %v", err)
	}
	dogVideo := filepath.Join(dogsDir, "cat-hidden.mp4")
	if err := os.WriteFile(dogVideo, []byte("video"), 0o644); err != nil {
		t.Fatalf("failed to create dog video: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
	}
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)
	if _, err := scanner.Scan(cfg.Scan.GetRoots()); err != nil {
		t.Fatalf("failed to seed scanner cache: %v", err)
	}

	h := New(cfg, scanner, nil, nil, nil)
	e := echo.New()
	req := httptest.NewRequest(
		http.MethodGet,
		"/api/v1/search?q=cat&path="+catsDir,
		nil,
	)
	rec := httptest.NewRecorder()

	if err := h.Search(e.NewContext(req, rec)); err != nil {
		t.Fatalf("Search returned error: %v", err)
	}

	var result models.SearchResult
	if err := json.Unmarshal(rec.Body.Bytes(), &result); err != nil {
		t.Fatalf("failed to decode search result: %v", err)
	}

	if len(result.Folders) != 1 {
		t.Fatalf("expected one matching folder, got %d", len(result.Folders))
	}
	if result.Folders[0].Path != catFavsDir {
		t.Fatalf("expected folder path %q, got %q", catFavsDir, result.Folders[0].Path)
	}

	if len(result.Files) != 1 {
		t.Fatalf("expected one matching file in scoped path, got %d", len(result.Files))
	}
	if result.Files[0].Path != catVideo {
		t.Fatalf("expected file path %q, got %q", catVideo, result.Files[0].Path)
	}
	if result.Files[0].RelativePath != catVideo {
		t.Fatalf("expected search result relative path to be navigable absolute path, got %q", result.Files[0].RelativePath)
	}
}

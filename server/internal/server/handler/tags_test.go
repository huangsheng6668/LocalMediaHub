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

func TestGetTaggedMediaReturnsMatchingFiles(t *testing.T) {
	root := t.TempDir()
	filePath := filepath.Join(root, "favorite.mp4")
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
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)
	if _, err := scanner.Scan(cfg.Scan.GetRoots()); err != nil {
		t.Fatalf("failed to seed scanner cache: %v", err)
	}

	tagsService, err := service.NewTagsService(t.TempDir())
	if err != nil {
		t.Fatalf("failed to create tags service: %v", err)
	}
	tag, err := tagsService.CreateTag("Favorites", "#ff0000")
	if err != nil {
		t.Fatalf("failed to create tag: %v", err)
	}
	if _, err := tagsService.AssociateFile(tag.ID, filePath); err != nil {
		t.Fatalf("failed to associate file: %v", err)
	}

	h := New(cfg, scanner, tagsService, nil, nil)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/tags/"+tag.ID+"/media", nil)
	rec := httptest.NewRecorder()
	ctx := e.NewContext(req, rec)
	ctx.SetParamNames("tag_id")
	ctx.SetParamValues(tag.ID)

	if err := h.GetTaggedMedia(ctx); err != nil {
		t.Fatalf("GetTaggedMedia returned error: %v", err)
	}

	var files []models.MediaFile
	if err := json.Unmarshal(rec.Body.Bytes(), &files); err != nil {
		t.Fatalf("failed to decode tagged media: %v", err)
	}

	if len(files) != 1 {
		t.Fatalf("expected one tagged media file, got %d", len(files))
	}
	if files[0].Path != filePath {
		t.Fatalf("expected file path %q, got %q", filePath, files[0].Path)
	}
	if files[0].RelativePath != filePath {
		t.Fatalf("expected navigable relative path %q, got %q", filePath, files[0].RelativePath)
	}
}

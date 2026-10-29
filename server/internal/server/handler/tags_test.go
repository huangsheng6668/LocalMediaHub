package handler

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
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
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
	if _, err := scanner.Scan(context.Background(), cfg.Scan.GetRoots()); err != nil {
		t.Fatalf("failed to seed scanner cache: %v", err)
	}

	tagsService, err := service.NewTagsService(t.TempDir())
	if err != nil {
		t.Fatalf("failed to create tags service: %v", err)
	}
	defer tagsService.Close()
	tag, err := tagsService.CreateTag("Favorites", "#ff0000")
	if err != nil {
		t.Fatalf("failed to create tag: %v", err)
	}
	if _, err := tagsService.AssociateFile(tag.ID, filePath); err != nil {
		t.Fatalf("failed to associate file: %v", err)
	}

	h := New(cfg, scanner, tagsService, nil, nil, nil, nil, nil)
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

func TestTagsPathNormalizationAndStaleRootGuard(t *testing.T) {
	root1 := t.TempDir()
	root2 := t.TempDir()
	outsideFile := filepath.Join(root2, "outside.mp4")
	if err := os.WriteFile(outsideFile, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root1, root2},
			VideoExtensions: []string{".mp4"},
		},
	}
	ts, err := service.NewTagsService(filepath.Join(t.TempDir(), "tags.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()
	sc := service.NewScanner(cfg.Scan.VideoExtensions, nil, nil)
	h := New(cfg, sc, ts, nil, nil, nil, nil, nil)

	tag, err := ts.CreateTag("fav", "#ff0000")
	if err != nil {
		t.Fatal(err)
	}

	// Associate with forward slashes & URL-encoded path
	e := echo.New()
	encoded := url.PathEscape(filepath.ToSlash(outsideFile))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/tags/"+tag.ID+"/files/"+encoded, nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.SetPath("/api/v1/tags/:tag_id/files/*")
	c.SetParamNames("tag_id", "*")
	c.SetParamValues(tag.ID, encoded)
	if err := h.AssociateTag(c); err != nil {
		t.Fatal(err)
	}
	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", rec.Code, rec.Body.String())
	}

	// Now narrow roots to root1 only; GetTaggedMedia must NOT return outsideFile via fallback
	_ = cfg.Scan.SetRoots([]string{root1})
	reqMedia := httptest.NewRequest(http.MethodGet, "/api/v1/tags/"+tag.ID+"/media", nil)
	recMedia := httptest.NewRecorder()
	cMedia := e.NewContext(reqMedia, recMedia)
	cMedia.SetParamNames("tag_id")
	cMedia.SetParamValues(tag.ID)
	if err := h.GetTaggedMedia(cMedia); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(recMedia.Body.String(), "outside.mp4") {
		t.Fatalf("expected outside.mp4 to be filtered out after root narrowing, got %s", recMedia.Body.String())
	}
}

func TestCreateTagValidation(t *testing.T) {
	ts, err := service.NewTagsService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()
	h := New(&config.Config{}, nil, ts, nil, nil, nil, nil, nil)
	e := echo.New()

	tests := []struct {
		name       string
		tagName    string
		tagColor   string
		wantStatus int
	}{
		{"empty name", "", "#fff", http.StatusBadRequest},
		{"whitespace name", "   ", "#fff", http.StatusBadRequest},
		{"name too long", strings.Repeat("a", 65), "#fff", http.StatusBadRequest},
		{"invalid color name", "valid_name", "red", http.StatusBadRequest},
		{"color too short", "valid_name", "#12", http.StatusBadRequest},
		{"color too long", "valid_name", "#123456789", http.StatusBadRequest},
		{"valid 3-hex color", "tag3", "#abc", http.StatusCreated},
		{"valid 6-hex color", "tag6", "#aabbcc", http.StatusCreated},
		{"valid 8-hex color", "tag8", "#aabbccdd", http.StatusCreated},
		{"empty color optional", "tag_no_color", "", http.StatusCreated},
		{"duplicate name conflict", "tag3", "#123", http.StatusConflict},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			body, _ := json.Marshal(CreateTagRequest{Name: tt.tagName, Color: tt.tagColor})
			req := httptest.NewRequest(http.MethodPost, "/api/v1/tags", bytes.NewReader(body))
			req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)
			if err := h.CreateTag(c); err != nil {
				t.Fatal(err)
			}
			if rec.Code != tt.wantStatus {
				t.Fatalf("expected status %d, got %d: %s", tt.wantStatus, rec.Code, rec.Body.String())
			}
		})
	}
}

func TestAssociateAndDisassociateTagWithBlockedRoot(t *testing.T) {
	root := t.TempDir()
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots: []string{root},
		},
	}
	ts, err := service.NewTagsService(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()
	h := New(cfg, nil, ts, nil, nil, nil, nil, nil)
	e := echo.New()

	tag, err := ts.CreateTag("test_tag", "#ffffff")
	if err != nil {
		t.Fatal(err)
	}

	// Blocked root path
	blockedPath := `C:\Windows\System32\cmd.exe`
	encoded := url.PathEscape(filepath.ToSlash(blockedPath))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/tags/"+tag.ID+"/files/"+encoded, nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.SetPath("/api/v1/tags/:tag_id/files/*")
	c.SetParamNames("tag_id", "*")
	c.SetParamValues(tag.ID, encoded)

	if err := h.AssociateTag(c); err != nil {
		t.Fatal(err)
	}
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 Forbidden for blocked root, got %d: %s", rec.Code, rec.Body.String())
	}
}

package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

// TestUpdateConfigRejectsBlockedRoot verifies that PUT /admin/config with a
// sensitive system directory as a scan root is rejected with 400 before
// Validate even runs (Phase 8 T8-01). The four cases mirror the blockedSegments
// list entries that an operator is most likely to misconfigure.
func TestUpdateConfigRejectsBlockedRoot(t *testing.T) {
	cases := []string{
		`C:\Windows`,
		`C:\Program Files`,
		`C:\Program Files (x86)`,
		`D:\$Recycle.Bin`,
	}
	for _, blocked := range cases {
		t.Run(blocked, func(t *testing.T) {
			// Construct the handler directly (same pattern as folders_test.go):
			// only cfg + scanner are needed; tags/streaming/thumbnail are nil
			// because UpdateConfig never touches them. Initial roots point at
			// a valid temp dir so any pre-existing state is clean.
			validRoot := t.TempDir()
			cfg := &config.Config{
				Scan: config.ScanConfig{
					Roots:           []string{validRoot},
					VideoExtensions: []string{".mp4"},
					ImageExtensions: []string{".jpg"},
				},
				Thumbnail: config.ThumbnailConfig{
					CacheDir: filepath.Join(t.TempDir(), "thumb"),
					MaxSize:  64,
					Format:   "jpeg",
				},
			}
			h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions), nil, nil, nil, nil, nil)

			e := echo.New()
			body, _ := json.Marshal(map[string][]string{"roots": {blocked}})
			req := httptest.NewRequest(http.MethodPut, "/api/v1/admin/config", bytes.NewReader(body))
			req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			if err := h.UpdateConfig(c); err != nil {
				t.Fatalf("UpdateConfig returned error: %v", err)
			}
			if rec.Code != http.StatusBadRequest {
				t.Errorf("blocked root %q: status = %d, want 400; body=%s",
					blocked, rec.Code, rec.Body.String())
			}
			if !bytes.Contains(rec.Body.Bytes(), []byte("restricted system directory")) {
				t.Errorf("blocked root %q: body missing expected text; got %s",
					blocked, rec.Body.String())
			}
		})
	}
}

// TestTranscodeStatusEndpoint verifies the admin transcode status payload:
// the session cap from the service constructor and a not-yet-probed
// encoder chain (empty auto / usable — the endpoint must NOT force a probe).
func TestTranscodeStatusEndpoint(t *testing.T) {
	streaming := service.NewStreamingService("", []string{"h264_nvenc", "h264_qsv"}, 2)
	h := New(&config.Config{}, nil, nil, streaming, nil, nil, nil)

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/transcode/status", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	if err := h.TranscodeStatus(c); err != nil {
		t.Fatalf("TranscodeStatus returned error: %v", err)
	}
	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200; body=%s", rec.Code, rec.Body.String())
	}
	var got struct {
		Active      int `json:"active"`
		MaxSessions int `json:"max_sessions"`
		Probe       struct {
			Auto       string   `json:"auto"`
			Usable     []string `json:"usable"`
			Preference []string `json:"preference"`
		} `json:"probe"`
	}
	if err := json.Unmarshal(rec.Body.Bytes(), &got); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if got.Active != 0 {
		t.Errorf("active = %d, want 0", got.Active)
	}
	if got.MaxSessions != 2 {
		t.Errorf("max_sessions = %d, want 2", got.MaxSessions)
	}
	if got.Probe.Auto != "" || len(got.Probe.Usable) != 0 {
		t.Errorf("probe = %+v, want unprobed (empty auto/usable)", got.Probe)
	}
	if len(got.Probe.Preference) != 2 {
		t.Errorf("preference = %v, want 2 entries", got.Probe.Preference)
	}
}

// TestUpdateConfigAcceptsValidRoot is the positive control for T8-01: a normal
// media directory must still be accepted so the guard doesn't over-block.
func TestUpdateConfigAcceptsValidRoot(t *testing.T) {
	validRoot := t.TempDir()
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{t.TempDir()},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: filepath.Join(t.TempDir(), "thumb"),
			MaxSize:  64,
			Format:   "jpeg",
		},
	}
	h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions), nil, nil, nil, nil, nil)

	e := echo.New()
	body, _ := json.Marshal(map[string][]string{"roots": {validRoot}})
	req := httptest.NewRequest(http.MethodPut, "/api/v1/admin/config", bytes.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	if err := h.UpdateConfig(c); err != nil {
		t.Fatalf("UpdateConfig returned error: %v", err)
	}
	if rec.Code != http.StatusOK {
		t.Errorf("valid root %q: status = %d, want 200; body=%s",
			validRoot, rec.Code, rec.Body.String())
	}
}

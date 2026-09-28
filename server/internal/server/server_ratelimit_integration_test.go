package server

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/labstack/echo/v4"
)

// TestRateLimitOnScanTriggerIntegration verifies that the RateLimit middleware
// mounted on /admin/scan/trigger actually fires on the real route. This is the
// Go httptest fallback for the manual curl spam test described in the task brief
// (Step 7), chosen because Windows background-process management for the manual
// curl approach is fragile in this environment.
//
// Expected sequence: 200, 200, 429, 429, 429 (limit = 2 per 30s window).
func TestRateLimitOnScanTriggerIntegration(t *testing.T) {
	cfg := newAuthTestConfig(t, "integration-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	wantCodes := []int{200, 200, 429, 429, 429}
	for i, want := range wantCodes {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/admin/scan/trigger", nil)
		req.Header.Set(echo.HeaderAuthorization, "Bearer integration-token")
		req.RemoteAddr = "192.168.1.100:1234"
		rec := httptest.NewRecorder()
		srv.Echo.ServeHTTP(rec, req)
		if rec.Code != want {
			t.Errorf("request %d: status = %d, want %d (body=%s)", i+1, rec.Code, want, rec.Body.String())
		}
	}
}

// TestImageOriginalsNotRateLimited verifies that original image assets are NOT
// caught by the thumbnail flood-protection rate limit. The web lightbox stitch
// mode loads every page of a manga folder at once (hundreds of /original
// requests within a minute) and 429s there render the whole comic browser as
// broken images. The rate limit exists to blunt thumbnail-generation floods
// (each miss decodes a full-size image), not to cap plain file sends.
func TestImageOriginalsNotRateLimited(t *testing.T) {
	cfg := newAuthTestConfig(t, "img-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	// 120 rapid requests = double the 60/min thumbnail limit. Every response
	// must be non-429 (they will be 403: the path is outside the scan roots,
	// but the rate limiter fires before the handler so a limited request
	// would surface as 429 regardless).
	for i := 0; i < 120; i++ {
		req := httptest.NewRequest(http.MethodGet, "/api/v1/images/C%3A/nonexistent/page.jpg/original", nil)
		req.Header.Set(echo.HeaderAuthorization, "Bearer img-token")
		req.RemoteAddr = "192.168.1.101:1234"
		rec := httptest.NewRecorder()
		srv.Echo.ServeHTTP(rec, req)
		if rec.Code == http.StatusTooManyRequests {
			t.Fatalf("request %d: /original got 429 — original assets must not be rate limited (body=%s)", i+1, rec.Body.String())
		}
	}
}

// TestImageThumbnailsStillRateLimited keeps the Phase 9 (M-3) protection
// intact after scoping the limiter to thumbnails: each thumbnail miss decodes
// a full-size image (a CPU amplifier under filename enumeration), so
// /thumbnail requests stay capped at 60/min per client IP.
func TestImageThumbnailsStillRateLimited(t *testing.T) {
	cfg := newAuthTestConfig(t, "img-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	url := "/api/v1/images/C%3A/nonexistent/page.jpg/thumbnail"
	for i := 0; i < 60; i++ {
		req := httptest.NewRequest(http.MethodGet, url, nil)
		req.Header.Set(echo.HeaderAuthorization, "Bearer img-token")
		req.RemoteAddr = "192.168.1.102:1234"
		rec := httptest.NewRecorder()
		srv.Echo.ServeHTTP(rec, req)
		if rec.Code == http.StatusTooManyRequests {
			t.Fatalf("request %d: got 429 too early (limit should be 60/min)", i+1)
		}
	}

	req := httptest.NewRequest(http.MethodGet, url, nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer img-token")
	req.RemoteAddr = "192.168.1.102:1234"
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("request 61: status = %d, want 429", rec.Code)
	}
}

// TestRateLimitOnDeleteIntegration verifies the RateLimit middleware mounted on
// /system/delete allows the first 5 requests per minute then rejects the 6th.
// We don't need a valid path to delete — the rate limiter fires before the
// handler, so the response will be either 200 (handler ran, may error on bad
// path) or 429 (rate limited). We only assert the 429 boundary.
func TestRateLimitOnDeleteIntegration(t *testing.T) {
	cfg := newAuthTestConfig(t, "delete-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	// Fire 6 requests; first 5 should NOT be 429, 6th MUST be 429.
	for i := 1; i <= 5; i++ {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/system/delete?path=/nonexistent", nil)
		req.Header.Set(echo.HeaderAuthorization, "Bearer delete-token")
		req.RemoteAddr = "10.0.0.1:1234"
		rec := httptest.NewRecorder()
		srv.Echo.ServeHTTP(rec, req)
		if rec.Code == http.StatusTooManyRequests {
			t.Errorf("request %d: got 429 too early (limit should be 5/min)", i)
		}
	}

	// 6th request should hit the rate limit.
	req := httptest.NewRequest(http.MethodPost, "/api/v1/system/delete?path=/nonexistent", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer delete-token")
	req.RemoteAddr = "10.0.0.1:1234"
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("request 6: status = %d, want 429", rec.Code)
	}
}

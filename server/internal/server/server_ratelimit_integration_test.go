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

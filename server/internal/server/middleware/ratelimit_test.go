package middleware

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/labstack/echo/v4"
)

func TestRateLimit(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}

	// 2 req per short window for fast tests
	mw := RateLimit(2, 100*time.Millisecond)
	wrapped := mw(handler)

	// First 2 requests pass
	for i := 1; i <= 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = "1.2.3.4:5678"
		rec := httptest.NewRecorder()
		c := e.NewContext(req, rec)
		if err := wrapped(c); err != nil {
			t.Fatalf("req %d: unexpected error %v", i, err)
		}
		if rec.Code != http.StatusOK {
			t.Errorf("req %d: status = %d, want 200", i, rec.Code)
		}
	}

	// Third request within window → 429
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "1.2.3.4:5678"
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	_ = wrapped(c)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("req 3: status = %d, want 429", rec.Code)
	}
}

func TestRateLimitWindowReset(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}
	mw := RateLimit(1, 50*time.Millisecond) // short window for fast test
	wrapped := mw(handler)

	// First request passes
	req1 := httptest.NewRequest(http.MethodGet, "/", nil)
	req1.RemoteAddr = "1.2.3.4:5678"
	rec1 := httptest.NewRecorder()
	wrapped(e.NewContext(req1, rec1))
	if rec1.Code != http.StatusOK {
		t.Errorf("req 1: status = %d, want 200", rec1.Code)
	}

	// Second request immediately → 429
	req2 := httptest.NewRequest(http.MethodGet, "/", nil)
	req2.RemoteAddr = "1.2.3.4:5678"
	rec2 := httptest.NewRecorder()
	wrapped(e.NewContext(req2, rec2))
	if rec2.Code != http.StatusTooManyRequests {
		t.Errorf("req 2 (immediate): status = %d, want 429", rec2.Code)
	}

	// Wait for window to reset
	time.Sleep(60 * time.Millisecond)

	// Third request after window → 200
	req3 := httptest.NewRequest(http.MethodGet, "/", nil)
	req3.RemoteAddr = "1.2.3.4:5678"
	rec3 := httptest.NewRecorder()
	wrapped(e.NewContext(req3, rec3))
	if rec3.Code != http.StatusOK {
		t.Errorf("req 3 (after reset): status = %d, want 200", rec3.Code)
	}
}

func TestRateLimitPerIPIsolation(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}
	mw := RateLimit(1, time.Hour)
	wrapped := mw(handler)

	// IP A exhausts its bucket
	reqA := httptest.NewRequest(http.MethodGet, "/", nil)
	reqA.RemoteAddr = "10.0.0.1:1234"
	recA := httptest.NewRecorder()
	wrapped(e.NewContext(reqA, recA))

	// IP B should be unaffected
	reqB := httptest.NewRequest(http.MethodGet, "/", nil)
	reqB.RemoteAddr = "10.0.0.2:5678"
	recB := httptest.NewRecorder()
	wrapped(e.NewContext(reqB, recB))
	if recB.Code != http.StatusOK {
		t.Errorf("IP B should not be affected by IP A's limit: status = %d", recB.Code)
	}
}

// TestRateLimitConcurrentSafety verifies the mutex protects the buckets map
// under concurrent access. Run with -race to detect data races.
func TestRateLimitConcurrentSafety(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}
	mw := RateLimit(100, time.Hour)
	wrapped := mw(handler)

	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			req.RemoteAddr = "1.2.3.4:1234"
			rec := httptest.NewRecorder()
			wrapped(e.NewContext(req, rec))
		}(i)
	}
	wg.Wait()
	// No panic / data race = pass. Run with -race to verify.
}

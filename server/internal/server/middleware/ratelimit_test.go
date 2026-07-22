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

func TestRateLimitLRUEviction(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusNotFound, "nf")
	}
	mw := RateLimitWithConfig(5, time.Minute, 3)
	wrapped := mw(handler)

	for _, ip := range []string{"1.1.1.1", "2.2.2.2", "3.3.3.3"} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = ip + ":1234"
		rec := httptest.NewRecorder()
		_ = wrapped(e.NewContext(req, rec))
		if rec.Code != http.StatusNotFound {
			t.Fatalf("ip %s: got %d, want 404", ip, rec.Code)
		}
	}

	// Insert a 4th distinct IP. Capacity is 3, so the LRU (1.1.1.1) is evicted.
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "4.4.4.4:1234"
	rec := httptest.NewRecorder()
	_ = wrapped(e.NewContext(req, rec))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("ip4: got %d, want 404", rec.Code)
	}

	// Re-entry of evicted IP 1.1.1.1 must be treated as a fresh bucket (pass,
	// not 429), proving its previous counter state was discarded.
	// Exhaust its fresh bucket first to verify reset: 5 requests allowed.
	for i := 0; i < 5; i++ {
		reqR := httptest.NewRequest(http.MethodGet, "/", nil)
		reqR.RemoteAddr = "1.1.1.1:1234"
		recR := httptest.NewRecorder()
		_ = wrapped(e.NewContext(reqR, recR))
		if recR.Code != http.StatusNotFound {
			t.Fatalf("evicted IP re-entry req %d: got %d, want 404 (fresh bucket)", i, recR.Code)
		}
	}
	// 6th request on the fresh bucket should now be rate-limited.
	req6 := httptest.NewRequest(http.MethodGet, "/", nil)
	req6.RemoteAddr = "1.1.1.1:1234"
	rec6 := httptest.NewRecorder()
	_ = wrapped(e.NewContext(req6, rec6))
	if rec6.Code != http.StatusTooManyRequests {
		t.Fatalf("evicted IP after refill: got %d, want 429", rec6.Code)
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

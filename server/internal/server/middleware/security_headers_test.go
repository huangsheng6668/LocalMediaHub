package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/labstack/echo/v4"
)

func TestSecurityHeaders(t *testing.T) {
	// Expected header values — keep in sync with security_headers.go.
	// CSP is verbatim per Global Constraint.
	// media-src must include blob:: hls.js (MSE) playback assigns a
	// URL.createObjectURL(MediaSource) blob: URL to <video>, and CSP 'self'
	// does not match the blob: scheme — without it Chrome rejects the source
	// ("Media load rejected by URL safety check") and every transcoded
	// (non-native-container) video plays as a black screen.
	// worker-src must include blob:: hls.js spawns its transmuxer worker
	// from a blob: URL; without an explicit worker-src the script-src
	// fallback blocks it (hls.js then degrades to main-thread demuxing).
	expectedHeaders := map[string]string{
		"X-Frame-Options":         "DENY",
		"X-Content-Type-Options":  "nosniff",
		"Referrer-Policy":         "no-referrer",
		"Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self' blob:; connect-src 'self'; worker-src 'self' blob:; base-uri 'none'; object-src 'none'; form-action 'self'",
	}

	e := echo.New()
	handlerCalled := false
	handler := func(c echo.Context) error {
		handlerCalled = true
		return c.String(http.StatusOK, "ok")
	}

	req := httptest.NewRequest(http.MethodGet, "/anything", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	mw := SecurityHeaders()(handler)
	if err := mw(c); err != nil {
		t.Fatalf("middleware returned error: %v", err)
	}

	if !handlerCalled {
		t.Errorf("inner handler was NOT called — middleware must pass through to next")
	}

	for name, want := range expectedHeaders {
		got := rec.Header().Get(name)
		if got != want {
			t.Errorf("header %q = %q, want %q", name, got, want)
		}
	}
}

func TestSecurityHeadersAppliesToAllMethods(t *testing.T) {
	// Verify headers are set regardless of HTTP method (OPTIONS preflight,
	// POST, DELETE — all go through the same middleware chain).
	methods := []string{
		http.MethodGet,
		http.MethodPost,
		http.MethodPut,
		http.MethodDelete,
		http.MethodOptions,
	}

	for _, method := range methods {
		t.Run(method, func(t *testing.T) {
			e := echo.New()
			handler := func(c echo.Context) error {
				return c.String(http.StatusOK, "ok")
			}

			req := httptest.NewRequest(method, "/anything", nil)
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			mw := SecurityHeaders()(handler)
			_ = mw(c)

			if got := rec.Header().Get("X-Frame-Options"); got != "DENY" {
				t.Errorf("method %s: X-Frame-Options = %q, want DENY", method, got)
			}
		})
	}
}

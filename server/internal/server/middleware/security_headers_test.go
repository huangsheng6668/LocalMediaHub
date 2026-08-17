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
	expectedHeaders := map[string]string{
		"X-Frame-Options":         "DENY",
		"X-Content-Type-Options":  "nosniff",
		"Referrer-Policy":         "no-referrer",
		"Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; base-uri 'none'; object-src 'none'; form-action 'self'",
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

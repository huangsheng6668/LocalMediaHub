package middleware

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/labstack/echo/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBearerToken(t *testing.T) {
	cases := []struct {
		name        string
		configToken string // token configured on the middleware
		authHeader  string // client-supplied Authorization header
		wantStatus  int
		wantCalled  bool // whether the inner handler should be invoked
	}{
		{
			name:        "empty config token passes through (open mode)",
			configToken: "",
			authHeader:  "",
			wantStatus:  http.StatusOK,
			wantCalled:  true,
		},
		{
			name:        "correct token allows access",
			configToken: "secret123",
			authHeader:  "Bearer secret123",
			wantStatus:  http.StatusOK,
			wantCalled:  true,
		},
		{
			name:        "missing Authorization header rejects with 401",
			configToken: "secret123",
			authHeader:  "",
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
		{
			name:        "wrong token rejects with 401",
			configToken: "secret123",
			authHeader:  "Bearer wrongpass",
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
		{
			name:        "malformed header (no Bearer prefix) rejects with 401",
			configToken: "secret123",
			authHeader:  "secret123",
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
		{
			name:        "timing-safe comparison: prefix-correct but wrong tail rejects",
			configToken: "secret123",
			authHeader:  "Bearer secret12", // one char short
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := echo.New()
			called := false
			handler := func(c echo.Context) error {
				called = true
				return c.String(http.StatusOK, "ok")
			}

			req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
			if tc.authHeader != "" {
				req.Header.Set(echo.HeaderAuthorization, tc.authHeader)
			}
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			mw := BearerToken(tc.configToken)(handler)
			err := mw(c)

			if tc.wantCalled && !called {
				t.Errorf("inner handler was not called, expected it to be called")
			}
			if !tc.wantCalled && called {
				t.Errorf("inner handler was called, expected it to be rejected")
			}

			if tc.wantStatus == http.StatusOK {
				if err != nil {
					t.Errorf("expected no error, got %v", err)
				}
				if rec.Code != http.StatusOK {
					t.Errorf("status = %d, want %d", rec.Code, tc.wantStatus)
				}
			} else {
				if rec.Code != tc.wantStatus {
					t.Errorf("status = %d, want %d", rec.Code, tc.wantStatus)
				}
				body := rec.Body.String()
				if !strings.Contains(body, `"error"`) {
					t.Errorf("401 body should be JSON envelope, got: %s", body)
				}
			}
		})
	}
}

func TestBearerTokenAcceptsTokenInQueryParam(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/img?token=secret", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := BearerToken("secret")(func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	})
	err := h(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBearerTokenHeaderTakesPrecedenceOverQueryParam(t *testing.T) {
	e := echo.New()
	// header has correct token; query has wrong token. Should pass.
	req := httptest.NewRequest(http.MethodGet, "/api?token=wrong", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer secret")
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := BearerToken("secret")(func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	})
	err := h(c)
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBearerTokenRejectsInvalidQueryParamToken(t *testing.T) {
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/img?token=wrong", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	h := BearerToken("secret")(func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	})
	_ = h(c)
	assert.Equal(t, http.StatusUnauthorized, rec.Code)
}

// TestBearerTokenQueryFallbackGetOnly locks the hardening that ?token= is
// accepted on GET only: a POST carrying the correct token purely in the query
// string must be rejected (mutations require the Authorization header), while
// the same POST with the header passes.
func TestBearerTokenQueryFallbackGetOnly(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}

	// Correct token in query only, POST method → 401.
	req := httptest.NewRequest(http.MethodPost, "/api/system/delete?token=secret", nil)
	rec := httptest.NewRecorder()
	_ = BearerToken("secret")(handler)(e.NewContext(req, rec))
	assert.Equal(t, http.StatusUnauthorized, rec.Code)

	// Correct token in header, POST method → 200 (header path unaffected).
	req = httptest.NewRequest(http.MethodPost, "/api/system/delete", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer secret")
	rec = httptest.NewRecorder()
	err := BearerToken("secret")(handler)(e.NewContext(req, rec))
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, rec.Code)
}

func TestAuthFailureLimiterBlocksAfterBurst(t *testing.T) {
	l := NewAuthFailureLimiter(3, time.Minute)
	now := time.Now()
	for i := 0; i < 3; i++ {
		l.RecordFailure("10.0.0.9", now)
	}
	if !l.Blocked("10.0.0.9", now) {
		t.Fatal("expected blocked after 3 failures")
	}
	if l.Blocked("10.0.0.8", now) {
		t.Fatal("other IP must not be blocked")
	}
	l.Reset("10.0.0.9")
	if l.Blocked("10.0.0.9", now.Add(time.Second)) {
		t.Fatal("reset must clear the window")
	}
	// 窗口过期自动解封
	l.RecordFailure("10.0.0.9", now)
	if l.Blocked("10.0.0.9", now.Add(2*time.Minute)) {
		t.Fatal("window expiry must unblock")
	}
}

func TestBearerTokenReturns429WhenFailureLimited(t *testing.T) {
	e := echo.New()
	limiter := NewAuthFailureLimiter(2, time.Minute)
	e.GET("/p", func(c echo.Context) error { return c.NoContent(200) },
		BearerToken("sekrit", limiter))
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/p", nil)
		req.Header.Set("Authorization", "Bearer wrong")
		rec := httptest.NewRecorder()
		e.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d = %d, want 401", i, rec.Code)
		}
	}
	req := httptest.NewRequest(http.MethodGet, "/p", nil)
	req.Header.Set("Authorization", "Bearer wrong")
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("third attempt = %d, want 429", rec.Code)
	}
	// 正确 token 在限速期内也被拒（防爆破期间绕过）
	req2 := httptest.NewRequest(http.MethodGet, "/p", nil)
	req2.Header.Set("Authorization", "Bearer sekrit")
	rec2 := httptest.NewRecorder()
	e.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusTooManyRequests {
		t.Fatalf("valid token during block = %d, want 429", rec2.Code)
	}
}

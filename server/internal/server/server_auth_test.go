package server

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/localmediahub/server/internal/config"
)

// newAuthTestConfig returns a config with the minimum fields required by New()
// (scan extensions + a valid thumbnail cache dir) plus the given token. This
// lets the integration tests focus on auth behaviour without each test repeating
// the boilerplate config setup.
func newAuthTestConfig(t *testing.T, token string) *config.Config {
	t.Helper()
	return &config.Config{
		Server: config.ServerConfig{
			Host:  "127.0.0.1",
			Port:  0,
			Token: token,
		},
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
}

func TestServerRejectsAdminWithoutToken(t *testing.T) {
	cfg := newAuthTestConfig(t, "required-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer wrong-token")
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestServerAcceptsAdminWithCorrectToken(t *testing.T) {
	cfg := newAuthTestConfig(t, "required-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer required-token")
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code == http.StatusUnauthorized {
		t.Errorf("status = 401, want non-401 (token should be accepted)")
	}
}

func TestServerOpenModeWhenTokenEmpty(t *testing.T) {
	cfg := newAuthTestConfig(t, "")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code == http.StatusUnauthorized {
		t.Errorf("status = 401, want non-401 (open mode should pass through)")
	}
}

func TestServerOpenModeAllowsDeletion(t *testing.T) {
	tmpDir := t.TempDir()
	testFile := filepath.Join(tmpDir, "test.txt")
	if err := os.WriteFile(testFile, []byte("hello"), 0644); err != nil {
		t.Fatalf("WriteFile failed: %v", err)
	}

	cfg := newAuthTestConfig(t, "")
	cfg.System.EnableDelete = true
	cfg.Scan.Roots = []string{tmpDir}

	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	body := `{"path":"` + filepath.ToSlash(testFile) + `","recursive":false}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/system/delete", strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if _, err := os.Stat(testFile); !os.IsNotExist(err) {
		t.Errorf("expected test file to be deleted, but it still exists")
	}
}

func TestServerRejectsLibraryWithoutToken(t *testing.T) {
	cfg := newAuthTestConfig(t, "required-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/library/favorites", nil)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestServerAcceptsLibraryWithCorrectToken(t *testing.T) {
	cfg := newAuthTestConfig(t, "required-token")
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	req := httptest.NewRequest(http.MethodGet, "/api/v1/library/favorites", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer required-token")
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want 200 (body: %s)", rec.Code, rec.Body.String())
	}
}



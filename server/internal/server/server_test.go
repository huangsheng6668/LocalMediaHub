package server

import (
	"bytes"
	"compress/gzip"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/labstack/echo/v4"
	echoMw "github.com/labstack/echo/v4/middleware"
	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/server/handler"
	"github.com/localmediahub/server/internal/service"
)

func TestRegisterRoutesServesThumbnailEndpoint(t *testing.T) {
	root := t.TempDir()
	cacheDir := filepath.Join(t.TempDir(), "thumb-cache")
	imagePath := filepath.Join(root, "cover.jpg")

	file, err := os.Create(imagePath)
	if err != nil {
		t.Fatalf("failed to create image file: %v", err)
	}
	img := image.NewRGBA(image.Rect(0, 0, 8, 8))
	for y := 0; y < 8; y++ {
		for x := 0; x < 8; x++ {
			img.Set(x, y, color.RGBA{R: 120, G: 200, B: 180, A: 255})
		}
	}
	if err := jpeg.Encode(file, img, nil); err != nil {
		file.Close()
		t.Fatalf("failed to encode jpeg: %v", err)
	}
	if err := file.Close(); err != nil {
		t.Fatalf("failed to close image file: %v", err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg", ".jpeg", ".png"},
		},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir,
			MaxSize:  256,
			Format:   "jpeg",
		},
	}
	s := &Server{Echo: newTestEcho(), Config: cfg}
	thumbnailService, err := service.NewThumbnailService(cfg.Thumbnail.CacheDir, cfg.Thumbnail.MaxSize, cfg.Thumbnail.Format, "")
	if err != nil {
		t.Fatalf("failed to create thumbnail service: %v", err)
	}
	h := handler.New(
		cfg,
		service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions),
		nil,
		service.NewStreamingService(""),
		thumbnailService,
		nil,
	)
	s.registerRoutes(h)

	requestPath := "/api/v1/images/" + strings.ReplaceAll(filepath.ToSlash(imagePath), " ", "%20") + "/thumbnail"
	req := httptest.NewRequest(http.MethodGet, requestPath, nil)
	rec := httptest.NewRecorder()

	s.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected thumbnail route to return 200, got %d with body %s", rec.Code, rec.Body.String())
	}
	// Cache-Control header is for browser clients (embedded web gallery loads
	// thumbnails via <img> tags). Coil 3.x on Android ignores these headers
	// (bypasses OkHttp Cache), but browsers honor them. See setMediaCacheHeaders.
	if got := rec.Result().Header.Get("Cache-Control"); got != "public, max-age=86400" {
		t.Fatalf("expected thumbnail Cache-Control 'public, max-age=86400', got %q", got)
	}
}

func newTestEcho() *echo.Echo {
	e := echo.New()
	e.HideBanner = true
	return e
}

func TestServerStartAndStopGracefulShutdown(t *testing.T) {
	// 选取一个空闲端口供 Start 绑定。
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()

	cacheDir := filepath.Join(t.TempDir(), "thumb")
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: port},
		Scan:   config.ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir, MaxSize: 64, Format: "jpeg",
		},
	}
	s, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	startErr := make(chan error, 1)
	go func() { startErr <- s.Start() }()

	healthURL := fmt.Sprintf("http://127.0.0.1:%d/api/v1/health", port)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		resp, err := http.Get(healthURL)
		if err == nil {
			resp.Body.Close()
			if resp.StatusCode == http.StatusOK {
				break
			}
		}
		time.Sleep(50 * time.Millisecond)
	}

	// 确认超时已配置。
	if s.httpServer == nil {
		t.Fatal("expected httpServer to be configured")
	}
	if s.httpServer.ReadHeaderTimeout <= 0 {
		t.Error("expected ReadHeaderTimeout > 0")
	}

	if err := s.Stop(); err != nil {
		t.Fatalf("Stop: %v", err)
	}
	if err := <-startErr; err != nil && err != http.ErrServerClosed {
		t.Fatalf("Start returned unexpected error: %v", err)
	}
}

func TestRegisterRoutesJsonCacheControl(t *testing.T) {
	root := t.TempDir()
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: 0},
		Scan:   config.ScanConfig{Roots: []string{root}, VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: filepath.Join(t.TempDir(), "thumb"), MaxSize: 64, Format: "jpeg",
		},
	}
	s, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer s.Stop()

	cases := []struct {
		path      string
		wantCache string
	}{
		// brief = 5s — endpoints that change when scan/add/delete files.
		// Kept short on purpose (commit 205768e lowered from 60s) so clients
		// re-fetching right after a delete/add see fresh data, not a stale
		// max-age=60 response.
		{"/api/v1/folders", "private, max-age=5"},
		{"/api/v1/search?q=foo", "private, max-age=5"},
		// standard = 300s — endpoints that change with tag operations / paging
		{"/api/v1/videos", "private, max-age=300"},
		{"/api/v1/images", "private, max-age=300"},
		{"/api/v1/tags", "private, max-age=300"},
		// static = 3600s — almost never change
		{"/api/v1/system/drives", "private, max-age=3600"},
		// not cached: /system/browse is path-sensitive
		// (cannot easily test without a real path — skip in unit test)
	}

	for _, tc := range cases {
		t.Run(tc.path, func(t *testing.T) {
			req := httptest.NewRequest(http.MethodGet, tc.path, nil)
			rec := httptest.NewRecorder()
			s.Echo.ServeHTTP(rec, req)
			cc := rec.Header().Get("Cache-Control")
			if cc != tc.wantCache {
				t.Errorf("Cache-Control = %q, want %q (status=%d)", cc, tc.wantCache, rec.Code)
			}
		})
	}
}

func TestPprofRoute_RegisteredUnderDebugPrefix(t *testing.T) {
	// Verify the route is wired up. Auth coverage lives in
	// middleware.PrivateNetOnly tests.
	cacheDir := filepath.Join(t.TempDir(), "thumb")
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: 0},
		Scan:   config.ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir, MaxSize: 64, Format: "jpeg",
		},
	}
	s, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	defer s.Stop()

	routes := s.Echo.Routes()
	found := false
	for _, r := range routes {
		if strings.HasPrefix(r.Path, "/debug/pprof") {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("no /debug/pprof route registered")
	}
}

// setupGzipTestEcho builds a minimal Echo engine with the same gzip middleware
// configuration used by registerRoutes (B3). It exists so the gzip behavior
// tests can exercise the middleware in isolation (unit-level Skipper semantics)
// without booting the full Server (which requires filesystem watchers, tags DB,
// etc.). The end-to-end check that registerRoutes actually mounts this config
// lives in TestGzipMountedOnRealServer below.
func setupGzipTestEcho() *echo.Echo {
	e := echo.New()
	e.HideBanner = true
	e.Use(echoMw.GzipWithConfig(echoMw.GzipConfig{
		Level: 5,
		Skipper: func(c echo.Context) bool {
			// B3 critical correctness: use c.Request().URL.Path (actual request
			// path), NOT c.Path() (route template). Route templates like
			// "/api/v1/videos/*" do not contain "/stream" and would fail to
			// skip transcoded streams.
			path := c.Request().URL.Path
			if strings.Contains(path, "/stream") ||
				strings.Contains(path, "/thumbnail") ||
				strings.Contains(path, "/original") ||
				strings.Contains(path, "/download") {
				return true
			}
			return false
		},
	}))
	return e
}

// newGzipTestServer boots a real Server via New(cfg) — exercising the
// production registerRoutes path — and returns it along with a cleanup that
// stops the server. Additional test-only routes can be registered on s.Echo
// after this returns, and they will still pass through the production
// middleware chain mounted by registerRoutes.
func newGzipTestServer(t *testing.T) *Server {
	t.Helper()
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: 0},
		Scan:   config.ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: filepath.Join(t.TempDir(), "thumb"), MaxSize: 64, Format: "jpeg",
		},
	}
	s, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	t.Cleanup(func() {
		if err := s.Stop(); err != nil {
			t.Logf("Stop: %v", err)
		}
	})
	return s
}

// TestGzipMountedOnRealServer is the end-to-end check that registerRoutes
// actually mounts the gzip middleware on the production router. It boots a real
// Server via New(), registers a test route that returns ~5KB JSON, and asserts
// the response is gzip-compressed when the client opts in. This test is the
// RED/GREEN gate for Step 4 of the B3 brief (fails before middleware is added
// to registerRoutes, passes after).
func TestGzipMountedOnRealServer(t *testing.T) {
	s := newGzipTestServer(t)

	s.Echo.GET("/api/v1/__test_gzip_probe", func(c echo.Context) error {
		large := make([]string, 500) // ~5KB JSON
		for i := range large {
			large[i] = strings.Repeat("x", 10)
		}
		return c.JSON(http.StatusOK, large)
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/__test_gzip_probe", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d (body=%q)", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Encoding"); got != "gzip" {
		t.Fatalf("expected Content-Encoding=gzip on production router, got %q (gzip middleware not mounted by registerRoutes?)", got)
	}
}

// TestGzipMiddleware_CompressesJSON verifies that JSON responses are
// gzip-compressed when the client opts in via Accept-Encoding: gzip, AND that
// the compressed body decompresses back to the original JSON. This is the
// "happy path" for B3.
func TestGzipMiddleware_CompressesJSON(t *testing.T) {
	e := setupGzipTestEcho()

	e.GET("/api/v1/test/big-json", func(c echo.Context) error {
		large := make([]string, 500) // ~5KB JSON
		for i := range large {
			large[i] = strings.Repeat("x", 10)
		}
		return c.JSON(http.StatusOK, large)
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/test/big-json", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d (body=%q)", rec.Code, rec.Body.String())
	}
	if got := rec.Header().Get("Content-Encoding"); got != "gzip" {
		t.Fatalf("expected Content-Encoding=gzip, got %q", got)
	}
	if got := rec.Header().Get("Vary"); got != "Accept-Encoding" {
		t.Fatalf("expected Vary=Accept-Encoding, got %q", got)
	}

	// Body MUST be valid gzip that decompresses to the original JSON array.
	zr, err := gzip.NewReader(rec.Body)
	if err != nil {
		t.Fatalf("gzip.NewReader: %v", err)
	}
	defer zr.Close()
	decompressed, err := io.ReadAll(zr)
	if err != nil {
		t.Fatalf("io.ReadAll(gzip): %v", err)
	}
	if !bytes.HasPrefix(bytes.TrimSpace(decompressed), []byte("[")) {
		t.Fatalf("decompressed body is not a JSON array, got prefix %q", string(decompressed[:min(20, len(decompressed))]))
	}
	// Sanity: each of the 500 entries should appear in the decompressed output.
	if got, want := bytes.Count(decompressed, []byte(strings.Repeat("x", 10))), 500; got != want {
		t.Fatalf("expected %d repeated entries after decompression, got %d", want, got)
	}
}

// TestGzipMiddleware_SkipsStreamEndpoints verifies the Skipper excludes binary
// endpoints. The path used here mirrors real wildcard routes: although the
// actual Echo route template would be "/api/v1/videos/*", the request path is
// "/api/v1/videos/foo/stream" which must match the Skipper via URL.Path.
func TestGzipMiddleware_SkipsStreamEndpoints(t *testing.T) {
	e := setupGzipTestEcho()

	e.GET("/api/v1/videos/*", func(c echo.Context) error {
		c.Response().Header().Set("Content-Type", "video/mp4")
		_, err := c.Response().Write(make([]byte, 10000))
		return err
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/videos/foo/stream", nil)
	req.Header.Set("Accept-Encoding", "gzip")
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if got := rec.Header().Get("Content-Encoding"); got != "" {
		t.Fatalf("expected NO Content-Encoding for /stream endpoint, got %q", got)
	}
	// Body must NOT be gzip-encoded — it should be the raw 10000 zero bytes.
	if rec.Body.Len() != 10000 {
		t.Fatalf("expected raw 10000-byte body (uncompressed), got %d bytes", rec.Body.Len())
	}
}

// TestGzipMiddleware_SkipsThumbnailAndOriginalAndDownload verifies the other
// three Skipper keywords — /thumbnail, /original, /download — also bypass
// compression. These cover the media-asset endpoints that serve pre-compressed
// binary payloads (JPEG/PNG/MP4) where gzip would waste CPU for ~0 ratio gain.
func TestGzipMiddleware_SkipsThumbnailAndOriginalAndDownload(t *testing.T) {
	cases := []string{
		"/api/v1/media/thumbnail",
		"/api/v1/media/original",
		"/api/v1/admin/download",
	}
	for _, path := range cases {
		t.Run(path, func(t *testing.T) {
			e := setupGzipTestEcho()
			e.GET(path, func(c echo.Context) error {
				c.Response().Header().Set("Content-Type", "application/octet-stream")
				_, err := c.Response().Write(make([]byte, 2048))
				return err
			})

			req := httptest.NewRequest(http.MethodGet, path, nil)
			req.Header.Set("Accept-Encoding", "gzip")
			rec := httptest.NewRecorder()
			e.ServeHTTP(rec, req)

			if rec.Code != http.StatusOK {
				t.Fatalf("expected status 200, got %d", rec.Code)
			}
			if got := rec.Header().Get("Content-Encoding"); got != "" {
				t.Fatalf("expected NO Content-Encoding for %s, got %q", path, got)
			}
			if rec.Body.Len() != 2048 {
				t.Fatalf("expected raw 2048-byte body (uncompressed) for %s, got %d bytes", path, rec.Body.Len())
			}
		})
	}
}

// TestGzipMiddleware_NoAcceptEncodingNoCompression verifies the middleware is
// a no-op when the client does NOT send Accept-Encoding: gzip. This is the
// behavior that keeps existing tests (which never set Accept-Encoding) valid.
func TestGzipMiddleware_NoAcceptEncodingNoCompression(t *testing.T) {
	e := setupGzipTestEcho()

	e.GET("/api/v1/test/plain", func(c echo.Context) error {
		return c.JSON(http.StatusOK, map[string]string{"hello": "world"})
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/test/plain", nil)
	// Intentionally do NOT set Accept-Encoding.
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if got := rec.Header().Get("Content-Encoding"); got != "" {
		t.Fatalf("expected NO Content-Encoding when client did not opt in, got %q", got)
	}
	// Body must be the raw JSON.
	want := `{"hello":"world"}`
	if got := strings.TrimSpace(rec.Body.String()); got != want {
		t.Fatalf("expected raw JSON %q, got %q", want, got)
	}
}

// BenchmarkGzipMiddleware_JSON measures the per-request overhead of the gzip
// middleware on a typical JSON response (~50KB). This is the 4.3-B baseline
// benchmark for B3.
func BenchmarkGzipMiddleware_JSON(b *testing.B) {
	e := echo.New()
	e.HideBanner = true
	e.Use(echoMw.GzipWithConfig(echoMw.GzipConfig{
		Level: 5,
		Skipper: func(c echo.Context) bool {
			path := c.Request().URL.Path
			return strings.Contains(path, "/stream") ||
				strings.Contains(path, "/thumbnail") ||
				strings.Contains(path, "/original") ||
				strings.Contains(path, "/download")
		},
	}))
	// 5000 strings * ~10 bytes each = ~50KB JSON.
	e.GET("/bench", func(c echo.Context) error {
		items := make([]string, 5000)
		for i := range items {
			items[i] = strings.Repeat("x", 10)
		}
		return c.JSON(http.StatusOK, items)
	})

	req := httptest.NewRequest(http.MethodGet, "/bench", nil)
	req.Header.Set("Accept-Encoding", "gzip")

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		rec := httptest.NewRecorder()
		e.ServeHTTP(rec, req)
		if rec.Code != http.StatusOK {
			b.Fatalf("status = %d, want 200", rec.Code)
		}
	}
}

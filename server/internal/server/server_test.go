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
	// Verify the route is wired up when Debug.Pprof=true. Round 32 S3 made
	// pprof opt-in, so this test now flips the flag on. Default-off coverage
	// lives in TestPprofDisabledByDefault. Auth coverage lives in
	// middleware.PrivateNetOnly tests.
	cacheDir := filepath.Join(t.TempDir(), "thumb")
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: 0},
		Scan:   config.ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir, MaxSize: 64, Format: "jpeg",
		},
	}
	cfg.Debug.Pprof = true
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

// TestPprofDisabledByDefault verifies that /debug/pprof/ returns 404 when
// Debug.Pprof is false (the default). Round 32 S3: pprof routes are no longer
// registered unconditionally — operator must opt in via config.debug.pprof=true
// OR --debug-pprof flag.
func TestPprofDisabledByDefault(t *testing.T) {
	cacheDir := filepath.Join(t.TempDir(), "thumb-cache")
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{t.TempDir()},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir, MaxSize: 256, Format: "jpeg",
		},
	}
	cfg.Debug.Pprof = false
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("got status %d, want 404", rec.Code)
	}
}

// TestPprofEnabledViaConfig verifies that /debug/pprof/ returns 200 when
// Debug.Pprof=true, gated by PrivateNetOnly (request comes from loopback).
func TestPprofEnabledViaConfig(t *testing.T) {
	cacheDir := filepath.Join(t.TempDir(), "thumb-cache")
	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{t.TempDir()},
			VideoExtensions: []string{".mp4"},
			ImageExtensions: []string{".jpg"},
		},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: cacheDir, MaxSize: 256, Format: "jpeg",
		},
	}
	cfg.Debug.Pprof = true
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234"
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("got status %d, want 200", rec.Code)
	}
}

// newPprofTestServer boots a real Server via New(cfg) with pprof enabled
// (Debug.Pprof=true) and the given bearer token ("" = open mode), mirroring
// newAuthTestServer. Built for the Phase 9 (L-2) pprof token-gate tests.
func newPprofTestServer(t *testing.T, token string) *Server {
	t.Helper()
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: 0, Token: token},
		Scan:   config.ScanConfig{Roots: []string{t.TempDir()}, VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail: config.ThumbnailConfig{
			CacheDir: filepath.Join(t.TempDir(), "thumb"), MaxSize: 64, Format: "jpeg",
		},
	}
	cfg.Debug.Pprof = true
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

// TestPprofRequiresTokenInTokenMode is the Phase 9 (L-2) gate: /debug/pprof
// must additionally be Bearer-gated in token mode. PrivateNetOnly alone is not
// enough — heap/goroutine profiles can embed secrets, and any LAN device sits
// on a private IP, so loopback-adjacent clients must present the token too:
// no token → 401, wrong token → 401, valid token → 200.
func TestPprofRequiresTokenInTokenMode(t *testing.T) {
	s := newPprofTestServer(t, "sekrit-token")

	// No token → 401 (auth middleware rejects before the pprof handler).
	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234" // loopback so PrivateNetOnly passes
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("GET /debug/pprof/ without token = %d, want 401", rec.Code)
	}

	// Wrong token → 401.
	req = httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234"
	req.Header.Set("Authorization", "Bearer wrong-token")
	rec = httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("GET /debug/pprof/ with wrong token = %d, want 401", rec.Code)
	}

	// Valid token → 200 (loopback passes PrivateNetOnly, auth passes, pprof
	// index responds).
	req = httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234"
	req.Header.Set("Authorization", "Bearer sekrit-token")
	rec = httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("GET /debug/pprof/ with valid token = %d, want 200", rec.Code)
	}
}

// TestPprofOpenModeStillPrivateNetOnly pins open-mode semantics for the
// Phase 9 (L-2) change: with no token configured the auth middleware is a
// passthrough, so /debug/pprof keeps its previous PrivateNetOnly-only
// behavior — loopback access still returns 200 without any credentials.
func TestPprofOpenModeStillPrivateNetOnly(t *testing.T) {
	s := newPprofTestServer(t, "") // open mode
	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234"
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusOK {
		t.Fatalf("open mode loopback GET /debug/pprof/ = %d, want 200", rec.Code)
	}
}

// TestRedactMiddleware_TokenRedactedFromLogButVisibleToDownstream verifies
// the two correctness invariants of the inline redact middleware mounted in
// registerRoutes (Round 32 Task 5 S2):
//
//  1. Downstream handlers see the ORIGINAL ?token= value. This proves
//     middleware.BearerToken (which has a c.QueryParam("token") fallback for
//     <img> tags that cannot set Authorization headers) would still receive
//     the real bearer token. The redaction is log-only.
//
//  2. The request's URL.RawQuery captured by the access log contains
//     "token=REDACTED" — not the real token — so echoMw.Logger cannot leak
//     bearer tokens into access logs, browser history mirrors, or any log
//     shipper.
//
// This works because Echo middleware runs LIFO on the request side: the
// redact middleware is registered AFTER echoMw.Logger in registerRoutes, so
// it executes FIRST. It calls c.QueryParams() to force Echo to parse and
// cache the query params into its internal context BEFORE mutating RawQuery;
// downstream c.QueryParam("token") reads from that cached map and returns
// the original value.
//
// We test the middleware function in isolation (not via a full Server boot)
// because the redact logic is the only thing under test — booting the full
// router would couple this test to every other middleware's behavior.
func TestRedactMiddleware_TokenRedactedFromLogButVisibleToDownstream(t *testing.T) {
	// Captured from the dummy downstream handler.
	var capturedToken string
	var capturedOther string

	// redactMiddleware mirrors the inline middleware in registerRoutes
	// (server.go lines 134-145). Kept in lockstep via the comment block above
	// the production middleware; if the production middleware moves or
	// changes shape, this replica must be updated to match.
	redactMiddleware := func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			_ = c.QueryParams() // force parse + cache into context
			req := c.Request()
			q := req.URL.Query()
			if q.Get("token") != "" {
				q.Set("token", "REDACTED")
				req.URL.RawQuery = q.Encode()
				// Echo Logger 打印 req.RequestURI（请求行原文，不随 URL 同步），必须一并改写
				req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
			}
			return next(c)
		}
	}

	e := echo.New()
	e.HideBanner = true
	e.GET("/probe", func(c echo.Context) error {
		capturedToken = c.QueryParam("token")
		capturedOther = c.QueryParam("other")
		return c.String(http.StatusOK, "ok")
	}, redactMiddleware)

	req := httptest.NewRequest(http.MethodGet, "/probe?token=secret123&other=keep", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (body=%q)", rec.Code, rec.Body.String())
	}

	// (1) Downstream handler saw the REAL token — not "REDACTED". This proves
	// the redact middleware is log-only and does not break BearerToken's
	// ?token= fallback for <img> tags.
	if capturedToken != "secret123" {
		t.Errorf("downstream c.QueryParam(\"token\") = %q, want %q (redaction must not break downstream auth)",
			capturedToken, "secret123")
	}
	// Sanity: other query params are passed through untouched.
	if capturedOther != "keep" {
		t.Errorf("downstream c.QueryParam(\"other\") = %q, want %q", capturedOther, "keep")
	}

	// (2) The URL.RawQuery (what echoMw.Logger would print) shows the token
	// redacted. This is the S2 security guarantee.
	rawQuery := req.URL.RawQuery
	if !strings.Contains(rawQuery, "token=REDACTED") {
		t.Errorf("expected RawQuery to contain token=REDACTED, got %q (bearer token would leak to access log)", rawQuery)
	}
	if strings.Contains(rawQuery, "secret123") {
		t.Errorf("RawQuery must NOT contain the raw token value, got %q", rawQuery)
	}

	// (3) req.RequestURI — the request-line copy echoMw.Logger prints via the
	// ${uri} tag — is rewritten as well. Mutating URL.RawQuery alone leaves
	// RequestURI stale, which leaked the raw token to the access log (Phase 9
	// H-3). Asserting both fields prevents the regression from being tested
	// only halfway.
	if !strings.Contains(req.RequestURI, "token=REDACTED") {
		t.Errorf("expected RequestURI to contain token=REDACTED, got %q", req.RequestURI)
	}
	if strings.Contains(req.RequestURI, "secret123") {
		t.Errorf("RequestURI must NOT contain the raw token value, got %q", req.RequestURI)
	}
}

// newAuthTestServer boots a real Server via New(cfg) with the given bearer
// token (empty string = open mode) and a temporary scan root, so requests
// through s.Echo exercise the production registerRoutes middleware chain —
// including middleware.BearerToken, which reads cfg.Server.Token. New() does
// not trigger a scan (only filesystem watching starts), so no media files are
// needed. Built for the Phase 9 (H-2) media-read auth tests.
func newAuthTestServer(t *testing.T, token string) *Server {
	t.Helper()
	cfg := &config.Config{
		Server: config.ServerConfig{Host: "127.0.0.1", Port: 0, Token: token},
		Scan:   config.ScanConfig{Roots: []string{t.TempDir()}, VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
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

// TestMediaReadEndpointsRequireToken is the Phase 9 (H-2) gate: every media
// read endpoint (folders / videos / images / texts / search, including the
// wildcard asset routes) must reject unauthenticated requests with 401 when a
// token is configured, while a valid Bearer header must pass the auth layer.
func TestMediaReadEndpointsRequireToken(t *testing.T) {
	// newTestServer 辅助若不存在，参照同文件既有带 token 的测试构造 Server
	s := newAuthTestServer(t, "sekrit-token") // token = "sekrit-token"
	for _, path := range []string{
		"/api/v1/folders", "/api/v1/videos", "/api/v1/images", "/api/v1/texts",
		"/api/v1/search?q=x",
		// wildcard asset routes (also auth-gated by Phase 9 H-2)
		"/api/v1/folders/C%3A/tmp/browse",
		"/api/v1/videos/foo/thumbnail",
		"/api/v1/images/foo/thumbnail",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		s.Echo.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("GET %s without token = %d, want 401", path, rec.Code)
		}
	}
	// 带 header 后不再 401（允许 200/404/400，取决于数据，但不得是 401/403）
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	req.Header.Set("Authorization", "Bearer sekrit-token")
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("GET /folders with token must not 401, got %d", rec.Code)
	}
}

// TestTagReadEndpointsRequireToken is the Phase 9 (I-3) gate: the four tag
// READ endpoints must reject unauthenticated requests with 401 when a token
// is configured (the tag graph enumerates host files, so it is an information
// disclosure on par with the H-2 media reads). Kept as its own test with its
// own server instance: the per-IP auth-failure backoff (10 x 401/min) would
// turn the trailing requests into 429 if these paths were appended to
// TestMediaReadEndpointsRequireToken's loop.
func TestTagReadEndpointsRequireToken(t *testing.T) {
	s := newAuthTestServer(t, "sekrit-token")
	for _, path := range []string{
		"/api/v1/tags",
		"/api/v1/tags/1/files",
		"/api/v1/tags/1/media",
		"/api/v1/tags/file-tags",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		s.Echo.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("GET %s without token = %d, want 401", path, rec.Code)
		}
	}
	// With the token the gate opens (allow 200/404/400 — anything but 401/403).
	req := httptest.NewRequest(http.MethodGet, "/api/v1/tags", nil)
	req.Header.Set("Authorization", "Bearer sekrit-token")
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized || rec.Code == http.StatusForbidden {
		t.Fatalf("GET /tags with token must not %d", rec.Code)
	}
}

// TestMediaReadEndpointsOpenModePassthrough pins the open-mode contract: when
// no token is configured, middleware.BearerToken is a no-op, so deployments
// that never set a token keep working exactly as before.
func TestMediaReadEndpointsOpenModePassthrough(t *testing.T) {
	s := newAuthTestServer(t, "") // 开放模式
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("open mode must stay passthrough, got 401")
	}
}

// TestBodyLimitRejectsOversizedPayload is the Phase 9 (M-1) gate: a global
// request-body cap must reject oversized JSON with 413 before the handler
// buffers it. Open mode (empty token) isolates the body-limit concern from
// authentication. The ~5MiB payload exceeds the 4M limit; without the
// middleware the handler would Bind the full body into memory first.
func TestBodyLimitRejectsOversizedPayload(t *testing.T) {
	s := newAuthTestServer(t, "") // 开放模式，排除认证干扰
	big := strings.NewReader(`{"roots":["` + strings.Repeat("A", 5<<20) + `"]}`)
	req := httptest.NewRequest(http.MethodPut, "/api/v1/admin/config", big)
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	req.Header.Set("Authorization", "Bearer x") // 开放模式下无实际作用，保持形态
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized body = %d, want 413", rec.Code)
	}
}

// TestTokenRedactRewritesRequestURI is the Phase 9 (H-3) end-to-end gate: it
// boots a REAL Server via New(cfg) so the production registerRoutes middleware
// chain — including the actual echoMw.Logger() — runs against the request.
//
// Background: echoMw.Logger prints ${uri} from req.RequestURI (the request-line
// copy, see echo v4 middleware/logger.go tagURI), which is NOT kept in sync
// with req.URL when RawQuery is mutated. The Round 32 S2 redact middleware only
// rewrote URL.RawQuery, so the access log still leaked ?token=<raw>. This test
// fails until the production redact middleware also rewrites req.RequestURI.
func TestTokenRedactRewritesRequestURI(t *testing.T) {
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

	// Capture the real Logger middleware output (echoMw.Logger writes to
	// e.Logger) so we can assert on the actual access-log line.
	var logBuf bytes.Buffer
	s.Echo.Logger.SetOutput(&logBuf)

	// Downstream probe: proves the cached query params still expose the
	// ORIGINAL token (middleware.BearerToken's ?token= fallback depends on it).
	var downstreamToken string
	s.Echo.GET("/api/v1/__test_redact_probe", func(c echo.Context) error {
		downstreamToken = c.QueryParam("token")
		return c.NoContent(http.StatusOK)
	})

	req := httptest.NewRequest(http.MethodGet, "/api/v1/__test_redact_probe?token=sekrit", nil)
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d (body=%q)", rec.Code, rec.Body.String())
	}

	// (1) req.RequestURI — the field echoMw.Logger prints via ${uri} — must be
	// rewritten, not just URL.RawQuery.
	if strings.Contains(req.RequestURI, "sekrit") {
		t.Errorf("RequestURI still leaks token: %s", req.RequestURI)
	}
	if req.RequestURI != "/api/v1/__test_redact_probe?token=REDACTED" {
		t.Errorf("unexpected RequestURI: %s", req.RequestURI)
	}

	// (2) The real echoMw.Logger output must not contain the raw token, and
	// must show the redacted one.
	if strings.Contains(logBuf.String(), "sekrit") {
		t.Errorf("access log leaked raw token, log line: %s", strings.TrimSpace(logBuf.String()))
	}
	if !strings.Contains(logBuf.String(), "token=REDACTED") {
		t.Errorf("expected token=REDACTED in access log, got: %s", strings.TrimSpace(logBuf.String()))
	}

	// (3) Cached query params still carry the original token for the auth
	// fallback (redaction must stay log-only).
	if downstreamToken != "sekrit" {
		t.Errorf("downstream c.QueryParam(\"token\") = %q, want %q (redaction must not break downstream auth)",
			downstreamToken, "sekrit")
	}
}

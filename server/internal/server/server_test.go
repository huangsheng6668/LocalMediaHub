package server

import (
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/labstack/echo/v4"
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
		service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions),
		nil,
		service.NewStreamingService(""),
		thumbnailService,
	)
	s.registerRoutes(h)

	requestPath := "/api/v1/images/" + strings.ReplaceAll(filepath.ToSlash(imagePath), " ", "%20") + "/thumbnail"
	req := httptest.NewRequest(http.MethodGet, requestPath, nil)
	rec := httptest.NewRecorder()

	s.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected thumbnail route to return 200, got %d with body %s", rec.Code, rec.Body.String())
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

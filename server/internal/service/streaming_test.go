package service

import (
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestServeFile_DirectStreamingHeaders(t *testing.T) {
	tmpDir := t.TempDir()
	testFilePath := filepath.Join(tmpDir, "test_video.mp4")
	content := []byte("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ") // 36 bytes
	if err := os.WriteFile(testFilePath, content, 0644); err != nil {
		t.Fatalf("failed to write temp file: %v", err)
	}

	svc := NewStreamingService("")

	t.Run("Full GET request returns 200 OK with Content-Length", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/stream", nil)
		rec := httptest.NewRecorder()

		err := svc.ServeFile(rec, req, testFilePath)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		res := rec.Result()
		defer res.Body.Close()

		if res.StatusCode != http.StatusOK {
			t.Errorf("expected status 200 OK, got %d", res.StatusCode)
		}
		if res.Header.Get("Content-Length") != "36" {
			t.Errorf("expected Content-Length 36, got %q", res.Header.Get("Content-Length"))
		}
		if res.Header.Get("Content-Type") != "video/mp4" {
			t.Errorf("expected Content-Type video/mp4, got %q", res.Header.Get("Content-Type"))
		}

		bodyBytes, _ := io.ReadAll(res.Body)
		if string(bodyBytes) != string(content) {
			t.Errorf("body mismatch, got %q", string(bodyBytes))
		}
	})

	t.Run("Range request returns 206 Partial Content with Content-Length and Content-Range", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, "/stream", nil)
		req.Header.Set("Range", "bytes=0-9")
		rec := httptest.NewRecorder()

		err := svc.ServeFile(rec, req, testFilePath)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		res := rec.Result()
		defer res.Body.Close()

		if res.StatusCode != http.StatusPartialContent {
			t.Errorf("expected status 206 Partial Content, got %d", res.StatusCode)
		}
		if res.Header.Get("Content-Length") != "10" {
			t.Errorf("expected Content-Length 10, got %q", res.Header.Get("Content-Length"))
		}
		if res.Header.Get("Content-Range") != "bytes 0-9/36" {
			t.Errorf("expected Content-Range 'bytes 0-9/36', got %q", res.Header.Get("Content-Range"))
		}

		bodyBytes, _ := io.ReadAll(res.Body)
		if string(bodyBytes) != "0123456789" {
			t.Errorf("body mismatch, got %q", string(bodyBytes))
		}
	})
}

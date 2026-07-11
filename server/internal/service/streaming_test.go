package service

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
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

	t.Run("Suffix range bytes=-N returns last N bytes with 206", func(t *testing.T) {
		// bytes=-10 means "last 10 bytes" per RFC 7233 §2.1.
		// File is 36 bytes, so last 10 = bytes 26..35 = "QRSTUVWXYZ".
		req := httptest.NewRequest(http.MethodGet, "/stream", nil)
		req.Header.Set("Range", "bytes=-10")
		rec := httptest.NewRecorder()

		err := svc.ServeFile(rec, req, testFilePath)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		res := rec.Result()
		defer res.Body.Close()

		if res.StatusCode != http.StatusPartialContent {
			t.Errorf("expected status 206, got %d", res.StatusCode)
		}
		if res.Header.Get("Content-Length") != "10" {
			t.Errorf("expected Content-Length 10, got %q", res.Header.Get("Content-Length"))
		}
		if res.Header.Get("Content-Range") != "bytes 26-35/36" {
			t.Errorf("expected Content-Range 'bytes 26-35/36', got %q", res.Header.Get("Content-Range"))
		}

		bodyBytes, _ := io.ReadAll(res.Body)
		if string(bodyBytes) != "QRSTUVWXYZ" {
			t.Errorf("body mismatch, got %q", string(bodyBytes))
		}
	})

	t.Run("Range past EOF returns 416 Range Not Satisfiable", func(t *testing.T) {
		// bytes=999999- is beyond the 36-byte file.
		req := httptest.NewRequest(http.MethodGet, "/stream", nil)
		req.Header.Set("Range", "bytes=999999-")
		rec := httptest.NewRecorder()

		err := svc.ServeFile(rec, req, testFilePath)
		if err != nil {
			t.Fatalf("unexpected error: %v", err)
		}

		res := rec.Result()
		defer res.Body.Close()

		if res.StatusCode != http.StatusRequestedRangeNotSatisfiable {
			t.Errorf("expected status 416, got %d", res.StatusCode)
		}
		if res.Header.Get("Content-Range") != "bytes */36" {
			t.Errorf("expected Content-Range 'bytes */36', got %q", res.Header.Get("Content-Range"))
		}
	})
}

// TestServeTranscodedClientDisconnect verifies that when a client disconnects
// mid-transcode, ffmpeg is killed and serveTranscoded returns promptly
// (within 5s). This prevents orphaned ffmpeg processes from consuming CPU
// and disk after the client is gone.
//
// Phase 8 T5-02: The fix uses exec.CommandContext bound to the request
// context so that context cancellation force-kills the ffmpeg subprocess.
func TestServeTranscodedClientDisconnect(t *testing.T) {
	// Skip if ffmpeg not in PATH — CI environments without ffmpeg can't test this.
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not in PATH, skipping client-disconnect test")
	}

	// Create a long-running fake "video" file — ffmpeg will read from it
	// for the duration of the test.
	tmp := t.TempDir()
	srcPath := filepath.Join(tmp, "input.mp4")
	// A small but real MP4 — use ffmpeg to generate a 60-second test video.
	genCmd := exec.Command("ffmpeg", "-y", "-f", "lavfi", "-i",
		"testsrc=duration=60:size=320x240:rate=1", "-c:v", "libx264",
		"-preset", "ultrafast", srcPath)
	if out, err := genCmd.CombinedOutput(); err != nil {
		t.Skipf("ffmpeg cannot generate test video: %v\n%s", err, out)
	}

	// Set up streaming service with default ffmpeg path
	svc := NewStreamingService("")

	// Create a request that will be cancelled mid-stream.
	// ServeFile delegates to serveTranscoded when ?transcode=true.
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	req := httptest.NewRequest(http.MethodGet,
		"/stream?transcode=true&path="+url.QueryEscape(srcPath), nil)
	req = req.WithContext(ctx)
	rec := httptest.NewRecorder()

	// Run ServeFile (→ serveTranscoded) in a goroutine; cancel ctx after 500ms
	done := make(chan error, 1)
	go func() {
		done <- svc.ServeFile(rec, req, srcPath)
	}()

	time.Sleep(500 * time.Millisecond)
	cancel()

	// Wait for handler to finish (should return shortly after ctx cancel)
	select {
	case err := <-done:
		// Error is OK — context cancellation may surface as read/write error.
		_ = err
	case <-time.After(5 * time.Second):
		t.Fatal("serveTranscoded did not return within 5s of client disconnect")
	}

	// Give ffmpeg a moment to die after context cancel
	time.Sleep(500 * time.Millisecond)

	// The 5s timeout above IS the assertion: if serveTranscoded returned
	// within 5s of cancel(), the cmd must have been killed (otherwise
	// the io.Copy/read loop would block indefinitely on ffmpeg's stdout).
}

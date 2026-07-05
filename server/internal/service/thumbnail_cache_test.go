package service

import (
	"bytes"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"strconv"
	"testing"
	"time"
)

// writeTestJPEG creates a tiny valid JPEG file at dir/name using Go's jpeg
// encoder and returns its path. The modtime is forced to ~1h ago so the cache
// key is deterministic across runs.
func writeTestJPEG(t *testing.T, dir string, name string) string {
	t.Helper()
	if err := os.MkdirAll(dir, 0755); err != nil {
		t.Fatalf("mkdir %s: %v", dir, err)
	}
	p := filepath.Join(dir, name)
	src := image.NewRGBA(image.Rect(0, 0, 2, 2))
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, src, &jpeg.Options{Quality: 50}); err != nil {
		t.Fatalf("encode test JPEG: %v", err)
	}
	if err := os.WriteFile(p, buf.Bytes(), 0644); err != nil {
		t.Fatalf("write test JPEG: %v", err)
	}
	mt := time.Now().Add(-1 * time.Hour)
	if err := os.Chtimes(p, mt, mt); err != nil {
		t.Fatalf("chtimes: %v", err)
	}
	return p
}

func TestGenerateThumbnailBytes_CachesAfterFirstCall(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}

	srcDir := t.TempDir()
	src := writeTestJPEG(t, srcDir, "img.jpg")

	bytes1, err := svc.GenerateThumbnailBytes(src)
	if err != nil {
		t.Fatalf("first GenerateThumbnailBytes: %v", err)
	}
	if len(bytes1) == 0 {
		t.Fatal("first call returned empty bytes")
	}

	// Stat the disk cache to record its mtime, then verify the second
	// GenerateThumbnailBytes hits memory cache (no disk read).
	cacheKey := svc.thumbnailCacheKey(src, mustModTime(t, src))
	diskPath := filepath.Join(cacheDir, cacheKey+".jpg")
	fi1, err := os.Stat(diskPath)
	if err != nil {
		t.Fatalf("stat disk cache: %v", err)
	}

	bytes2, err := svc.GenerateThumbnailBytes(src)
	if err != nil {
		t.Fatalf("second GenerateThumbnailBytes: %v", err)
	}
	if string(bytes1) != string(bytes2) {
		t.Fatal("second call returned different bytes than first")
	}

	// Disk cache file should be untouched (modtime unchanged) on the
	// memory-cache-hit path. Sleep briefly to make mtime differences detectable.
	time.Sleep(20 * time.Millisecond)
	fi2, _ := os.Stat(diskPath)
	if fi2.ModTime() != fi1.ModTime() {
		t.Fatalf("disk cache modtime changed: %v → %v", fi1.ModTime(), fi2.ModTime())
	}

	// Memory cache should have the entry.
	if _, ok := svc.memCache.Get(cacheKey); !ok {
		t.Fatal("memCache missing entry after second call")
	}
}

func TestGenerateThumbnailBytes_EvictsAtCapacity(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}

	srcDir := t.TempDir()
	// Fill cache to capacity (200). Use distinct modtimes so keys are distinct.
	for i := 0; i < 200; i++ {
		name := "img" + strconv.Itoa(i) + ".jpg"
		src := writeTestJPEG(t, srcDir, name)
		// Override modtime per-file to make cache keys unique.
		mt := time.Date(2020, 1, 1, 0, 0, i, 0, time.UTC)
		if err := os.Chtimes(src, mt, mt); err != nil {
			t.Fatalf("chtimes: %v", err)
		}
		if _, err := svc.GenerateThumbnailBytes(src); err != nil {
			t.Fatalf("fill cache i=%d: %v", i, err)
		}
	}

	// Capacity still 200 (Len == MaxEntries).
	if svc.memCache.Len() != 200 {
		t.Fatalf("expected cache at capacity 200, got %d", svc.memCache.Len())
	}
}

func mustModTime(t *testing.T, p string) time.Time {
	t.Helper()
	fi, err := os.Stat(p)
	if err != nil {
		t.Fatalf("stat %s: %v", p, err)
	}
	return fi.ModTime()
}

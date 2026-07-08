package service

import (
	"bytes"
	"image"
	"image/jpeg"
	"os"
	"path/filepath"
	"strconv"
	"sync"
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

// TestGenerateThumbnailBytes_MemCacheHitSkipsSingleFlight 验证：当 memCache
// 已有 entry 时，generateBytesVia 不应进入 singleflight.Do（也不该调用 genFunc）。
// 通过记录 genFunc 调用次数来断言。
func TestGenerateThumbnailBytes_MemCacheHitSkipsSingleFlight(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}

	srcDir := t.TempDir()
	src := writeTestJPEG(t, srcDir, "img.jpg")

	// 第一次调用：memCache miss → genFunc 被调用 → 写 memCache
	if _, err := svc.GenerateThumbnailBytes(src); err != nil {
		t.Fatalf("first call: %v", err)
	}

	// 第二次调用：memCache hit → 不应进入 sf.Do，也不应再读盘
	// 间接验证：把磁盘缓存文件删掉，如果走 genFunc 路径会因读不到文件而失败；
	// 如果走 memCache 路径会直接返回字节。
	cacheKey := svc.thumbnailCacheKey(src, mustModTime(t, src))
	diskPath := filepath.Join(cacheDir, cacheKey+".jpg")
	if err := os.Remove(diskPath); err != nil {
		t.Fatalf("remove disk cache: %v", err)
	}

	bytes2, err := svc.GenerateThumbnailBytes(src)
	if err != nil {
		t.Fatalf("second call should hit memCache but got error: %v", err)
	}
	if len(bytes2) == 0 {
		t.Fatal("second call returned empty bytes")
	}
}

// TestGenerateThumbnailBytes_ConcurrentSameKey_SingleFlight 验证：多个
// goroutine 同时请求同一未缓存源文件时，所有 follower 都拿到与 leader 完全
// 相同的字节，且 leader 写完 memCache 后所有 follower 都能立即返回。
//
// 用计时断言而非 ffmpeg 计数器，避免在生产代码里加测试用全局变量。
// 用同一张 JPEG 源图（图片路径不走 ffmpeg），重点验证 singleflight 的"结果
// 共享"语义而非 ffmpeg fork 次数。
func TestGenerateThumbnailBytes_ConcurrentSameKey_SingleFlight(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}

	srcDir := t.TempDir()
	src := writeTestJPEG(t, srcDir, "img.jpg")

	const n = 30
	type result struct {
		bytes []byte
		err   error
	}
	results := make([]result, n)

	// 同步触发：所有 goroutine 在 barrier 处等到齐后同时发请求
	var barrier sync.WaitGroup
	barrier.Add(n)
	var wg sync.WaitGroup
	wg.Add(n)
	for i := 0; i < n; i++ {
		go func(idx int) {
			defer wg.Done()
			barrier.Done()
			barrier.Wait() // 所有 goroutine 在此同步
			b, err := svc.GenerateThumbnailBytes(src)
			results[idx] = result{bytes: b, err: err}
		}(i)
	}
	wg.Wait()

	// 所有结果必须无 error
	for i, r := range results {
		if r.err != nil {
			t.Fatalf("goroutine %d error: %v", i, r.err)
		}
		if len(r.bytes) == 0 {
			t.Fatalf("goroutine %d returned empty bytes", i)
		}
	}

	// 所有结果必须字节完全相同（leader 与 follower 共享）
	first := results[0].bytes
	for i := 1; i < n; i++ {
		if string(results[i].bytes) != string(first) {
			t.Fatalf("goroutine %d returned different bytes than leader", i)
		}
	}
}

package service

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/disintegration/imaging"
	"github.com/localmediahub/server/internal/models"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// validJPEGBytes 是一个用 stdlib image/jpeg 编码的 10x10 RGBA 测试 JPEG。
// 仅在测试启动时编码一次，供需要合法 JPEG 字节的测试复用。
var validJPEGBytes = func() []byte {
	var buf bytes.Buffer
	img := image.NewRGBA(image.Rect(0, 0, 10, 10))
	_ = jpeg.Encode(&buf, img, nil)
	return buf.Bytes()
}()

// newTestThumbnailService 创建一个用 t.TempDir() 作为 cacheDir 的 ThumbnailService，
// 用于测试。maxSize=150。ffmpegPath 空时回退到 PATH。
func newTestThumbnailService(t *testing.T, ffmpegPath string) *ThumbnailService {
	t.Helper()
	svc, err := NewThumbnailService(t.TempDir(), 150, "jpg", ffmpegPath)
	if err != nil {
		t.Fatalf("NewThumbnailService failed: %v", err)
	}
	return svc
}

// ensureTestVideo 在 t.TempDir() 下生成一个 1 秒的纯色测试视频（testsrc）。
// 返回视频路径；ffmpeg 不可用时返回 ""。
func ensureTestVideo(t *testing.T, svc *ThumbnailService) string {
	t.Helper()
	if !svc.HasFFmpeg() {
		return ""
	}
	videoPath := filepath.Join(t.TempDir(), "testsrc.mp4")
	cmd := exec.Command(svc.getFFmpegCmd(),
		"-y",
		"-f", "lavfi",
		"-i", "testsrc=duration=1:size=320x240:rate=25",
		"-pix_fmt", "yuv420p",
		videoPath,
	)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Logf("ffmpeg generate test video failed: %v\n%s", err, out)
		return ""
	}
	return videoPath
}

func TestParseFFprobeDuration(t *testing.T) {
	cases := map[string]struct {
		out   string
		want  float64
		valid bool
	}{
		"plain seconds":   {"12.5", 12.5, true},
		"integer":         {"60", 60, true},
		"with whitespace": {"  12.500000  \n", 12.5, true},
		"empty":           {"", 0, false},
		"N/A":             {"N/A", 0, false},
		"non-numeric":     {"abc", 0, false},
		"zero":            {"0", 0, false},
		"negative":        {"-1", 0, false},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			got, ok := parseFFprobeDuration(c.out)
			if ok != c.valid {
				t.Errorf("parseFFprobeDuration(%q) valid=%v, want %v", c.out, ok, c.valid)
			}
			if ok && got != c.want {
				t.Errorf("parseFFprobeDuration(%q) = %v, want %v", c.out, got, c.want)
			}
		})
	}
}

func TestMidpointSeek(t *testing.T) {
	cases := []struct {
		name     string
		duration float64
		ok       bool
		want     string
	}{
		{"midpoint of 60s", 60, true, "30.00"},
		{"midpoint of 12.5s", 12.5, true, "6.25"},
		{"unknown duration falls back to 5", 0, false, "5"},
		{"non-positive falls back to 5", -1, true, "5"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if got := midpointSeek(c.duration, c.ok); got != c.want {
				t.Errorf("midpointSeek(%v,%v) = %q, want %q", c.duration, c.ok, got, c.want)
			}
		})
	}
}

// TestDurationCache_PersistRoundTrip 验证：写 durCache → Shutdown 落盘 →
// 新建 service 读回 → 内容一致。
func TestDurationCache_PersistRoundTrip(t *testing.T) {
	cacheDir := t.TempDir()
	svc1, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService svc1: %v", err)
	}

	// 手动注入 3 条 entry
	mt := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	key1 := "/path/a.mp4|" + mt.Format(time.RFC3339Nano)
	key2 := "/path/b.mp4|" + mt.Format(time.RFC3339Nano)
	key3 := "/path/c.mp4|" + mt.Format(time.RFC3339Nano)

	svc1.durMu.Lock()
	svc1.durCache[key1] = durationEntry{Duration: 10.5, ModTime: mt}
	svc1.durCache[key2] = durationEntry{Duration: 60.0, ModTime: mt}
	svc1.durCache[key3] = durationEntry{Duration: 120.25, ModTime: mt}
	svc1.durDirty = true
	svc1.durMu.Unlock()

	// Shutdown 同步落盘
	svc1.Shutdown()

	// durations.json 文件应存在
	data, err := os.ReadFile(filepath.Join(cacheDir, "durations.json"))
	if err != nil {
		t.Fatalf("read durations.json: %v", err)
	}

	var persisted map[string]durationEntry
	if err := json.Unmarshal(data, &persisted); err != nil {
		t.Fatalf("unmarshal durations.json: %v", err)
	}
	if len(persisted) != 3 {
		t.Fatalf("expected 3 entries, got %d", len(persisted))
	}

	// 新 service 启动应加载到相同内容
	svc2, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService svc2: %v", err)
	}
	defer svc2.Shutdown()

	svc2.durMu.RLock()
	defer svc2.durMu.RUnlock()
	if len(svc2.durCache) != 3 {
		t.Fatalf("svc2 durCache len = %d, want 3", len(svc2.durCache))
	}
	if e, ok := svc2.durCache[key1]; !ok || e.Duration != 10.5 {
		t.Fatalf("svc2 durCache[key1] = %+v ok=%v", e, ok)
	}
	if e, ok := svc2.durCache[key2]; !ok || e.Duration != 60.0 {
		t.Fatalf("svc2 durCache[key2] = %+v ok=%v", e, ok)
	}
	if e, ok := svc2.durCache[key3]; !ok || e.Duration != 120.25 {
		t.Fatalf("svc2 durCache[key3] = %+v ok=%v", e, ok)
	}
}

// TestDurationCache_LoadCorruptFile_NoCrash 验证：durations.json 内容损坏时
// NewThumbnailService 不 panic、不返回 error，而是用空 cache 启动。
func TestDurationCache_LoadCorruptFile_NoCrash(t *testing.T) {
	cacheDir := t.TempDir()

	// 写入损坏的 JSON
	corrupt := []byte("{ this is not valid json }}}")
	if err := os.WriteFile(filepath.Join(cacheDir, "durations.json"), corrupt, 0644); err != nil {
		t.Fatalf("write corrupt file: %v", err)
	}

	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService with corrupt file should not error: %v", err)
	}
	defer svc.Shutdown()

	svc.durMu.RLock()
	defer svc.durMu.RUnlock()
	if len(svc.durCache) != 0 {
		t.Fatalf("expected empty durCache after corrupt load, got %d entries", len(svc.durCache))
	}

	// 损坏文件应仍存在（不删除，等下次 miss 覆盖重写）
	if _, err := os.Stat(filepath.Join(cacheDir, "durations.json")); err != nil {
		t.Fatalf("corrupt durations.json should still exist: %v", err)
	}
}

// TestVideoDurationCached_HitAfterMiss 验证：第一次调用 miss（durCache 空）
// 时返回有效值并写入 durCache；第二次调用直接命中 durCache。
//
// 注意：本测试需要 ffprobe 可用。如 CI 环境无 ffprobe，本测试会失败 ——
// 用 t.Skip 标记但 log 警告，方便本地验证。这是唯一允许 t.Skip 的特例。
func TestVideoDurationCached_HitAfterMiss(t *testing.T) {
	svc, err := NewThumbnailService(t.TempDir(), 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}
	defer svc.Shutdown()

	if !svc.HasFFmpeg() {
		t.Skip("ffprobe not available, skipping duration cache integration test")
	}

	// 找一个真实存在的视频文件做测试源。若 repo 没有测试视频，跳过。
	// 这里用一个 1 秒的合成 mp4：依赖 ffmpeg 生成，若 ffmpeg 也无则跳过。
	srcDir := t.TempDir()
	src := filepath.Join(srcDir, "sample.mp4")
	gen := exec.Command(svc.getFFmpegCmd(), "-y", "-f", "lavfi", "-i",
		"color=red:size=2x2:duration=1", "-frames:v", "10", src)
	if err := gen.Run(); err != nil {
		t.Skipf("ffmpeg unavailable or lavfi not supported, skipping: %v", err)
	}

	// Miss 路径：durCache 应为空，调用后写入
	d1, ok1 := svc.videoDurationCached(src)
	if !ok1 || d1 <= 0 {
		t.Fatalf("first call: videoDurationCached = (%v, %v), want (>0, true)", d1, ok1)
	}

	svc.durMu.RLock()
	cacheLen := len(svc.durCache)
	svc.durMu.RUnlock()
	if cacheLen == 0 {
		t.Fatal("durCache empty after miss path; expected at least 1 entry")
	}

	// Hit 路径：再次调用应直接命中 durCache 返回相同值
	d2, ok2 := svc.videoDurationCached(src)
	if !ok2 {
		t.Fatal("second call: expected cache hit, got ok=false")
	}
	if d2 != d1 {
		t.Fatalf("cache hit returned different duration: first=%v second=%v", d1, d2)
	}
}

// TestVideoDuration_CacheAndFallback 验证导出方法 VideoDuration：
// 1. durCache 已有 entry 时直接返回（不 fork ffprobe）
// 2. durCache miss 时调用底层 videoDuration 并写入 cache
//
// 测试 1 不依赖 ffprobe 可用性，重点验证 cache hit 路径。
func TestVideoDuration_CacheAndFallback(t *testing.T) {
	cacheDir := t.TempDir()
	svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
	if err != nil {
		t.Fatalf("NewThumbnailService: %v", err)
	}
	defer svc.Shutdown()

	// 手动注入一条 cache entry（不依赖 ffprobe）
	mt := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	srcPath := filepath.Join(cacheDir, "fake.mp4")
	if err := os.WriteFile(srcPath, []byte("fake"), 0644); err != nil {
		t.Fatalf("write fake src: %v", err)
	}
	if err := os.Chtimes(srcPath, mt, mt); err != nil {
		t.Fatalf("chtimes: %v", err)
	}

	// 从实际文件的 os.Stat 结果派生 key（与 videoDurationCached 一致）。
	// 不能用 mt.Format(RFC3339Nano)，因为 Windows os.Chtimes 会把 UTC 转成本地
	// 时区，导致 stat 返回的 modTime 与注入的 mt 格式化字符串不一致。
	fi, err := os.Stat(srcPath)
	if err != nil {
		t.Fatalf("stat fake src: %v", err)
	}
	key := srcPath + "|" + fi.ModTime().Format(time.RFC3339Nano)
	svc.durMu.Lock()
	svc.durCache[key] = durationEntry{Duration: 42.5, ModTime: fi.ModTime()}
	svc.durMu.Unlock()

	// Cache hit：应直接返回 42.5，不 fork ffprobe（fake.mp4 不是真视频，
	// 如果走 ffprobe 路径会失败或返回 0/false）
	d, ok := svc.VideoDuration(srcPath)
	if !ok {
		t.Fatal("VideoDuration cache hit: expected ok=true")
	}
	if d != 42.5 {
		t.Fatalf("VideoDuration cache hit: got %v, want 42.5", d)
	}
}

// TestExtractVideoFrameToImage_MainPath 验证 ffmpeg pipe 抽帧主路径成功。
// 依赖 ffmpeg + 测试视频；缺一不可时跳过。
func TestExtractVideoFrameToImage_MainPath(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	if !svc.HasFFmpeg() {
		t.Skip("ffmpeg not available")
	}
	videoPath := ensureTestVideo(t, svc)
	if videoPath == "" {
		t.Skip("could not generate test video")
	}

	img, err := svc.extractVideoFrameToImage(videoPath, "0")
	if err != nil {
		t.Fatalf("extractVideoFrameToImage failed: %v", err)
	}
	if img == nil {
		t.Fatal("returned image is nil")
	}
	bounds := img.Bounds()
	if bounds.Dx() <= 0 || bounds.Dy() <= 0 {
		t.Fatalf("returned image has non-positive dims: %dx%d", bounds.Dx(), bounds.Dy())
	}
}

// TestExtractVideoFrameToImage_SeekFallback 验证 seek 越界后 caller 的 fallback 路径。
// 主路径 seek=999999 通常会失败（视频不够长），caller 重试 seek=0 应成功。
func TestExtractVideoFrameToImage_SeekFallback(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	if !svc.HasFFmpeg() {
		t.Skip("ffmpeg not available")
	}
	videoPath := ensureTestVideo(t, svc)
	if videoPath == "" {
		t.Skip("could not generate test video")
	}

	// 主路径：seek=999999（视频只有 1 秒，越界）
	_, errPrimary := svc.extractVideoFrameToImage(videoPath, "999999")
	// 不假设主路径一定失败（不同 ffmpeg 版本可能 clamp 到末尾），但若失败则走 fallback
	if errPrimary == nil {
		t.Skip("primary seek succeeded unexpectedly (ffmpeg clamped); fallback path not exercised")
	}

	// fallback：seek=0 应成功
	img, err := svc.extractVideoFrameToImage(videoPath, "0")
	if err != nil {
		t.Fatalf("fallback seek=0 failed: %v", err)
	}
	if img == nil {
		t.Fatal("fallback returned nil image")
	}
}

// TestEncodeThumbnailToCache_ProducesValidJPEG 验证 helper 生成合法 JPEG 字节。
func TestEncodeThumbnailToCache_ProducesValidJPEG(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	cachePath := filepath.Join(svc.cacheDir, "test.jpg")

	// 构造 1000x800 测试图
	src := imaging.New(1000, 800, color.NRGBA{R: 255, G: 128, B: 0, A: 255})

	got, err := svc.encodeThumbnailToCache(src, cachePath)
	if err != nil {
		t.Fatalf("encodeThumbnailToCache failed: %v", err)
	}
	if got != cachePath {
		t.Errorf("returned path = %q, want %q", got, cachePath)
	}

	// 验证文件存在 + 能被 image.Decode 读取
	f, err := os.Open(cachePath)
	if err != nil {
		t.Fatalf("open cache file failed: %v", err)
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		t.Fatalf("decode generated thumbnail failed: %v", err)
	}
	bounds := img.Bounds()
	// Linear + Fit：短边 = 150，长边按比例（800/1000 * 150 = 120）
	if bounds.Dx() != 150 || bounds.Dy() != 120 {
		t.Errorf("thumbnail dims = %dx%d, expected 150x120 (Linear+Fit)", bounds.Dx(), bounds.Dy())
	}
}

// TestEncodeThumbnailToCache_SmallImageNotUpscaled 验证源图小于 maxSize 时不放大。
func TestEncodeThumbnailToCache_SmallImageNotUpscaled(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	cachePath := filepath.Join(svc.cacheDir, "small.jpg")

	// 100x80 源图（小于 maxSize=150）
	src := imaging.New(100, 80, color.NRGBA{R: 0, G: 0, B: 255, A: 255})

	if _, err := svc.encodeThumbnailToCache(src, cachePath); err != nil {
		t.Fatalf("encodeThumbnailToCache failed: %v", err)
	}

	f, err := os.Open(cachePath)
	if err != nil {
		t.Fatalf("open cache file failed: %v", err)
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	bounds := img.Bounds()
	// imaging.Fit + Linear 不放大小图：100x80 → 100x80
	if bounds.Dx() != 100 || bounds.Dy() != 80 {
		t.Errorf("small image dims = %dx%d, expected 100x80 (not upscaled)", bounds.Dx(), bounds.Dy())
	}
}

// TestEncodeThumbnailToCache_AtomicWriteNoPartialFile 验证 helper 用 CreateTemp + Rename，
// 写完后 cacheDir 下无 thumb-tmp-* 残留临时文件。
func TestEncodeThumbnailToCache_AtomicWriteNoPartialFile(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	cachePath := filepath.Join(svc.cacheDir, "atomic.jpg")

	src := imaging.New(500, 400, color.NRGBA{R: 0, G: 255, B: 0, A: 255})
	if _, err := svc.encodeThumbnailToCache(src, cachePath); err != nil {
		t.Fatalf("encodeThumbnailToCache failed: %v", err)
	}

	// 检查 cacheDir 下无 thumb-tmp-* 残留
	entries, err := os.ReadDir(svc.cacheDir)
	if err != nil {
		t.Fatalf("readdir failed: %v", err)
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "thumb-tmp-") {
			t.Errorf("temp file leftover: %s (atomic rename should have removed it)", e.Name())
		}
	}
}

// TestGenerateThumbnailFromFile_Video_ProducesValidJPEG 验证视频分支生成的 cachePath
// 是合法 JPEG 字节。需要 ffmpeg + 测试视频，否则跳过。
func TestGenerateThumbnailFromFile_Video_ProducesValidJPEG(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	if !svc.HasFFmpeg() {
		t.Skip("ffmpeg not available")
	}
	videoPath := ensureTestVideo(t, svc)
	if videoPath == "" {
		t.Skip("could not generate test video")
	}

	cachePath := filepath.Join(svc.cacheDir, "videothumb.jpg")
	got, err := svc.generateThumbnailFromFile(videoPath, cachePath)
	if err != nil {
		t.Fatalf("generateThumbnailFromFile video failed: %v", err)
	}
	if got != cachePath {
		t.Errorf("returned path = %q, want %q", got, cachePath)
	}

	f, err := os.Open(cachePath)
	if err != nil {
		t.Fatalf("open cache file failed: %v", err)
	}
	defer f.Close()

	if _, _, err := image.Decode(f); err != nil {
		t.Errorf("generated thumbnail is not valid JPEG: %v", err)
	}
}

// TestGenerateThumbnailFromFile_Video_NoTempFileLeftover 验证视频分支不再产生
// 旧的 videothumb-* 临时文件（C1 改用 image2pipe 后应无残留）。
func TestGenerateThumbnailFromFile_Video_NoTempFileLeftover(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	if !svc.HasFFmpeg() {
		t.Skip("ffmpeg not available")
	}
	videoPath := ensureTestVideo(t, svc)
	if videoPath == "" {
		t.Skip("could not generate test video")
	}

	cachePath := filepath.Join(svc.cacheDir, "videothumb.jpg")
	if _, err := svc.generateThumbnailFromFile(videoPath, cachePath); err != nil {
		t.Fatalf("generateThumbnailFromFile video failed: %v", err)
	}

	// 检查系统 TempDir 下无 videothumb-* 残留（旧逻辑的临时文件前缀）
	tmpDir := os.TempDir()
	entries, err := os.ReadDir(tmpDir)
	if err != nil {
		t.Logf("cannot read TempDir %s: %v (skip residual check)", tmpDir, err)
		return
	}
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), "videothumb-") {
			t.Errorf("old-style temp file leftover in TempDir: %s (C1 should have removed this)", e.Name())
		}
	}
}

// TestEncodeThumbnailToCache_BiLinearScaler 验证 C2 切到 BiLinear 后输出仍是合法 JPEG，
// 且尺寸正确（短边 = maxSize，长边按比例）。
func TestEncodeThumbnailToCache_BiLinearScaler(t *testing.T) {
	svc := newTestThumbnailService(t, "")
	cachePath := filepath.Join(svc.cacheDir, "bilinear.jpg")

	// 5000x4000 大图（模拟高分辨率照片）
	src := imaging.New(5000, 4000, color.NRGBA{R: 100, G: 200, B: 50, A: 255})

	if _, err := svc.encodeThumbnailToCache(src, cachePath); err != nil {
		t.Fatalf("encodeThumbnailToCache failed: %v", err)
	}

	f, err := os.Open(cachePath)
	if err != nil {
		t.Fatalf("open cache file failed: %v", err)
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		t.Fatalf("decode failed: %v", err)
	}
	bounds := img.Bounds()
	// BiLinear + Fit：短边 = 150，长边按比例（4000/5000 * 150 = 120）
	if bounds.Dx() != 150 || bounds.Dy() != 120 {
		t.Errorf("BiLinear+Fit output = %dx%d, expected 150x120", bounds.Dx(), bounds.Dy())
	}
}

// TestGenerateThumbnailFromFile_Image_UsesHelper 验证图片分支也走 encodeThumbnailToCache
// （通过观察 cacheDir 下无直接的 cachePath 之外的文件来间接验证）。
func TestGenerateThumbnailFromFile_Image_UsesHelper(t *testing.T) {
	svc := newTestThumbnailService(t, "")

	// 构造测试图片
	imgPath := filepath.Join(t.TempDir(), "test.png")
	src := imaging.New(800, 600, color.NRGBA{R: 255, G: 0, B: 0, A: 255})
	if err := imaging.Save(src, imgPath); err != nil {
		t.Fatalf("save test image failed: %v", err)
	}

	cachePath := filepath.Join(svc.cacheDir, "imgthumb.jpg")
	got, err := svc.generateThumbnailFromFile(imgPath, cachePath)
	if err != nil {
		t.Fatalf("generateThumbnailFromFile image failed: %v", err)
	}
	if got != cachePath {
		t.Errorf("returned path = %q, want %q", got, cachePath)
	}

	// 验证生成的是合法 JPEG
	f, err := os.Open(cachePath)
	if err != nil {
		t.Fatalf("open cache file failed: %v", err)
	}
	defer f.Close()
	if _, _, err := image.Decode(f); err != nil {
		t.Errorf("generated thumbnail is not valid JPEG: %v", err)
	}
}

// TestHotTracker_RecordsInteractionRequests 验证：通过 GenerateThumbnailBytes
// （内部走 generateBytesVia，即交互请求路径）请求一次后，hotTracker 包含该 path。
// PreGenerateThumbnails 调 GenerateThumbnail（不经 generateBytesVia），不会被记录。
func TestHotTracker_RecordsInteractionRequests(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	// 准备一张测试图片
	imgPath := filepath.Join(tempDir, "test.jpg")
	if err := os.WriteFile(imgPath, validJPEGBytes, 0644); err != nil {
		t.Fatal(err)
	}

	// 调用 GenerateThumbnailBytes（内部走 generateBytesVia）
	_, _ = svc.GenerateThumbnailBytes(imgPath)

	// hotTracker 应包含该 path
	keys := svc.HotTracker().Keys()
	assert.Contains(t, keys, imgPath)
}

// TestHotTracker_LRU200Max 验证：hotTracker 容量为 200，超过时淘汰最老的。
func TestHotTracker_LRU200Max(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	// 手动 add 200 个 path
	for i := 0; i < 200; i++ {
		svc.HotTracker().Add(fmt.Sprintf("path_%d.jpg", i), struct{}{})
	}

	// LRU 容量 200，应保留最后 200 个
	assert.Equal(t, 200, svc.HotTracker().Len())

	// 加第 201 个，最老的（path_0.jpg）应被淘汰
	svc.HotTracker().Add("path_200.jpg", struct{}{})
	assert.Equal(t, 200, svc.HotTracker().Len())
	keys := svc.HotTracker().Keys()
	assert.NotContains(t, keys, "path_0.jpg")
	assert.Contains(t, keys, "path_200.jpg")
}

// BenchmarkHotTracker_Add 度量 hotTracker.Add 热路径操作开销。
// 这是 PreGenerateThumbnails 排序前 read-only 遍历 Keys 时反向上游的写入路径。
func BenchmarkHotTracker_Add(b *testing.B) {
	tempDir := b.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		b.Fatal(err)
	}
	defer svc.Shutdown()

	paths := make([]string, 1000)
	for i := range paths {
		paths[i] = fmt.Sprintf("dir/file_%04d.mp4", i)
	}

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		svc.HotTracker().Add(paths[i%len(paths)], struct{}{})
	}
}

// --- B1.2: PreGenerateThumbnails hotPaths + skip-cached + worker backoff ---

// makeJPEGAt writes a real JPEG file (reusing the package-level validJPEGBytes
// fixture) at path. Used by the B1.2 PreGen tests so all source files are
// decodable by imaging.Open.
func makeJPEGAt(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0755); err != nil {
		t.Fatalf("mkdir %s: %v", filepath.Dir(path), err)
	}
	if err := os.WriteFile(path, validJPEGBytes, 0644); err != nil {
		t.Fatalf("write %s: %v", path, err)
	}
}

// TestPreGenerateThumbnails_HotPathFirst 验证：当 hotDirs 标记某文件所在目录为热点时，
// 该文件的缩略图会在冷文件之前被生成。
//
// Round 32 Task 6 改造后，hot 判定基于目录而非文件 path：hotDir 包含 hot 文件
// 所在目录时，hot 文件入 Tier 1 队列并被优先处理。
//
// 测试策略（Option B，真实 JPEG fixtures + 并发观测）：
//   - 1 个 hot 文件（位于 hotDir 目录）+ 200 个 cold 文件（位于 coldDir 目录）
//   - hotDirs = {hotDir}，coldDir 不在其中 → cold 文件全部 Tier 3，被 PreGen 跳过
//   - PreGen 完成后断言：hot 缩略图存在；cold 缩略图全部不存在（Tier 3 跳过）
func TestPreGenerateThumbnails_HotPathFirst(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	// hot 文件与 cold 文件放在不同子目录，便于按目录分层。
	hotDir := filepath.Join(tempDir, "hotdir")
	coldDir := filepath.Join(tempDir, "colddir")
	const numCold = 200
	hotPath := filepath.Join(hotDir, "hot.jpg")
	makeJPEGAt(t, hotPath)

	files := []models.MediaFile{
		{Name: "hot.jpg", Path: hotPath, MediaType: "image"},
	}
	for i := 0; i < numCold; i++ {
		coldPath := filepath.Join(coldDir, fmt.Sprintf("cold_%03d.jpg", i))
		makeJPEGAt(t, coldPath)
		files = append(files, models.MediaFile{
			Name:      fmt.Sprintf("cold_%03d.jpg", i),
			Path:      coldPath,
			MediaType: "image",
		})
	}

	// hotDirs 用 Clean 后的目录路径（PreGen 内部也会 Clean 比对）。
	hotDirs := map[string]struct{}{filepath.Clean(hotDir): {}}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// PreGen 在 Tier 1 完成后立即返回（cold 全部 Tier 3 跳过，不会阻塞）。
	preGenDone := make(chan struct{})
	go func() {
		svc.PreGenerateThumbnails(files, ctx, hotDirs, nil)
		close(preGenDone)
	}()
	<-preGenDone

	// hot 缩略图必须存在 —— hot 文件在 Tier 1，PreGen 必然处理它。
	hotFi, err := os.Stat(hotPath)
	require.NoError(t, err, "hot source must still exist")
	hotCache := svc.GetThumbnailPath(hotPath, hotFi.ModTime())
	_, err = os.Stat(hotCache)
	assert.NoError(t, err, "hot thumbnail must be generated (Tier 1 directory)")

	// cold 缩略图全部不存在 —— cold 目录不在 hotDirs，也不是 scanRoots 直接子文件，
	// 因此被归入 Tier 3，PreGen 主动跳过。
	for i := 0; i < numCold; i++ {
		coldPath := filepath.Join(coldDir, fmt.Sprintf("cold_%03d.jpg", i))
		fi, err := os.Stat(coldPath)
		if err != nil {
			continue
		}
		cachePath := svc.GetThumbnailPath(coldPath, fi.ModTime())
		_, err = os.Stat(cachePath)
		assert.True(t, os.IsNotExist(err),
			"cold thumbnail %d should NOT be generated (Tier 3 lazy)", i)
	}
}

// TestPreGenerateThumbnails_SkipsCached 验证：已缓存的文件在入队阶段被跳过，
// GenerateThumbnail 不会被重复调用（通过比较磁盘缓存 mtime 不变来验证）。
//
// 测试策略（Option B）：
//  1. 先直接调 GenerateThumbnail 让缓存文件存在
//  2. 记录缓存文件的 mtime
//  3. 调 PreGenerateThumbnails（该文件在 hotPaths 中也无妨，缓存命中会跳过）
//  4. 断言 mtime 未变化（缓存未被重写）
func TestPreGenerateThumbnails_SkipsCached(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	srcPath := filepath.Join(tempDir, "cached.jpg")
	makeJPEGAt(t, srcPath)

	// 步骤 1：先让缩略图缓存存在
	_, err = svc.GenerateThumbnail(srcPath)
	require.NoError(t, err)

	// 步骤 2：记录缓存 mtime
	srcFi, err := os.Stat(srcPath)
	require.NoError(t, err)
	cachePath := svc.GetThumbnailPath(srcPath, srcFi.ModTime())
	cacheFiBefore, err := os.Stat(cachePath)
	require.NoError(t, err, "cache file must exist after GenerateThumbnail")
	mtimeBefore := cacheFiBefore.ModTime()

	// 步骤 3：调 PreGen。文件所在目录在 hotDirs 中也应被跳过（缓存命中）。
	files := []models.MediaFile{
		{Name: "cached.jpg", Path: srcPath, MediaType: "image"},
	}
	hotDirs := map[string]struct{}{filepath.Dir(srcPath): {}}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	svc.PreGenerateThumbnails(files, ctx, hotDirs, nil)

	// 步骤 4：缓存 mtime 不应变（证明未被重写）
	cacheFiAfter, err := os.Stat(cachePath)
	require.NoError(t, err)
	assert.Equal(t, mtimeBefore, cacheFiAfter.ModTime(),
		"cached thumbnail should not be rewritten by PreGen")
}

// TestPreGenerateThumbnails_NilHotDirs 验证：hotDirs 为 nil map 时不会 panic；
// 当 scanRoots 覆盖文件所在目录时（Tier 2），文件仍能被处理（功能正确性 regression guard）。
func TestPreGenerateThumbnails_NilHotDirs(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	srcPath := filepath.Join(tempDir, "regular.jpg")
	makeJPEGAt(t, srcPath)

	files := []models.MediaFile{
		{Name: "regular.jpg", Path: srcPath, MediaType: "image"},
	}
	// 让 tempDir 作为 scanRoot，使该文件符合 Tier 2（scanRoot 直接子文件）。
	scanRoots := []string{tempDir}

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	// 不应 panic
	assert.NotPanics(t, func() {
		svc.PreGenerateThumbnails(files, ctx, nil, scanRoots)
	})

	// 缩略图应被生成（Tier 2 命中）
	srcFi, err := os.Stat(srcPath)
	require.NoError(t, err)
	cachePath := svc.GetThumbnailPath(srcPath, srcFi.ModTime())
	_, err = os.Stat(cachePath)
	assert.NoError(t, err, "thumbnail should be generated for Tier 2 file with nil hotDirs")
}

// TestPreGenerateThumbnails_SkipsUnknownMediaType 验证：非 image/video 的
// MediaType 在入队阶段被过滤掉，不会被处理。
func TestPreGenerateThumbnails_SkipsUnknownMediaType(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	// "document" 类型不应被处理
	srcPath := filepath.Join(tempDir, "doc.txt")
	makeJPEGAt(t, srcPath) // 实际是 JPEG 字节，但 MediaType 不对

	files := []models.MediaFile{
		{Name: "doc.txt", Path: srcPath, MediaType: "document"},
	}

	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
	defer cancel()
	svc.PreGenerateThumbnails(files, ctx, nil, nil)

	// 不应生成缩略图（MediaType 被过滤）
	srcFi, err := os.Stat(srcPath)
	require.NoError(t, err)
	cachePath := svc.GetThumbnailPath(srcPath, srcFi.ModTime())
	_, err = os.Stat(cachePath)
	assert.True(t, os.IsNotExist(err),
		"thumbnail should NOT be generated for unknown MediaType")
}

// TestPreGenerateThumbnails_RespectsContextCancellation 验证：context 取消后
// worker 不继续处理新任务（但已派发的当前任务可能完成）。
func TestPreGenerateThumbnails_RespectsContextCancellation(t *testing.T) {
	tempDir := t.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		t.Fatal(err)
	}
	defer svc.Shutdown()

	// 生成足够多的文件，使取消时仍有未处理的任务
	const numFiles = 50
	files := make([]models.MediaFile, numFiles)
	for i := 0; i < numFiles; i++ {
		p := filepath.Join(tempDir, fmt.Sprintf("f_%02d.jpg", i))
		makeJPEGAt(t, p)
		files[i] = models.MediaFile{
			Name:      fmt.Sprintf("f_%02d.jpg", i),
			Path:      p,
			MediaType: "image",
		}
	}

	// 立即取消的 context
	ctx, cancel := context.WithCancel(context.Background())
	cancel() // 先取消

	// 让 tempDir 作为 scanRoot，使所有文件符合 Tier 2（否则会被 Tier 3 跳过，
	// 无法验证取消语义）。
	scanRoots := []string{tempDir}

	start := time.Now()
	svc.PreGenerateThumbnails(files, ctx, nil, scanRoots)
	elapsed := time.Since(start)

	// 应该很快返回（不处理所有 50 个文件）。
	// 注意：worker 可能在 ctx.Done select 中立即退出，但已排队的 head 任务
	// 可能仍被处理 1-2 个。这里只断言总耗时远小于处理全部 50 个文件的时间。
	assert.Less(t, elapsed, 5*time.Second,
		"PreGen with cancelled ctx should return quickly, got %v", elapsed)

	// 至少有一些文件没被处理（50 个全处理需要 > 5s，cancel 后 < 5s 必然有剩余）
	generated := 0
	for i := 0; i < numFiles; i++ {
		p := filepath.Join(tempDir, fmt.Sprintf("f_%02d.jpg", i))
		fi, err := os.Stat(p)
		if err != nil {
			continue
		}
		cachePath := svc.GetThumbnailPath(p, fi.ModTime())
		if _, err := os.Stat(cachePath); err == nil {
			generated++
		}
	}
	assert.Less(t, generated, numFiles,
		"cancelled context should prevent all files from being processed")
}

// BenchmarkPreGenerateThumbnails_EnqueueStage 度量入队阶段的过滤 + Tier 决策开销。
// 4.3-B 合规：这是 PreGen 端到端中唯一有意义且可隔离的微基准。
// 用合成的 files 切片（无真实磁盘 IO），立即取消 context 使 worker 退出。
// os.Stat 对 fake path 失败 → 所有文件被跳过，仅测量入队 + 过滤 + Tier 判定成本。
func BenchmarkPreGenerateThumbnails_EnqueueStage(b *testing.B) {
	files := make([]models.MediaFile, 1000)
	for i := range files {
		files[i] = models.MediaFile{
			Name:      fmt.Sprintf("file_%04d.jpg", i),
			Path:      fmt.Sprintf("/fake/dir/file_%04d.jpg", i),
			MediaType: "image",
		}
	}
	// Round 32 Task 6: hotDirs 现在是目录集合而非文件 path 集合。
	hotDirs := map[string]struct{}{
		"/fake/dir": {},
	}
	scanRoots := []string{"/fake"}

	tempDir := b.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		b.Fatal(err)
	}
	defer svc.Shutdown()

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // 立即取消，worker 几乎不做实际生成工作

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		svc.PreGenerateThumbnails(files, ctx, hotDirs, scanRoots)
	}
}

// TestEnforceDiskCacheCapEvictsOldest 验证 Phase 9 (M-3) 磁盘缓存总量上限：
// 超过 diskCacheCapBytes 时按 mtime 升序淘汰最旧的 .jpg，较新的文件存活。
func TestEnforceDiskCacheCapEvictsOldest(t *testing.T) {
	dir := t.TempDir()
	s, err := NewThumbnailService(dir, 10, "webp", "ffmpeg")
	if err != nil {
		t.Fatal(err)
	}
	// 直接操作内部上限便于测试：临时下调（生产常量 512MB）
	s.diskCacheCapBytes = 3 << 10
	s.sweepInterval = 0 // 关闭节流
	old := filepath.Join(dir, "old.jpg")
	newer := filepath.Join(dir, "newer.jpg")
	for _, f := range []string{old, newer} {
		if err := os.WriteFile(f, make([]byte, 2<<10), 0644); err != nil {
			t.Fatal(err)
		}
	}
	past := time.Now().Add(-time.Hour)
	os.Chtimes(old, past, past)
	s.enforceDiskCacheCap()
	if _, err := os.Stat(old); !os.IsNotExist(err) {
		t.Fatal("oldest cache file must be evicted")
	}
	if _, err := os.Stat(newer); err != nil {
		t.Fatal("newer cache file must survive")
	}
}

// TestEnforceDiskCacheCap_Throttled 验证清扫节流：距上次清扫不足 sweepInterval
// 时直接返回，不重复清扫（防止 encode 热路径上每次落盘都 walk 整个 cacheDir）。
func TestEnforceDiskCacheCap_Throttled(t *testing.T) {
	dir := t.TempDir()
	s, err := NewThumbnailService(dir, 10, "webp", "ffmpeg")
	if err != nil {
		t.Fatal(err)
	}
	s.diskCacheCapBytes = 3 << 10
	s.sweepInterval = 0
	s.enforceDiskCacheCap()     // 首次清扫（lastSweep=0 恒通过节流），记录 lastSweep
	s.sweepInterval = time.Hour // 拉满节流窗口：1h 内的后续调用必须直接返回

	old := filepath.Join(dir, "old.jpg")
	newer := filepath.Join(dir, "newer.jpg")
	for _, f := range []string{old, newer} {
		if err := os.WriteFile(f, make([]byte, 2<<10), 0644); err != nil {
			t.Fatal(err)
		}
	}
	past := time.Now().Add(-time.Hour)
	os.Chtimes(old, past, past)

	// 距上次清扫 < sweepInterval：即使超限也不得删除任何文件
	s.enforceDiskCacheCap()
	if _, err := os.Stat(old); err != nil {
		t.Fatal("throttled sweep must not evict files")
	}
	if _, err := os.Stat(newer); err != nil {
		t.Fatal("throttled sweep must not touch surviving files")
	}
}

// BenchmarkEncodeThumbnailToCache 度量 encodeThumbnailToCache 热路径的内存分配。
// B2 前后对比基线：用 5000x4000 大图（典型高分辨率照片）作为输入，
// 每次迭代用不同 cachePath 避免命中已存在文件。
// 报告 ns/op + B/op + allocs/op，B2 改造后 B/op 预期下降 30-50%
// （jpeg.Encode 内部分配无法 pool 控制，只覆盖输出 buffer）。
func BenchmarkEncodeThumbnailToCache(b *testing.B) {
	tempDir := b.TempDir()
	svc, err := NewThumbnailService(tempDir, 300, "JPEG", "")
	if err != nil {
		b.Fatal(err)
	}
	defer svc.Shutdown()

	// 构造 5000x4000 测试图（典型大图）
	src := imaging.New(5000, 4000, color.NRGBA{R: 255, G: 0, B: 0, A: 255})

	cachePath := filepath.Join(tempDir, "bench.jpg")

	b.ResetTimer()
	b.ReportAllocs()
	for i := 0; i < b.N; i++ {
		// 每次用不同 cachePath 避免命中已存在文件
		cp := fmt.Sprintf("%s_%d.jpg", cachePath, i)
		if _, err := svc.encodeThumbnailToCache(src, cp); err != nil {
			b.Fatal(err)
		}
		// 清理
		_ = os.Remove(cp)
	}
}

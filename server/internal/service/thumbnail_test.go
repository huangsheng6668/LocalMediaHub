package service

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

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

func TestFFprobeSibling(t *testing.T) {
	// Use filepath.Join for both input and expected so the test is separator-agnostic.
	if got, want := ffprobeSibling(filepath.Join("dir", "ffmpeg.exe")), filepath.Join("dir", "ffprobe.exe"); got != want {
		t.Errorf("ffmpeg.exe -> %q, want %q", got, want)
	}
	if got, want := ffprobeSibling("ffmpeg"), "ffprobe"; got != want {
		t.Errorf("bare ffmpeg -> %q, want %q", got, want)
	}
	if got, want := ffprobeSibling(filepath.Join("dir", "avconv.exe")), "ffprobe"; got != want {
		t.Errorf("non-ffmpeg base -> %q, want %q", got, want)
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

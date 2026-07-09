package service

import (
	"bytes"
	"context"
	"crypto/md5"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	_ "image/png"
	"log/slog"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/disintegration/imaging"
	"github.com/hashicorp/golang-lru/v2"
	"github.com/localmediahub/server/internal/models"
	"golang.org/x/sync/singleflight"
)

type ThumbnailService struct {
	cacheDir   string
	maxSize    int
	format     string
	sem        chan struct{}
	ffmpegPath string
	// memCache stores JPEG bytes keyed by md5(sourcePath + "|" + modTime).
	// Both GenerateThumbnailBytes and GenerateSystemThumbnailBytes share this
	// cache. The shared key is safe because the underlying
	// generateThumbnailFromFile pipeline uses the same maxSize/format/quality
	// for both call paths — the only difference is which disk subdirectory
	// (cacheDir/ vs cacheDir/system/) the bytes are persisted to. So a cache
	// hit from either path returns byte-identical output for the same source
	// file. If the two pipelines ever diverge (different maxSize per path),
	// the key MUST be namespaced (e.g. "regular:" / "system:" prefix).
	memCache *lru.Cache[string, []byte]

	// sf 防止多客户端同时请求同一未缓存视频时重复 fork ffmpeg/ffprobe。
	// Do 的 key 用 thumbnailCacheKey(sourcePath, modTime)，含 modTime 所以
	// 文件被替换后会自然产生新 key，不会把新旧版本串到一起。
	sf singleflight.Group

	// durations.json 持久化缓存：避免视频缩略图 miss 时每次都 fork ffprobe。
	// 也通过 VideoDuration 导出方法共享给 /api/v1/media/duration handler。
	durMu           sync.RWMutex
	durCache        map[string]durationEntry
	durDirty        bool               // 内存数据是否脏（待落盘）
	durTimerPending bool               // 是否已启动 5s 延迟落盘协程
	ctx             context.Context    // 用于 goroutine 生命周期控制
	durCancel       context.CancelFunc // 用于在服务停止时取消 goroutine
}

func NewThumbnailService(cacheDir string, maxSize int, format string, ffmpegPath string) (*ThumbnailService, error) {
	if err := os.MkdirAll(cacheDir, 0755); err != nil {
		return nil, err
	}
	// golang-lru/v2 returns no error when size > 0; the explicit discard is
	// documented. 200 entries ≈ 20 MB heap at ~100 KB per thumbnail.
	memCache, _ := lru.NewWithEvict[string, []byte](200, nil)

	ctx, cancel := context.WithCancel(context.Background())
	s := &ThumbnailService{
		cacheDir:   cacheDir,
		maxSize:    maxSize,
		format:     format,
		sem:        make(chan struct{}, runtime.NumCPU()),
		ffmpegPath: ffmpegPath,
		memCache:   memCache,
		durCache:   make(map[string]durationEntry),
		ctx:        ctx,
		durCancel:  cancel,
	}
	s.loadDurationCache()
	return s, nil
}

func (s *ThumbnailService) getFFmpegCmd() string {
	if s.ffmpegPath != "" {
		return s.ffmpegPath
	}
	return "ffmpeg"
}

func (s *ThumbnailService) HasFFmpeg() bool {
	cmdName := s.getFFmpegCmd()
	_, err := exec.LookPath(cmdName)
	return err == nil
}

func (s *ThumbnailService) getFFprobeCmd() string {
	if s.ffmpegPath != "" {
		return ffprobeSibling(s.ffmpegPath)
	}
	return "ffprobe"
}

// videoDuration returns the file's duration in seconds via ffprobe, or
// (0, false) if ffprobe is unavailable or the probe fails.
func (s *ThumbnailService) videoDuration(sourcePath string) (float64, bool) {
	cmd := exec.Command(s.getFFprobeCmd(),
		"-v", "error",
		"-show_entries", "format=duration",
		"-of", "default=noprint_wrappers=1:nokey=1",
		sourcePath)
	out, err := cmd.Output()
	if err != nil {
		return 0, false
	}
	return parseFFprobeDuration(string(out))
}

// videoDurationCached 是 videoDuration 的缓存版本：先查内存 durCache（读锁），
// miss 时 fork ffprobe 并写回 durCache（写锁）+ 标记 dirty 触发防抖落盘。
// os.Stat 失败时 fallback 到原 videoDuration（无缓存），与历史行为一致。
func (s *ThumbnailService) videoDurationCached(sourcePath string) (float64, bool) {
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return s.videoDuration(sourcePath)
	}
	key := sourcePath + "|" + fi.ModTime().Format(time.RFC3339Nano)

	s.durMu.RLock()
	if entry, ok := s.durCache[key]; ok {
		s.durMu.RUnlock()
		return entry.Duration, true
	}
	s.durMu.RUnlock()

	d, ok := s.videoDuration(sourcePath)
	if ok {
		s.durMu.Lock()
		s.durCache[key] = durationEntry{Duration: d, ModTime: fi.ModTime()}
		s.markDurDirty()
		s.durMu.Unlock()
	}
	return d, ok
}

// VideoDuration 是 videoDurationCached 的导出版本，供 handler 层
// （/api/v1/media/duration）共享同一份时长缓存，避免重复 fork ffprobe。
// 行为与 videoDurationCached 完全一致：先查内存 cache，miss 时 fork ffprobe
// 并写回 cache + 标记 dirty。
func (s *ThumbnailService) VideoDuration(sourcePath string) (float64, bool) {
	return s.videoDurationCached(sourcePath)
}

// extractVideoFrameToImage 调用 ffmpeg 从 sourcePath 的 seek 秒位置抽取一帧，
// 通过 stdout pipe 直接返回 image.Image，避免临时文件 IO。
// 失败时返回 error，由 caller 决定 fallback 策略。
func (s *ThumbnailService) extractVideoFrameToImage(sourcePath, seek string) (image.Image, error) {
	// 限制 ffmpeg 子进程执行时间，防止损坏视频导致永久挂起
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, s.getFFmpegCmd(),
		"-y", "-ss", seek, "-i", sourcePath,
		"-vframes", "1",
		"-f", "image2pipe",
		"-vcodec", "mjpeg",
		"pipe:1",
	)

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	// 捕获 stderr 用于错误诊断
	var stderr bytes.Buffer
	cmd.Stderr = &stderr

	if err := cmd.Start(); err != nil {
		return nil, err
	}

	// Go 标准库 image.Decode 自动识别 mjpeg → jpeg decoding
	// （thumbnail.go 已 import _ "image/png" + image/jpeg 隐式注册）
	img, _, decodeErr := image.Decode(stdout)

	// 显式关闭 pipe 读端。若 Decode 提前退出/报错，向 ffmpeg 写端发送
	// SIGPIPE/EPIPE，避免 ffmpeg 因 pipe 缓冲区满而阻塞挂起。
	_ = stdout.Close()

	// 等待 ffmpeg 退出以释放子进程资源，避免 zombie 进程
	waitErr := cmd.Wait()

	if decodeErr != nil {
		return nil, fmt.Errorf("failed to decode ffmpeg pipe: %w (wait err: %v, stderr: %s)", decodeErr, waitErr, stderr.String())
	}
	// decodeErr == nil 说明图片已完整解析；Wait 的 EPIPE/exit 1 是 pipe 提前关闭的预期副作用
	return img, nil
}

// encodeThumbnailToCache 把 src 等比缩放到 max×max 框内并写入 cachePath。
// C1 阶段保留 imaging.Thumbnail + Box 缩放器（C2 再优化为 BiLinear）。
// 用 os.CreateTemp + os.Rename 原子写入：进程崩溃/并发写不会留下半截损坏 jpg。
func (s *ThumbnailService) encodeThumbnailToCache(src image.Image, cachePath string) (string, error) {
	thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Box)

	tempFile, err := os.CreateTemp(filepath.Dir(cachePath), "thumb-tmp-*.jpg")
	if err != nil {
		return "", err
	}
	tempPath := tempFile.Name()
	defer os.Remove(tempPath) // 出错提前返回时自动清理

	if err := jpeg.Encode(tempFile, thumb, &jpeg.Options{Quality: 85}); err != nil {
		tempFile.Close()
		return "", err
	}
	if err := tempFile.Close(); err != nil {
		return "", err
	}

	if err := os.Rename(tempPath, cachePath); err != nil {
		return "", err
	}
	return cachePath, nil
}

func isVideoFile(filePath string) bool {
	ext := strings.ToLower(filepath.Ext(filePath))
	switch ext {
	case ".mp4", ".mkv", ".avi", ".mov", ".wmv", ".flv", ".ts", ".webm", ".m4v", ".3gp":
		return true
	}
	return false
}

func (s *ThumbnailService) GetThumbnailPath(sourcePath string, modTime time.Time) string {
	key := sourcePath + "|" + modTime.Format(time.RFC3339Nano)
	hash := fmt.Sprintf("%x", md5.Sum([]byte(key)))
	return filepath.Join(s.cacheDir, hash+".jpg")
}

// thumbnailCacheKey returns the md5 hash used as both disk cache filename
// (sans .jpg) and memory cache key. MUST match GetThumbnailPath's format
// exactly — both use RFC3339Nano, NOT UnixNano().
func (s *ThumbnailService) thumbnailCacheKey(sourcePath string, modTime time.Time) string {
	key := sourcePath + "|" + modTime.Format(time.RFC3339Nano)
	return fmt.Sprintf("%x", md5.Sum([]byte(key)))
}

func (s *ThumbnailService) generateThumbnailFromFile(sourcePath string, cachePath string) (string, error) {
	if isVideoFile(sourcePath) {
		if !s.HasFFmpeg() {
			return "", fmt.Errorf("ffmpeg not found, cannot generate video thumbnail")
		}

		seek := midpointSeek(s.videoDurationCached(sourcePath))

		// 主路径：seek 到 midpoint 抽帧
		src, err := s.extractVideoFrameToImage(sourcePath, seek)
		if err != nil {
			// fallback：seek=0 重试（视频太短或 midpoint 越界）
			src, err = s.extractVideoFrameToImage(sourcePath, "0")
			if err != nil {
				return "", fmt.Errorf("failed to extract video frame: %w", err)
			}
		}

		// C1: 传递未缩放的 src，由 encodeThumbnailToCache 完成缩放和落盘
		return s.encodeThumbnailToCache(src, cachePath)
	}

	// 图片分支（C1 阶段保留旧逻辑；C2 Task 4 改用 helper）
	src, err := imaging.Open(sourcePath)
	if err != nil {
		return "", err
	}

	thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Box)

	out, err := os.Create(cachePath)
	if err != nil {
		return "", err
	}
	defer out.Close()

	if err := jpeg.Encode(out, thumb, &jpeg.Options{Quality: 85}); err != nil {
		return "", err
	}

	return cachePath, nil
}

func (s *ThumbnailService) GenerateThumbnail(sourcePath string) (string, error) {
	// Get file info for modTime-based cache key
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return "", err
	}

	cachePath := s.GetThumbnailPath(sourcePath, fi.ModTime())
	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	// Acquire semaphore slot for CPU-intensive rendering
	s.sem <- struct{}{}
	defer func() { <-s.sem }()

	// Double-check cache in case it was generated while waiting
	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	return s.generateThumbnailFromFile(sourcePath, cachePath)
}

func (s *ThumbnailService) GenerateSystemThumbnail(sourcePath string) (string, error) {
	// Get file info for modTime-based cache key
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return "", err
	}

	systemCacheDir := filepath.Join(s.cacheDir, "system")
	if err := os.MkdirAll(systemCacheDir, 0755); err != nil {
		return "", err
	}

	key := sourcePath + "|" + fi.ModTime().Format(time.RFC3339Nano)
	hash := fmt.Sprintf("%x", md5.Sum([]byte(key)))
	cachePath := filepath.Join(systemCacheDir, hash+".jpg")

	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	// Acquire semaphore slot for CPU-intensive rendering
	s.sem <- struct{}{}
	defer func() { <-s.sem }()

	// Double-check cache in case it was generated while waiting
	if _, err := os.Stat(cachePath); err == nil {
		return cachePath, nil
	}

	return s.generateThumbnailFromFile(sourcePath, cachePath)
}

func (s *ThumbnailService) PreGenerateThumbnails(files []models.MediaFile, ctx context.Context) {
	hasFFmpeg := s.HasFFmpeg()
	var queue []models.MediaFile
	for _, f := range files {
		switch f.MediaType {
		case "image":
			queue = append(queue, f)
		case "video":
			if hasFFmpeg {
				queue = append(queue, f)
			}
		}
	}

	if len(queue) == 0 {
		return
	}

	numWorkers := runtime.NumCPU() / 2
	if numWorkers < 1 {
		numWorkers = 1
	}

	jobs := make(chan models.MediaFile, len(queue))
	for _, img := range queue {
		jobs <- img
	}
	close(jobs)

	var wg sync.WaitGroup
	for i := 0; i < numWorkers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case img, ok := <-jobs:
					if !ok {
						return
					}
					// Quick check cache
					fi, err := os.Stat(img.Path)
					if err != nil {
						continue
					}
					cachePath := s.GetThumbnailPath(img.Path, fi.ModTime())
					if _, err := os.Stat(cachePath); err == nil {
						continue
					}

					// Let's also check context before taking the semaphore
					select {
					case <-ctx.Done():
						return
					default:
					}

					_, _ = s.GenerateThumbnail(img.Path)
				}
			}
		}()
	}
	wg.Wait()
}

// generateBytesVia returns the JPEG bytes for [sourcePath], serving from
// memCache on hit. On miss it calls [genFunc] to ensure the disk-cached
// file exists, then reads it into memCache. The genFunc indirection lets
// both GenerateThumbnailBytes and GenerateSystemThumbnailBytes share
// logic — only the disk path differs.
//
// singleflight 包裹 genFunc + ReadFile + memCache.Add：多客户端并发请求同一
// 未缓存视频时只 fork 一次 ffmpeg/ffprobe，follower 等待 leader 写入 memCache
// 后直接拿到字节返回。
func (s *ThumbnailService) generateBytesVia(
	sourcePath string,
	genFunc func(string) (string, error),
) ([]byte, error) {
	fi, err := os.Stat(sourcePath)
	if err != nil {
		return nil, err
	}
	cacheKey := s.thumbnailCacheKey(sourcePath, fi.ModTime())

	// 快速路径：memCache 命中直接返回，不进入 singleflight。
	if cached, ok := s.memCache.Get(cacheKey); ok {
		return cached, nil
	}

	// 慢路径：用 cacheKey（含 modTime）作为 singleflight key。文件被替换后
	// modTime 变化 → key 变化 → 新 leader 重新生成，不会串版本。
	val, err, _ := s.sf.Do(cacheKey, func() (interface{}, error) {
		cachePath, err := genFunc(sourcePath)
		if err != nil {
			return nil, err
		}
		bytes, err := os.ReadFile(cachePath)
		if err != nil {
			return nil, err
		}
		s.memCache.Add(cacheKey, bytes)
		return bytes, nil
	})
	if err != nil {
		return nil, err
	}
	return val.([]byte), nil
}

// GenerateThumbnailBytes is the bytes-equivalent of GenerateThumbnail.
// On memory-cache hit returns JPEG bytes without touching disk.
func (s *ThumbnailService) GenerateThumbnailBytes(sourcePath string) ([]byte, error) {
	return s.generateBytesVia(sourcePath, s.GenerateThumbnail)
}

// GenerateSystemThumbnailBytes is the bytes-equivalent of GenerateSystemThumbnail.
// System thumbnails live under cacheDir/system/ but share the same memory
// cache (keyed by md5(path + modtime) which is unique per source file).
func (s *ThumbnailService) GenerateSystemThumbnailBytes(sourcePath string) ([]byte, error) {
	return s.generateBytesVia(sourcePath, s.GenerateSystemThumbnail)
}

// DecodeImage decodes an image file and returns the Go image object.
func DecodeImage(path string) (image.Image, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()

	img, _, err := image.Decode(f)
	if err != nil {
		return nil, err
	}
	return img, nil
}

// ffprobeSibling derives the ffprobe path from an ffmpeg path: same directory
// and extension with the basename ffmpeg -> ffprobe. If the basename does not
// contain "ffmpeg", it returns the bare "ffprobe" (relying on PATH lookup).
func ffprobeSibling(ffmpegPath string) string {
	base := strings.ToLower(filepath.Base(ffmpegPath))
	if !strings.Contains(base, "ffmpeg") {
		return "ffprobe"
	}
	ext := filepath.Ext(ffmpegPath)
	return filepath.Join(filepath.Dir(ffmpegPath), "ffprobe"+ext)
}

// parseFFprobeDuration parses ffprobe's duration output (seconds, decimal).
// Returns false on empty / "N/A" / non-numeric / non-positive input.
func parseFFprobeDuration(out string) (float64, bool) {
	out = strings.TrimSpace(out)
	if out == "" || out == "N/A" {
		return 0, false
	}
	d, err := strconv.ParseFloat(out, 64)
	if err != nil || d <= 0 {
		return 0, false
	}
	return d, true
}

// midpointSeek returns the seek offset (seconds, 2 decimals) at half the video
// duration for a representative frame; falls back to "5" when the duration is
// unknown (preserving the prior hardcoded -ss 5 behavior).
func midpointSeek(duration float64, ok bool) string {
	if !ok || duration <= 0 {
		return "5"
	}
	return strconv.FormatFloat(duration/2, 'f', 2, 64)
}

// durationEntry 是 durations.json 持久化缓存的单条记录。
// key 形如 "<sourcePath>|<RFC3339Nano modTime>"，ModTime 仅作信息记录，
// 真正的失效由 key 中的 modTime 字符串变化来保证。
type durationEntry struct {
	Duration float64   `json:"duration"` // seconds
	ModTime  time.Time `json:"modTime"`  // source file mtime; mismatch → invalidate
}

// loadDurationCache 启动时从 cacheDir/durations.json 加载视频时长缓存。
// 文件不存在视为空 cache（首次启动）；解析失败 log warn 后用空 cache 启动，
// 不删除文件（避免误删有用数据），下次 miss 会覆盖式重写。
func (s *ThumbnailService) loadDurationCache() {
	filePath := filepath.Join(s.cacheDir, "durations.json")
	bytes, err := os.ReadFile(filePath)
	if err != nil {
		// 文件不存在或读失败：保持构造函数里初始化的空 map
		return
	}

	var cache map[string]durationEntry
	if err := json.Unmarshal(bytes, &cache); err != nil {
		slog.Warn("Failed to unmarshal durations.json, starting with empty cache", "error", err)
		return
	}

	s.durMu.Lock()
	s.durCache = cache
	s.durMu.Unlock()
}

// markDurDirty 必须在持有 durMu.Lock() 时调用：标记数据脏并启动 5s 防抖落盘
// 协程（如尚未启动）。释放锁后才执行磁盘 I/O，避免阻塞查询路径。
func (s *ThumbnailService) markDurDirty() {
	s.durDirty = true
	if s.durTimerPending {
		return
	}
	s.durTimerPending = true

	go func() {
		select {
		case <-s.ctx.Done():
			// 服务退出：Shutdown 方法会做同步落盘，本协程直接返回
			return
		case <-time.After(5 * time.Second):
		}

		s.durMu.Lock()
		if !s.durDirty {
			s.durTimerPending = false
			s.durMu.Unlock()
			return
		}
		s.durTimerPending = false
		s.durMu.Unlock()

		s.persistDurationCache()
	}()
}

// persistDurationCache 把 durCache 落盘到 cacheDir/durations.json。
// 先持锁 marshal + 清 dirty 标记，再释放锁执行磁盘 I/O。
// 写入失败时恢复 dirty 标记以便下次重试。
func (s *ThumbnailService) persistDurationCache() {
	s.durMu.Lock()
	if !s.durDirty {
		s.durMu.Unlock()
		return
	}
	bytes, err := json.Marshal(s.durCache)
	s.durDirty = false
	s.durMu.Unlock()

	if err != nil {
		slog.Warn("Failed to marshal duration cache", "error", err)
		return
	}

	filePath := filepath.Join(s.cacheDir, "durations.json")
	if err := os.WriteFile(filePath, bytes, 0644); err != nil {
		slog.Warn("Failed to write durations.json", "error", err)
		// 写入失败：恢复脏标记，下次 markDurDirty 时会再次尝试
		s.durMu.Lock()
		s.durDirty = true
		s.durMu.Unlock()
	}
}

// Shutdown 取消防抖协程并同步落盘。由 Server.Stop() 调用。
// 幂等：多次调用安全（durCancel 可重入，persistDurationCache 自带 dirty 守卫）。
func (s *ThumbnailService) Shutdown() {
	s.durCancel()
	s.persistDurationCache()
}

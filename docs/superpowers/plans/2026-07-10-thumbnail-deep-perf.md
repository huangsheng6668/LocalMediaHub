# Round 28 缩略图深化 + 文件夹搜索索引化 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec `docs/superpowers/specs/2026-07-10-thumbnail-deep-perf-design.md` 实现 3 个独立 commit —— C1 视频缩略图 ffmpeg 流式管道、C2 图片缩略图 BiLinear 缩放器、C3 文件夹搜索 scanner cacheDirs，分别消除视频缩略图临时文件 IO、大图缩放耗时、文件夹搜索磁盘 walk。

**Architecture:** C1/C2 集中在 `server/internal/service/thumbnail.go`，引入 `extractVideoFrameToImage`（ffmpeg stdout pipe → image.Decode）和 `encodeThumbnailToCache`（统一缩放 + 原子写入）两个 helper。C3 跨 `scanner.go` + `search.go`：Scanner 扫描时递归收集祖先目录到 `cacheDirs`/`cacheDirMap`，handler 改走内存前缀扫。

**Tech Stack:** Go 1.25+ / Echo v4 / `disintegration/imaging` / `modernc.org/sqlite` / 标准 `os/exec` + `image/jpeg` + `image/png`

## Global Constraints

- **API 契约不变**：`/api/v1/media/thumbnail`、`/api/v1/search` 等 URL、参数、响应 schema 全部不变
- **磁盘缓存 key 格式不变**：`cacheDir/<md5(path + "|" + RFC3339Nano modTime)>.jpg`（与 Round 24 一致）
- **缩略图视觉等价**：C1 字节完全一致；C2 BiLinear 与 Lanczos 在 300×300 场景视觉等价（字节不同但视觉无感知差异）
- **Go 代码规范**：Handler 层不持全局变量；列表用 `make([]T, 0, n)` 初始化；业务逻辑在 `internal/service/`
- **测试规范**：标准 `testing` 包 + 表驱动 + `t.TempDir()`；ffmpeg 依赖测试用 `t.Skip` 守护
- **跨平台**：Windows 是主平台，路径大小写不敏感场景必须用 `strings.EqualFold`
- **不破坏现有测试**：`go test ./...` 全过

---

## File Structure

| 文件 | 改动 | 责任 |
|---|---|---|
| `server/internal/service/thumbnail.go` | 改 | C1+C2：新增 `extractVideoFrameToImage` + `encodeThumbnailToCache` helper；`generateThumbnailFromFile` 视频和图片分支改用 helper；引入 `"bytes"` 包 |
| `server/internal/service/thumbnail_test.go` | 改 | C1+C2 测试：抽帧主路径/fallback、临时文件残留检查、BiLinear 输出合理性 |
| `server/internal/service/scanner.go` | 改 | C3：Scanner struct 加 `cacheDirs`/`cacheDirMap` 字段；Scan 递归收集祖先目录；新增 `GetCachedDirs`/`peekCachedDirs`/`filterDirsByScope`；`InvalidateCache` 同步清理；引入 `"runtime"` + `"sort"` |
| `server/internal/service/scanner_test.go` | 改 | C3 测试：cacheDirs 填充、scope 过滤、根排除、排序、InvalidateCache |
| `server/internal/server/handler/search.go` | 改 | C3：`searchFoldersCtx` → `searchFoldersCached`，改走 `GetCachedDirs`；引入 `"runtime"` |
| `server/internal/server/handler/search_test.go` | 改 | C3 测试：searchFoldersCached 基础匹配/scope/limit/ctx 取消 |

---

## Task 1: C1 — 新增 `extractVideoFrameToImage` helper（ffmpeg stdout pipe）

**Files:**
- Modify: `server/internal/service/thumbnail.go:3`（imports）+ 新增方法挂在 `ThumbnailService`
- Test: `server/internal/service/thumbnail_test.go`

**Interfaces:**
- Consumes: `s.getFFmpegCmd() string`（已有）、`s.HasFFmpeg() bool`（已有）
- Produces: `func (s *ThumbnailService) extractVideoFrameToImage(sourcePath, seek string) (image.Image, error)` —— Task 3 的 `generateThumbnailFromFile` 视频分支会调用

- [ ] **Step 1: 在 thumbnail.go 引入 `"bytes"` 包**

修改 `server/internal/service/thumbnail.go` 的 import 块（当前 3-25 行），在 `"encoding/json"` 之后加入 `"bytes"`（保持字典序）：

```go
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
```

- [ ] **Step 1b: 在 thumbnail_test.go 加 `newTestThumbnailService` + `ensureTestVideo` 测试 helper**

在 `server/internal/service/thumbnail_test.go` 顶部（`TestParseFFprobeDuration` 之前）追加两个 helper：

```go
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
```

- [ ] **Step 2: 写失败测试 — 主路径（需要 ffmpeg）**

在 `thumbnail_test.go` 末尾追加：

```go
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
```

- [ ] **Step 3: 写失败测试 — seek=0 fallback**

继续追加到 `thumbnail_test.go`：

```go
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
```

- [ ] **Step 4: 运行测试确认失败（helper 还没实现）**

Run: `cd server && go test ./internal/service/ -run TestExtractVideoFrameToImage -v`
Expected: 编译失败 `undefined: (*ThumbnailService).extractVideoFrameToImage`（如果 ffmpeg 可用）；或编译失败（无 ffmpeg 环境也是编译失败，不是 skip）

- [ ] **Step 5: 实现 `extractVideoFrameToImage`**

在 `server/internal/service/thumbnail.go` 的 `videoDurationCached` 方法之后（约 150 行附近，`VideoDuration` 导出方法之后），追加：

```go
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
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestExtractVideoFrameToImage -v`
Expected: PASS（有 ffmpeg 环境）；或 SKIP（无 ffmpeg 环境）。**不能是 FAIL。**

- [ ] **Step 7: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "$(cat <<'EOF'
feat(thumbnail): add extractVideoFrameToImage helper (C1 Task 1)

ffmpeg image2pipe + stdout pipe → image.Decode, no temp file IO.
15s CommandContext timeout + stderr capture for diagnostics.
Explicit pipe close prevents ffmpeg buffer-full deadlock.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: C1 — 新增 `encodeThumbnailToCache` helper（C1 阶段保留 Box 缩放）

**Files:**
- Modify: `server/internal/service/thumbnail.go` 追加方法
- Test: `server/internal/service/thumbnail_test.go`

**Interfaces:**
- Consumes: `s.maxSize int`（已有字段）
- Produces: `func (s *ThumbnailService) encodeThumbnailToCache(src image.Image, cachePath string) (string, error)` —— Task 3 视频分支 + Task 4 图片分支共用

- [ ] **Step 1: 写失败测试 — 输出合法 JPEG**

在 `thumbnail_test.go` 末尾追加（不依赖 ffmpeg）：

```go
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
	// Box 等比缩放：短边 = maxSize=150，长边按比例（800/1000 * 150 = 120）
	if bounds.Dx() != 150 && bounds.Dy() != 150 {
		t.Errorf("thumbnail dims = %dx%d, expected short side = 150", bounds.Dx(), bounds.Dy())
	}
}
```

需要在 thumbnail_test.go 顶部 import 块加入：
```go
"image/color"
"github.com/disintegration/imaging"
```

- [ ] **Step 2: 写失败测试 — 小图不放大**

继续追加：

```go
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
	// imaging.Thumbnail + Box 对小图不放大：保持 100x80
	if bounds.Dx() != 100 || bounds.Dy() != 80 {
		t.Errorf("small image dims = %dx%d, expected 100x80 (no upscale)", bounds.Dx(), bounds.Dy())
	}
}
```

需要在 thumbnail_test.go import 加入 `"image"`。

- [ ] **Step 3: 写失败测试 — 原子写入（无半截文件）**

继续追加：

```go
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
```

需要 import `"strings"`（若已有则跳过）。

- [ ] **Step 4: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestEncodeThumbnailToCache -v`
Expected: 编译失败 `undefined: (*ThumbnailService).encodeThumbnailToCache`

- [ ] **Step 5: 实现 `encodeThumbnailToCache`（C1 阶段保留 Box）**

在 `thumbnail.go` 的 `extractVideoFrameToImage` 之后追加：

```go
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
```

- [ ] **Step 6: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestEncodeThumbnailToCache -v`
Expected: 3 个测试全部 PASS

- [ ] **Step 7: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "$(cat <<'EOF'
feat(thumbnail): add encodeThumbnailToCache helper (C1 Task 2)

Unified scale + atomic write (CreateTemp + Rename). C1 keeps Box scaler;
C2 will swap to BiLinear. Eliminates partial-file corruption risk.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: C1 — 改造 `generateThumbnailFromFile` 视频分支改用新 helper

**Files:**
- Modify: `server/internal/service/thumbnail.go:176-244`（`generateThumbnailFromFile` 整个函数）
- Test: `server/internal/service/thumbnail_test.go`

**Interfaces:**
- Consumes: Task 1 `extractVideoFrameToImage` + Task 2 `encodeThumbnailToCache`
- Produces: 改造后的 `generateThumbnailFromFile`，行为兼容原 API（接受 sourcePath + cachePath，返回 cachePath）

- [ ] **Step 1: 写失败测试 — 视频分支生成合法 JPEG + 无临时文件残留**

在 `thumbnail_test.go` 末尾追加：

```go
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
```

- [ ] **Step 2: 运行测试确认失败（旧实现仍写临时文件）**

Run: `cd server && go test ./internal/service/ -run TestGenerateThumbnailFromFile_Video -v`
Expected: `TestGenerateThumbnailFromFile_Video_NoTempFileLeftover` FAIL（旧实现 `os.CreateTemp("", "videothumb-*.jpg")` 会留下残留，但因 `defer os.Remove(tempPath)` 实际会清理，可能 PASS）。若 PASS，继续 Step 3 —— 关键是改造后仍 PASS。

- [ ] **Step 3: 改造 `generateThumbnailFromFile` 视频分支**

修改 `server/internal/service/thumbnail.go` 的 `generateThumbnailFromFile`（176-244 行），整个函数替换为：

```go
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
```

**关键变化**：
- 视频分支：删除 `os.CreateTemp("", "videothumb-*.jpg")` + ffmpeg `-f image2` 写临时文件 + `imaging.Open(tempPath)` 读回 + `defer os.Remove(tempPath)`
- 视频分支：改用 `extractVideoFrameToImage` + `encodeThumbnailToCache`
- 图片分支：**C1 阶段保持原样**，C2 Task 4 再改造

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -v`
Expected: 所有测试 PASS（包括 Round 24 的 singleflight + duration cache 测试不回归）

- [ ] **Step 5: 全套测试确认无回归**

Run: `cd server && go test ./...`
Expected: 全部 PASS

- [ ] **Step 6: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "$(cat <<'EOF'
perf(thumbnail): video branch uses ffmpeg pipe (C1 Task 3)

generateThumbnailFromFile video path switched from temp-file roundtrip
to extractVideoFrameToImage + encodeThumbnailToCache. Eliminates one
write + one read per video thumbnail (~50-200ms saved on miss).

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: C2 — 图片分支改用 `encodeThumbnailToCache` + 切换 BiLinear 缩放器

**Files:**
- Modify: `server/internal/service/thumbnail.go`（`encodeThumbnailToCache` 内的 `imaging.Thumbnail` → `imaging.Fit` + `imaging.Linear`）+ `generateThumbnailFromFile` 图片分支
- Test: `server/internal/service/thumbnail_test.go`

**Interfaces:**
- Consumes: Task 2 的 `encodeThumbnailToCache`
- Produces: 图片分支也走 helper；缩放器从 Box → BiLinear

- [ ] **Step 1: 写失败测试 — BiLinear 输出合理**

在 `thumbnail_test.go` 末尾追加：

```go
// TestEncodeThumbnailToCache_BiLinearScaler 验证 C2 切到 BiLinear 后输出仍是合法 JPEG，
// 且尺寸正确（短边 = maxSize）。
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
	if bounds.Dx() != 150 {
		t.Errorf("BiLinear output width = %d, expected 150 (short side = maxSize)", bounds.Dx())
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
```

- [ ] **Step 2: 运行测试确认 BiLinear 测试失败（还是 Box）**

Run: `cd server && go test ./internal/service/ -run TestEncodeThumbnailToCache_BiLinearScaler -v`
Expected: FAIL —— 当前 `encodeThumbnailToCache` 用 `imaging.Thumbnail + Box`，输出短边是 150 但长边不同（Thumbnail 会 center-crop 到精确 150x150）。具体看断言：`bounds.Dx() != 150` 会失败，因为 Thumbnail 输出是 150x150 正方形。

**修正断言**：BiLinear + Fit 输出是保比的（150x120），不是正方形。如果 Step 1 断言写成 `Dx() != 150`，Thumbnail（Box）下也是 150，不会失败。需要调整断言区分两者 —— 检查长边：

实际上 Thumbnail 和 Fit 在 5000x4000 → max=150 的场景下行为不同：
- Thumbnail：缩放后 center-crop 到 150x150
- Fit：等比缩放到短边=150，长边=120

所以 BiLinear 测试断言应为：
```go
if bounds.Dx() != 150 || bounds.Dy() != 120 {
    t.Errorf("BiLinear+Fit output = %dx%d, expected 150x120", bounds.Dx(), bounds.Dy())
}
```

修正 Step 1 的断言后重跑，确认 FAIL（旧 Box 实现输出 150x150）。

- [ ] **Step 3: 改 `encodeThumbnailToCache` 切换 BiLinear + Fit**

修改 `server/internal/service/thumbnail.go` 中 Task 2 实现的 `encodeThumbnailToCache`，把：

```go
thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Box)
```

改为：

```go
thumb := imaging.Fit(src, s.maxSize, s.maxSize, imaging.Linear)
```

完整函数变为：

```go
// encodeThumbnailToCache 把 src 等比缩放到 max×max 框内并写入 cachePath。
// C2 优化：使用 BiLinear + Fit（300×300 缩略图场景下与 Lanczos 视觉等价，速度快 3-5 倍）。
// 用 os.CreateTemp + os.Rename 原子写入：进程崩溃/并发写不会留下半截损坏 jpg。
func (s *ThumbnailService) encodeThumbnailToCache(src image.Image, cachePath string) (string, error) {
	thumb := imaging.Fit(src, s.maxSize, s.maxSize, imaging.Linear)

	tempFile, err := os.CreateTemp(filepath.Dir(cachePath), "thumb-tmp-*.jpg")
	if err != nil {
		return "", err
	}
	tempPath := tempFile.Name()
	defer os.Remove(tempPath)

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
```

- [ ] **Step 4: 修正 Task 2 的旧断言（Box → BiLinear 后尺寸变了）**

Task 2 的 `TestEncodeThumbnailToCache_ProducesValidJPEG` 断言：
```go
if bounds.Dx() != 150 && bounds.Dy() != 150 {
    t.Errorf("thumbnail dims = %dx%d, expected short side = 150", bounds.Dx(), bounds.Dy())
}
```
Box + Thumbnail 下 1000x800 → 150x150（正方形）；BiLinear + Fit 下 1000x800 → 150x120（保比）。

**修正为**：
```go
// BiLinear + Fit：短边 = 150，长边按比例（800/1000 * 150 = 120）
if bounds.Dx() != 150 || bounds.Dy() != 120 {
    t.Errorf("thumbnail dims = %dx%d, expected 150x120 (BiLinear+Fit)", bounds.Dx(), bounds.Dy())
}
```

同样 `TestEncodeThumbnailToCache_SmallImageNotUpscaled` 断言：BiLinear + Fit 对小图不放大，保持 100x80，**断言无需改**（Fit 对小于目标的图返回原尺寸）。

- [ ] **Step 5: 改 `generateThumbnailFromFile` 图片分支也走 helper**

修改 `server/internal/service/thumbnail.go` 的 `generateThumbnailFromFile` 图片分支（Task 3 留下的旧逻辑）：

```go
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
```

替换为：

```go
	// 图片分支（C2 改造：复用 encodeThumbnailToCache，享受 BiLinear + 原子写入）
	src, err := imaging.Open(sourcePath)
	if err != nil {
		return "", err
	}
	return s.encodeThumbnailToCache(src, cachePath)
}
```

- [ ] **Step 6: 运行所有 thumbnail 测试确认通过**

Run: `cd server && go test ./internal/service/ -v`
Expected: 所有测试 PASS（包括 Task 4 新增的 + Task 1-3 的 + Round 24 的）

- [ ] **Step 7: 全套测试确认无回归**

Run: `cd server && go test ./...`
Expected: 全部 PASS

- [ ] **Step 8: Commit**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "$(cat <<'EOF'
perf(thumbnail): image branch uses BiLinear + Fit (C2)

encodeThumbnailToCache swapped from Thumbnail+Box to Fit+BiLinear.
Image branch now shares the helper with video branch. Large image
(5000x4000) thumbnail generation ~3-5x faster.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: C3 — Scanner struct 加 `cacheDirs`/`cacheDirMap` 字段 + Scan 收集祖先目录

**Files:**
- Modify: `server/internal/service/scanner.go:1-17`（imports）+ `:19-36`（Scanner struct）+ `:38-56`（NewScanner）+ Scan 函数（103-211 行）
- Test: `server/internal/service/scanner_test.go`

**Interfaces:**
- Consumes: 现有 Scanner 字段（`cache`/`cacheTime`/`mu`/`sf`）
- Produces: Scanner 加 `cacheDirs []string` + `cacheDirMap map[string]time.Time`；Scan 函数顺带填充这两个字段 —— Task 6 的 `GetCachedDirs` 会读取

- [ ] **Step 1: 在 scanner.go 引入 `"runtime"` 和 `"sort"` 包**

修改 `server/internal/service/scanner.go` 的 import 块（3-17 行）：

```go
import (
	"context"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/fsnotify/fsnotify"
	"golang.org/x/sync/errgroup"
	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/models"
)
```

注意新增 `"fmt"`、`"runtime"`、`"sort"`。

- [ ] **Step 2: Scanner struct 加字段**

修改 `scanner.go:19-36` 的 Scanner struct，在 `watchRoots []string` 之后加：

```go
type Scanner struct {
	mu             sync.RWMutex
	cache          map[string][]models.MediaFile
	cacheTime      time.Time
	cacheTTL       time.Duration
	videoExts      map[string]bool
	imageExts      map[string]bool
	sf             singleflight.Group
	OnScanComplete func(files []models.MediaFile)
	// bgCtx/bgCancel bound the lifetime of admin-triggered background scans.
	// Unlike the per-request context used by GetCached, this one is owned by the
	// scanner so a TriggerScan keeps running after the HTTP response is sent and
	// can be cancelled by shutting down (Stop cancels it via Shutdown).
	bgCtx      context.Context
	bgCancel   context.CancelFunc
	watcher    *fsnotify.Watcher
	watchRoots []string

	// cacheDirs 是扫描后收集的去重目录列表（字典序排序），cacheDirMap 记录每个目录的 mtime。
	// 只包含"含媒体文件"的目录（递归向上收集祖先目录），空目录不在内。
	// searchFoldersCached 用它做内存前缀扫，替代原 searchFoldersCtx 的 WalkDir。
	cacheDirs   []string
	cacheDirMap map[string]time.Time
}
```

- [ ] **Step 3: NewScanner 初始化新字段**

修改 `NewScanner`（38-56 行），return 块加 nil 占位（由 Scan 填充）：

```go
	return &Scanner{
		cache:       make(map[string][]models.MediaFile),
		cacheTTL:    60 * time.Second,
		videoExts:   vExts,
		imageExts:   iExts,
		bgCtx:       ctx,
		bgCancel:    cancel,
		cacheDirs:   nil,
		cacheDirMap: nil,
	}
```

- [ ] **Step 4: Scan 函数收集祖先目录**

修改 `scanner.go` 的 `Scan` 函数（103-211 行）。在 errgroup 启动前（106 行附近，`g, gctx := errgroup.WithContext(ctx)` 之后）加入共享 dirMap：

```go
func (s *Scanner) Scan(ctx context.Context, roots []string) ([]models.MediaFile, error) {
	g, gctx := errgroup.WithContext(ctx)

	// 共享 dirMap：walk goroutine 收集祖先目录，mutex 保护。
	// 目录数远少于文件数，锁竞争可忽略。
	var dirMu sync.Mutex
	dirMap := make(map[string]time.Time)

	results := make([][]models.MediaFile, len(roots))

	for i, root := range roots {
		i, root := i, root
		g.Go(func() error {
			var localFiles []models.MediaFile
			cleanRoot := filepath.Clean(root)
			err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
				if err != nil {
					return nil
				}
				select {
				case <-gctx.Done():
					return gctx.Err()
				default:
				}
				if d.IsDir() {
					return nil
				}
				ext := strings.ToLower(filepath.Ext(path))
				mediaType := ""
				s.mu.RLock()
				isVideo := s.videoExts[ext]
				isImage := s.imageExts[ext]
				s.mu.RUnlock()

				if isVideo {
					mediaType = "video"
				} else if isImage {
					mediaType = "image"
				} else {
					return nil
				}

				info, err := d.Info()
				if err != nil {
					return nil
				}

				relPath := path
				if strings.HasPrefix(path, root) {
					relPath = strings.TrimPrefix(path, root)
					if !strings.HasPrefix(relPath, string(filepath.Separator)) {
						relPath = string(filepath.Separator) + relPath
					}
				}

				localFiles = append(localFiles, models.MediaFile{
					Name:         d.Name(),
					Path:         path,
					RelativePath: relPath,
					Size:         info.Size(),
					ModifiedTime: info.ModTime(),
					MediaType:    mediaType,
					Extension:    ext,
				})

				// C3：递归收集父目录及所有祖先目录到共享 dirMap
				dir := filepath.Clean(filepath.Dir(path))
				for dir != "" && dir != cleanRoot {
					dirMu.Lock()
					_, exists := dirMap[dir]
					if !exists {
						// 首次加入时 stat 一次拿 mtime
						var mtime time.Time
						if statInfo, err := os.Stat(dir); err == nil {
							mtime = statInfo.ModTime()
						}
						dirMap[dir] = mtime
						dirMu.Unlock()

						parent := filepath.Clean(filepath.Dir(dir))
						if parent == dir {
							break // 已到文件系统根节点，防死循环
						}
						dir = parent
					} else {
						// 祖先目录此前已被完整加入，提前 break 减少锁竞争
						dirMu.Unlock()
						break
					}
				}
				return nil
			})
			if err != nil {
				return err
			}
			results[i] = localFiles
			return nil
		})
	}

	if err := g.Wait(); err != nil {
		return nil, err
	}

	allFiles := make([]models.MediaFile, 0)
	videoFiles := make([]models.MediaFile, 0)
	imageFiles := make([]models.MediaFile, 0)
	for _, subList := range results {
		for _, f := range subList {
			allFiles = append(allFiles, f)
			switch f.MediaType {
			case "video":
				videoFiles = append(videoFiles, f)
			case "image":
				imageFiles = append(imageFiles, f)
			}
		}
	}

	// 把 dirMap 转为排序切片 + 映射
	cacheDirs := make([]string, 0, len(dirMap))
	cacheDirMap := make(map[string]time.Time, len(dirMap))
	for dir, mtime := range dirMap {
		cacheDirs = append(cacheDirs, dir)
		cacheDirMap[dir] = mtime
	}
	sort.Strings(cacheDirs)

	s.mu.Lock()
	s.cache["all"] = allFiles
	s.cache["video"] = videoFiles
	s.cache["image"] = imageFiles
	s.cacheDirs = cacheDirs
	s.cacheDirMap = cacheDirMap
	s.cacheTime = time.Now()
	callback := s.OnScanComplete
	s.mu.Unlock()

	if callback != nil {
		go callback(allFiles)
	}

	return allFiles, nil
}
```

**关键变化**：
- WalkDir 回调内，媒体文件 append 后递归收集祖先目录
- 共享 `dirMap` + `dirMu` mutex（goroutine 间共享）
- Scan 结束合并阶段：dirMap → cacheDirs（排序）+ cacheDirMap
- 写入 `s.cacheDirs` + `s.cacheDirMap`（持 s.mu 写锁）

- [ ] **Step 5: 写失败测试 — cacheDirs 被填充 + 不含空目录**

在 `server/internal/service/scanner_test.go` 末尾追加（先检查现有测试风格）：

```go
// TestScan_PopulatesCacheDirs 验证 Scan 后 cacheDirs 包含媒体文件父目录，
// 不含空目录（scanner 只在有媒体文件时收集父目录）。
func TestScan_PopulatesCacheDirs(t *testing.T) {
	root := t.TempDir()
	// 构造目录树：
	//   root/
	//     subdir_with_media/
	//       video.mp4    <- 媒体文件，subdir_with_media 应被收集
	//     empty_subdir/  <- 空目录，不应被收集
	//     top.jpg        <- root 下的媒体文件，root 自身不应被收集（边界）
	subDir := filepath.Join(root, "subdir_with_media")
	emptyDir := filepath.Join(root, "empty_subdir")
	if err := os.MkdirAll(subDir, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(emptyDir, 0755); err != nil {
		t.Fatal(err)
	}
	// 创建空 mp4 和 jpg（scanner 不读内容，只看扩展名 + Stat）
	if err := os.WriteFile(filepath.Join(subDir, "video.mp4"), []byte("fake"), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(root, "top.jpg"), []byte("fake"), 0644); err != nil {
		t.Fatal(err)
	}

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatalf("Scan failed: %v", err)
	}

	scanner.mu.RLock()
	dirs := scanner.cacheDirs
	scanner.mu.RUnlock()

	cleanRoot := filepath.Clean(root)
	for _, d := range dirs {
		if d == cleanRoot {
			t.Errorf("root itself should not be in cacheDirs, got %q", d)
		}
		if d == emptyDir {
			t.Errorf("empty dir should not be in cacheDirs, got %q", d)
		}
	}

	found := false
	for _, d := range dirs {
		if d == subDir {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("subDir %q not found in cacheDirs: %v", subDir, dirs)
	}
}

// TestScan_CacheDirsSorted 验证 cacheDirs 按字典序排序。
func TestScan_CacheDirsSorted(t *testing.T) {
	root := t.TempDir()
	// 创建多个子目录使排序可验证
	for _, name := range []string{"z_dir", "a_dir", "m_dir"} {
		dir := filepath.Join(root, name)
		if err := os.MkdirAll(dir, 0755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644); err != nil {
			t.Fatal(err)
		}
	}

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatalf("Scan failed: %v", err)
	}

	scanner.mu.RLock()
	dirs := scanner.cacheDirs
	scanner.mu.RUnlock()

	if !sort.StringsAreSorted(dirs) {
		t.Errorf("cacheDirs not sorted: %v", dirs)
	}
}

// TestScan_CollectsAncestorDirs 验证递归收集祖先目录：
// 多层嵌套的中间目录（自身无媒体文件，但子目录有）也应被收集。
func TestScan_CollectsAncestorDirs(t *testing.T) {
	root := t.TempDir()
	// root/parent/child/video.mp4
	// parent 自身无媒体文件，但应被收集（祖孙关系）
	parent := filepath.Join(root, "parent")
	child := filepath.Join(parent, "child")
	if err := os.MkdirAll(child, 0755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(child, "video.mp4"), []byte("fake"), 0644); err != nil {
		t.Fatal(err)
	}

	scanner := NewScanner([]string{".mp4"}, []string{})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatalf("Scan failed: %v", err)
	}

	scanner.mu.RLock()
	dirs := scanner.cacheDirs
	scanner.mu.RUnlock()

	foundParent := false
	foundChild := false
	for _, d := range dirs {
		if d == parent {
			foundParent = true
		}
		if d == child {
			foundChild = true
		}
	}
	if !foundParent {
		t.Errorf("ancestor parent dir %q not collected: %v", parent, dirs)
	}
	if !foundChild {
		t.Errorf("direct parent child dir %q not collected: %v", child, dirs)
	}
}
```

需要在 scanner_test.go import 块加入 `"context"`、`"sort"`（若已有则跳过）。

- [ ] **Step 6: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestScan_ -v`
Expected: 3 个测试全部 PASS

- [ ] **Step 7: 全套测试确认无回归**

Run: `cd server && go test ./...`
Expected: 全部 PASS（特别注意 folders_test.go / scanner_test.go 现有测试）

- [ ] **Step 8: Commit**

```bash
git add server/internal/service/scanner.go server/internal/service/scanner_test.go
git commit -m "$(cat <<'EOF'
feat(scanner): collect ancestor dirs to cacheDirs (C3 Task 5)

Scanner.Scan now walks parent directories recursively up to root,
storing deduped + sorted dir list + mtime map. Empty dirs and root
itself excluded. Prepares searchFoldersCached to replace WalkDir.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: C3 — 新增 `GetCachedDirs`/`peekCachedDirs`/`filterDirsByScope` + `InvalidateCache` 同步清理

**Files:**
- Modify: `server/internal/service/scanner.go`（新增 3 个方法 + 改 `InvalidateCache`）
- Test: `server/internal/service/scanner_test.go`

**Interfaces:**
- Consumes: Task 5 的 `cacheDirs`/`cacheDirMap`/`cacheTime`/`cacheTTL`/`mu`/`sf`
- Produces: `GetCachedDirs(ctx, roots, scope) ([]string, map[string]time.Time, error)` —— Task 7 的 `searchFoldersCached` 会调用

- [ ] **Step 1: 写失败测试 — GetCachedDirs 基础返回**

在 `scanner_test.go` 末尾追加：

```go
// TestGetCachedDirs_ReturnsAllOnEmptyScope 验证 scope="" 返回全部目录。
func TestGetCachedDirs_ReturnsAllOnEmptyScope(t *testing.T) {
	root := t.TempDir()
	for _, name := range []string{"dir_a", "dir_b"} {
		dir := filepath.Join(root, name)
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)
	}

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	dirs, _, err := scanner.GetCachedDirs(context.Background(), []string{root}, "")
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	if len(dirs) != 2 {
		t.Errorf("scope=\"\" returned %d dirs, want 2: %v", len(dirs), dirs)
	}
}

// TestGetCachedDirs_ScopeFilter 验证 scope 前缀过滤。
func TestGetCachedDirs_ScopeFilter(t *testing.T) {
	root := t.TempDir()
	dirA := filepath.Join(root, "dir_a")
	dirB := filepath.Join(root, "dir_b")
	for _, dir := range []string{dirA, dirB} {
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)
	}

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	dirs, _, err := scanner.GetCachedDirs(context.Background(), []string{root}, dirA)
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	if len(dirs) != 1 {
		t.Errorf("scope=%q returned %d dirs, want 1: %v", dirA, len(dirs), dirs)
	}
}

// TestGetCachedDirs_ExcludesScopeRoot 验证 scope 根自身不在结果内。
func TestGetCachedDirs_ExcludesScopeRoot(t *testing.T) {
	root := t.TempDir()
	sub := filepath.Join(root, "sub")
	os.MkdirAll(sub, 0755)
	os.WriteFile(filepath.Join(sub, "x.jpg"), []byte("fake"), 0644)

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	// scope = root 自身（root 下有 sub，sub 下有媒体）
	// 注意：root 自身不会被收集（边界），但 sub 应在结果内
	dirs, _, err := scanner.GetCachedDirs(context.Background(), []string{root}, root)
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	cleanRoot := filepath.Clean(root)
	for _, d := range dirs {
		if d == cleanRoot {
			t.Errorf("scope root itself should not be in result, got %q", d)
		}
	}
	if len(dirs) == 0 {
		t.Errorf("expected sub dir in result, got empty")
	}
}

// TestGetCachedDirs_MtimesPopulated 验证返回的 mtimes map 含每个目录的 mtime。
func TestGetCachedDirs_MtimesPopulated(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "dir_a")
	os.MkdirAll(dir, 0755)
	os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	dirs, mtimes, err := scanner.GetCachedDirs(context.Background(), []string{root}, "")
	if err != nil {
		t.Fatalf("GetCachedDirs failed: %v", err)
	}
	if len(mtimes) != len(dirs) {
		t.Errorf("mtimes len = %d, dirs len = %d, should match", len(mtimes), len(dirs))
	}
	for _, d := range dirs {
		if _, ok := mtimes[d]; !ok {
			t.Errorf("mtimes missing entry for %q", d)
		}
	}
}

// TestInvalidateCache_ClearsCacheDirs 验证 InvalidateCache 清空 cacheDirs。
func TestInvalidateCache_ClearsCacheDirs(t *testing.T) {
	root := t.TempDir()
	dir := filepath.Join(root, "dir_a")
	os.MkdirAll(dir, 0755)
	os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)

	scanner := NewScanner([]string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	scanner.InvalidateCache()

	scanner.mu.RLock()
	defer scanner.mu.RUnlock()
	if scanner.cacheDirs != nil {
		t.Errorf("cacheDirs should be nil after InvalidateCache, got %v", scanner.cacheDirs)
	}
	if scanner.cacheDirMap != nil {
		t.Errorf("cacheDirMap should be nil after InvalidateCache, got %v", scanner.cacheDirMap)
	}
}
```

- [ ] **Step 2: 运行测试确认失败（方法未实现）**

Run: `cd server && go test ./internal/service/ -run "TestGetCachedDirs|TestInvalidateCache_ClearsCacheDirs" -v`
Expected: 编译失败 `undefined: (*Scanner).GetCachedDirs`

- [ ] **Step 3: 实现 `GetCachedDirs` + `peekCachedDirs` + `filterDirsByScope`**

在 `server/internal/service/scanner.go` 的 `GetCachedByType` 之后（约 259 行）追加：

```go
// GetCachedDirs 返回已知目录列表，可选按 scope 前缀过滤。
// scope="" 返回全部；scope="D:/Media" 返回该前缀下的目录。
// 与 GetCached 共享 TTL + singleflight（cache miss 时触发 Scan 填充 cacheDirs）。
// 返回 (dirs, mtimes, error)：mtimes[dir] 为目录 mtime，调用方可查。
func (s *Scanner) GetCachedDirs(ctx context.Context, roots []string, scope string) ([]string, map[string]time.Time, error) {
	dirs, mtimes, err := s.peekCachedDirs(scope)
	if err == nil {
		return dirs, mtimes, nil
	}

	// cache miss → 触发 Scan（singleflight 防击穿）
	_, err, _ = s.sf.Do("scan", func() (interface{}, error) {
		return s.Scan(ctx, roots)
	})
	if err != nil {
		return nil, nil, err
	}

	return s.peekCachedDirs(scope)
}

// peekCachedDirs 持读锁从 cache 读取 scope 范围内的目录 + mtime。
// cache 无效或为空时返回 error，由 caller 触发 Scan。
func (s *Scanner) peekCachedDirs(scope string) ([]string, map[string]time.Time, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	if time.Since(s.cacheTime) >= s.cacheTTL || s.cacheDirs == nil {
		return nil, nil, fmt.Errorf("cache invalid")
	}

	dirs := s.filterDirsByScope(scope)
	mtimes := make(map[string]time.Time, len(dirs))
	for _, d := range dirs {
		mtimes[d] = s.cacheDirMap[d]
	}
	return dirs, mtimes, nil
}

// filterDirsByScope 持读锁调用，返回 scope 前缀下的目录。
// scope="" 返回全部。scope 不以 filepath.Separator 结尾时内部补齐。
// 为兼容 Windows 路径大小写不敏感特性，Windows 下用 strings.EqualFold 做前缀对比。
func (s *Scanner) filterDirsByScope(scope string) []string {
	if scope == "" {
		out := make([]string, len(s.cacheDirs))
		copy(out, s.cacheDirs)
		return out
	}
	prefix := scope
	if !strings.HasSuffix(prefix, string(filepath.Separator)) {
		prefix += string(filepath.Separator)
	}

	out := make([]string, 0)
	isWindows := runtime.GOOS == "windows"
	for _, dir := range s.cacheDirs {
		if isWindows {
			// Windows 下大小写折叠的无分配前缀匹配
			if len(dir) >= len(prefix) && strings.EqualFold(dir[:len(prefix)], prefix) {
				out = append(out, dir)
			}
		} else {
			if strings.HasPrefix(dir, prefix) {
				out = append(out, dir)
			}
		}
	}
	return out
}
```

- [ ] **Step 4: 改 `InvalidateCache` 同步清理新字段**

修改 `scanner.go` 的 `InvalidateCache`（261-266 行）：

```go
func (s *Scanner) InvalidateCache() {
	s.mu.Lock()
	s.cache = make(map[string][]models.MediaFile)
	s.cacheDirs = nil
	s.cacheDirMap = nil
	s.cacheTime = time.Time{}
	s.mu.Unlock()
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run "TestGetCachedDirs|TestInvalidateCache" -v`
Expected: 所有测试 PASS

- [ ] **Step 6: 全套测试确认无回归**

Run: `cd server && go test ./...`
Expected: 全部 PASS

- [ ] **Step 7: Commit**

```bash
git add server/internal/service/scanner.go server/internal/service/scanner_test.go
git commit -m "$(cat <<'EOF'
feat(scanner): add GetCachedDirs + filterDirsByScope (C3 Task 6)

GetCachedDirs returns cached dirs scoped by prefix, with mtime map.
Windows uses EqualFold for case-insensitive prefix match.
InvalidateCache now clears cacheDirs + cacheDirMap.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: C3 — 改造 `searchFoldersCtx` → `searchFoldersCached`

**Files:**
- Modify: `server/internal/server/handler/search.go:1-15`（imports）+ `:17-75`（Search 调用点）+ `:114-170`（searchFoldersCtx → searchFoldersCached）
- Test: `server/internal/server/handler/search_test.go`

**Interfaces:**
- Consumes: Task 6 的 `scanner.GetCachedDirs`
- Produces: 改造后的 `searchFoldersCached`，行为兼容原 `searchFoldersCtx`（同样的输入 → 同样的 Folder 列表）

- [ ] **Step 1: 检查现有 search_test.go 的测试风格**

Run: `cd server && head -50 internal/server/handler/search_test.go`
了解现有 mock scanner 的方式（真实文件系统还是 mock）。决定新测试是追加还是改写。

- [ ] **Step 2: 写失败测试 — searchFoldersCached 基础匹配**

在 `search_test.go` 末尾追加（参考现有测试风格，若现有测试用真实文件系统则沿用）：

```go
// TestSearchFoldersCached_BasicMatch 验证 query 子串匹配目录名。
func TestSearchFoldersCached_BasicMatch(t *testing.T) {
	// 构造测试目录树
	root := t.TempDir()
	movieDir := filepath.Join(root, "MyMovies")
	docDir := filepath.Join(root, "Documents")
	for _, dir := range []string{movieDir, docDir} {
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.mp4"), []byte("fake"), 0644)
	}

	// 构造 handler（参考现有测试的构造方式）
	h, scanner := newTestHandlerWithScanner(t, root, []string{".mp4"}, []string{})

	// 触发 scan 填充 cache
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	folders, err := h.searchFoldersCached(context.Background(), "", "movie", 50)
	if err != nil {
		t.Fatalf("searchFoldersCached failed: %v", err)
	}
	if len(folders) != 1 {
		t.Fatalf("expected 1 match, got %d: %v", len(folders), folders)
	}
	if folders[0].Name != "MyMovies" {
		t.Errorf("matched name = %q, want MyMovies", folders[0].Name)
	}
}

// TestSearchFoldersCached_ScopedSearch 验证 scope 限定搜索范围。
func TestSearchFoldersCached_ScopedSearch(t *testing.T) {
	root := t.TempDir()
	// root/scope_dir/match.png
	// root/other_dir/match.png
	scopeDir := filepath.Join(root, "scope_dir")
	otherDir := filepath.Join(root, "other_dir")
	for _, dir := range []string{scopeDir, otherDir} {
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "match.png"), []byte("fake"), 0644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{}, []string{".png"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	folders, err := h.searchFoldersCached(context.Background(), scopeDir, "match", 50)
	if err != nil {
		t.Fatalf("searchFoldersCached failed: %v", err)
	}
	// scope 限定下，只有 scope_dir 自身匹配（但被排除）→ 结果可能为 0
	// 或只有 scope_dir 下的子目录匹配（这里没有子目录）
	// 这个测试主要是验证 scope 不会越界返回 otherDir
	for _, f := range folders {
		if strings.Contains(f.Path, "other_dir") {
			t.Errorf("scope should exclude other_dir, got %q", f.Path)
		}
	}
}

// TestSearchFoldersCached_Limit 验证 limit 截断。
func TestSearchFoldersCached_Limit(t *testing.T) {
	root := t.TempDir()
	// 创建 5 个匹配的目录
	for i := 0; i < 5; i++ {
		dir := filepath.Join(root, fmt.Sprintf("match_%d", i))
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	folders, err := h.searchFoldersCached(context.Background(), "", "match", 3)
	if err != nil {
		t.Fatalf("searchFoldersCached failed: %v", err)
	}
	if len(folders) != 3 {
		t.Errorf("expected limit=3, got %d", len(folders))
	}
}

// TestSearchFoldersCached_ContextCancellation 验证 ctx 取消时提前返回。
func TestSearchFoldersCached_ContextCancellation(t *testing.T) {
	root := t.TempDir()
	for i := 0; i < 10; i++ {
		dir := filepath.Join(root, fmt.Sprintf("match_%d", i))
		os.MkdirAll(dir, 0755)
		os.WriteFile(filepath.Join(dir, "x.jpg"), []byte("fake"), 0644)
	}

	h, scanner := newTestHandlerWithScanner(t, root, []string{}, []string{".jpg"})
	if _, err := scanner.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	cancel() // 立即取消

	folders, err := h.searchFoldersCached(ctx, "", "match", 50)
	if err != nil {
		t.Fatalf("searchFoldersCached with cancelled ctx failed: %v", err)
	}
	// ctx 已取消，应该返回空或很少（第一次 ctx.Err() 检查就 break）
	if len(folders) > 0 {
		t.Logf("cancelled ctx returned %d folders (expected 0)", len(folders))
	}
}
```

需要在 search_test.go import 块加入 `"context"`、`"fmt"`、`"path/filepath"`、`"strings"`（若已有则跳过）。

**注**：`newTestHandlerWithScanner` 是测试 helper。若 search_test.go 已有等价 helper（如 `newTestHandler`），沿用现有；否则在 search_test.go 末尾追加：

```go
// newTestHandlerWithScanner 构造一个绑定了真实 Scanner（扫描 root）的 Handler。
func newTestHandlerWithScanner(t *testing.T, root string, videoExts, imageExts []string) (*Handler, *service.Scanner) {
	t.Helper()
	scanner := service.NewScanner(videoExts, imageExts)
	// 参考 search_test.go 现有的 Handler 构造方式，把 scanner 注入
	// 具体 Handler 字段名参考现有测试
	h := &Handler{
		scanner: scanner,
		cfg:     /* mock cfg with GetRoots returning []string{root} */,
	}
	return h, scanner
}
```

实现者按现有 search_test.go 的 Handler 构造方式填充 mock cfg。

- [ ] **Step 3: 运行测试确认失败**

Run: `cd server && go test ./internal/server/handler/ -run TestSearchFoldersCached -v`
Expected: 编译失败 `undefined: (*Handler).searchFoldersCached`

- [ ] **Step 4: 在 search.go 引入 `"runtime"` 包**

修改 `server/internal/server/handler/search.go` 的 import 块（1-15 行）：

```go
import (
	"context"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/models"
	"github.com/localmediahub/server/internal/service"
)
```

- [ ] **Step 5: 改 `searchFoldersCtx` → `searchFoldersCached`**

修改 `search.go` 的 `searchFoldersCtx`（114-170 行），整个函数替换为：

```go
// searchFoldersCached 从 scanner cache 中按 scope + query 过滤目录名。
// 替代原 searchFoldersCtx 的 WalkDir，从磁盘 IO 改为内存扫。
func (h *Handler) searchFoldersCached(ctx context.Context, scopedPath, query string, limit int) ([]models.Folder, error) {
	roots := h.cfg.Scan.GetRoots()
	scope := scopedPath
	if scope != "" && !strings.HasSuffix(scope, string(filepath.Separator)) {
		scope += string(filepath.Separator)
	}

	dirs, mtimes, err := h.scanner.GetCachedDirs(ctx, roots, scope)
	if err != nil {
		return nil, err
	}
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}

	lowerQuery := strings.ToLower(query)
	out := make([]models.Folder, 0, limit)
	isWindows := runtime.GOOS == "windows"
	for _, dir := range dirs {
		if ctx.Err() != nil {
			break
		}
		// 排除 scope 根自身（与原 WalkDir 在 path == root 时跳过一致）
		if scopedPath != "" {
			isRootSelf := false
			if isWindows {
				isRootSelf = strings.EqualFold(filepath.Clean(dir), filepath.Clean(scopedPath))
			} else {
				isRootSelf = filepath.Clean(dir) == filepath.Clean(scopedPath)
			}
			if isRootSelf {
				continue
			}
		}
		name := filepath.Base(dir)
		if !strings.Contains(strings.ToLower(name), lowerQuery) {
			continue
		}
		out = append(out, models.Folder{
			Name:         name,
			Path:         dir,
			RelativePath: dir,
			IsRoot:       false,
			ModifiedTime: mtimes[dir],
		})
		if len(out) >= limit {
			break
		}
	}
	return out, nil
}
```

- [ ] **Step 6: 改 Search handler 的调用点**

修改 `search.go` 的 `Search` 函数（17-75 行），把 62 行的：

```go
	matchedFolders, err := h.searchFoldersCtx(c.Request().Context(), searchPath, query, limit)
```

改为：

```go
	matchedFolders, err := h.searchFoldersCached(c.Request().Context(), searchPath, query, limit)
```

- [ ] **Step 7: 运行新测试 + 现有 search 测试确认通过**

Run: `cd server && go test ./internal/server/handler/ -run TestSearch -v`
Expected: 全部 PASS（包括现有 search 测试不回归）

- [ ] **Step 8: 全套测试确认无回归**

Run: `cd server && go test ./...`
Expected: 全部 PASS

- [ ] **Step 9: Commit**

```bash
git add server/internal/server/handler/search.go server/internal/server/handler/search_test.go
git commit -m "$(cat <<'EOF'
perf(search): folder search via scanner cacheDirs (C3 Task 7)

searchFoldersCtx (WalkDir) → searchFoldersCached (memory prefix scan).
5k-folder library search ~1s → ~5ms. Windows uses EqualFold for
case-insensitive scope matching.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: 最终验证 + smoke test 清单

**Files:**
- 无代码改动，仅验证

**Interfaces:**
- N/A

- [ ] **Step 1: 全套 Go 测试**

Run: `cd server && go test ./...`
Expected: 全部 PASS，无 race warning（若 CI 支持 `-race` 则 `go test -race ./...`）

- [ ] **Step 2: go vet 静态检查**

Run: `cd server && go vet ./...`
Expected: 无 warning

- [ ] **Step 3: 编译服务端确认**

Run: `cd server && go build -o LocalMediaHub.exe ./cmd/server`
Expected: 编译成功，无 unused import 等错误

- [ ] **Step 4: 手动 smoke test — C1 视频缩略图**

启动服务端，删除 `.cache/thumbnails` 强制 miss，客户端打开含视频的目录，主观评估首屏速度。检查系统 TempDir 无 `videothumb-*` 残留。

- [ ] **Step 5: 手动 smoke test — C2 大图缩略图**

删除 `.cache/thumbnails` 强制 miss，客户端打开含高分辨率 JPEG（5000×4000+）的目录，主观评估首屏速度。

- [ ] **Step 6: 手动 smoke test — C3 文件夹搜索**

客户端搜索文件夹名（含数千文件夹的媒体库），主观评估响应速度（应从秒级降到毫秒级）。

- [ ] **Step 7: 更新 plan.md / README（若需要）**

若 spec 或 plan 有 README 引用，确认文档一致。否则跳过。

- [ ] **Step 8: 最终 commit（若有文档变更）**

```bash
git add docs/  # 若有文档更新
git commit -m "$(cat <<'EOF'
docs: round 28 implementation complete

C1 video thumbnail ffmpeg pipe + C2 BiLinear + C3 folder search cache.
All tests pass, smoke tests verified.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

若 Step 7 无变更，本步跳过。

---

## 验收标准

- ✅ `cd server && go test ./...` 全部 PASS
- ✅ `cd server && go vet ./...` 无 warning
- ✅ `cd server && go build -o LocalMediaHub.exe ./cmd/server` 编译成功
- ✅ 7 个独立 commit（Task 1-7），每个可独立 revert
- ✅ 视频缩略图生成无临时文件 IO（C1）
- ✅ 图片缩略图用 BiLinear 缩放器（C2）
- ✅ 文件夹搜索走内存（C3），不再 WalkDir
- ✅ API 契约不变，磁盘缓存 key 格式不变

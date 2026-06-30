# 服务端缩略图性能（Round 3）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把服务端缩略图从"视频按需同步 ffmpeg + 无浏览器缓存"升级为"视频扫描后后台预热（ffprobe 中间帧）+ 缩略图/原图带 Cache-Control"。

**Architecture:** 仅服务端，4 个任务。Task 1 抽 ffprobe 解析/seek 计算/路径推导三个纯函数并单测；Task 2 加 ffprobe 方法并把中间帧接入 `generateThumbnailFromFile`；Task 3 把 `PreGenerateThumbnails` 从仅图片扩展到含视频；Task 4 加 `Cache-Control` helper 并接入 7 个缩略图/原图 handler。前 3 任务在 `internal/service/`，Task 4 在 `internal/server/handler/` + 测试。

**Tech Stack:** Go 1.22+ / Echo v4 / `os/exec`（ffmpeg + ffprobe）/ `encoding`（imaging）。

## Global Constraints

- **提交策略**（`AGENTS.md`）：本项目约定本地改动自动同步推送至 GitHub `master`。所有提交直接在 `master`，**不开 feature 分支**。
- **Go 编码规则**（`AGENTS.md`）：业务逻辑放 `internal/service/`；handler 只做参数解析与响应；列表用 `make([]T, 0)`；5xx 用 `respondError`/`respondInternalError`，不回显 `err.Error()`。
- **Go 测试风格**：平铺 `func TestXxx(t *testing.T)`、`t.TempDir()`；`thumbnail_test.go` 与被测包同包（`package service`），可测未导出符号。
- **Go 代理**（中国大陆）：拉依赖失败用 `GOPROXY=https://goproxy.cn,direct`。
- **ffmpeg/ffprobe**：ffmpeg 路径来自 `config.yaml` 的 `system.ffmpeg_path`（空则用 PATH 的 `ffmpeg`）。**ffprobe 是软依赖**——不在则 `videoDuration` 返回 `false`、seek 回退 `"5"`，不得报错。
- **行为约束**：视频预热是后台 post-scan（复用 Round 2 接好的 `ctx` 取消）；`Cache-Control` **不加到 stream 端点**。
- **范围外**（spec §2 非目标）：Android Coil 调优、扫描器/搜索/zip 性能、异步后台生成、缓存破坏 URL。

## File Structure

- 修改 `server/internal/service/thumbnail.go` — 新增 `ffprobeSibling`、`parseFFprobeDuration`、`midpointSeek`（包级纯函数）+ `getFFprobeCmd`/`HasFFprobe`/`videoDuration`（方法）；改 `generateThumbnailFromFile` 视频分支用中间帧；改 `PreGenerateThumbnails` 含视频。加 `"strconv"` import。
- 新增 `server/internal/service/thumbnail_test.go` — 三个纯函数的表驱动测试。
- 修改 `server/internal/server/handler/handler.go` — 新增 `setMediaCacheHeaders(c echo.Context)` helper。
- 修改 `server/internal/server/handler/{system,media,images,videos}.go` — 7 个 handler 在 `c.File` 前调 `setMediaCacheHeaders`。
- 修改 `server/internal/server/server_test.go` — 断言缩略图路由响应含 `Cache-Control`。

---

## Task 1: ffprobe 纯函数（解析 / seek / 路径推导）（TDD）

**Files:**
- Modify: `server/internal/service/thumbnail.go`（import 块加 `"strconv"`；文件末尾追加三个函数）
- Test: `server/internal/service/thumbnail_test.go`（新建）

**Interfaces:**
- Produces（包级纯函数，后续任务依赖）：
  - `func parseFFprobeDuration(out string) (float64, bool)`
  - `func midpointSeek(duration float64, ok bool) string`
  - `func ffprobeSibling(ffmpegPath string) string`

- [ ] **Step 1: 写失败测试**

新建 `server/internal/service/thumbnail_test.go`：

```go
package service

import (
	"path/filepath"
	"testing"
)

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
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -run "TestParseFFprobeDuration|TestMidpointSeek|TestFFprobeSibling" -v`
Expected: 编译失败，提示 `undefined: parseFFprobeDuration` / `midpointSeek` / `ffprobeSibling`。

- [ ] **Step 3: 写实现**

在 `server/internal/service/thumbnail.go` import 块加入 `"strconv"`（与既有 `"fmt"`、`"os/exec"`、`"path/filepath"`、`"strings"` 同组）。在文件末尾追加：

```go
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
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run "TestParseFFprobeDuration|TestMidpointSeek|TestFFprobeSibling" -v`
Expected: PASS（全部子用例）。

- [ ] **Step 5: 全量 service 包测试 + 提交**

Run: `cd server && go test ./internal/service/ -v`
Expected: PASS（既有测试无回归）。

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go
git commit -m "feat(server): add ffprobe duration/seek/path pure helpers for video thumbnails

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: ffprobe 方法 + 中间帧接入 `generateThumbnailFromFile`

**Files:**
- Modify: `server/internal/service/thumbnail.go`（加 `getFFprobeCmd`/`HasFFprobe`/`videoDuration` 方法；改视频分支用 `midpointSeek`）

**Interfaces:**
- Consumes: Task 1 的 `midpointSeek`、`ffprobeSibling`、`parseFFprobeDuration`；既有 `getFFmpegCmd`/`HasFFmpeg`/`isVideoFile`。
- Produces: 方法 `getFFprobeCmd() string`、`HasFFprobe() bool`、`videoDuration(sourcePath string) (float64, bool)`；视频分支现在 seek 到中间帧。

> 本任务是 ffmpeg 集成接线，无独立单测（依赖外部 ffmpeg/ffprobe）——靠 `go build` + 既有测试无回归 + Task 5 手工验证。

- [ ] **Step 1: 加 ffprobe 方法**

在 `server/internal/service/thumbnail.go` 的 `HasFFmpeg`（约 `:50-54`）之后插入：

```go
func (s *ThumbnailService) getFFprobeCmd() string {
	if s.ffmpegPath != "" {
		return ffprobeSibling(s.ffmpegPath)
	}
	return "ffprobe"
}

func (s *ThumbnailService) HasFFprobe() bool {
	_, err := exec.LookPath(s.getFFprobeCmd())
	return err == nil
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
```

- [ ] **Step 2: 改视频分支用中间帧**

在 `generateThumbnailFromFile`（约 `:71`）的视频分支里，定位这段：

```go
		// 1. Try to extract at 5 seconds
		ffmpegCmd := s.getFFmpegCmd()
		cmd := exec.Command(ffmpegCmd, "-y", "-ss", "5", "-i", sourcePath, "-vframes", "1", "-f", "image2", tempPath)
```

替换为（用 ffprobe 中间帧，回退仍是 `"5"` 再 `"0"`）：

```go
		// Seek to a representative frame (video midpoint when ffprobe is
		// available, else the prior default of 5s). Fall back to 0s on failure.
		ffmpegCmd := s.getFFmpegCmd()
		seek := midpointSeek(s.videoDuration(sourcePath))
		cmd := exec.Command(ffmpegCmd, "-y", "-ss", seek, "-i", sourcePath, "-vframes", "1", "-f", "image2", tempPath)
```

（其后的 `cmdFallback` 用 `-ss "0"` 兜底逻辑不动。）

- [ ] **Step 3: 编译 + 全量测试**

Run: `cd server && go build ./... && go vet ./... && go test ./...`
Expected: 编译通过；全部测试 PASS（无回归）。若拉依赖失败加 `GOPROXY=https://goproxy.cn,direct`。

- [ ] **Step 4: 提交**

```bash
git add server/internal/service/thumbnail.go
git commit -m "perf(server): seek video thumbnails to midpoint via ffprobe

Replaces the hardcoded -ss 5 (which failed on short videos, yielding -ss 0 black
frames) with a midpoint seek derived from ffprobe duration. ffprobe is a soft
dependency: absent it falls back to 5s, then 0s.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: `PreGenerateThumbnails` 扩展到视频

**Files:**
- Modify: `server/internal/service/thumbnail.go`（`PreGenerateThumbnails` 约 `:195-254`）

**Interfaces:**
- Consumes: Task 2 的 `HasFFmpeg`；既有 worker 池 + `ctx` 取消。
- Produces：扫描完成后视频缩略图也被预热。

> 集成改动，靠 `go build` + 既有测试无回归 + Task 5 手工验证。

- [ ] **Step 1: 改 `PreGenerateThumbnails` 含视频**

将 `PreGenerateThumbnails`（约 `:195`）开头这段：

```go
func (s *ThumbnailService) PreGenerateThumbnails(files []models.MediaFile, ctx context.Context) {
	var images []models.MediaFile
	for _, f := range files {
		if f.MediaType == "image" {
			images = append(images, f)
		}
	}

	if len(images) == 0 {
		return
	}

	numWorkers := runtime.NumCPU() / 2
```

替换为（图片 + 视频，视频需 ffmpeg）：

```go
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
```

然后把该函数内对 `images` 的两处引用改为 `queue`：

- `jobs := make(chan models.MediaFile, len(images))` → `len(queue)`
- `for _, img := range images {` → `for _, img := range queue {`

（其余 worker 循环、`GenerateThumbnail` 调用、`ctx` 取消逻辑不动。）

- [ ] **Step 2: 编译 + 全量测试**

Run: `cd server && go build ./... && go vet ./... && go test ./...`
Expected: 编译通过；全部测试 PASS。

- [ ] **Step 3: 提交**

```bash
git add server/internal/service/thumbnail.go
git commit -m "perf(server): pre-generate video thumbnails after scan

PreGenerateThumbnails previously warmed only images, so every video thumbnail
was generated on-demand (synchronous ffmpeg) on first view. Include videos
(when ffmpeg is present) so the first client request hits the cache. Reuses the
existing worker pool and Round-2 ctx cancellation.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 4: 缩略图/原图 `Cache-Control` 缓存头

**Files:**
- Modify: `server/internal/server/handler/handler.go`（新增 helper）
- Modify: `server/internal/server/handler/system.go`、`media.go`、`images.go`、`videos.go`（7 处接入）
- Test: `server/internal/server/server_test.go`（断言响应头）

**Interfaces:**
- Produces: `func setMediaCacheHeaders(c echo.Context)`（handler 包内 helper）。

- [ ] **Step 1: 写失败测试（断言响应头）**

在 `server/internal/server/server_test.go` 的 `TestRegisterRoutesServesThumbnailEndpoint` 末尾（`if rec.Code != http.StatusOK { ... }` 之后）追加：

```go
	if got := rec.Result().Header.Get("Cache-Control"); got != "public, max-age=86400" {
		t.Fatalf("expected thumbnail Cache-Control 'public, max-age=86400', got %q", got)
	}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/server/ -run TestRegisterRoutesServesThumbnailEndpoint -v`
Expected: FAIL（`got ""`）。

- [ ] **Step 3: 加 helper**

在 `server/internal/server/handler/handler.go` 的 `respondNotFound`（约 `:87-92`）之后追加：

```go
// setMediaCacheHeaders marks a thumbnail/original response as browser-cacheable
// for one day. The thumbnail cache key includes the source file's modtime, so a
// changed source produces a new cache file with a different modtime and browsers
// revalidating via If-Modified-Since get a 200 — correct outside the max-age
// window. Not applied to stream endpoints (different Range semantics).
func setMediaCacheHeaders(c echo.Context) {
	c.Response().Header().Set("Cache-Control", "public, max-age=86400")
}
```

- [ ] **Step 4: 接入 7 个 handler**

在以下每个 handler 的 `return c.File(...)` **前一行**插入 `setMediaCacheHeaders(c)`：

- `server/internal/server/handler/system.go` `SystemThumbnail`（`return c.File(thumbPath)` 前）
- `server/internal/server/handler/system.go` `SystemOriginal`（`return c.File(resolved)` 前）
- `server/internal/server/handler/media.go` `MediaThumbnail`（`return c.File(thumbPath)` 前）
- `server/internal/server/handler/media.go` `MediaOriginal`（`return c.File(resolved)` 前）
- `server/internal/server/handler/images.go` `GetThumbnail`（`return c.File(thumbPath)` 前）
- `server/internal/server/handler/images.go` `GetOriginal`（`return c.File(resolved)` 前）
- `server/internal/server/handler/videos.go` `GetVideoThumbnail`（`return c.File(thumbPath)` 前）

每处形如：

```go
	setMediaCacheHeaders(c)
	return c.File(thumbPath)
```

（`thumbPath` 或 `resolved` 视具体 handler 而定。）

**不要**给 stream handler（`SystemStream`/`MediaStream`/`StreamVideo`）加。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd server && go test ./internal/server/ -run TestRegisterRoutesServesThumbnailEndpoint -v`
Expected: PASS（响应头断言通过）。

- [ ] **Step 6: 全量构建 + 测试 + 提交**

Run: `cd server && go build ./... && go vet ./... && go test ./...`
Expected: 全部 PASS。

```bash
git add server/internal/server/handler/handler.go server/internal/server/handler/system.go server/internal/server/handler/media.go server/internal/server/handler/images.go server/internal/server/handler/videos.go server/internal/server/server_test.go
git commit -m "perf(server): add Cache-Control to thumbnail/original responses

The 7 thumbnail/original handlers served via c.File with no Cache-Control, so
browsers re-validated on every view. Set 'public, max-age=86400'; stream
endpoints are intentionally excluded (Range/ExoPlayer semantics).

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 5: 手工验证（视频预热 + 缓存头，无代码改动）

**Files:** 无改动（纯验证）。

- [ ] **Step 1: 准备真实媒体**

在某个 scan root 下放一个短视频（如 `clip.mp4`，5–10 秒，用于验证中间帧而非黑帧）和一张图片。

- [ ] **Step 2: 起服务、触发扫描、观察预热**

```bash
cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless
```

观察日志：扫描完成后后台预热应覆盖视频（CPU 信号量限流）。在另一终端请求该视频缩略图：

```bash
curl -i "http://localhost:8000/api/v1/videos/<route-encoded-path>/thumbnail"
```

Expected: **HTTP 200**，响应头含 `Cache-Control: public, max-age=86400`；返回的 JPEG 是视频中点附近一帧（非黑帧）。再次请求（带 `If-Modified-Since`）应得 **304** 或命中缓存。

- [ ] **Step 3: 验证图片缩略图缓存头**

```bash
curl -i "http://localhost:8000/api/v1/images/<route-encoded-path>/thumbnail"
```

Expected: 200 + `Cache-Control: public, max-age=86400`。

- [ ] **Step 4: 验证 stream 端点未加缓存头**

```bash
curl -i "http://localhost:8000/api/v1/videos/<route-encoded-path>/stream"
```

Expected: 200（或 206 Partial），响应头**不含** `Cache-Control: public, max-age=86400`。

- [ ] **Step 5: 记录验证结果**

在交付说明中记录：视频预热生效（首次访问命中缓存、中点帧非黑帧）、缩略图/原图带 Cache-Control、stream 未带。

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §3.1 ffprobe 探测（`getFFprobeCmd`/`HasFFprobe`/`videoDuration`/`parseFFprobeDuration`/`midpointSeek`/`ffprobeSibling`）→ Task 1（纯函数）+ Task 2（方法 + 接入）。✅
- §3.2 改 `generateThumbnailFromFile` 视频分支用中间帧 → Task 2 Step 2。✅
- §3.3 `PreGenerateThumbnails` 扩展含视频 → Task 3。✅
- §4 Cache-Control helper + 7 handler + 不加 stream → Task 4。✅
- §5 测试（纯函数表驱动 + server_test 头断言）→ Task 1 + Task 4 Step 1。✅
- §7 决策（ffprobe 软依赖、max-age 86400、覆盖缩略图+原图不含 stream、不做异步）→ 各任务落地。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码；每条命令含期望输出；手工验证含具体 curl 与期望状态码/响应头。✅

**3. 类型/签名一致性**：
- `parseFFprobeDuration(out string) (float64, bool)` —— Task 1 定义、Task 2 `videoDuration` 调用。✅
- `midpointSeek(duration float64, ok bool) string` —— Task 1 定义、Task 2 `midpointSeek(s.videoDuration(sourcePath))`（多返回值直传，合法 Go）调用。✅
- `ffprobeSibling(ffmpegPath string) string` —— Task 1 定义、Task 2 `getFFprobeCmd` 调用。✅
- `setMediaCacheHeaders(c echo.Context)` —— Task 4 定义、7 handler 调用。✅
- import 增补一致：thumbnail.go +`"strconv"`（Task 1）；handler.go 无新 import（`echo` 已在）。✅
- Task 3 把 `images` 改为 `queue`：定义处、`len(images)`→`len(queue)`、`range images`→`range queue` 三处一致。✅

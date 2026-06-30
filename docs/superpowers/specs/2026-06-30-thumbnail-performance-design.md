# 服务端缩略图性能设计（Thumbnail Performance · Round 3）

- **日期**: 2026-06-30
- **范围**: Go 服务端（仅 `thumbnail.go` + 缩略图/原图 handler + 测试）
- **策略**: A — 视频预热（ffprobe 中间帧）+ 缓存头
- **状态**: 待评审
- **前置**: Round 2 安全加固（`Scanner.OnScanComplete` 已接好可取消的 `preGenCtx`/`preGenCancel`，本轮视频预热复用该取消机制）

---

## 1. 背景与动机

三端深度审计指出：服务端缩略图是头号性能大户。具体两处：

- **视频缩略图未预热**：`server/internal/service/thumbnail.go` 的 `PreGenerateThumbnails`（`:195`）只收 `f.MediaType=="image"`（`:198`）。视频缩略图全靠 `generateThumbnailFromFile`（`:71`）**按需同步**跑 ffmpeg（首次访问阻塞），且硬编码 `-ss 5`、失败回退 `-ss 0`——短视频常因 `-ss 5` 越界失败而落到 `-ss 0`，得到**黑帧**。这是 Android/Web 画廊首次浏览视频时的主要卡顿源。
- **无 `Cache-Control`**：所有缩略图/原图 handler 用 `c.File(...)`，无 `Cache-Control` 头（只有 `http.ServeContent` 默认按文件 modtime 的 `Last-Modified`），浏览器每次都发 `If-Modified-Since` 重验证。

**预期校准（重要）**：Android 客户端的 Coil 自带磁盘缓存且 `respectCacheHeaders=false`，因此**服务端缓存头主要惠及 Web 画廊**（浏览器尊重 `Cache-Control`）；而**视频预热是所有客户端通吃的最大服务端收益**（首次访问命中缓存而非同步 ffmpeg）。本轮目标定位如此。

本轮只做缩略图专项（性能 Round 3，服务端）。扫描器/搜索/zip 性能、Android Coil 调优留待后续轮次。

---

## 2. 目标与非目标

### 目标
1. **视频缩略图预热**：`PreGenerateThumbnails` 扩展到含视频，扫描完成后后台预热视频缩略图，首次访问即命中缓存。
2. **ffprobe 中间帧**：用 `ffprobe` 取时长 → seek `duration/2`，替代硬编码 `-ss 5`（同时修黑帧质量问题）；ffprobe 不可用时优雅回退。
3. **Cache-Control 缓存头**：缩略图/原图响应加 `Cache-Control: public, max-age=86400`，让浏览器在窗口期内不发请求。

### 非目标（留待后续轮次）
- Android Coil 缓存调优（`respectCacheHeaders` / 内存缓存比例）——客户端改动。
- 扫描器按类型缓存、scoped 搜索去重复 normalize、`DownloadFolderZip` FD/压缩——其余服务端性能项。
- 异步后台生成（未命中返回占位图 + 后台生成 + 去重）——个人 LAN 并发冷启动极少，YAGNI。
- 缓存破坏 URL（URL 内嵌 modtime 哈希 + immutable）——需改 Android+Web 客户端构造 URL，跨出服务端范围。

---

## 3. 视频预热 + ffprobe 中间帧

### 3.1 ffprobe 探测（软依赖）

`thumbnail.go` 新增（镜像现有 `getFFmpegCmd`/`HasFFmpeg`）：

```go
func (s *ThumbnailService) getFFprobeCmd() string {
	if s.ffmpegPath != "" {
		// 推导同目录的 ffprobe（替换文件名 ffmpeg → ffprobe，保留目录与扩展名）
		return ffprobeSibling(s.ffmpegPath)
	}
	return "ffprobe"
}
func (s *ThumbnailService) HasFFprobe() bool {
	_, err := exec.LookPath(s.getFFprobeCmd())
	return err == nil
}
```

`ffprobeSibling(path)`：取 `filepath.Dir(path)` + `ffprobe` + 原扩展名（如 `C:\tools\ffmpeg.exe` → `C:\tools\ffprobe.exe`）；若文件基名不含 `ffmpeg` 则直接返回 `"ffprobe"`（回退到 PATH 查找）。

时长解析抽成**纯函数**（便于单测）：

```go
// parseFFprobeDuration parses ffprobe's duration output (seconds, decimal).
// Returns false on empty/non-numeric/non-positive input.
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

// midpointSeek returns the seek offset (seconds, 2 decimals) at half the
// video duration for a representative frame; falls back to "5" when the
// duration is unknown (preserving prior behavior).
func midpointSeek(duration float64, ok bool) string {
	if !ok || duration <= 0 {
		return "5"
	}
	return strconv.FormatFloat(duration/2, 'f', 2, 64)
}
```

### 3.2 改 `generateThumbnailFromFile` 视频分支

`thumbnail.go:71` 视频分支当前：`-ss 5`，失败回退 `-ss 0`。改为用 ffprobe 中间帧：

```go
if isVideoFile(sourcePath) {
	if !s.HasFFmpeg() {
		return "", fmt.Errorf("ffmpeg not found, cannot generate video thumbnail")
	}
	seek := midpointSeek(s.videoDuration(sourcePath)) // ffprobe 不可用 → "5"

	tempFile, err := os.CreateTemp("", "videothumb-*.jpg")
	// …（创建 tempPath、defer os.Remove(tempPath) 不变）

	ffmpegCmd := s.getFFmpegCmd()
	cmd := exec.Command(ffmpegCmd, "-y", "-ss", seek, "-i", sourcePath, "-vframes", "1", "-f", "image2", tempPath)
	if err := cmd.Run(); err != nil {
		// 回退到 0 秒（黑帧兜底，与现状一致）
		cmdFallback := exec.Command(ffmpegCmd, "-y", "-ss", "0", "-i", sourcePath, "-vframes", "1", "-f", "image2", tempPath)
		if err := cmdFallback.Run(); err != nil {
			return "", fmt.Errorf("failed to extract video frame: %w", err)
		}
	}
	// …（imaging.Open → Thumbnail → jpeg.Encode → 返回 cachePath 不变）
}
```

> `midpointSeek(s.videoDuration(sourcePath))` 是合法 Go：`videoDuration` 返回 `(float64, bool)`，正好作为 `midpointSeek(float64, bool)` 的两个实参（多返回值直接整体传入）。

### 3.3 扩展 `PreGenerateThumbnails` 到视频

`thumbnail.go:195`：去掉"仅图片"过滤，图片+视频都预热：

```go
func (s *ThumbnailService) PreGenerateThumbnails(files []models.MediaFile, ctx context.Context) {
	hasFFmpeg := s.HasFFmpeg()
	var queue []models.MediaFile
	for _, f := range files {
		if f.MediaType == "image" {
			queue = append(queue, f)
		} else if f.MediaType == "video" && hasFFmpeg {
			queue = append(queue, f)
		}
	}
	if len(queue) == 0 {
		return
	}
	// …（现有 worker 池 NumCPU/2 + 信号量 + ctx 取消逻辑不变，遍历 queue 而非 images）
}
```

**代价与控制**：视频预热每文件跑一次 ffprobe + ffmpeg（比图片重），大视频库预热更久；但它是后台 post-scan、`NumCPU` 信号量限流、且复用 Round 2 接好的 `ctx` 取消（关停/被新扫描取代时中断）。可接受。

---

## 4. Cache-Control 缓存头

### 4.1 helper

`server/internal/server/handler/handler.go` 新增：

```go
// setMediaCacheHeaders marks thumbnail/original responses as browser-cacheable.
// The thumbnail cache key is md5(path+sourceModTime), so a changed source produces
// a new cache file with a different modtime — browsers revalidating via
// If-Modified-Since get a 200, keeping this correct outside the max-age window.
func setMediaCacheHeaders(c echo.Context) {
	c.Response().Header().Set("Cache-Control", "public, max-age=86400")
}
```

### 4.2 接入 7 个 handler

在以下 handler 的 `c.File(...)` **之前**调用 `setMediaCacheHeaders(c)`：

| 文件 | handler | 服务对象 |
|------|---------|---------|
| `system.go` | `SystemThumbnail` / `SystemOriginal` | 系统缩略图/原图 |
| `media.go` | `MediaThumbnail` / `MediaOriginal` | 统一缩略图/原图 |
| `images.go` | `GetThumbnail` / `GetOriginal` | 图片缩略图/原图 |
| `videos.go` | `GetVideoThumbnail` | 视频缩略图 |

**不加到 stream 端点**（`SystemStream`/`MediaStream`/`StreamVideo`）——流式 + Range 语义不同，ExoPlayer 自管缓存。

### 4.3 正确性

- `http.ServeContent`（`c.File` 内部）按**文件 modtime** 设 `Last-Modified`。缩略图缓存键含源 modtime → 源文件改 → 新缓存文件（不同 modtime）→ 浏览器 `If-Modified-Since` 命中不同文件返回 200。
- max-age 窗口（1 天）内的源文件改动会有最多 1 天陈旧——媒体文件极少改，可接受。

---

## 5. 测试

### 5.1 `server/internal/service/thumbnail_test.go`（新建）
纯函数表驱动测试（不依赖 ffmpeg）：
- `parseFFprobeDuration`：`"12.5"`→12.5,true；`""`/`"N/A"`→false；`"abc"`→false；`"-1"`/`"0"`→false。
- `midpointSeek`：`(60, true)`→`"30.00"`；`(0, false)`→`"5"`；`(-1, true)`→`"5"`。
- `ffprobeSibling`（若抽成可测函数）：`C:\tools\ffmpeg.exe`→`C:\tools\ffprobe.exe`；基名不含 ffmpeg→`"ffprobe"`。

### 5.2 `server/internal/server/server_test.go`（扩展现有）
断言缩略图路由响应含 `Cache-Control: public, max-age=86400`（在现有 `TestRegisterRoutesServesThumbnailEndpoint` 基础上加一行 header 断言）。

视频预热本身依赖 ffmpeg/ffprobe，沿用现有惯例靠手工验证（不强制单测）。

---

## 6. 实现顺序与提交策略

集中服务端，按内聚度分次提交、每次 `go test ./...`：

1. **ffprobe + 中间帧（§3.1-3.2）**：`thumbnail.go` 加 `getFFprobeCmd`/`HasFFprobe`/`videoDuration`/`parseFFprobeDuration`/`midpointSeek`/`ffprobeSibling`，改 `generateThumbnailFromFile` 视频分支；补 `thumbnail_test.go` 纯函数测试。
2. **视频预热（§3.3）**：`PreGenerateThumbnails` 扩展含视频。
3. **缓存头（§4）**：`handler.go` 加 helper，7 个 handler 接入；`server_test.go` 断言。

不涉及 Android/Web，无需 APK 或浏览器回归（Web 画廊缓存改进可顺带手工确认）。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 方案 | A（视频预热 + 缓存头） | 最大服务端瓶颈是视频未预热；个人 LAN 无需异步复杂度 |
| 视频帧位置 | ffprobe 时长中点 | 比硬编码 `-ss 5` 更具代表性，顺带修短视频黑帧 |
| ffprobe | 软依赖，不在则回退 `-ss 5`/`-ss 0` | 不强制安装；优雅降级 |
| `Cache-Control` max-age | `86400`（1 天） | 媒体极少改；改动 1 天内通过 modtime 重验证传播 |
| 覆盖范围 | 缩略图 + 原图（7 handler），不含 stream | 静态-ish 响应适合浏览器缓存；流式语义不同 |
| 异步后台生成 | 不做（YAGNI） | 个人 LAN 并发冷启动极少；预热 + 缓存头已覆盖主路径 |

---

## 8. 后续轮次（不在本 spec，仅备忘）

- **性能**：扫描器按类型缓存、scoped 搜索去重复 normalize、`DownloadFolderZip` FD/压缩、Android Coil `respectCacheHeaders`/内存缓存、Web dashboard 冗余请求、stitch scroll 节流。
- **架构**：`BrowseViewModel` 拆分、`app.js` 模块化、`RetrofitClient` 可注入。
- **稳健性**：ExoPlayer 进度持久化、`GetRoots` `sync.Once` 竞态。

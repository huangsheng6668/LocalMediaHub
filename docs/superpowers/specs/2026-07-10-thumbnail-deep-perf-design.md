# 缩略图管线深化 + 搜索文件夹索引化（Round 28）

- **日期**: 2026-07-10
- **范围**: 服务端缩略图生成深化（C1 + C2）+ 搜索文件夹索引化（C3）
- **策略**: 3 commit 打包，每项独立可回滚，推荐执行顺序 C1 → C2 → C3
- **状态**: 待评审
- **前置**:
  - Round 24（缩略图 singleflight + duration cache + OkHttp dispatcher）
  - Round 23（searchFiles 已基于 scanner cache，仅 searchFoldersCtx 仍走 WalkDir）

---

## 1. 背景与动机

### 1.1 用户痛点

- **A1 视频缩略图生成延迟**：首屏打开大视频目录时，新视频缩略图从请求到显示明显有"咯噔"感（每个 miss 要 ~0.5-2s）
- **A2 大图缩略图生成延迟**：高分辨率照片（5000×4000 JPEG，~5MB）缩略图生成 ~1s 起步，首屏批量加载时排队感强
- **A3 文件夹搜索慢**：在含数千文件夹的媒体库中搜索目录名，响应 500ms-2s，纯磁盘 IO 瓶颈

### 1.2 规模假设

- **目录规模 S1**（200-1000 项）—— 文件搜索现状已够用
- **媒体库规模 M1**（1k-10k 文件 / 1k-5k 文件夹）—— 家用场景典型量级
- 不针对企业级规模（10 万+ 文件）做优化，避免过度工程

### 1.3 已确认的瓶颈（代码证据）

| 假设 | 证据 | 影响 |
|---|---|---|
| **G1 视频缩略图临时文件 IO** | `thumbnail.go:182-202` fork ffmpeg 写临时 jpg → `imaging.Open(tempPath)` 读回 → `imaging.Thumbnail` → 写 cache。临时文件写+读完全多余 | 每次视频缩略图生成多 1 次写 + 1 次读（机械盘 ~50-200ms，SSD ~5-20ms），并发时 IO 队列堆积 |
| **G2 图片缩略图 Lanczos 全量缩放** | `thumbnail.go:231` `imaging.Thumbnail(src, max, max, imaging.Box)` —— `imaging.Box` 等比缩放但 `imaging.Open` 全量 decode 后用 Lanczos 缩放，5000×4000 图 ~1.2s | 大图缩略图生成 60-80% 时间花在 Lanczos 缩放上 |
| **G3 文件夹搜索全树 WalkDir** | `search.go:114-170` `searchFoldersCtx` 每次 `filepath.WalkDir(root)`，即便 root 下有数千子目录也要全走一遍 | 家用 5k 文件夹库搜索 500ms-2s，纯磁盘 IO |

### 1.4 关键洞察

- **C1 ROI 最高**：视频缩略图是首屏最慢路径，省临时文件 IO 即时见效
- **C2 视觉等价**：300×300 缩略图场景 Lanczos vs BiLinear 肉眼不可辨，但速度差 3-5 倍
- **C3 痛点最明确**：文件夹搜索是唯一仍在 WalkDir 的搜索路径，文件搜索（`searchFiles`）Round 23 已基于 scanner cache，本轮补齐

---

## 2. 目标与非目标

### 目标

1. **C1**: 视频缩略图生成改为 ffmpeg `-f image2pipe` + stdout pipe → `imaging.Decode(reader)`，消除临时文件 IO
2. **C2**: 图片缩略图缩放器从 Lanczos 降到 BiLinear，大图生成时间下降 60-80%
3. **C3**: Scanner 扫描时顺带收集已知目录列表，文件夹搜索从 WalkDir → 内存前缀匹配
4. **行为完全兼容**：API、URL、磁盘缓存 key 格式、客户端视觉表现全部不变

### 非目标

- ❌ 改 ffmpeg/ffprobe 命令本身的 seek 逻辑（`-ss` 前置已合理）
- ❌ 改 `models.MediaFile` 持久化结构（scanner 文件结构不动）
- ❌ 改 `NativeDecoderFactory`（Rust 解码层稳定）
- ❌ 改 Coil / OkHttp / ExoPlayer 配置（Round 24 已调优）
- ❌ 改文件搜索（`searchFiles`）现状（已基于 cache，无瓶颈）
- ❌ JPEG/PNG 部分采样 decode（Go 标准库不支持，换 decoder 复杂度过高）
- ❌ FTS5 全文索引（家用量级 O(n) 内存扫够用，避免中文分词坑）
- ❌ 内存倒排索引 / n-gram trie（过度工程）
- ❌ FFmpeg so 裁剪（独立议题，留后续轮次）
- ❌ wrk 性能 baseline（spec 记录建议但不强制）

---

## 3. 架构总览与 Commit 切分

| Commit | 主题 | 范围 | 主要收益 |
|---|---|---|---|
| **C1** | 视频缩略图 ffmpeg 流式管道 | `thumbnail.go` | A1 主要：消除临时文件 IO，每次生成省 50-200ms |
| **C2** | 图片缩略图 BiLinear 缩放器 | `thumbnail.go` | A2 主要：大图生成时间 -60-80% |
| **C3** | 文件夹搜索走 scanner cache | `scanner.go` + `search.go` | A3 主要：搜索从 WalkDir → 内存扫，降 10-100 倍 |

**执行顺序 C1 → C2 → C3**：

- C1/C2 都改 `thumbnail.go`，连续改动减少上下文切换
- C3 跨 scanner + handler，独立成 commit 便于回滚

**跨 commit 不变约束**：

- API 契约不变（URL、参数、响应 schema）
- 磁盘缓存目录结构与 key 格式不变（与 Round 24 LRU + singleflight 一致）
- 缩略图字节内容合理等价（C2 视觉差异肉眼不可辨，字节不同但视觉等价）
- 所有现有测试不回归

---

## 4. C1 视频缩略图 ffmpeg 流式管道

### 4.1 问题精确化

`thumbnail.go:176-224` 的 `generateThumbnailFromFile` 对视频：

1. `os.CreateTemp("", "videothumb-*.jpg")` → 建临时文件
2. `exec ffmpeg -ss X -i src -vframes 1 -f image2 tempPath` → ffmpeg 写临时 jpg
3. `imaging.Open(tempPath)` → 读回 jpg 并 decode
4. `imaging.Thumbnail(src, ...)` → 缩放
5. `jpeg.Encode(out, thumb, ...)` → 写 cache
6. `defer os.Remove(tempPath)` → 删临时文件

步骤 1+2+3+6 完全多余 —— ffmpeg 本就能把单帧输出到 stdout pipe，直接喂给 `imaging.Decode(reader)` 即可。

### 4.2 改造方案

引入 helper `extractVideoFrameToImage`，封装 ffmpeg stdout pipe → image.Image：

```go
// extractVideoFrameToImage 调用 ffmpeg 从 sourcePath 的 seek 秒位置抽取一帧，
// 通过 stdout pipe 直接返回 image.Image，避免临时文件 IO。
// 失败时返回 error，由 caller 决定 fallback 策略。
func (s *ThumbnailService) extractVideoFrameToImage(sourcePath, seek string) (image.Image, error) {
    ffmpegCmd := s.getFFmpegCmd()
    cmd := exec.Command(ffmpegCmd,
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
    if err := cmd.Start(); err != nil {
        return nil, err
    }

    // imaging.Decode 内部用 image.Decode，自动识别 mjpeg → jpeg decoding
    img, decodeErr := imaging.Decode(stdout)
    // 必须等 ffmpeg 退出，避免 zombie 进程 + 释放 pipe 资源
    waitErr := cmd.Wait()

    if decodeErr != nil {
        return nil, fmt.Errorf("failed to decode ffmpeg pipe: %w", decodeErr)
    }
    if waitErr != nil {
        return nil, fmt.Errorf("ffmpeg exited with error: %w", waitErr)
    }
    return img, nil
}
```

`generateThumbnailFromFile` 视频分支改造（保留 seek=X → fallback seek=0 两阶段重试）：

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

        thumb := imaging.Thumbnail(src, s.maxSize, s.maxSize, imaging.Box)
        return s.encodeThumbnailToCache(thumb, cachePath)
    }

    // 图片分支（C2 改造，见 §5）
    ...
}
```

**注意**：`imaging.Decode(reader)` 来自 `disintegration/imaging`，内部就是 `image.Decode`，能自动识别 mjpeg 输出（ffmpeg `-vcodec mjpeg` 输出的是标准 JPEG 字节流）。

### 4.3 涉及文件

| 文件 | 改动类型 |
|---|---|
| `server/internal/service/thumbnail.go` | 改：新增 `extractVideoFrameToImage` helper；`generateThumbnailFromFile` 视频分支改用 helper；删除 `os.CreateTemp` / `defer os.Remove(tempPath)` 临时文件逻辑；`os`/`imaging` import 不变 |

### 4.4 风险与缓解

1. **ffmpeg stdout pipe 阻塞**：若 ffmpeg 输出速率 > Go 读取速率，pipe 缓冲区满会阻塞 ffmpeg。`imaging.Decode` 是流式读取，缓冲区默认 64KB 足够单帧 mjpeg（典型 50-200KB），偶发阻塞无影响（ffmpeg 等一下即可）。
2. **ffmpeg 退出码非 0 但 pipe 已写部分字节**：先 `Decode` 后 `Wait`，`Decode` 成功即返回 image；若 `Wait` 报错，返回 error 让 caller fallback。**Decode 和 Wait 都成功才算成功**。
3. **mjpeg pipe vs image2 文件字节差异**：`-f image2pipe -vcodec mjpeg` 输出的字节流与 `-f image2 file.jpg` 文件内容**语义等价**（都是 JPEG 格式），decode 后的 image.Image 像素完全一致。**缩略图字节内容完全不变**（下游 `imaging.Thumbnail` + `jpeg.Encode` 完全相同）。
4. **imaging.Decode 错误处理**：`image.Decode` 失败时返回的 error 由 caller fallback 到 seek=0 重试，与现状行为一致。

---

## 5. C2 图片缩略图 BiLinear 缩放器

### 5.1 问题精确化

`thumbnail.go:226-243` 图片分支：

```go
src, _ := imaging.Open(sourcePath)                    // 全量 decode（无法避免，Go 标准库限制）
thumb := imaging.Thumbnail(src, max, max, imaging.Box) // Box filter ≈ Lanczos
```

`imaging.Box` 是 `disintegration/imaging` 的默认高质量缩放器，对 5000×4000 → 300×300 的 ~16x 下采样要 ~1s。300×300 缩略图场景下，BiLinear 与 Box 视觉差异肉眼不可辨，但速度快 3-5 倍。

### 5.2 改造方案

引入 helper `encodeThumbnailToCache`（C1 已用），把缩放 + 写 cache 逻辑统一。用 `imaging.Fit` + `imaging.BiLinear`：

```go
// encodeThumbnailToCache 把 src 等比缩放到 max×max 框内并写入 cachePath。
// 使用 BiLinear 缩放器（300×300 缩略图场景视觉等价 Lanczos，速度快 3-5 倍）。
func (s *ThumbnailService) encodeThumbnailToCache(src image.Image, cachePath string) (string, error) {
    thumb := imaging.Fit(src, s.maxSize, s.maxSize, imaging.BiLinear)

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

**选择 `imaging.Fit` 而非 `imaging.Resize`/`imaging.Thumbnail` 的理由**：

- `imaging.Resize(src, w, h, filter)` 拉伸到精确 w×h，**不保比** —— 直接用会变形
- `imaging.Fit(src, w, h, filter)` 等比缩放到 w×h 框内（短边 = w/h，长边 ≤），**保比**
- `imaging.Thumbnail(src, w, h, filter)` = `Fit` + `Center Crop` 到精确 w×h

源图缩放后短边 = 300、长边 ≤ 300 时，`Thumbnail` 的 crop 操作无实际裁剪（已是正方形目标），因此 `Fit` 与 `Thumbnail` 在本场景下**行为完全等价**，选更轻量的 `Fit`。

`generateThumbnailFromFile` 图片分支：

```go
// 图片分支
src, err := imaging.Open(sourcePath)
if err != nil {
    return "", err
}
return s.encodeThumbnailToCache(src, cachePath)
```

视频分支（C1 改造后）也复用 `encodeThumbnailToCache`：

```go
// 视频分支（C1 改造后）
src, err := s.extractVideoFrameToImage(sourcePath, seek)
if err != nil { ... fallback ... }
return s.encodeThumbnailToCache(src, cachePath)
```

### 5.3 涉及文件

| 文件 | 改动类型 |
|---|---|
| `server/internal/service/thumbnail.go` | 改：新增 `encodeThumbnailToCache` helper（统一缩放 + 写 cache）；视频和图片分支都改用 helper；缩放器从 `imaging.Thumbnail(src, max, max, imaging.Box)` 改为 `imaging.Fit(src, max, max, imaging.BiLinear)` |

### 5.4 风险与缓解

1. **BiLinear 输出字节与 Lanczos 不同**：下游 memCache / 磁盘缓存 key 含 modTime，**文件未变则 key 不变**，但 key 对应的字节内容会因 C2 改动而变化。**用户首次升级后，旧缓存仍按原 key 命中（Lanczos 字节），新文件 miss 时生成 BiLinear 字节**。视觉无感知差异（300×300 缩略图 Lanczos/BiLinear 几乎像素级相近）。
2. **缩略图质量退化**：300×300 JPEG Quality 85 + BiLinear，理论上比 Lanczos 略软。家用浏览场景（手机 6 寸屏看缩略图）肉眼不可辨。
3. **极端边缘 case**：源图本身就是 300×300 以下小图，`imaging.Fit` 不放大（与 `imaging.Thumbnail` 一致），无副作用。
4. **回归测试覆盖**：现有 `thumbnail_test.go` / `thumbnail_cache_test.go` 不断言具体字节内容，只断言生成成功 + memCache hit/miss 行为，**不会因 BiLinear 字节变化而失败**。

---

## 6. C3 文件夹搜索走 scanner cache

### 6.1 问题精确化

`search.go:114-170` `searchFoldersCtx` 每次搜索都 `filepath.WalkDir(root)`：

```go
for _, root := range searchRoots {
    err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
        // 检查 ctx.Done()
        // 检查 limit
        // d.IsDir() && strings.Contains(name, query) → append
    })
}
```

家用 5k 文件夹库（含子目录）每次搜索要 walk 整棵树，磁盘 IO 500ms-2s。文件搜索（`searchFiles`）Round 23 已基于 scanner cache O(n) 内存扫，但文件夹搜索漏掉了。

### 6.2 改造方案

#### 6.2.1 Scanner 收集已知目录

`scanner.go` `Scan` 函数遍历媒体文件时，顺带把每个文件的父目录加入去重集合。扫描结束后存入新字段 `cacheDirs`。

**Scanner struct 新增字段**：

```go
type Scanner struct {
    // existing fields...
    cacheDirs   []string           // 去重后的已知目录列表（含 mtime）
    cacheDirMap map[string]time.Time // 目录 → mtime，去重 + mtime 记录
}
```

**Scan 函数改造**：walk goroutine 通过 `sync.Mutex` 保护的共享 `dirMap` 收集目录（目录数远少于文件数，锁竞争可忽略）：

```go
var dirMu sync.Mutex
dirMap := make(map[string]time.Time)

for i, root := range roots {
    i, root := i, root
    g.Go(func() error {
        var localFiles []models.MediaFile
        err := filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
            // ... existing WalkDir 逻辑：ctx 检查、ext 判断、stat、append localFiles ...

            if isVideo || isImage {
                // ... existing MediaFile append 到 localFiles ...

                // 收集父目录到共享 dirMap
                dir := filepath.Dir(path)
                dirMu.Lock()
                if _, exists := dirMap[dir]; !exists {
                    if dirInfo, err := os.Stat(dir); err == nil {
                        dirMap[dir] = dirInfo.ModTime()
                    } else {
                        dirMap[dir] = time.Time{} // fallback 空 mtime
                    }
                }
                dirMu.Unlock()
            }
            return nil
        })
        // ... existing: results[i] = localFiles ...
    })
}
```

**Scan 结束写入 cacheDirs**（`dirMap` 由 walk goroutine 共享填充，合并阶段直接转存）：

```go
s.mu.Lock()
s.cache["all"] = allFiles
s.cache["video"] = videoFiles
s.cache["image"] = imageFiles
// 把 dirMap 转为排序切片（按字典序，便于后续前缀匹配二分查找）
cacheDirs := make([]string, 0, len(dirMap))
cacheDirMap := make(map[string]time.Time, len(dirMap))
for dir, mtime := range dirMap {
    cacheDirs = append(cacheDirs, dir)
    cacheDirMap[dir] = mtime
}
sort.Strings(cacheDirs)
s.cacheDirs = cacheDirs
s.cacheDirMap = cacheDirMap
s.cacheTime = time.Now()
// ... callback ...
s.mu.Unlock()
```

#### 6.2.2 新增导出方法 GetCachedDirs

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

// filterDirsByScope 持读锁调用，返回 scope 前缀下的目录（已排序）。
// scope="" 返回全部。scope 不以 filepath.Separator 结尾时内部补齐。
func (s *Scanner) filterDirsByScope(scope string) []string {
    if scope == "" {
        out := make([]string, len(s.cacheDirs))
        copy(out, s.cacheDirs)
        return out
    }
    // cacheDirs 已排序，二分查找前缀范围
    prefix := scope
    if !strings.HasSuffix(prefix, string(filepath.Separator)) {
        prefix += string(filepath.Separator)
    }
    start := sort.SearchStrings(s.cacheDirs, prefix)
    end := start
    for end < len(s.cacheDirs) && strings.HasPrefix(s.cacheDirs[end], prefix) {
        end++
    }
    out := make([]string, end-start)
    copy(out, s.cacheDirs[start:end])
    return out
}
```

#### 6.2.3 InvalidateCache 同步清理

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

#### 6.2.4 search.go 改造

`searchFoldersCtx` 改为走 cache：

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
    for _, dir := range dirs {
        if ctx.Err() != nil {
            break
        }
        // 排除 scope 根自身（与原 WalkDir 行为一致：path == root 时跳过）
        if scope != "" && dir == strings.TrimSuffix(scope, string(filepath.Separator)) {
            continue
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

`Search` handler 把 `searchFoldersCtx` 调用改为 `searchFoldersCached`：

```go
matchedFolders, err := h.searchFoldersCached(c.Request().Context(), searchPath, query, limit)
```

### 6.3 涉及文件

| 文件 | 改动类型 |
|---|---|
| `server/internal/service/scanner.go` | 改：Scanner struct 加 `cacheDirs []string` + `cacheDirMap map[string]time.Time`；Scan walk goroutine 收集父目录到共享 `dirMap`（mutex 保护）；Scan 合并阶段写 `cacheDirs`（排序）+ `cacheDirMap`；新增 `GetCachedDirs` + `peekCachedDirs` + `filterDirsByScope`；`InvalidateCache` 同步清理；`NewScanner` 初始化新字段 |
| `server/internal/server/handler/search.go` | 改：`searchFoldersCtx` → `searchFoldersCached`，改用 `h.scanner.GetCachedDirs`；`Search` 调用点改名 |
| `server/internal/server/handler/search_test.go` | 改：现有测试若有 mock scanner WalkDir 的断言需调整；新增 cache 命中/miss + scope 过滤测试 |

### 6.4 风险与缓解

1. **空目录不会出现**：scanner 只在见到媒体文件时收集父目录。空目录（无媒体文件）不会出现在搜索结果。**与现有 BrowseResult 行为一致**（浏览页也只展示含媒体的目录），无回归。
2. **scope 根自身排除**：原 WalkDir 在 `path == root` 时跳过（不把搜索根作为结果返回）。`searchFoldersCached` 显式检查 `dir == TrimSuffix(scope, Separator)` 跳过，保持行为一致。
3. **cache miss 时搜索触发 Scan**：`GetCachedDirs` cache miss 会通过 singleflight 触发 `Scan`。与现有 `GetCached` 行为一致（`Search` handler 已经调 `GetCached` 拿文件列表），无新风险。
4. **目录 mtime 缺失**：若 `os.Stat(dir)` 失败（目录被删/权限问题），`cacheDirMap[dir] = time.Time{}`（零值），搜索结果 ModifiedTime 为空。客户端搜索结果若展示 mtime 会显示空，可接受（原 WalkDir 在目录被删时 `d.Info()` 也会失败跳过）。
5. **fsnotify 触发重扫**：`watchEvents` 防抖后调 `InvalidateCache` + `TriggerScan`，cacheDirs 会随重扫重建。新增/删除媒体文件 → 父目录自动加入/移除 cacheDirs。
6. **scope 二分查找正确性**：`sort.SearchStrings` 找第一个 ≥ prefix 的位置，然后线性扫描直到前缀不匹配。`cacheDirs` 已字典序排序，二分 + 线性扫描的复杂度 O(log n + k)，k 为匹配数。

---

## 7. 端到端数据流

### 7.1 场景 A：首次浏览大视频目录（C1 + C2 生效）

```
[Client] GET /api/v1/media/thumbnail?path=X.mp4
  ↓
[Server: handler/media.go::MediaThumbnail]
  └─ ThumbnailService.GenerateThumbnailBytes
       └─ generateBytesVia
            ├─ memCache miss
            ├─ singleflight.Do(cacheKey, ...)
            │    └─ GenerateThumbnail
            │         ├─ 磁盘 cache miss
            │         ├─ sem <- struct{}{}
            │         └─ generateThumbnailFromFile
            │              ├─ videoDurationCached ← Round 24 cache
            │              └─ extractVideoFrameToImage ← C1 新增
            │                   ├─ exec ffmpeg -ss midpoint -f image2pipe pipe:1
            │                   ├─ imaging.Decode(stdout_pipe) → image.Image
            │                   └─ cmd.Wait() ← 等待 ffmpeg 退出
            │              └─ encodeThumbnailToCache ← C2 新增（BiLinear）
            │                   ├─ imaging.Fit(src, 300, 300, BiLinear) ← C2 改造
            │                   └─ jpeg.Encode(out, thumb, Q85)
            │         → 写磁盘缓存 + 读 bytes + 写 memCache
            └─ 返回 bytes
  ↓
[Client: Coil decode + render]
```

**对比改动前**：省了 `os.CreateTemp` + ffmpeg 写临时 jpg + `imaging.Open(tempPath)` 读回 + `os.Remove(tempPath)`。Lanczos → BiLinear 缩放速度提升 3-5 倍。

### 7.2 场景 B：搜索文件夹（C3 生效）

```
[Client] GET /api/v1/search?q=movie&path=D:/Media
  ↓
[Server: handler/search.go::Search]
  ├─ searchFiles（基于 scanner cache，已优化，无改动）
  └─ searchFoldersCached ← C3 新增
       ├─ h.scanner.GetCachedDirs(ctx, roots, "D:/Media/")
       │    ├─ cache TTL 有效 → 直接返回 cacheDirs 子集
       │    └─ cache miss → singleflight.Scan → 填充 cacheDirs → 返回
       ├─ 遍历 dirs（已按 scope 过滤 + 字典序排序）
       │    └─ strings.Contains(lower(name), "movie") → append Folder
       └─ 返回 []Folder
  ↓
[Client: 渲染搜索结果]
```

**对比改动前**：从 `filepath.WalkDir(root)` 全树遍历 → 内存前缀扫。5k 文件夹库从 ~1s → ~5ms。

### 7.3 关键不变量

- 视频缩略图字节内容 **视觉等价**（C1 字节完全一致；C2 BiLinear 字节不同但视觉等价）
- 磁盘缓存路径与文件名 **完全不变**（`cacheDir/<md5>.jpg`）
- API URL 与响应 schema **完全不变**
- 客户端看到的行为：**只是更快**，无功能/视觉退化

---

## 8. 错误处理与边界情况

### 8.1 C1 视频缩略图

| 情况 | 处理 |
|---|---|
| ffmpeg 不存在 | `HasFFmpeg()` 已检查，返回明确 error（与现状一致） |
| ffmpeg Start 失败 | `cmd.Start()` 返回 error，`extractVideoFrameToImage` 返回 error，caller fallback seek=0 重试，再失败返回 error |
| imaging.Decode 失败（pipe 字节损坏） | 返回 error，caller fallback seek=0 重试 |
| cmd.Wait 失败（ffmpeg 退出码非 0） | 返回 error，caller fallback seek=0 重试 |
| Decode 成功但 Wait 失败 | 优先返回 Wait error（Decode 成功但 ffmpeg 异常退出，结果不可信） |
| seek 越界（>duration） | ffmpeg 内部 clamp，不报错；midpoint fallback 到 5s 已有逻辑保护 |
| 视频文件损坏 ffmpeg 直接退出 | 主路径失败 → seek=0 fallback → 仍失败 → 返回 error，handler 返回 500 |

### 8.2 C2 图片缩略图

| 情况 | 处理 |
|---|---|
| imaging.Open 失败（非图片/损坏） | 返回 error，handler 返回 500（与现状一致） |
| imaging.Fit 异常输入（nil image） | imaging.Open 成功即非 nil，无此路径 |
| 源图小于 300×300 | imaging.Fit 不放大，返回原图尺寸（与 imaging.Thumbnail 行为一致） |
| BiLinear 缩放器 panic | imaging 库稳定，无 panic 路径 |

### 8.3 C3 文件夹搜索

| 情况 | 处理 |
|---|---|
| cacheDirs 为 nil（首次启动未扫完） | GetCachedDirs 触发 Scan，Scan 完成后填充 |
| scope 不存在（不在任何 root 下） | filterDirsByScope 返回空切片，搜索返回空结果（与原 WalkDir 行为一致） |
| scope 根被排除 | `dir == TrimSuffix(scope, Separator)` 显式跳过，与原 `path == root` 跳过一致 |
| 目录 mtime 为零值（os.Stat 失败） | ModifiedTime 字段为 `time.Time{}`，JSON 序列化为零值，客户端显示空（与原行为一致） |
| fsnotify 触发重扫时搜索并发 | InvalidateCache 清空 cacheDirs，并发搜索可能拿到 nil → 触发 Scan 重建。singleflight 保证只有一个 Scan 跑 |
| ctx 取消（客户端断开） | searchFoldersCached 循环内检查 `ctx.Err()`，提前 break 返回部分结果（与原 searchFoldersCtx 一致） |
| 大规模媒体库（>10 万文件） | cacheDirs 内存占用上升（每目录 ~100 字节，1 万目录 ~1MB），家用可接受 |

### 8.4 跨 commit 边界

| 边界 | 处理 |
|---|---|
| 只部署 C1 不部署 C2/C3 | 视频缩略图快了，图片仍 Lanczos，搜索仍 WalkDir。**A1 部分收益** |
| 只部署 C2 不部署 C1/C3 | 图片快了，视频仍写临时文件，搜索仍 WalkDir。**A2 部分收益** |
| 只部署 C3 不部署 C1/C2 | 搜索快了，缩略图仍慢。**A3 部分收益** |
| 三个 commit 都部署 | 完整效果。**推荐** |

---

## 9. 测试策略

### 9.1 C1 视频缩略图（可单测）

**`thumbnail_test.go` 扩展**：

```go
// 1. extractVideoFrameToImage 主路径：用测试 ffmpeg + 小测试视频，
//    断言返回的 image.Image 尺寸合理（非 nil，宽高 > 0）
func TestExtractVideoFrameToImage_MainPath(t *testing.T)

// 2. extractVideoFrameToImage seek=0 fallback：seek=999999（越界）→ fallback seek=0 成功
func TestExtractVideoFrameToImage_SeekFallback(t *testing.T)

// 3. generateThumbnailFromFile 视频分支：生成的 cachePath 文件存在 + 字节是合法 JPEG
func TestGenerateThumbnailFromFile_Video_ProducesValidJPEG(t *testing.T)

// 4. generateThumbnailFromFile 视频分支：不再产生临时文件（grep 临时文件前缀 "videothumb-"）
//    —— 通过检查 os.TempDir() 下无残留验证
func TestGenerateThumbnailFromFile_Video_NoTempFileLeftover(t *testing.T)
```

**测试 1-2 依赖 ffmpeg + 测试视频**：若 CI 环境无 ffmpeg，跳过（`t.Skip("ffmpeg not available")`）。本地开发环境验证。

**测试 3-4 是集成测试**：用 `testdata/sample.mp4`（小视频，仓库内或 .gitignore）。若无 sample.mp4，可生成纯色测试视频（ffmpeg -f lavfi -i testsrc=duration=1:size=320x240）。

### 9.2 C2 图片缩略图（可单测）

**`thumbnail_test.go` 扩展**：

```go
// 5. encodeThumbnailToCache：用 imaging.New 创建 5000x4000 测试图，
//    断言生成的 cachePath 文件存在 + 字节合法 JPEG + 解码后尺寸 ≤ 300x300
func TestEncodeThumbnailToCache_ProducesValidJPEG(t *testing.T)

// 6. encodeThumbnailToCache 小图不放大：源 100x100 → 输出 100x100（不放大到 300）
func TestEncodeThumbnailToCache_SmallImageNotUpscaled(t *testing.T)

// 7. generateThumbnailFromFile 图片分支：BiLinear 生成的缩略图字节长度合理
//    （不断言具体字节，只断言 > 0 且 < 源图字节）
func TestGenerateThumbnailFromFile_Image_BiLinearOutputReasonable(t *testing.T)
```

**测试 5-7 不依赖外部工具**，纯 Go imaging 库操作。

### 9.3 C3 文件夹搜索（可单测）

**`scanner_test.go` 扩展**：

```go
// 8. Scan 后 cacheDirs 被填充：构造测试目录树（含媒体文件 + 空目录），
//    断言 cacheDirs 包含媒体文件父目录，不含空目录
func TestScan_PopulatesCacheDirs(t *testing.T)

// 9. GetCachedDirs scope 过滤：构造 D:/root/A/x.jpg + D:/root/B/y.jpg，
//    scope="D:/root/A" 只返回 A 目录
func TestGetCachedDirs_ScopeFilter(t *testing.T)

// 10. GetCachedDirs scope 根排除：scope="D:/root" 返回子目录但不含 "D:/root" 自身
func TestGetCachedDirs_ExcludesScopeRoot(t *testing.T)

// 11. InvalidateCache 清空 cacheDirs
func TestInvalidateCache_ClearsCacheDirs(t *testing.T)

// 12. cacheDirs 字典序排序（二分查找前置条件）
func TestScan_CacheDirsSorted(t *testing.T)
```

**`search_test.go` 扩展**：

```go
// 13. searchFoldersCached 基础：query 匹配目录名子串
func TestSearchFoldersCached_BasicMatch(t *testing.T)

// 14. searchFoldersCached scope 限定
func TestSearchFoldersCached_ScopedSearch(t *testing.T)

// 15. searchFoldersCached limit 生效
func TestSearchFoldersCached_Limit(t *testing.T)

// 16. searchFoldersCached ctx 取消提前返回
func TestSearchFoldersCached_ContextCancellation(t *testing.T)
```

### 9.4 Smoke test 矩阵

| 步骤 | C1 | C2 | C3 |
|---|---|---|---|
| 服务端 `go test ./...` 全过 | ✓ | ✓ | ✓ |
| 1. 启动服务端，扫描含视频的目录 | ✓ | — | — |
| 2. 删除 `.cache/thumbnails` 强制 miss | ✓ | ✓ | — |
| 3. 客户端打开视频目录 → 主观评估首屏速度 | ✓ | — | — |
| 4. 客户端打开大图目录（5000x4000+ JPEG）→ 主观评估 | — | ✓ | — |
| 5. 客户端搜索文件夹名 → 主观评估响应速度 | — | — | ✓ |
| 6. 服务端 `os.TempDir()` 检查无 `videothumb-*` 残留 | ✓ | — | — |
| 7. 多客户端并发请求同一视频缩略图 → 无错误 | ✓ | — | — |

### 9.5 性能 baseline（建议，非强制）

- C1 部署后用 `time` 测单个视频缩略图生成耗时（删 cache 后），对比改动前后
- C2 部署后用 `time` 测大图（5000×4000 JPEG）缩略图生成耗时
- C3 部署后用 `curl -w "%{time_total}"` 测搜索响应时间，对比改动前后

**spec 记录建议但不强制做 baseline**，避免拖慢交付。

---

## 10. 回滚预案

每个 commit 独立可 `git revert`：

- **C1 revert**：恢复 `generateThumbnailFromFile` 视频分支临时文件逻辑，删除 `extractVideoFrameToImage` helper。无副作用。
- **C2 revert**：恢复 `imaging.Thumbnail(src, max, max, imaging.Box)`。`encodeThumbnailToCache` helper 可保留（视频分支仍在用）或一并删除。无副作用。
- **C3 revert**：恢复 `searchFoldersCtx` WalkDir 实现；Scanner `cacheDirs`/`cacheDirMap` 字段可保留（不影响功能）或一并删除。

---

## 11. 决策点汇总

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | C1 + C2 + C3 三 commit 打包 | 用户明确选 all |
| 视频缩略图策略 | ffmpeg image2pipe + stdout pipe + imaging.Decode | 消除临时文件 IO，单次省 50-200ms |
| 图片缩略图策略 | imaging.Fit + BiLinear（替代 Thumbnail + Box） | 300×300 场景视觉等价，速度快 3-5 倍 |
| 缩放器统一 | 新增 encodeThumbnailToCache helper，视频/图片共用 | 消除重复代码，C1/C2 改动集中 |
| 文件搜索索引 | 不索引（已基于 scanner cache） | searchFiles Round 23 已优化，无瓶颈 |
| 文件夹搜索索引 | scanner 收集 cacheDirs，内存前缀扫 | 避免 FTS5 中文分词坑，避免内存倒排索引复杂度 |
| 目录 mtime | 保留（os.Stat 收集） | 用户明确选保留；客户端搜索结果展示 |
| cacheDirs 排序 | 字典序 + sort.SearchStrings 二分 | scope 前缀过滤从 O(n) → O(log n + k) |
| scope 根排除 | 显式 `dir == TrimSuffix(scope, Sep)` 跳过 | 与原 WalkDir `path == root` 行为一致 |
| 空目录处理 | 不收集（scanner 只在有媒体文件时收集父目录） | 与 BrowseResult 行为一致 |
| FFmpeg so 裁剪 | 不做（留后续轮次） | 独立议题，重编流程复杂 |
| wrk baseline | 记录建议，非强制 | 避免拖慢交付 |

---

## 12. 非目标（再次明确）

- ❌ 改 ffmpeg/ffprobe 命令本身的 seek 逻辑
- ❌ 改 `models.MediaFile` 持久化结构
- ❌ 改 `NativeDecoderFactory` / Rust 解码层
- ❌ 改 Coil / OkHttp / ExoPlayer 配置
- ❌ 改文件搜索（`searchFiles`）
- ❌ JPEG/PNG 部分采样 decode（Go 标准库限制）
- ❌ FTS5 全文索引 / 内存倒排索引
- ❌ FFmpeg so 裁剪
- ❌ HTTP/2 / TLS / QUIC
- ❌ API 破坏性变更
- ❌ 配置文件 schema 变更

---

## 13. 后续轮次（备忘）

- **FFmpeg so 裁剪**：重编 ffmpeg 只保留项目实际用到的 demuxer + decoder，APK 体积可能从 6.7MB → 3-4MB
- **Scanner B1/B2/B4/B5**（Round 23 显式延后）：搜索 O(n) 优化（若文件量真破 10 万再做）、cap 预估、sync.Pool、扫描并发调优
- **JPEG/PNG 部分采样 decode**：若 Go 标准库未来支持 libjpeg-turbo 绑定或自定义 decoder，可进一步降低大图 decode 时间
- **视频缩略图 seek 预览**：客户端拖动进度条时实时请求多帧缩略图（需服务端支持多帧批量生成接口）

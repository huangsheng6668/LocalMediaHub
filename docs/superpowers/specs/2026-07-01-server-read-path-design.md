# 服务端读取热路径设计（Server Read-Path · Round 6）

- **日期**: 2026-07-01
- **范围**: Go 服务端（`scanner.go`、`search.go`、`videos.go`/`images.go`、`folders.go` + 测试）
- **策略**: A — scanner 按类型缓存 + 搜索去重复 normalize + zip FD 泄漏修复（保留 Store）
- **状态**: 待评审

---

## 1. 背景与动机

round-3 处理了缩略图热路径。本轮处理**其余服务端读取热路径**的三处开销/缺陷，均随媒体库变大线性放大：

- **Scanner 每次分页重过滤**：`server/internal/service/scanner.go` 的 `Scan`（`:171`）只存 `cache["all"]`；`GetVideos`/`GetImages` 每次分页请求都 `FilterByType(files, ...)`（`:214`，无容量提示）重过滤整个缓存切片。库越大、每次列表/翻页的 O(N) 开销越大。
- **scoped 搜索每文件双归一化**：`server/internal/server/handler/search.go` 的 `searchFiles`（`:80-86`）对每个文件调 `service.IsPathWithinRoots(file.Path, []string{scopedPath})`，而 `scopedPath` 早已在 handler（`:54`）归一化、`file.Path` 来自扫描本就绝对——每文件重复 2 次 `NormalizePath` + `Rel`。
- **DownloadFolderZip FD 泄漏（真 bug）**：`server/internal/server/handler/folders.go:238` 的 `defer fileToZip.Close()` 在 `filepath.Walk` 回调内，所有打开的文件**直到整个 zip 结束才关闭** → 大目录 zip 耗尽文件句柄。

本轮只做这三处（方案 A）。zip 压缩（Store→Deflate）、singleflight key / RLock / `mediaExtensions` 等 micro、`searchFolders` 文件夹列表缓存、streaming Range 测试——留后续。

---

## 2. 目标与非目标

### 目标
1. **Scanner 按类型缓存**：扫描时一次分流存 `cache["video"]`/`cache["image"]`；`GetVideos`/`GetImages` 直读，分页不再重过滤。
2. **scoped 搜索去重复 normalize**：预算一次 `scopePrefix`，逐文件前缀检查替代 `IsPathWithinRoots`。
3. **DownloadFolderZip FD 泄漏修复**：每文件 open/copy/close 包进匿名函数，`defer Close` 作用域到单文件（拷完即关）。

### 非目标（留待后续轮次）
- zip 压缩（`Deflate`）——媒体已压缩，YAGNI（见 §3、§7）。
- `singleflight` key（忽略 roots）、Scanner 每文件 `RLock`、`mediaExtensions` 重建等 micro。
- `searchFolders` 文件夹列表缓存、streaming Range 测试。
- Android / Web 任何改动。

---

## 3. Scanner 按类型缓存

`scanner.go` 改动：

- `Scan` 在合并切片存 `cache["all"]`（`:170-174`）的同时，按 `MediaType` 分流，额外存 `cache["video"]`/`cache["image"]`（一次过滤，扫描时完成，非每请求）：

```go
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

s.mu.Lock()
s.cache["all"] = allFiles
s.cache["video"] = videoFiles
s.cache["image"] = imageFiles
s.cacheTime = time.Now()
callback := s.OnScanComplete
s.mu.Unlock()
```

- 新增 `GetCachedByType`，复用 `GetCached` 的有效性判断 + `singleflight`：

```go
// GetCachedByType returns the cached scan results filtered to mediaType,
// triggering a scan on cache miss (shared via singleflight, same as GetCached).
func (s *Scanner) GetCachedByType(ctx context.Context, roots []string, mediaType string) ([]models.MediaFile, error) {
    s.mu.RLock()
    if time.Since(s.cacheTime) < s.cacheTTL {
        if files, ok := s.cache[mediaType]; ok {
            s.mu.RUnlock()
            return files, nil
        }
    }
    s.mu.RUnlock()

    val, err, _ := s.sf.Do("scan", func() (interface{}, error) {
        return s.Scan(ctx, roots)
    })
    if err != nil {
        return nil, err
    }
    // Scan 刚填充了 cache[mediaType]；读回请求的类型切片。
    s.mu.RLock()
    defer s.mu.RUnlock()
    return s.cache[mediaType], nil
}
```

- `GetVideos`/`GetImages`（`videos.go`/`images.go`）改为调 `GetCachedByType(ctx, roots, "video"/"image")` + 直接分页，删去 `FilterByType` 调用。
- `FilterByType` 保留（`GetTaggedMedia` 仍用，过滤的是标签结果而非缓存）。`InvalidateCache` 已 `make` 整个 map（`:209`），per-type 键随之清空，无需改。

---

## 4. scoped 搜索去重复 normalize

`search.go` 的 `searchFiles`（`:76-101`）改为预算一次前缀、逐文件 `HasPrefix`：

```go
func (h *Handler) searchFiles(files []models.MediaFile, scopedPath, query string, limit int) []models.MediaFile {
    lowerQuery := strings.ToLower(query)
    matchedFiles := make([]models.MediaFile, 0, limit)

    // scopedPath 已在 handler 归一化；预算前缀（仅当无尾分隔符才补，正确处理盘根 D:\）。
    var scopePrefix string
    if scopedPath != "" {
        scopePrefix = scopedPath
        if !strings.HasSuffix(scopePrefix, string(filepath.Separator)) {
            scopePrefix += string(filepath.Separator)
        }
    }

    for _, file := range files {
        if scopePrefix != "" && !strings.HasPrefix(file.Path, scopePrefix) {
            continue
        }
        if !strings.Contains(strings.ToLower(file.Name), lowerQuery) {
            continue
        }
        matched := file
        matched.RelativePath = file.Path
        matchedFiles = append(matchedFiles, matched)
        if len(matchedFiles) >= limit {
            break
        }
    }
    return matchedFiles
}
```

消除每文件 `IsPathWithinRoots`（2 次 `NormalizePath` + `Rel`），改为单次 O(len) `HasPrefix`。

> **正确性**：此处是**显示过滤**（scoped 搜索结果），非安全边界。`file.Path` 来自扫描（绝对、cleaned），`scopedPath` 来自服务端浏览（大小写一致），故大小写敏感的前缀检查在实际中无误。盘根 `D:\`（尾带分隔符）经"仅当无尾分隔符才补"处理，`scopePrefix` 不产生双反斜杠失配。

---

## 5. DownloadFolderZip FD 泄漏修复

`folders.go` 的 `DownloadFolderZip`（`:215-252`）把每文件 open/copy/close 包进匿名函数，使 `defer fileToZip.Close()` 作用域到单文件（拷完即关，不再积压到 Walk 结束）：

```go
err = filepath.Walk(pathStr, func(filePath string, info os.FileInfo, err error) error {
    if err != nil {
        return err
    }
    if info.IsDir() {
        return nil
    }
    ext := strings.ToLower(filepath.Ext(info.Name()))
    if !videoExts[ext] && !imageExts[ext] {
        return nil
    }
    relPath, err := filepath.Rel(filepath.Dir(pathStr), filePath)
    if err != nil {
        return err
    }
    relPath = filepath.ToSlash(relPath)

    // 包进匿名函数：defer Close 作用域到本文件，拷完立即关，避免大目录 FD 积压。
    return func() error {
        fileToZip, err := os.Open(filePath)
        if err != nil {
            return err
        }
        defer fileToZip.Close()

        header := &zip.FileHeader{
            Name:     relPath,
            Method:   zip.Store, // 媒体已压缩，Store 正确（Deflate YAGNI）
            Modified: info.ModTime(),
        }
        writer, err := zipWriter.CreateHeader(header)
        if err != nil {
            return err
        }
        _, err = io.Copy(writer, fileToZip)
        return err
    }()
})
```

**保留 `zip.Store`**：媒体（JPEG/PNG/WebP/MP4/MKV）本已压缩，`Deflate` 收益边际、徒增每 zip 的 CPU；仅罕见未压缩类型（BMP）受益，不值。其余逻辑（`Store`、header、错误处理、已提交响应不回写错误）不动。

---

## 6. 测试

- **`server/internal/service/scanner_test.go`**：扫描一个含混合 video/image 文件的临时目录 → 断言 `cache["video"]`/`cache["image"]` 正确填充（仅含对应类型）+ `GetCachedByType(ctx, roots, "video"/"image")` 返回对应子集。`GetCached` 既有测试不受影响。
- **`server/internal/server/handler/search_test.go`**：新增 scoped 搜索用例——`?path=<子目录>&q=...` 只返回该目录下文件（覆盖 §4 前缀改写，确保行为不变；含一个不在 scope 下的文件被排除）。
- **`server/internal/server/handler/folders_test.go` / 手工**：zip FD 修复是结构性（defer 作用域），靠代码审查 + 既有小目录 zip 行为佐证；大目录 FD 泄漏需实测（手工——下载含上千文件的大目录 zip，确认不报 `too many open files`）。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| Scanner 分流时机 | 扫描时一次分流存 per-type | 过滤每扫描一次，非每请求；`GetCachedByType` 直读 |
| 搜索前缀 | 大小写敏感 `HasPrefix`（路径来自服务端、大小写一致） | O(len) 替代每文件双归一化；显示过滤非安全边界 |
| 盘根边界 | "仅当无尾分隔符才补 sep" 预算 `scopePrefix` | 避免 `D:\` + `\` = 双反斜杠失配 |
| zip FD 修复 | 匿名函数作用域 `defer Close` | 拷完即关，不再积压；最小侵入 |
| zip 压缩 | 保留 `Store` | 媒体已压缩，`Deflate` YAGNI（收益边际、CPU 成本） |
| micro / 文件夹列表缓存 / streaming 测试 | 不做 | 零散/低价值/单独议题，留后续 |

---

## 8. 后续轮次（不在本 spec，仅备忘）

- **服务端**：`singleflight` key（忽略 roots 的去重 bug）、Scanner 每文件 `RLock`（扩展名表不变）、`mediaExtensions` 重建、`searchFolders` 文件夹列表缓存、streaming Range 测试。
- **Web**：`style.css` 响应式 `@media` + dashboard/stitch/标签渲染性能。
- **Android**：旋转屏 `rememberSaveable`、ExoPlayer 进程保留、OkHttp/Coil 网络缓存。
- **架构**：`app.js` 模块化、`RetrofitClient` Hilt 可注入、Scanner 共享切片竞态。

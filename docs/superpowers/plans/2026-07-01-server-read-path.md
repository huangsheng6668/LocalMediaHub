# 服务端读取热路径（Round 6）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 去掉服务端读取热路径的三处开销/缺陷——Scanner 每次分页重过滤、scoped 搜索每文件双归一化、DownloadFolderZip 的 FD 泄漏。

**Architecture:** 仅 Go 服务端，3 个任务。Task 1 让 Scanner 扫描时按类型分流缓存 + `GetCachedByType`，`GetVideos`/`GetImages` 直读；Task 2 把 `searchFiles` 的每文件 `IsPathWithinRoots` 改为预算前缀的 `HasPrefix`（已有 scoped 搜索测试覆盖）；Task 3 用匿名函数作用域 `defer Close` 修 zip 的 FD 积压。

**Tech Stack:** Go 1.22+ / Echo v4 / `archive/zip` / `path/filepath`。

## Global Constraints

- **提交策略**（`AGENTS.md`）：本地改动自动同步推送至 GitHub `master`。所有提交直接在 `master`，**不开 feature 分支**；conventional commit + `Co-Authored-By: Claude <noreply@anthropic.com>` 尾注。
- **Go 编码规则**（`AGENTS.md`）：业务逻辑放 `internal/service/`；handler 只做参数解析与响应；列表用 `make([]T, 0)`；5xx 用 `respondError`/`respondInternalError`，不回显 `err.Error()`。
- **Go 测试风格**：沿用 `server/internal/service/scanner_test.go` 既有风格——`testify/assert`、`t.TempDir()`、真实文件。
- **Go 代理**（中国大陆）：拉依赖失败用 `GOPROXY=https://goproxy.cn,direct`。
- **行为约束**：`FilterByType` 保留（`GetTaggedMedia` 仍用）；`InvalidateCache` 已清整个 map；**保留 `zip.Store`**（媒体已压缩）。
- **范围外**（spec §2 非目标）：zip `Deflate`、singleflight key/RLock/`mediaExtensions` micro、`searchFolders` 文件夹列表缓存、streaming Range 测试、Android/Web。

## File Structure

- 修改 `server/internal/service/scanner.go` — `Scan` 分流存 per-type；新增 `GetCachedByType`。
- 修改 `server/internal/server/handler/videos.go` / `images.go` — `GetVideos`/`GetImages` 改调 `GetCachedByType`。
- 修改 `server/internal/server/handler/search.go` — `searchFiles` 预算前缀 + `HasPrefix`。
- 修改 `server/internal/server/handler/folders.go` — `DownloadFolderZip` 每文件匿名函数作用域 `defer Close`。
- 修改 `server/internal/service/scanner_test.go` — 新增 per-type 缓存测试。

---

## Task 1: Scanner 按类型缓存（TDD）

**Files:**
- Modify: `server/internal/service/scanner.go`（`Scan` 合并段 `:164-178`；新增 `GetCachedByType`）
- Modify: `server/internal/server/handler/videos.go:25-31`、`images.go:25-31`
- Test: `server/internal/service/scanner_test.go`（末尾追加）

**Interfaces:**
- Produces: `func (s *Scanner) GetCachedByType(ctx context.Context, roots []string, mediaType string) ([]models.MediaFile, error)`。`GetVideos`/`GetImages` 依赖它。
- Consumes: 既有 `Scan`/`GetCached`/`singleflight`。

- [ ] **Step 1: 写失败测试**

在 `server/internal/service/scanner_test.go` 末尾追加（沿用该文件 `testify/assert` 风格）：

```go
func TestScanCachesPerType(t *testing.T) {
	tempDir := t.TempDir()
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "v.mp4"), []byte("v"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "i.jpg"), []byte("i"), 0644))
	assert.NoError(t, os.WriteFile(filepath.Join(tempDir, "skip.txt"), []byte("x"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"})
	files, err := scanner.Scan(context.Background(), []string{tempDir})
	assert.NoError(t, err)
	assert.Len(t, files, 2)

	// Scan 应按类型分流缓存
	assert.Len(t, scanner.cache["video"], 1)
	assert.Len(t, scanner.cache["image"], 1)

	// GetCachedByType 返回对应子集（缓存新鲜）
	vids, err := scanner.GetCachedByType(context.Background(), []string{tempDir}, "video")
	assert.NoError(t, err)
	assert.Len(t, vids, 1)
	assert.Equal(t, "video", vids[0].MediaType)

	imgs, err := scanner.GetCachedByType(context.Background(), []string{tempDir}, "image")
	assert.NoError(t, err)
	assert.Len(t, imgs, 1)
	assert.Equal(t, "image", imgs[0].MediaType)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd server && go test ./internal/service/ -run TestScanCachesPerType -v`
Expected: 编译失败，`scanner.GetCachedByType undefined`。

- [ ] **Step 3: `Scan` 分流存 per-type**

将 `scanner.go` 的合并段（约 `:164-178`）：

```go
	// Merge slices
	allFiles := make([]models.MediaFile, 0)
	for _, subList := range results {
		allFiles = append(allFiles, subList...)
	}

	s.mu.Lock()
	s.cache["all"] = allFiles
	s.cacheTime = time.Now()
	callback := s.OnScanComplete
	s.mu.Unlock()
```

替换为（合并时按 `MediaType` 分流）：

```go
	// Merge slices, splitting by media type so list endpoints can read per-type
	// slices directly instead of re-filtering the whole cache on every request.
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

- [ ] **Step 4: 新增 `GetCachedByType`**

在 `scanner.go` 的 `GetCached`（约 `:186-205`）之后插入：

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

- [ ] **Step 5: 运行测试确认通过**

Run: `cd server && go test ./internal/service/ -run TestScanCachesPerType -v`
Expected: PASS。

- [ ] **Step 6: rewire `GetVideos` / `GetImages`**

将 `videos.go` 的 `GetVideos`（`:25-31`）：

```go
	files, err := h.scanner.GetCached(c.Request().Context(), h.cfg.Scan.GetRoots())
	if err != nil {
		return respondInternalError(c, err)
	}

	videos := h.scanner.FilterByType(files, "video")
	start, end := paginateBounds(len(videos), page, pageSize)
```

替换为：

```go
	videos, err := h.scanner.GetCachedByType(c.Request().Context(), h.cfg.Scan.GetRoots(), "video")
	if err != nil {
		return respondInternalError(c, err)
	}

	start, end := paginateBounds(len(videos), page, pageSize)
```

将 `images.go` 的 `GetImages`（`:25-31`）同理替换（`"video"` → `"image"`、`videos` → `images`）：

```go
	images, err := h.scanner.GetCachedByType(c.Request().Context(), h.cfg.Scan.GetRoots(), "image")
	if err != nil {
		return respondInternalError(c, err)
	}

	start, end := paginateBounds(len(images), page, pageSize)
```

- [ ] **Step 7: 全量构建 + 测试 + 提交**

Run: `cd server && go build ./... && go test ./...`
Expected: 全部 PASS（既有 `scanner_test`/`search_test` 等无回归；`FilterByType` 仍被 `GetTaggedMedia` 使用，保留）。

```bash
git add server/internal/service/scanner.go server/internal/service/scanner_test.go server/internal/server/handler/videos.go server/internal/server/handler/images.go
git commit -m "perf(server): cache per-type scan slices; drop per-page re-filter

Scan now splits results into cache[video]/cache[image] at scan time, and
GetCachedByType reads them directly. GetVideos/GetImages no longer re-filter
the whole cached slice on every paginated request. FilterByType is retained
for GetTaggedMedia.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: scoped 搜索去重复 normalize

**Files:**
- Modify: `server/internal/server/handler/search.go`（`searchFiles` 约 `:76-101`）

**Interfaces:** 无外部接口变化（`searchFiles` 内部改写）。

> 既有 `search_test.go` 的 `TestSearchScopesResultsToRequestedPathAndReturnsFolders` **已覆盖** scoped 行为（scoped path 只返回该目录下文件、排除同名外目录文件）——本任务是行为保持的优化，该测试是回归护栏，**无需新增测试**。

- [ ] **Step 1: 改 `searchFiles` 预算前缀**

将 `search.go` 的 `searchFiles`（`:76-101`）整体替换为：

```go
func (h *Handler) searchFiles(files []models.MediaFile, scopedPath, query string, limit int) []models.MediaFile {
	lowerQuery := strings.ToLower(query)
	matchedFiles := make([]models.MediaFile, 0, limit)

	// scopedPath 已在 handler 归一化；预算前缀（仅当无尾分隔符才补，正确处理盘根 D:\），
	// 逐文件 HasPrefix 替代每文件 IsPathWithinRoots（消除双 NormalizePath + Rel）。
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

（`filepath` 已在 `search.go` import；不再用 `service.IsPathWithinRoots`——`service` import 仍被 `Search` handler 的其它调用使用，保留。）

- [ ] **Step 2: 全量构建 + 测试（含既有 scoped 搜索测试）**

Run: `cd server && go build ./... && go test ./...`
Expected: 全部 PASS——尤其 `TestSearchScopesResultsToRequestedPathAndReturnsFolders` 仍绿（验证前缀改写与原 `IsPathWithinRoots` 行为等价）。

- [ ] **Step 3: 提交**

```bash
git add server/internal/server/handler/search.go
git commit -m "perf(server): precompute scope prefix in searchFiles

searchFiles called IsPathWithinRoots (NormalizePath x2 + Rel) per file for
scoped searches, re-normalizing the already-normalized scope path every
iteration. Precompute a scope prefix once and use HasPrefix per file. The
existing scoped-search test confirms behavior is unchanged.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: DownloadFolderZip FD 泄漏修复

**Files:**
- Modify: `server/internal/server/handler/folders.go`（`DownloadFolderZip` 的 Walk 回调 `:215-252`）

**Interfaces:** 无外部接口变化。

> 结构性修复（defer 作用域），无独立单测（FD 泄漏需大目录实测）；靠 `go build` + 既有行为佐证 + 手工验证。

- [ ] **Step 1: 改 Walk 回调为每文件匿名函数作用域**

将 `folders.go` 的 `DownloadFolderZip` 内 Walk 回调（`:215-252`）：

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

		fileToZip, err := os.Open(filePath)
		if err != nil {
			return err
		}
		defer fileToZip.Close()

		header := &zip.FileHeader{
			Name:     relPath,
			Method:   zip.Store,
			Modified: info.ModTime(),
		}
		writer, err := zipWriter.CreateHeader(header)
		if err != nil {
			return err
		}

		_, err = io.Copy(writer, fileToZip)
		return err
	})
```

替换为（每文件 open/copy/close 包进匿名函数，`defer Close` 作用域到单文件）：

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

		// Per-file anonymous scope: defer Close runs after each file's copy,
		// not accumulated until the whole Walk ends (which exhausted FDs on
		// large folders). Method stays Store (media is already compressed).
		return func() error {
			fileToZip, err := os.Open(filePath)
			if err != nil {
				return err
			}
			defer fileToZip.Close()

			header := &zip.FileHeader{
				Name:     relPath,
				Method:   zip.Store,
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

（仅新增匿名函数包裹；`relPath`/`info`/`filePath`/`zipWriter` 经闭包捕获，语义不变。）

- [ ] **Step 2: 全量构建 + 测试**

Run: `cd server && go build ./... && go test ./...`
Expected: 全部 PASS、无回归。

- [ ] **Step 3: 手工验证（可选）+ 提交**

可选：起服务，下载一个含上千文件的大目录 zip，确认不再 `too many open files`。小目录 zip 仍正常。

```bash
git add server/internal/server/handler/folders.go
git commit -m "fix(server): scope DownloadFolderZip file close to each file

defer fileToZip.Close() ran inside the Walk callback, so every opened file
stayed open until the entire zip finished — exhausting file descriptors on
large folders. Wrap each file's open/copy/close in an anonymous func so the
deferred close runs per file immediately after its copy.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §1 Scanner 按类型缓存（`Scan` 分流 + `GetCachedByType` + rewire `GetVideos`/`GetImages`）→ Task 1。✅
- §2 scoped 搜索去重复 normalize（`scopePrefix` + `HasPrefix`）→ Task 2。✅
- §3 DownloadFolderZip FD 泄漏修复（匿名函数作用域 defer）→ Task 3。✅
- §6 测试 → Task 1 新增 `TestScanCachesPerType`；Task 2 复用既有 `TestSearchScopesResultsToRequestedPathAndReturnsFolders`（spec 原以为需新增，实际已有覆盖）；Task 3 手工。✅
- §7 决策（扫描时分流、大小写敏感前缀、盘根边界、保留 Store）→ 各任务落地。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码；每条命令含期望输出。✅

**3. 类型/签名一致性**：
- `GetCachedByType(ctx context.Context, roots []string, mediaType string) ([]models.MediaFile, error)` —— Task 1 Step 4 定义、Step 1 测试、Step 6 `GetVideos`/`GetImages` 调用，签名一致。✅
- `searchFiles(files, scopedPath, query, limit)` —— Task 2 整体替换，签名不变。✅
- `scanner.cache["video"]/["image"]` —— Task 1 Step 3 写入、Step 1 测试读取、`GetCachedByType` 读取，键一致。✅
- Task 1 rewire 后 `FilterByType` 仍被 `GetTaggedMedia` 使用（未被删除）—— 保留。✅
- Task 2 改后 `service` import 仍被 `Search` handler 其它调用使用（`NormalizePath`/`IsPathWithinRoots`）—— 保留。✅

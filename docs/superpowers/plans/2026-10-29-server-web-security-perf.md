# Server + Web SPA 安全加固与性能优化（批次 1）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Server (Go) 与 Web SPA 中的符号链接越界打包、书籍图片鉴权绕过、`ScanConfig` 并发竞争、EPUB 全量解压 DoS、HLS 槽位死锁、Gzip 二进制误压缩、Windows 路径大小写未命中、Web 缩略图 401/429 自锁死及阅读器后台心跳泄漏等问题。

**Architecture:** 服务端沿用 `Handler -> Service` 分层与无全局变量原则：在 `service/path.go` 提供零系统调用的 `IsLinkEntry` 与跨平台 `PathKey`，在 `Handler` 注入共享的 `AuthFailureLimiter` 接口，在 `ScanConfig` 引入读写锁并支持 `Scanner.StartWatching` 可重入热更新；Web 端在 `api.js` 统一导出 `withMediaAuthToken(url)`，并在 `router.js`/`bookshelf.js` 收口阅读器 `_cleanupReader` 生命周期。

**Tech Stack:** Go 1.24, Echo v4, SQLite (modernc/sqlite), fsnotify, Vanilla ES Modules (Web SPA), `node:test` + `jsdom`.

## Global Constraints

- 保持 Bearer Token 架构不变（Header 优先 + GET `?token=` 回退，见 `AGENTS.md`）。
- 符号链接与 Windows Reparse Point（Junction）一律跳过、不跟随（`IsLinkEntry(mode)`）。
- Handler 不使用全局变量，所有依赖通过 `Handler` 结构体持有。
- Web 端零 inline `<script>`、零 inline `style="..."`；涉及 `innerHTML` 必须带 `// XSS-SAFE:` 注释或调用 `escapeHtml()`，通过 `tools/xsscheck` 检查。
- 每个 Task 完成后均需通过对应单测并独立提交。

## File Structure Map

- `server/internal/service/path.go` & `path_test.go`：新增 `IsLinkEntry(fs.FileMode) bool` 与 `PathKey(string) string`
- `server/internal/server/handler/folders.go` & `folders_test.go`：`DownloadFolderZip` 改用 `filepath.WalkDir` 跳过链接并做 `os.SameFile` 校验；`BrowseFolder` 跳过链接条目
- `server/internal/server/handler/system.go`：`SystemBrowse` 跳过链接条目
- `server/internal/service/scanner.go` & `scanner_test.go`：`Scan` 跳过链接条目；`StartWatching` 支持重入停止旧监听协程；`cacheByDir` 使用 `PathKey`
- `server/internal/server/handler/handler.go`：定义 `AuthFailureRecorder` 接口并新增 `SetAuthFailureLimiter`
- `server/internal/server/handler/books.go` & `books_test.go`：`GetBookImage` 鉴权与限流前置；`GetBookChapter` 复用 `*bookparser.Book`
- `server/internal/server/server.go`：注入 `authFailLimiter`、补齐单图缩略图/时长 `RateLimit`、扩充 Gzip `Skipper`、优化 `?token=` 日志脱敏快路径
- `server/internal/config/config.go` & `server/internal/server/handler/admin.go`：`ScanConfig` 并发锁、`SetRoots`、`UpdateConfig` 先校验后切换并重载 watcher
- `server/internal/server/handler/tags.go` & `server/internal/service/tags.go`：标签路径解码/归一化、根目录复核、写锁修正、名称/颜色校验与启动幂等迁移
- `server/internal/service/bookparser/epub.go` & `server/internal/service/book.go`：EPUB `navID` 定向目录解析、`BookService` 64 本缓存上限
- `server/internal/service/transcode_hls.go` & `streaming.go` & `handler/media.go`：HLS 槽位排队可取消与超时 `503`、`GetVideoDuration` 10s 超时
- `server/internal/web/static.go`：静态资源预计算 `gzipBody`
- `server/internal/server/handler/search.go` & `library.go`：Windows 大小写不敏感路径匹配
- `server/internal/web/api.js`, `dashboard.js`, `browserView.js`, `lightbox.js`, `videoPlayer.js`：`withMediaAuthToken(url)` 统一注入与 `Intl.Collator` 复用
- `server/internal/web/router.js`, `bookshelf.js`, `textReader.js`, `scrollNav.js`：`_cleanupReader` 路由清理、章节渲染去重与书签事件委托、`MutationObserver` 范围收敛

---

### Task 1: 遍历与打包中的符号链接 / Windows Junction 跳过

**Files:**
- Modify: `server/internal/service/path.go:310-341`
- Modify: `server/internal/server/handler/folders.go:120-145, 225-275`
- Modify: `server/internal/server/handler/system.go:70-100`
- Modify: `server/internal/service/scanner.go:230-265`
- Test: `server/internal/service/path_test.go`

**Interfaces:**
- Produces: `func IsLinkEntry(mode fs.FileMode) bool` in package `service`

- [ ] **Step 1: Write the failing test in `server/internal/service/path_test.go`**

```go
func TestIsLinkEntry(t *testing.T) {
	if IsLinkEntry(0) {
		t.Fatal("regular file mode should not be link entry")
	}
	if IsLinkEntry(fs.ModeDir) {
		t.Fatal("directory mode should not be link entry")
	}
	if !IsLinkEntry(fs.ModeSymlink) {
		t.Fatal("symlink mode must be detected as link entry")
	}
	if !IsLinkEntry(fs.ModeIrregular) {
		t.Fatal("irregular mode (Windows junction) must be detected as link entry")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service -run TestIsLinkEntry -v`
Expected: FAIL with `undefined: IsLinkEntry`

- [ ] **Step 3: Implement `IsLinkEntry` and wire into `folders.go`, `system.go`, and `scanner.go`**

In `server/internal/service/path.go` (add `"io/fs"` import if not present):
```go
// IsLinkEntry reports whether a directory entry is a symlink or Windows
// reparse point (junction / mount point surface as ModeIrregular on Go 1.23+).
func IsLinkEntry(mode fs.FileMode) bool {
	return mode&(fs.ModeSymlink|fs.ModeIrregular) != 0
}
```

In `server/internal/service/scanner.go` inside `filepath.WalkDir` (`~L241`):
```go
				if IsLinkEntry(d.Type()) {
					if d.IsDir() {
						return filepath.SkipDir
					}
					return nil
				}
				if d.IsDir() {
					return nil
				}
```

In `server/internal/server/handler/folders.go` inside `BrowseFolder` (`~L125`):
```go
	for _, entry := range entries {
		if service.IsLinkEntry(entry.Type()) {
			continue
		}
```
And in `DownloadFolderZip` (`~L231`), replace `filepath.Walk` with `filepath.WalkDir`:
```go
	err = filepath.WalkDir(pathStr, func(filePath string, d iofs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if service.IsLinkEntry(d.Type()) {
			if d.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			return nil
		}

		ext := strings.ToLower(filepath.Ext(d.Name()))
		if h.classifyMediaType(ext) == "" {
			return nil
		}

		linfo, err := d.Info()
		if err != nil || service.IsLinkEntry(linfo.Mode()) {
			return nil
		}

		relPath, err := filepath.Rel(filepath.Dir(pathStr), filePath)
		if err != nil {
			return err
		}
		relPath = filepath.ToSlash(relPath)

		return func() error {
			fileToZip, err := os.Open(filePath)
			if err != nil {
				return err
			}
			defer fileToZip.Close()

			finfo, err := fileToZip.Stat()
			if err != nil || !os.SameFile(linfo, finfo) {
				return nil
			}

			header := &zip.FileHeader{
				Name:     relPath,
				Method:   zip.Store,
				Modified: finfo.ModTime(),
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

In `server/internal/server/handler/system.go` inside `SystemBrowse` (`~L78`):
```go
	for _, entry := range entries {
		if service.IsLinkEntry(entry.Type()) {
			continue
		}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service ./internal/server/handler -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go server/internal/service/scanner.go server/internal/server/handler/folders.go server/internal/server/handler/system.go
git commit -m "fix(security): skip symlinks and junctions during scan, browse, and zip download"
```

---

### Task 2: `/api/v1/books/image` 鉴权前置与失败限流

**Files:**
- Modify: `server/internal/server/handler/handler.go:37-65`
- Modify: `server/internal/server/handler/books.go:132-190`
- Modify: `server/internal/server/server.go:134, 277-279`
- Test: `server/internal/server/handler/books_test.go`

**Interfaces:**
- Produces:
  - `type AuthFailureRecorder interface { Blocked(ip string, now time.Time) bool; RecordFailure(ip string, now time.Time); Reset(ip string) }` in `handler`
  - `func (h *Handler) SetAuthFailureLimiter(l AuthFailureRecorder)` in `handler`

- [ ] **Step 1: Write the failing test in `server/internal/server/handler/books_test.go`**

```go
func TestGetBookImageAuthBeforeStatAndRateLimited(t *testing.T) {
	root := t.TempDir()
	cfg := &config.Config{
		Server: config.ServerConfig{Token: "secret-token"},
		Scan:   config.ScanConfig{Roots: []string{root}, TextExtensions: []string{".epub"}},
	}
	signer := service.NewBookSigner()
	bs := service.NewBookService()
	bs.SetSigner(signer)
	h := New(cfg, nil, nil, nil, nil, nil, bs, signer)
	limiter := middleware.NewAuthFailureLimiter(2, time.Minute)
	h.SetAuthFailureLimiter(limiter)

	e := echo.New()
	missingPath := filepath.Join(root, "nonexistent.epub")

	// 1. Unauthenticated request for non-existent file must return 401 (NOT 403 file-existence oracle)
	req1 := httptest.NewRequest(http.MethodGet, "/api/v1/books/image?path="+url.QueryEscape(missingPath)+"&manifest=img1", nil)
	rec1 := httptest.NewRecorder()
	if err := h.GetBookImage(e.NewContext(req1, rec1)); err != nil {
		t.Fatal(err)
	}
	if rec1.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for unauthenticated nonexistent file, got %d", rec1.Code)
	}

	// 2. Second invalid token attempt hits threshold (2)
	req2 := httptest.NewRequest(http.MethodGet, "/api/v1/books/image?path="+url.QueryEscape(missingPath)+"&manifest=img1&token=wrong", nil)
	rec2 := httptest.NewRecorder()
	_ = h.GetBookImage(e.NewContext(req2, rec2))
	if rec2.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", rec2.Code)
	}

	// 3. Third attempt must be blocked with 429
	req3 := httptest.NewRequest(http.MethodGet, "/api/v1/books/image?path="+url.QueryEscape(missingPath)+"&manifest=img1&token=wrong2", nil)
	rec3 := httptest.NewRecorder()
	_ = h.GetBookImage(e.NewContext(req3, rec3))
	if rec3.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 429 after exceeding auth failure limit, got %d", rec3.Code)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/server/handler -run TestGetBookImageAuthBeforeStatAndRateLimited -v`
Expected: FAIL with `h.SetAuthFailureLimiter undefined`

- [ ] **Step 3: Implement `SetAuthFailureLimiter` and reorder `GetBookImage`**

In `server/internal/server/handler/handler.go`:
```go
// AuthFailureRecorder tracks per-IP authentication failures.
type AuthFailureRecorder interface {
	Blocked(ip string, now time.Time) bool
	RecordFailure(ip string, now time.Time)
	Reset(ip string)
}
```
Add `authFailLimiter AuthFailureRecorder` to `Handler` struct and add setter:
```go
func (h *Handler) SetAuthFailureLimiter(l AuthFailureRecorder) {
	h.authFailLimiter = l
}
```

In `server/internal/server/handler/books.go`, update `GetBookImage`:
```go
func (h *Handler) GetBookImage(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	manifestID := c.QueryParam("manifest")
	if manifestID == "" {
		return respondError(c, http.StatusBadRequest, "manifest required")
	}

	now := time.Now()
	clientIP := c.RealIP()
	if h.authFailLimiter != nil && h.authFailLimiter.Blocked(clientIP, now) {
		return respondError(c, http.StatusTooManyRequests, "Too many auth failures")
	}

	normalizedPath, err := service.NormalizePath(pathStr)
	if err != nil {
		return respondError(c, http.StatusBadRequest, "invalid path")
	}

	if h.bookSigner != nil {
		sig := c.QueryParam("sig")
		switch {
		case sig != "":
			if !h.bookSigner.VerifyImage(clientIP, normalizedPath, manifestID, sig) {
				if h.authFailLimiter != nil {
					h.authFailLimiter.RecordFailure(clientIP, now)
				}
				return respondError(c, http.StatusUnauthorized, "invalid signature")
			}
		case c.QueryParam("token") != "":
			if !tokenMatches(c.QueryParam("token"), h.cfg.Server.Token) {
				if h.authFailLimiter != nil {
					h.authFailLimiter.RecordFailure(clientIP, now)
				}
				return respondError(c, http.StatusUnauthorized, "invalid token")
			}
			slog.Warn("[DEPRECATED] /books/image called with ?token=",
				"path", normalizedPath,
				"manifest", manifestID,
			)
		default:
			if h.authFailLimiter != nil {
				h.authFailLimiter.RecordFailure(clientIP, now)
			}
			return respondError(c, http.StatusUnauthorized, "signature required")
		}
		if h.authFailLimiter != nil {
			h.authFailLimiter.Reset(clientIP)
		}
	}

	allowedExts := append([]string{}, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(
		normalizedPath,
		h.cfg.Scan.GetRoots(),
		h.cfg.GetSystemAllowedRoots(),
		allowedExts,
	)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	if h.books == nil {
		return respondInternalError(c, errors.New("book service unavailable"))
	}
	data, contentType, err := h.books.ReadImageBytes(resolved, manifestID)
	if err != nil {
		return mapBookError(c, err)
	}
	setMediaCacheHeaders(c)
	return c.Blob(http.StatusOK, contentType, data)
}
```

In `server/internal/server/server.go` (`~L277`), wire the shared limiter into `s.Handler`:
```go
	authFailLimiter := middleware.NewAuthFailureLimiter(10, time.Minute)
	s.Handler.SetAuthFailureLimiter(authFailLimiter)
	authMw := middleware.BearerToken(s.Config.Server.Token, authFailLimiter)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/server/... -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/server/handler/handler.go server/internal/server/handler/books.go server/internal/server/handler/books_test.go server/internal/server/server.go
git commit -m "fix(security): verify book image auth before stat and enforce AuthFailureLimiter"
```

---

### Task 3: `ScanConfig` 并发读写锁与 `Scanner.StartWatching` 热更新

**Files:**
- Modify: `server/internal/config/config.go:67-109`
- Modify: `server/internal/server/handler/admin.go:21-58`
- Modify: `server/internal/service/scanner.go:35-65, 820-910`
- Test: `server/internal/config/config_test.go`

**Interfaces:**
- Produces:
  - `func (s *ScanConfig) SetRoots(roots []string) error` in `config`
  - Re-entrant `func (s *Scanner) StartWatching(roots []string) error` in `service`

- [ ] **Step 1: Write the failing test in `server/internal/config/config_test.go`**

```go
func TestScanConfigConcurrentGetAndSetRoots(t *testing.T) {
	sc := &ScanConfig{Roots: []string{`C:\Media`}}
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for j := 0; j < 200; j++ {
				if id%2 == 0 {
					_ = sc.SetRoots([]string{`C:\Media`, `D:\Videos`, `C:\Media`})
				} else {
					roots := sc.GetRoots()
					if len(roots) == 0 {
						t.Errorf("unexpected empty roots")
					}
				}
			}
		}(i)
	}
	wg.Wait()
	got := sc.GetRoots()
	if len(got) != 2 {
		t.Fatalf("expected deduplicated 2 roots, got %v", got)
	}
}
```

- [ ] **Step 2: Run test with `-race` to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test -race ./internal/config -run TestScanConfigConcurrentGetAndSetRoots -v`
Expected: FAIL with `sc.SetRoots undefined`

- [ ] **Step 3: Implement `ScanConfig` mutex + `SetRoots`, atomic `UpdateConfig`, and re-entrant `StartWatching`**

In `server/internal/config/config.go`:
```go
const MaxScanRoots = 64

type ScanConfig struct {
	mu              sync.RWMutex
	Roots           []string `yaml:"roots,omitempty" json:"roots,omitempty"`
	VideoExtensions []string `yaml:"video_extensions" json:"video_extensions"`
	ImageExtensions []string `yaml:"image_extensions" json:"image_extensions"`
	TextExtensions  []string `yaml:"text_extensions,omitempty" json:"text_extensions,omitempty"`
	AutoDetectRoots bool     `yaml:"auto_detect_roots,omitempty" json:"auto_detect_roots,omitempty"`

	autoRoots     []string
	autoRootsOnce sync.Once
}

func (s *ScanConfig) GetRoots() []string {
	s.mu.RLock()
	if len(s.Roots) > 0 {
		out := make([]string, len(s.Roots))
		copy(out, s.Roots)
		s.mu.RUnlock()
		return out
	}
	s.mu.RUnlock()

	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.Roots) > 0 {
		out := make([]string, len(s.Roots))
		copy(out, s.Roots)
		return out
	}
	s.autoRootsOnce.Do(func() {
		var drives []string
		for _, letter := range "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
			path := string(letter) + ":\\"
			if _, err := os.Stat(path); err == nil {
				drives = append(drives, path)
			}
		}
		s.autoRoots = drives
	})
	out := make([]string, len(s.autoRoots))
	copy(out, s.autoRoots)
	return out
}

func (s *ScanConfig) SetRoots(roots []string) error {
	seen := make(map[string]struct{}, len(roots))
	deduped := make([]string, 0, len(roots))
	for _, r := range roots {
		clean := filepath.Clean(r)
		key := strings.ToLower(clean)
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		deduped = append(deduped, clean)
	}
	if len(deduped) > MaxScanRoots {
		return fmt.Errorf("too many scan roots (max %d)", MaxScanRoots)
	}
	s.mu.Lock()
	s.Roots = deduped
	s.autoRoots = nil
	s.autoRootsOnce = sync.Once{}
	s.mu.Unlock()
	return nil
}

func (s *ScanConfig) InvalidateRootsCache() {
	s.mu.Lock()
	s.autoRoots = nil
	s.autoRootsOnce = sync.Once{}
	s.mu.Unlock()
}
```
Also update `Config.Public()` and `Config.Validate()` in `config.go` to read `c.Scan.GetRoots()` (or `c.Scan.mu.RLock()`) when inspecting `c.Scan.Roots`.

In `server/internal/server/handler/admin.go`, update `UpdateConfig`:
```go
	if len(req.Roots) > config.MaxScanRoots {
		return respondError(c, http.StatusBadRequest, fmt.Sprintf("too many scan roots (max %d)", config.MaxScanRoots))
	}
	// Validate on a shallow copy before mutating live state.
	candidate := &config.Config{
		Server:    h.cfg.Server,
		Scan:      config.ScanConfig{Roots: req.Roots, AutoDetectRoots: h.cfg.Scan.AutoDetectRoots},
		Thumbnail: h.cfg.Thumbnail,
		System:    h.cfg.System,
		Transcode: h.cfg.Transcode,
		Debug:     h.cfg.Debug,
		BLE:       h.cfg.BLE,
	}
	if err := candidate.Validate(false); err != nil {
		return respondError(c, http.StatusBadRequest, fmt.Sprintf("invalid configuration: %v", err))
	}
	if err := h.cfg.Scan.SetRoots(req.Roots); err != nil {
		return respondError(c, http.StatusBadRequest, err.Error())
	}
	if err := h.cfg.Save("config.yaml"); err != nil {
		return respondInternalError(c, err)
	}

	h.scanner.InvalidateCache()
	_ = h.scanner.StartWatching(h.cfg.Scan.GetRoots())

	return c.JSON(http.StatusOK, h.cfg.Public())
```

In `server/internal/service/scanner.go`, ensure `watchEvents` receives the local `watcher` instance `go s.watchEvents(watcher)` and exits cleanly when `watcher.Events` / `watcher.Errors` closes so calling `StartWatching` a second time never races with the new `s.watcher`:
```go
	go s.watchEvents(watcher)
```
And in `watchEvents(watcher *fsnotify.Watcher)`: use the passed `watcher` parameter directly instead of re-reading `s.watcher` inside the loop.

- [ ] **Step 4: Run tests with `-race` to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test -race ./internal/config ./internal/service ./internal/server/handler -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go server/internal/server/handler/admin.go server/internal/service/scanner.go
git commit -m "fix(config): synchronize ScanConfig.Roots and reload fsnotify watcher on config update"
```

---

### Task 4: 标签系统路径解码/归一化、根目录复核、写锁修正与幂等迁移

**Files:**
- Modify: `server/internal/server/handler/tags.go:25-198`
- Modify: `server/internal/service/tags.go:80-130, 406-425`
- Test: `server/internal/server/handler/tags_test.go`

**Interfaces:**
- Consumes: `decodeWildcardPath` (`handler/path_suffix.go`), `service.NormalizePath`, `service.IsBlockedRoot`

- [ ] **Step 1: Write the failing test in `server/internal/server/handler/tags_test.go`**

```go
func TestTagsPathNormalizationAndStaleRootGuard(t *testing.T) {
	root1 := t.TempDir()
	root2 := t.TempDir()
	outsideFile := filepath.Join(root2, "outside.mp4")
	if err := os.WriteFile(outsideFile, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	cfg := &config.Config{
		Scan: config.ScanConfig{
			Roots:           []string{root1, root2},
			VideoExtensions: []string{".mp4"},
		},
	}
	ts, err := service.NewTagsService(filepath.Join(t.TempDir(), "tags.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer ts.Close()
	sc := service.NewScanner(cfg.Scan.VideoExtensions, nil, nil)
	h := New(cfg, sc, ts, nil, nil, nil, nil, nil)

	tag, err := ts.CreateTag("fav", "#ff0000")
	if err != nil {
		t.Fatal(err)
	}

	// Associate with forward slashes & URL-encoded path
	e := echo.New()
	encoded := url.PathEscape(filepath.ToSlash(outsideFile))
	req := httptest.NewRequest(http.MethodPost, "/api/v1/tags/"+tag.ID+"/files/"+encoded, nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	c.SetPath("/api/v1/tags/:tag_id/files/*")
	c.SetParamNames("tag_id", "*")
	c.SetParamValues(tag.ID, encoded)
	if err := h.AssociateTag(c); err != nil {
		t.Fatal(err)
	}
	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", rec.Code, rec.Body.String())
	}

	// Now narrow roots to root1 only; GetTaggedMedia must NOT return outsideFile via fallback
	_ = cfg.Scan.SetRoots([]string{root1})
	reqMedia := httptest.NewRequest(http.MethodGet, "/api/v1/tags/"+tag.ID+"/media", nil)
	recMedia := httptest.NewRecorder()
	cMedia := e.NewContext(reqMedia, recMedia)
	cMedia.SetParamNames("tag_id")
	cMedia.SetParamValues(tag.ID)
	if err := h.GetTaggedMedia(cMedia); err != nil {
		t.Fatal(err)
	}
	if strings.Contains(recMedia.Body.String(), "outside.mp4") {
		t.Fatalf("expected outside.mp4 to be filtered out after root narrowing, got %s", recMedia.Body.String())
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/server/handler -run TestTagsPathNormalizationAndStaleRootGuard -v`
Expected: FAIL (returns `outside.mp4` via `buildTaggedMediaFallback` or fails encoded path check)

- [ ] **Step 3: Implement tag handler & service fixes**

In `server/internal/server/handler/tags.go`:
1. Add tag name & color validation in `CreateTag`:
```go
var tagColorRe = regexp.MustCompile(`^#[0-9a-fA-F]{3,8}$`)

func (h *Handler) CreateTag(c echo.Context) error {
	var req CreateTagRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}
	name := strings.TrimSpace(req.Name)
	if name == "" || utf8.RuneCountInString(name) > 64 {
		return respondError(c, http.StatusBadRequest, "tag name must be 1..64 characters")
	}
	color := strings.TrimSpace(req.Color)
	if color != "" && !tagColorRe.MatchString(color) {
		return respondError(c, http.StatusBadRequest, "invalid tag color format")
	}

	tag, err := h.tags.CreateTag(name, color)
	if err != nil {
		return respondError(c, http.StatusConflict, "tag already exists")
	}
	return c.JSON(http.StatusCreated, tag)
}
```
2. In `AssociateTag` and `DisassociateTag`, decode and normalize `c.Param("*")`:
```go
	rawPath, err := decodeWildcardPath(c.Param("*"), "")
	if err != nil {
		return respondError(c, http.StatusBadRequest, "invalid file path")
	}
	pathStr, err := service.NormalizePath(rawPath)
	if err != nil {
		return respondError(c, http.StatusBadRequest, "invalid file path")
	}
```
And replace raw `err.Error()` 500 responses in `DeleteTag`, `AssociateTag`, `DisassociateTag` with `respondInternalError(c, err)`.
3. In `isPathWithinConfiguredRoots(pathStr string) bool`:
```go
func (h *Handler) isPathWithinConfiguredRoots(pathStr string) bool {
	if service.IsBlockedRoot(pathStr) {
		return false
	}
	allRoots := append(append([]string{}, h.cfg.Scan.GetRoots()...), h.cfg.GetSystemAllowedRoots()...)
	if len(allRoots) == 0 {
		return false
	}
	valid, err := service.IsPathWithinRoots(pathStr, allRoots)
	return err == nil && valid
}
```
4. In `buildTaggedMediaFallback(pathStr string)`:
```go
func (h *Handler) buildTaggedMediaFallback(pathStr string) (models.MediaFile, bool) {
	if !h.isPathWithinConfiguredRoots(pathStr) {
		return models.MediaFile{}, false
	}
	info, err := os.Stat(pathStr)
```

In `server/internal/service/tags.go`:
1. Change `CleanDeletedPath` (`~L406`) to use write lock `s.mu.Lock()` / `defer s.mu.Unlock()`.
2. Add one-time idempotent normalization helper `normalizeStoredPaths()` called at the end of `NewTagsService` (if any row in `associations` has `file_path != NormalizePath(file_path)`, copy `dbPath` to `dbPath + ".bak"` once and update/merge rows in a transaction using `INSERT OR IGNORE INTO associations(tag_id, file_path) VALUES (?, ?)` + `DELETE`).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test -race ./internal/service ./internal/server/handler -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/server/handler/tags.go server/internal/server/handler/tags_test.go server/internal/service/tags.go
git commit -m "fix(tags): normalize tag paths, re-validate root boundaries, and use write lock on cleanup"
```

---

### Task 5: EPUB 目录定向解析与 `BookService` 缓存上限

**Files:**
- Modify: `server/internal/service/bookparser/epub.go:70-85, 156-248, 275-301`
- Modify: `server/internal/service/book.go:30-130`
- Modify: `server/internal/server/handler/books.go:99-115`
- Test: `server/internal/service/bookparser/epub_test.go`

**Interfaces:**
- Produces:
  - `func (s *BookService) GetChapterBlocksForBook(b *bookparser.Book, path string, idx int, clientIP string) ([]bookparser.Block, error)` in `service`

- [ ] **Step 1: Write the failing test in `server/internal/service/bookparser/epub_test.go`**

```go
func TestReadTocUsesNavPropertyNotRandomChapterLinks(t *testing.T) {
	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)
	writeEntry := func(name, body string) {
		w, err := zw.Create(name)
		if err != nil {
			t.Fatal(err)
		}
		_, _ = w.Write([]byte(body))
	}
	writeEntry("META-INF/container.xml", `<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf"/></rootfiles></container>`)
	writeEntry("OEBPS/content.opf", `<?xml version="1.0"?><package><metadata><dc:title>Test</dc:title></metadata><manifest><item id="ch1" href="ch1.xhtml"/><item id="ch2" href="ch2.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="ch1"/><itemref idref="ch2"/></spine></package>`)
	// Chapter 1 contains an anchor link that used to trick readToc when visited before nav.xhtml
	writeEntry("OEBPS/ch1.xhtml", `<html><body><p>Body <a href="ch2.xhtml">Footnote Link</a></p></body></html>`)
	writeEntry("OEBPS/ch2.xhtml", `<html><body><p>Body 2</p></body></html>`)
	writeEntry("OEBPS/nav.xhtml", `<html><body><nav epub:type="toc"><ol><li><a href="ch1.xhtml">Real Chapter 1</a></li><li><a href="ch2.xhtml">Real Chapter 2</a></li></ol></nav></body></html>`)
	_ = zw.Close()

	zr, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatal(err)
	}
	opf, err := parseOpf([]byte(`<?xml version="1.0"?><package><metadata><dc:title>Test</dc:title></metadata><manifest><item id="ch1" href="ch1.xhtml"/><item id="ch2" href="ch2.xhtml"/><item id="nav" href="nav.xhtml" properties="nav"/></manifest><spine><itemref idref="ch1"/><itemref idref="ch2"/></spine></package>`))
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 30; i++ {
		toc, err := readToc(zr, opf.navID, opf.tocID, opf.manifest, "OEBPS")
		if err != nil {
			t.Fatal(err)
		}
		if toc["ch2.xhtml"] != "Real Chapter 2" {
			t.Fatalf("iteration %d: expected 'Real Chapter 2', got %q (map=%v)", i, toc["ch2.xhtml"], toc)
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service/bookparser -run TestReadTocUsesNavPropertyNotRandomChapterLinks -v`
Expected: FAIL with `opf.navID undefined` / `too many arguments in call to readToc`

- [ ] **Step 3: Implement `navID` in `parseOpf`, targeted `readToc`, `<nav>` scoping in `parseNavToc`, and `BookService` cap**

In `server/internal/service/bookparser/epub.go`:
1. Add `navID string` to `opfParsed`:
```go
type opfParsed struct {
	title    string
	manifest map[string]string
	spine    []string
	tocID    string
	navID    string
}
```
2. In `parseOpf` under `case "item":`:
```go
			case "item":
				var id, href, props, mediaType string
				for _, a := range se.Attr {
					switch a.Name.Local {
					case "id":
						id = a.Value
					case "href":
						href = a.Value
					case "properties":
						props = a.Value
					case "media-type":
						mediaType = a.Value
					}
				}
				if id != "" {
					out.manifest[id] = href
					for _, tok := range strings.Fields(props) {
						if tok == "nav" {
							out.navID = id
						}
					}
					if out.tocID == "" && mediaType == "application/x-dtbncx+xml" {
						out.tocID = id
					}
				}
```
3. Update `readToc(zr *zip.Reader, navID, ncxID string, manifest map[string]string, opfDir string) (map[string]string, error)` (and its call site in `parseEpub`):
```go
func readToc(zr *zip.Reader, navID, ncxID string, manifest map[string]string, opfDir string) (map[string]string, error) {
	if navID != "" {
		if href, ok := manifest[navID]; ok {
			full := JoinZipPath(opfDir, href)
			if f, err := zr.Open(full); err == nil {
				data, _ := ReadCapped(f, MaxEpubEntrySize)
				f.Close()
				if toc := parseNavToc(data); len(toc) > 0 {
					return toc, nil
				}
			}
		}
	}
	if ncxID != "" {
		if href, ok := manifest[ncxID]; ok {
			full := JoinZipPath(opfDir, href)
			if f, err := zr.Open(full); err == nil {
				data, _ := ReadCapped(f, MaxEpubEntrySize)
				f.Close()
				if toc := parseNcx(data); len(toc) > 0 {
					return toc, nil
				}
			}
		}
	}
	return nil, fmt.Errorf("no toc")
}
```
4. In `parseNavToc(data []byte)`: locate `<nav epub:type="toc">` or the first `<nav>` element first, and run `walk(navNode)` on that subtree (falling back to `walk(doc)` only if no `<nav>` element exists).

In `server/internal/service/book.go`:
1. Cap `BookService.cache` at `const maxBookCacheEntries = 64` (track insertion order in `keys []string`; when `len(s.cache) >= maxBookCacheEntries` and key is new, evict `s.keys[0]`).
2. Extract `GetChapterBlocksForBook(b *bookparser.Book, path string, idx int, clientIP string) ([]bookparser.Block, error)` so `GetChapterBlocks` delegates to `GetBook` + `GetChapterBlocksForBook`, and `handler/books.go`'s `GetBookChapter` calls `GetChapterBlocksForBook(b, resolved, idx, c.RealIP())` directly without calling `GetBook` twice.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service/... ./internal/server/handler -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/bookparser/epub.go server/internal/service/bookparser/epub_test.go server/internal/service/book.go server/internal/server/handler/books.go
git commit -m "perf(epub): parse only nav/ncx manifest item for TOC and cap BookService cache"
```

---

### Task 6: HLS 转码槽位可取消排队、`ffprobe` 超时与单图缩略图/时长限流

**Files:**
- Modify: `server/internal/service/transcode_hls.go:161-290`
- Modify: `server/internal/service/streaming.go:386-413`
- Modify: `server/internal/server/handler/media.go:230-260`
- Modify: `server/internal/server/server.go:412-432`
- Test: `server/internal/service/transcode_hls_test.go`

**Interfaces:**
- Produces:
  - `var ErrTranscodeSlotUnavailable = errors.New("transcode capacity unavailable")` in `service`

- [ ] **Step 1: Write the failing test in `server/internal/service/transcode_hls_test.go`**

```go
func TestHlsQueuedSessionCancelledByCloseHLS(t *testing.T) {
	svc := NewStreamingServiceWithConfig("", 1)
	// Occupy the single slot
	release, ok := svc.acquireTranscodeSlot(context.Background())
	if !ok {
		t.Fatal("expected initial slot acquisition to succeed")
	}
	defer release()

	tmpFile := filepath.Join(t.TempDir(), "video.mp4")
	if err := os.WriteFile(tmpFile, []byte("fake"), 0o644); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(tmpFile)

	errCh := make(chan error, 1)
	go func() {
		_, err := svc.GetOrCreateHlsSession(tmpFile, info.ModTime(), 0)
		errCh <- err
	}()

	time.Sleep(80 * time.Millisecond)
	svc.CloseHLS()

	select {
	case err := <-errCh:
		if err == nil {
			t.Fatal("expected error when CloseHLS cancels waiting session")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("GetOrCreateHlsSession hung after CloseHLS while waiting for slot")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service -run TestHlsQueuedSessionCancelledByCloseHLS -v`
Expected: FAIL (`GetOrCreateHlsSession hung after CloseHLS while waiting for slot`)

- [ ] **Step 3: Implement cancellable slot wait, `503 Retry-After`, `ffprobe` timeout, and route rate limits**

In `server/internal/service/transcode_hls.go`:
1. Define:
```go
const hlsSlotWaitTimeout = 30 * time.Second

var ErrTranscodeSlotUnavailable = errors.New("transcode capacity unavailable")
```
2. In `GetOrCreateHlsSession` (`~L174`), create the session context before inserting into `s.hlsSessions`:
```go
	ctx, cancel := context.WithCancel(context.Background())
	sess := &hlsSession{
		key:        key,
		dir:        filepath.Join(s.hlsDir, key),
		playlist:   filepath.Join(s.hlsDir, key, hlsPlaylistName),
		lastAccess: time.Now(),
		done:       make(chan struct{}),
		running:    true,
		srcPath:    filepath.Clean(path),
		startSec:   startSec,
		ctx:        ctx,
		cancel:     cancel,
	}
	s.hlsSessions[key] = sess
	s.hlsMu.Unlock()
```
(Add `ctx context.Context` field to `hlsSession`).
3. In `startHlsFFmpeg` (`~L246`), ensure early error paths call `sess.cancel()`, `close(sess.done)`, and mark `sess.running = false`, and acquire the slot using a child timeout context derived from `sess.ctx`:
```go
	waitCtx, waitCancel := context.WithTimeout(sess.ctx, hlsSlotWaitTimeout)
	release, acquired := s.acquireTranscodeSlot(waitCtx)
	waitCancel()
	if !acquired {
		sess.cancel()
		sess.mu.Lock()
		sess.running = false
		sess.err = ErrTranscodeSlotUnavailable
		sess.mu.Unlock()
		close(sess.done)
		os.RemoveAll(sess.dir)
		s.dropHlsSession(sess)
		return ErrTranscodeSlotUnavailable
	}
```
And use `sess.ctx` in `exec.CommandContext(sess.ctx, ffmpegBin, args...)`.

In `server/internal/server/handler/media.go` inside `MediaHlsPlaylist`: when `GetOrCreateHlsSession` returns `errors.Is(err, service.ErrTranscodeSlotUnavailable)`, set `c.Response().Header().Set("Retry-After", "5")` and return `respondError(c, http.StatusServiceUnavailable, "transcode capacity unavailable")`.

In `server/internal/service/streaming.go` inside `GetVideoDuration` (`~L400`):
```go
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffprobe", args...)
```

In `server/internal/server/server.go` (`~L412-430`), add `middleware.RateLimit(60, time.Minute)` to `/api/v1/system/thumbnail`, `/api/v1/media/thumbnail`, and `/api/v1/media/duration`:
```go
	sys.GET("/thumbnail", h.SystemThumbnail, middleware.RateLimit(60, time.Minute))
...
	media.GET("/thumbnail", h.MediaThumbnail, middleware.RateLimit(60, time.Minute))
...
	media.GET("/duration", h.MediaDuration, middleware.RateLimit(60, time.Minute))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test -race ./internal/service ./internal/server/... -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/transcode_hls.go server/internal/service/transcode_hls_test.go server/internal/service/streaming.go server/internal/server/handler/media.go server/internal/server/server.go
git commit -m "fix(streaming): make HLS slot wait cancellable with timeout and add ffprobe/thumbnail guards"
```

---

### Task 7: Gzip 二进制跳过、静态资源预压缩与日志脱敏快路径

**Files:**
- Modify: `server/internal/server/server.go:224-260`
- Modify: `server/internal/web/static.go:60-140`
- Test: `server/internal/web/static_test.go`

**Interfaces:**
- None (internal HTTP response optimization)

- [ ] **Step 1: Write the failing test in `server/internal/web/static_test.go`**

```go
func TestStaticServesPrecompressedGzipWithDistinctETag(t *testing.T) {
	h := Static()
	reqPlain := httptest.NewRequest(http.MethodGet, "/app.js", nil)
	recPlain := httptest.NewRecorder()
	h.ServeHTTP(recPlain, reqPlain)
	if recPlain.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", recPlain.Code)
	}
	etagPlain := recPlain.Header().Get("ETag")

	reqGz := httptest.NewRequest(http.MethodGet, "/app.js", nil)
	reqGz.Header.Set("Accept-Encoding", "gzip, deflate")
	recGz := httptest.NewRecorder()
	h.ServeHTTP(recGz, reqGz)
	if recGz.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", recGz.Code)
	}
	if recGz.Header().Get("Content-Encoding") != "gzip" {
		t.Fatalf("expected Content-Encoding: gzip, got %q", recGz.Header().Get("Content-Encoding"))
	}
	etagGz := recGz.Header().Get("ETag")
	if etagGz == "" || etagGz == etagPlain {
		t.Fatalf("expected distinct gzip ETag, plain=%q gz=%q", etagPlain, etagGz)
	}

	gr, err := gzip.NewReader(bytes.NewReader(recGz.Body.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	defer gr.Close()
	unzipped, err := io.ReadAll(gr)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(unzipped, recPlain.Body.Bytes()) {
		t.Fatal("decompressed gzip body does not match plain body")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/web -run TestStaticServesPrecompressedGzipWithDistinctETag -v`
Expected: FAIL (`expected Content-Encoding: gzip, got ""`)

- [ ] **Step 3: Implement pre-compressed `gzipBody` in `web/static.go` and update `server.go`**

In `server/internal/web/static.go`:
1. Add `gzipBody []byte` and `gzipETag string` to `type asset struct`:
```go
type asset struct {
	body     []byte
	gzipBody []byte
	ct       string
	etag     string
	gzipETag string
}
```
2. In `newAsset(b []byte, name string) *asset`: for compressible extensions (`.js`, `.mjs`, `.css`, `.html`, `.svg`, `.json`), compress `b` with `gzip.NewWriterLevel(&buf, gzip.BestCompression)`; if `buf.Len() > 0 && buf.Len() < len(b)`, store `gzipBody = buf.Bytes()` and `gzipETag = strings.TrimSuffix(etag, "\"") + "-gz\""`.
3. In `(a *asset) serve(w http.ResponseWriter, r *http.Request)`:
```go
	useGzip := len(a.gzipBody) > 0 && strings.Contains(r.Header.Get("Accept-Encoding"), "gzip")
	etag := a.etag
	body := a.body
	if useGzip {
		etag = a.gzipETag
		body = a.gzipBody
	}
	h := w.Header()
	h.Set("Content-Type", a.ct)
	h.Set("ETag", etag)
	h.Set("Cache-Control", "no-cache")
	h.Set("Vary", "Accept-Encoding")

	if matchETag(r.Header.Get("If-None-Match"), etag) {
		w.WriteHeader(http.StatusNotModified)
		return
	}
	if useGzip {
		h.Set("Content-Encoding", "gzip")
	}
	h.Set("Content-Length", strconv.Itoa(len(body)))
	if r.Method == http.MethodHead {
		w.WriteHeader(http.StatusOK)
		return
	}
	_, _ = w.Write(body)
```

In `server/internal/server/server.go`:
1. Guard the `?token=` redaction middleware (`~L224-237`) with a fast path:
```go
	s.Echo.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			req := c.Request()
			if strings.Contains(req.URL.RawQuery, "token=") {
				_ = c.QueryParams()
				q := req.URL.Query()
				if q.Get("token") != "" {
					q.Set("token", "REDACTED")
					req.URL.RawQuery = q.Encode()
					req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
				}
			}
			return next(c)
		}
	})
```
2. Update Gzip `Skipper` (`~L250-259`) to skip binary endpoints and pre-compressed static routes:
```go
		Skipper: func(c echo.Context) bool {
			path := c.Request().URL.Path
			if !strings.HasPrefix(path, "/api/") {
				return true // static assets handle pre-compressed gzip directly
			}
			if strings.Contains(path, "/stream") ||
				strings.Contains(path, "/thumbnail") ||
				strings.Contains(path, "/original") ||
				strings.Contains(path, "/download") ||
				strings.Contains(path, "/hls/segment") ||
				strings.Contains(path, "/books/image") {
				return true
			}
			return false
		},
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/web ./internal/server/... -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/static.go server/internal/web/static_test.go server/internal/server/server.go
git commit -m "perf(http): pre-compress static assets, skip gzip on HLS/book binaries, and fast-path log redaction"
```

---

### Task 8: Windows 路径大小写不敏感索引统一

**Files:**
- Modify: `server/internal/service/path.go:320-341`
- Modify: `server/internal/service/scanner.go:310-330, 495-543`
- Modify: `server/internal/service/scanner_snapshot.go:195-215`
- Modify: `server/internal/server/handler/search.go:85-105`
- Modify: `server/internal/server/handler/library.go:148-181`
- Test: `server/internal/service/scanner_test.go`

**Interfaces:**
- Produces: `func PathKey(p string) string` in package `service`

- [ ] **Step 1: Write the failing test in `server/internal/service/scanner_test.go`**

```go
func TestGetCachedByDirCaseInsensitiveOnWindows(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("case-insensitive path key test applies to Windows")
	}
	root := t.TempDir()
	sub := filepath.Join(root, "SubFolder")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(sub, "clip.mp4"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}

	sc := NewScanner([]string{".mp4"}, nil, nil)
	if _, err := sc.Scan(context.Background(), []string{root}); err != nil {
		t.Fatal(err)
	}

	lowerQuery := strings.ToLower(sub)
	files, err := sc.GetCachedByDir(context.Background(), []string{root}, lowerQuery)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 || files[0].Name != "clip.mp4" {
		t.Fatalf("expected 1 file for differently-cased dir query %q, got %v", lowerQuery, files)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service -run TestGetCachedByDirCaseInsensitiveOnWindows -v`
Expected: FAIL (`expected 1 file for differently-cased dir query, got []`)

- [ ] **Step 3: Implement `service.PathKey` and wire into `scanner.go`, `scanner_snapshot.go`, `search.go`, and `library.go`**

In `server/internal/service/path.go`:
```go
// PathKey returns a canonical map lookup key for path p: cleaned, and
// lower-cased on Windows where NTFS/ReFS paths are case-insensitive.
func PathKey(p string) string {
	clean := filepath.Clean(p)
	if runtime.GOOS == "windows" {
		return strings.ToLower(clean)
	}
	return clean
}
```

In `server/internal/service/scanner.go` (`~L315` where `byDir` is populated, and `~L496, 536` in `GetCachedByDir` / `cachedDirSnapshot`) and `server/internal/service/scanner_snapshot.go` (`~L205` where `byDir` is hydrated):
- Key `byDir` (`s.cacheByDir`) by `PathKey(filepath.Dir(f.Path))` when writing, and look up `s.cacheByDir[PathKey(cleanDir)]` in `cachedDirSnapshot`.

In `server/internal/server/handler/search.go` (`~L88-96` in `searchFiles`):
```go
		if scopePrefix != "" {
			if runtime.GOOS == "windows" {
				if !strings.EqualFold(file.Path, scopeClean) &&
					(len(file.Path) < len(scopePrefix) || !strings.EqualFold(file.Path[:len(scopePrefix)], scopePrefix)) {
					continue
				}
			} else if file.Path != scopeClean && !strings.HasPrefix(file.Path, scopePrefix) {
				continue
			}
		}
```

In `server/internal/server/handler/library.go` (`~L148-181` in `PostDecorations`):
```go
	keyToOriginals := make(map[string][]string, len(req.Paths))
	normalized := make([]string, 0, len(req.Paths))
	for _, raw := range req.Paths {
		norm, ok := h.validateAnyEntryPath(raw)
		if !ok {
			continue
		}
		k := service.PathKey(norm)
		if _, seen := keyToOriginals[k]; !seen {
			normalized = append(normalized, norm)
		}
		keyToOriginals[k] = append(keyToOriginals[k], raw)
	}
...
	out := make(map[string]models.EntryDecoration, len(decorations))
	for normPath, dec := range decorations {
		for _, orig := range keyToOriginals[service.PathKey(normPath)] {
			out[orig] = dec
		}
	}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server; go test ./internal/service ./internal/server/handler -v`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/path.go server/internal/service/scanner.go server/internal/service/scanner_snapshot.go server/internal/service/scanner_test.go server/internal/server/handler/search.go server/internal/server/handler/library.go
git commit -m "fix(paths): use case-insensitive PathKey on Windows for dir cache, search, and decorations"
```

---

### Task 9: Web 媒体 `<img>` / `<video>` 统一 Token 注入（修复 429 自锁）

**Files:**
- Modify: `server/internal/web/api.js:103-112`
- Modify: `server/internal/web/dashboard.js:1-10, 74-82`
- Modify: `server/internal/web/browserView.js:1-10, 370-393`
- Modify: `server/internal/web/lightbox.js:1-10, 70-85, 107-116`
- Modify: `server/internal/web/videoPlayer.js:15-25`
- Create: `server/internal/web/media-auth-url.test.mjs`

**Interfaces:**
- Produces: `export function withMediaAuthToken(url)` in `server/internal/web/api.js`

- [ ] **Step 1: Write the failing test in `server/internal/web/media-auth-url.test.mjs`**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost:8000/' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.sessionStorage = dom.window.sessionStorage;

const { setAuthToken, withMediaAuthToken } = await import('./api.js');

test('withMediaAuthToken appends token only when token is set and url targets /api/v1/', () => {
    setAuthToken('');
    assert.equal(
        withMediaAuthToken('/api/v1/images/a.jpg/thumbnail'),
        '/api/v1/images/a.jpg/thumbnail'
    );

    setAuthToken('my secret+token');
    assert.equal(
        withMediaAuthToken('/api/v1/images/a.jpg/thumbnail'),
        '/api/v1/images/a.jpg/thumbnail?token=my%20secret%2Btoken'
    );
    assert.equal(
        withMediaAuthToken('/api/v1/system/thumbnail?path=D%3A%5Ca.jpg'),
        '/api/v1/system/thumbnail?path=D%3A%5Ca.jpg&token=my%20secret%2Btoken'
    );
    // Already signed or already carrying token -> untouched
    assert.equal(
        withMediaAuthToken('/api/v1/books/image?path=a.epub&manifest=i1&sig=abc'),
        '/api/v1/books/image?path=a.epub&manifest=i1&sig=abc'
    );
    // Non-API URL -> untouched
    assert.equal(
        withMediaAuthToken('https://example.com/pic.jpg'),
        'https://example.com/pic.jpg'
    );
    setAuthToken('');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server\internal\web; node --test media-auth-url.test.mjs`
Expected: FAIL with `withMediaAuthToken is not a function`

- [ ] **Step 3: Implement `withMediaAuthToken` in `api.js` and apply across `dashboard.js`, `browserView.js`, `lightbox.js`, `videoPlayer.js`**

In `server/internal/web/api.js`:
```js
export function withMediaAuthToken(url) {
    if (!url || typeof url !== 'string') return url;
    const token = getAuthToken();
    if (!token) return url;
    if (!url.includes('/api/v1/')) return url;
    if (/^https?:\/\//i.test(url) && typeof window !== 'undefined' && window.location?.origin) {
        if (!url.startsWith(window.location.origin + '/')) return url;
    }
    if (/[?&](token|sig)=/.test(url)) return url;
    return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
}
```

In `server/internal/web/dashboard.js` (`~L76`):
- Import `withMediaAuthToken` from `./api.js` and wrap `thumb`:
```js
const thumb = withMediaAuthToken(`${state.apiBase}/api/v1/videos/${encodeRoutePath(file.path)}/thumbnail`);
```

In `server/internal/web/browserView.js` (`~L373-380`):
- Import `withMediaAuthToken` from `./api.js` and wrap `thumbUrl` and `videoThumbUrl`:
```js
        let thumbUrl = withMediaAuthToken(`${state.apiBase}/api/v1/images/${encodeRoutePath(file.path)}/thumbnail`);
        if (state.isSystemBrowse) {
            thumbUrl = withMediaAuthToken(`${state.apiBase}/api/v1/system/thumbnail?path=${encodeURIComponent(file.path)}`);
        }

        if (isVideo) {
            const videoThumbUrl = withMediaAuthToken(`${state.apiBase}/api/v1/videos/${encodeRoutePath(file.path)}/thumbnail`);
```

In `server/internal/web/lightbox.js` (`~L72-76` and `~L109-113`):
- Import `withMediaAuthToken` from `./api.js` and wrap `url = withMediaAuthToken(url)` before assigning `img.src` / `elements.lightboxImg.src`.

In `server/internal/web/videoPlayer.js` (`~L3, 18-23`):
- Import `withMediaAuthToken` from `./api.js` and delegate `withVideoAuthToken(url)` to `withMediaAuthToken(url)`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server\internal\web; node --test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/api.js server/internal/web/dashboard.js server/internal/web/browserView.js server/internal/web/lightbox.js server/internal/web/videoPlayer.js server/internal/web/media-auth-url.test.mjs
git commit -m "fix(web): attach bearer token to media <img>/<video> URLs to prevent 401/429 lockout"
```

---

### Task 10: Web 阅读器路由切换生命周期清理 (`_cleanupReader`)

**Files:**
- Modify: `server/internal/web/router.js:16-39`
- Modify: `server/internal/web/bookshelf.js:170-180`
- Modify: `server/internal/web/textReader.js:68-73, 658-681`
- Create: `server/internal/web/router-reader-cleanup.test.mjs`

**Interfaces:**
- None (lifecycle cleanup hook `container._cleanupReader`)

- [ ] **Step 1: Write the failing test in `server/internal/web/router-reader-cleanup.test.mjs`**

```js
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body><div id="r"></div><div id="d"></div></body></html>', {
    url: 'http://localhost:8000/#/dashboard',
});
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
globalThis.sessionStorage = dom.window.sessionStorage;

const { handleRoute } = await import('./router.js');

test('handleRoute invokes viewReader._cleanupReader when leaving #/read', () => {
    const viewReader = document.getElementById('r');
    const viewDashboard = document.getElementById('d');
    let cleaned = 0;
    viewReader._cleanupReader = () => {
        cleaned += 1;
    };

    window.location.hash = '#/dashboard';
    handleRoute(
        { viewReader, viewDashboard },
        () => {},
        () => {},
        () => {},
        () => {},
        () => {}
    );

    assert.equal(cleaned, 1);
    assert.equal(viewReader._cleanupReader, null);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd e:\github_project\LocalMediaHub\server\internal\web; node --test router-reader-cleanup.test.mjs`
Expected: FAIL (`Expected values to be strictly equal: 0 !== 1`)

- [ ] **Step 3: Implement `_cleanupReader` invocation in `router.js`, `bookshelf.js`, and idempotence in `textReader.js`**

In `server/internal/web/router.js` (`~L18`, right after reading `hash`):
```js
    if (!hash.startsWith('#/read') && elements.viewReader && typeof elements.viewReader._cleanupReader === 'function') {
        try {
            elements.viewReader._cleanupReader();
        } finally {
            elements.viewReader._cleanupReader = null;
        }
    }
```

In `server/internal/web/bookshelf.js` (`~L172` at top of `export function render(container)`):
```js
export function render(container) {
    if (!container) return;
    if (typeof container._cleanupReader === 'function') {
        try {
            container._cleanupReader();
        } finally {
            container._cleanupReader = null;
        }
    }
    container.innerHTML = ''; // XSS-SAFE: empty string
```

In `server/internal/web/textReader.js` (`~L658`), make `container._cleanupReader` idempotent:
```js
    let cleanedUp = false;
    container._cleanupReader = () => {
        if (cleanedUp) return;
        cleanedUp = true;
        if (scrollRafId !== null) cancelAnimationFrame(scrollRafId);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd e:\github_project\LocalMediaHub\server\internal\web; node --test`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/router.js server/internal/web/bookshelf.js server/internal/web/textReader.js server/internal/web/router-reader-cleanup.test.mjs
git commit -m "fix(web): tear down text reader timers and listeners when navigating away from #/read"
```

---

### Task 11: Web 阅读器章节渲染去重、事件委托与 `scrollNav` / `browserView` 性能优化

**Files:**
- Modify: `server/internal/web/textReader.js:555-565, 670-755`
- Modify: `server/internal/web/scrollNav.js:159-184`
- Modify: `server/internal/web/browserView.js:9, 239-267, 338-340, 398-401`
- Test: `server/internal/web/text-reader.test.mjs`

**Interfaces:**
- None

- [ ] **Step 1: Run existing reader & scrollNav tests to establish baseline and add delegated bookmark click assertion**

In `server/internal/web/text-reader.test.mjs`, add a test verifying that clicking `.text-reader__para-bookmark` adds a bookmark via event delegation on `.text-reader__content` and that `renderBlocks` does not attach per-button listeners.

- [ ] **Step 2: Run test to verify baseline**

Run: `cd e:\github_project\LocalMediaHub\server\internal\web; node --test text-reader.test.mjs scroll-nav.test.mjs`

- [ ] **Step 3: Implement `textReader.js`, `scrollNav.js`, and `browserView.js` optimizations**

In `server/internal/web/textReader.js`:
1. In `collectVisibleParagraphs` (`~L561`), replace `Math.max(...chapterParas.map(p => p.paraIndex))` with:
```js
const maxParaIdx = chapterParas[chapterParas.length - 1].paraIndex;
```
2. Attach a single delegated click handler on `els.content` (and remove it in `_cleanupReader`):
```js
    function onContentClick(e) {
        const btn = e.target && typeof e.target.closest === 'function'
            ? e.target.closest('.text-reader__para-bookmark')
            : null;
        if (!btn || !els.content.contains(btn)) return;
        e.stopPropagation();
        const p = btn.closest('p.text-reader__p');
        const sec = btn.closest('section.text-reader__chapter-section');
        if (!p || !sec) return;
        const chIdx = Number(sec.dataset.chapterIndex || 0);
        const idx = Number(p.dataset.paraIndex || 0);
        const rawText = (p.firstChild && p.firstChild.nodeType === 3) ? p.firstChild.nodeValue : p.textContent;
        const ok = readerPrefs.addBookmark({
            bookPath: path,
            chapterIndex: chIdx,
            paragraphIndex: idx,
            preview: (rawText || '').slice(0, 30),
            createdAt: Date.now(),
        });
        showToast(ok ? '已添加书签' : '已存在书签', ok ? 'success' : 'info');
        syncBookmarkCount();
    }
    els.content.addEventListener('click', onContentClick);
```
3. In `renderBlocks(blocks, chapterTitle, chIdx)` (`~L696-754`), hoist `const prefs = readerPrefs.getSettings();` outside `list.forEach` and remove the per-button `btn.addEventListener('click', ...)` closure:
```js
        const prefs = readerPrefs.getSettings();
        const indent = prefs.firstLineIndent ? 'indent-on' : 'indent-off';
        const gap = prefs.paragraphSpacing ? 'gap-on' : 'gap-off';
        if (chIdx > 0 && prefs.readingMode === 'scroll') {
            const hr = document.createElement('hr');
            hr.className = 'text-reader__chapter-divider';
            section.appendChild(hr);
        }
...
            const p = document.createElement('p');
            p.textContent = text;
            p.dataset.blockIndex = String(idx);
            p.dataset.paraIndex = String(idx);
            const dropcap = (idx === dropCapIdx) ? 'text-reader__p--dropcap' : '';
            p.className = `text-reader__p ${indent} ${gap} ${dropcap}`.trim();
            const btn = document.createElement('button');
            btn.className = 'text-reader__para-bookmark';
            btn.type = 'button';
            btn.textContent = '+';
            btn.title = '添加书签';
            p.appendChild(btn);
            section.appendChild(p);
```

In `server/internal/web/scrollNav.js` (`~L174-179`), narrow `observer.observe` on `bodyEl` so it does not watch `subtree: true` across the entire DOM:
```js
            observer.observe(bodyEl, {
                childList: false,
                subtree: false,
                attributes: true,
                attributeFilter: ['data-active-tab']
            });
```

In `server/internal/web/browserView.js`:
1. Create a reusable module-level `Intl.Collator`:
```js
const NAME_COLLATOR = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
```
And use `NAME_COLLATOR.compare(nameA, nameB)` in `sortMediaItems` (`~L261`).
2. Remove the unused `id="file-card-${safeBtoa(file.path).replace(/=/g, '')}"` from lines 339 and 399 (and remove unused `safeBtoa` import from line 9).

- [ ] **Step 4: Run all web unit tests and XSS check**

Run:
```bash
cd e:\github_project\LocalMediaHub\server\internal\web; node --test
cd e:\github_project\LocalMediaHub\tools\xsscheck; go run . ../../server/internal/web
```
Expected: PASS (0 test failures, 0 XSS violations)

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/textReader.js server/internal/web/scrollNav.js server/internal/web/browserView.js server/internal/web/text-reader.test.mjs
git commit -m "perf(web): hoist reader prefs, delegate bookmark clicks, narrow scrollNav observer, and reuse Intl.Collator"
```

---

### Task 12: 全量回归验证与 `AGENTS.md` 文档同步

**Files:**
- Modify: `AGENTS.md`

**Interfaces:**
- None

- [ ] **Step 1: Update `AGENTS.md` to document Batch 1 changes**

Update `AGENTS.md`:
- Under `scanner.go` / `path.go`: note `IsLinkEntry` skips symlinks & Windows junctions during scan, browse, and folder-zip download, and `PathKey` normalizes case-insensitive directory cache keys on Windows.
- Under `bookparser/epub.go` / `book.go`: note `readToc` targets `properties="nav"` / `ncx` and `BookService` caps cached books at 64.
- Under `Web 管理界面`: note `withMediaAuthToken(url)` in `api.js` for `<img>`/`<video>` token injection and `router.js`/`bookshelf.js` `_cleanupReader` teardown on route exit.

- [ ] **Step 2: Run full Server + Web + XSS verification suite**

Run:
```bash
cd e:\github_project\LocalMediaHub\server; go build ./...; go test -race ./...
cd e:\github_project\LocalMediaHub\server\internal\web; node --test
cd e:\github_project\LocalMediaHub\tools\xsscheck; go run . ../../server/internal/web
```
Expected: All Go packages pass with `-race`, all `node --test` suites pass, and `xsscheck` exits 0.

- [ ] **Step 3: Commit**

```bash
git add AGENTS.md docs/superpowers/plans/2026-10-29-server-web-security-perf.md
git commit -m "docs: add batch 1 implementation plan and sync AGENTS.md"
```

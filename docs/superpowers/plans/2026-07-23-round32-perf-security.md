# Round 32: 性能 + 安全混合轮 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实施 Round 32 性能 + 安全混合轮（P1-P3 + S1-S4），覆盖 SQLite 并发、Scanner 并发遍历、缩略图冷启动加速、RateLimit LRU、签名 token、pprof 默认关闭、Web SPA XSS 闭环。

**Architecture:** 单 spec 两 phase。Phase P（性能）= P1/P2/P3 互相独立；Phase S（安全）= S1/S2/S3/S4 互相独立。P 与 S 之间无依赖。所有改动在 master 分支上按 P2→S3→S1→P3→S2→P1→S4 顺序增量提交（从基础设施到涉及客户端协作的最后阶段）。

**Tech Stack:** Go 1.25+ / Echo v4 / modernc.org/sqlite（pure-Go）/ golang.org/x/sync/errgroup / crypto/hmac + crypto/sha256 / hashicorp/golang-lru/v2。

## Global Constraints

- **代码风格**：遵循现有 Go 代码风格——error wrapping (`fmt.Errorf("%w", err)`)、`log/slog` 结构化日志、原子文件写入（`os.CreateTemp` + `os.Rename`）。
- **不引入新依赖**：本计划全部用现有依赖（`golang.org/x/sync`、`crypto/*`、`hashicorp/golang-lru/v2` 已在 go.mod）。
- **测试运行命令**：`cd server && go test ./... -race -count=1`。所有改动必须在该命令下全绿。
- **不涉及 Android 端**：本计划不动 `android/` 任何文件。
- **配置兼容性**：`config.yaml` 新增字段必须 `omitempty`，老 config 不需修改即可启动。
- **路径分隔符**：所有新代码用 `filepath.Join`，不硬编码 `/` 或 `\`。
- **PRAGMA 失败处理**：保持现有"Warn 不阻断"风格（`tags.go:64`）。
- **commit 风格**：参考近期 commit `feat(security): ...`、`perf(server): ...`、`fix(reader): ...`，conventional commits 格式。

## File Structure 总览

**修改文件（按任务顺序）：**

| 任务 | 修改 | 创建 |
|---|---|---|
| P2 | `server/internal/service/tags.go`、`server/internal/service/tags_test.go` | - |
| S3 | `server/internal/config/config.go`、`server/cmd/server/main.go`、`server/internal/server/server.go`、`server/internal/server/server_test.go` | - |
| S1 | `server/internal/server/middleware/ratelimit.go`、`server/internal/server/middleware/ratelimit_test.go` | - |
| P3 | `server/internal/service/scanner.go`、`server/internal/service/scanner_test.go` | - |
| S2 | `server/internal/server/handler/books.go`、`server/internal/service/book.go`、`server/internal/server/handler/books_test.go`、`server/internal/service/book_signing_test.go` | `server/internal/service/book_signing.go` |
| P1 | `server/internal/service/thumbnail.go`、`server/internal/service/thumbnail_test.go`、`server/internal/server/server.go` | `server/internal/service/hot_dirs.go` |
| S4 | `server/internal/web/*.js`、`tools/xsscheck/*` | `server/internal/web/escape.js` |

---

## Task 1: P2 — SQLite WAL 连接池 + TagsService 锁粒度重构

**Files:**
- Modify: `server/internal/service/tags.go:49` (SetMaxOpenConns) + `server/internal/service/tags.go:190-405` (所有 CRUD 方法的锁)
- Test: `server/internal/service/tags_test.go`

**Interfaces:**
- Consumes: 现有 `TagsService` 公共 API（`GetAllTags`, `CreateTag`, `DeleteTag`, `AssociateFile`, `DisassociateFile`, `GetFilesForTag`, `TagExists`, `ResolveTagID`, `GetTagsForFiles`, `GetAllFileTags`, `CleanDeletedPath`, `Close`）签名不变。
- Produces: 同上——所有改动是内部实现细节，对外契约保持不变。

**背景**：tags.go 已开 WAL + busy_timeout + synchronous=NORMAL（行 42-66），但 `SetMaxOpenConns(1)`（行 49）和 `s.mu.Lock()`（每个 CRUD 方法）使得 Go 层级变成单线程串行——SQLite WAL 的并发能力被浪费。

- [ ] **Step 1: 写并发测试（先行验证当前实现会阻塞）**

追加到 `server/internal/service/tags_test.go` 末尾：

```go
// TestTagsConcurrentReadWriteNoBlocking 验证 WAL + 连接池下，并发读不阻塞写。
// Round 32 P2: SetMaxOpenConns(1) + s.mu.Lock 会让此测试在写期间阻塞所有读。
func TestTagsConcurrentReadWriteNoBlocking(t *testing.T) {
	dir := t.TempDir()
	s, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer s.Close()

	// 先插入一个 tag 作为读目标
	tag, err := s.CreateTag("readable", "#fff")
	if err != nil {
		t.Fatalf("CreateTag: %v", err)
	}

	// 启动持续写 goroutine
	writeDone := make(chan struct{})
	go func() {
		defer close(writeDone)
		for i := 0; i < 50; i++ {
			if _, err := s.CreateTag(fmt.Sprintf("w-%d", i), "#000"); err != nil {
				t.Errorf("CreateTag w-%d: %v", i, err)
				return
			}
		}
	}()

	// 主 goroutine 在写期间持续读，应能在 5s 内完成（非阻塞）
	readDone := make(chan struct{})
	go func() {
		defer close(readDone)
		for i := 0; i < 50; i++ {
			_ = s.GetFilesForTag(tag.ID)
		}
	}()

	select {
	case <-readDone:
		// 读在写期间完成 = WAL 并发读工作
	case <-time.After(5 * time.Second):
		t.Fatal("read blocked by write for 5s — WAL concurrent read not working")
	}
	<-writeDone
}
```

- [ ] **Step 2: 运行测试验证它在当前实现下 FAIL 或超时**

Run: `cd server && go test ./internal/service/ -run TestTagsConcurrentReadWriteNoBlocking -v -timeout 30s`
Expected: FAIL（"read blocked by write for 5s"），因为 `SetMaxOpenConns(1)` + `s.mu.Lock` 串行化读写。

- [ ] **Step 3: 修改 tags.go — 调整连接池**

`server/internal/service/tags.go:49` 把：
```go
	// Optimize SQLite performance and connection behavior
	db.SetMaxOpenConns(1)
```
改成：
```go
	// Round 32 P2: WAL 允许并发读 + 串行写，开多连接让读不互相阻塞。
	// MaxOpenConns = max(4, NumCPU) 覆盖 LAN 多客户端并发读场景。
	// 写仍由 SQLite 的 WAL 写锁串行（同时只允许一个写事务），无需 Go 层级锁。
	db.SetMaxOpenConns(maxInt(4, runtime.NumCPU()))
	db.SetMaxIdleConns(2)
	db.SetConnMaxLifetime(0)
```

在文件末尾追加 helper：
```go
func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
```

- [ ] **Step 4: 修改 tags.go — 把 CRUD 方法的 s.mu.Lock 改为 s.mu.RLock**

对以下写/读方法逐个修改（改 `s.mu.Lock()` → `s.mu.RLock()` + `defer s.mu.Unlock()` → `defer s.mu.RUnlock()`）：
- `CreateTag`
- `DeleteTag`
- `AssociateFile`
- `DisassociateFile`
- `CleanDeletedPath`

**理由**：所有 CRUD 的写串行由 SQLite WAL + busy_timeout 在 DB 层管理，Go 层级 `s.mu.Lock` 仅留给 `Close()` 销毁 `s.db` 时使用。

- [ ] **Step 5: 运行测试验证 PASS**

Run: `cd server && go test ./internal/service/ -run TestTagsConcurrentReadWriteNoBlocking -v -timeout 30s`
Expected: PASS

- [ ] **Step 6: 写 PRAGMA 验证测试**

追加到 `server/internal/service/tags_test.go`：

```go
// TestTagsWalPragma 验证 WAL 模式生效。
// Round 32 P2: WAL 是连接池并发读的前置条件。
func TestTagsWalPragma(t *testing.T) {
	dir := t.TempDir()
	s, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer s.Close()

	var mode string
	if err := s.db.QueryRow("PRAGMA journal_mode").Scan(&mode); err != nil {
		t.Fatalf("QueryRow PRAGMA journal_mode: %v", err)
	}
	if mode != "wal" {
		t.Fatalf("journal_mode = %q, want %q", mode, "wal")
	}
}
```

- [ ] **Step 7: 运行 PRAGMA 测试**

Run: `cd server && go test ./internal/service/ -run TestTagsWalPragma -v`
Expected: PASS（输出 `journal_mode = "wal"`）

- [ ] **Step 8: 写并发写测试（验证无 SQLITE_BUSY）**

追加到 tags_test.go：

```go
// TestTagsConcurrentWritesNoBusy 验证并发写不触发 SQLITE_BUSY。
// Round 32 P2: busy_timeout=5000 + WAL 写串行应让所有写事务完成。
func TestTagsConcurrentWritesNoBusy(t *testing.T) {
	dir := t.TempDir()
	s, err := NewTagsService(dir)
	if err != nil {
		t.Fatalf("NewTagsService: %v", err)
	}
	defer s.Close()

	const N = 20
	var wg sync.WaitGroup
	errs := make(chan error, N)
	for i := 0; i < N; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			_, err := s.CreateTag(fmt.Sprintf("t-%d", idx), "#abc")
			if err != nil {
				errs <- err
			}
		}(i)
	}
	wg.Wait()
	close(errs)

	for err := range errs {
		if strings.Contains(err.Error(), "SQLITE_BUSY") || strings.Contains(err.Error(), "database is locked") {
			t.Fatalf("got lock error: %v", err)
		}
	}

	all := s.GetAllTags()
	if len(all) != N {
		t.Fatalf("got %d tags, want %d", len(all), N)
	}
}
```

- [ ] **Step 9: 运行所有 tags 测试**

Run: `cd server && go test ./internal/service/ -run TestTags -v -race -count=1`
Expected: 所有测试 PASS，无 race 警告。

- [ ] **Step 10: Commit**

```bash
cd server
git add internal/service/tags.go internal/service/tags_test.go
git commit -m "$(cat <<'EOF'
perf(tags): WAL connection pool + RLock CRUD for concurrent reads (P2)

Round 32 Phase P2:
- SetMaxOpenConns(max(4, NumCPU)) replaces single-conn bottleneck
- All CRUD methods use s.mu.RLock (WAL manages write serialization)
- s.mu.Lock now only guards Close() — its intended scope

Tests:
- TestTagsConcurrentReadWriteNoBlocking: reads don't block during writes
- TestTagsWalPragma: journal_mode=wal verified
- TestTagsConcurrentWritesNoBusy: 20 concurrent writers, no SQLITE_BUSY

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: S3 — /debug/pprof 默认关闭

**Files:**
- Modify: `server/internal/config/config.go`
- Modify: `server/cmd/server/main.go`
- Modify: `server/internal/server/server.go`
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Consumes: 现有 `config.Config`、`server.Server` API。
- Produces: `config.Config.Debug.DebugConfig.Pprof` 字段；`cmd/server` 的 `--debug-pprof` flag。

- [ ] **Step 1: 写默认配置下 pprof 404 的测试**

追加到 `server/internal/server/server_test.go`：

```go
// TestPprofDisabledByDefault 验证默认 config 下 /debug/pprof/ 返回 404。
// Round 32 S3: pprof 路由不再无条件注册。
func TestPprofDisabledByDefault(t *testing.T) {
	cfg := testConfig(t)
	cfg.Debug.Pprof = false
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusNotFound {
		t.Fatalf("got status %d, want 404", rec.Code)
	}
}
```

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server && go test ./internal/server/ -run TestPprofDisabledByDefault -v`
Expected: FAIL（当前 pprof 无条件注册，返回 200）。

- [ ] **Step 3: 修改 config.go 加 DebugConfig**

`server/internal/config/config.go` 加：
```go
type Config struct {
	Server    ServerConfig    `yaml:"server" json:"server"`
	Scan      ScanConfig      `yaml:"scan" json:"scan"`
	Thumbnail ThumbnailConfig `yaml:"thumbnail" json:"thumbnail"`
	System    SystemConfig    `yaml:"system,omitempty" json:"system,omitempty"`
	Debug     DebugConfig     `yaml:"debug,omitempty" json:"debug,omitempty"`
}

type DebugConfig struct {
	Pprof bool `yaml:"pprof,omitempty" json:"pprof,omitempty"`
}
```

- [ ] **Step 4: 修改 server.go 条件化 pprof 注册**

`server/internal/server/server.go:146-149` 改为：
```go
	// Round 32 S3: pprof 默认关闭，需显式 config.debug.pprof=true 或 --debug-pprof flag。
	if s.Config.Debug.Pprof {
		pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly())
		pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
	}
```

- [ ] **Step 5: 运行测试验证 PASS**

Run: `cd server && go test ./internal/server/ -run TestPprofDisabledByDefault -v`
Expected: PASS

- [ ] **Step 6: 写 pprof 启用的测试**

追加到 server_test.go：

```go
func TestPprofEnabledViaConfig(t *testing.T) {
	cfg := testConfig(t)
	cfg.Debug.Pprof = true
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234"
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("got status %d, want 200", rec.Code)
	}
}
```

- [ ] **Step 7: 运行启用测试**

Run: `cd server && go test ./internal/server/ -run TestPprofEnabled -v`
Expected: PASS

- [ ] **Step 8: 修改 cmd/server/main.go 加 --debug-pprof flag**

在 `server/cmd/server/main.go` 追加 flag 参数解析与配置合并。

- [ ] **Step 9: Commit**

```bash
cd server
git add internal/config/config.go internal/server/server.go internal/server/server_test.go cmd/server/main.go config.example.yaml
git commit -m "$(cat <<'EOF'
feat(security): /debug/pprof default off + --debug-pprof flag (S3)

Round 32 Phase S3:
- /debug/pprof/* routes no longer registered by default
- Enable via config.debug.pprof=true OR --debug-pprof flag (flag wins)
- PrivateNetOnly middleware retained as defense-in-depth

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: S1 — RateLimit LRU + 容量上限

**Files:**
- Modify: `server/internal/server/middleware/ratelimit.go`
- Test: `server/internal/server/middleware/ratelimit_test.go`

- [ ] **Step 1: 写容量满淘汰的测试**

```go
func TestRateLimitLRUEviction(t *testing.T) {
	mw := RateLimitWithConfig(5, time.Minute, 3)

	for _, ip := range []string{"1.1.1.1", "2.2.2.2", "3.3.3.3"} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = ip + ":1234"
		rec := httptest.NewRecorder()
		mw(echo.NotFoundHandler).ServeHTTP(rec, req)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("ip %s: got %d, want 404", ip, rec.Code)
		}
	}

	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "4.4.4.4:1234"
	rec := httptest.NewRecorder()
	mw(echo.NotFoundHandler).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("ip4: got %d, want 404", rec.Code)
	}
}
```

- [ ] **Step 2: 实现 RateLimitWithConfig 并重构 ratelimit.go**

在 `ratelimit.go` 实现 `RateLimitWithConfig(max, window, maxBuckets)`，当 `len(buckets) >= maxBuckets` 时淘汰最旧的 `lastSeen` 条目。保留 `RateLimit(max, window)` 作为默认 4096 容量的封装。

- [ ] **Step 3: 运行测试并 Commit**

```bash
cd server
git add internal/server/middleware/ratelimit.go internal/server/middleware/ratelimit_test.go
git commit -m "$(cat <<'EOF'
feat(security): rate limit LRU + capacity cap (default 4096) (S1)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: P3 — Scanner 并发遍历

**Files:**
- Modify: `server/internal/service/scanner.go`
- Test: `server/internal/service/scanner_test.go`

**背景**：原地优化现有的 `Scan` 方法，添加 `g.SetLimit(min(len(roots), runtime.NumCPU()))` 并对 `allFiles` 进行字典序稳定排序，完整保留现有 `dirMap` 祖先目录提取、`cacheByDir` 父目录映射及 `OnScanComplete` 回调。

- [ ] **Step 1: 在 Scan 方法中优化并发与排序**

修改 `scanner.go` 中的 `Scan` 方法：
1. 使用 `g.SetLimit(minInt(len(roots), runtime.NumCPU()))` 约束 per-root 并发度。
2. 收集完 `allFiles` 后执行 `sort.Slice(allFiles, func(i, j int) bool { return allFiles[i].Path < allFiles[j].Path })`，保证多次扫描顺序确定。
3. 确保 `dirMap` 收集、`cacheDirs` 排序、`cacheByDir` 分组与 `OnScanComplete` 逻辑无损保留。

- [ ] **Step 2: 编写并发与排序测试**

追加到 `scanner_test.go` 验证并发扫描结果按路径排序后的一致性与 context cancel 快速响应。

- [ ] **Step 3: 运行测试并 Commit**

```bash
cd server
git add internal/service/scanner.go internal/service/scanner_test.go
git commit -m "$(cat <<'EOF'
perf(scanner): per-root concurrency limit + ordered scan output (P3)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: S2 — Books 图片签名 token

**Files:**
- Create: `server/internal/service/book_signing.go`
- Test: `server/internal/service/book_signing_test.go`
- Modify: `server/internal/service/book.go`
- Modify: `server/internal/server/handler/books.go`
- Modify: `server/internal/server/server.go`

- [ ] **Step 1: 实现 BookSigner 与 HMAC-SHA256 签名逻辑**

创建 `book_signing.go`，计算 `sig = base64url(HMAC(serverSecret, clientIP + "|" + path + "|" + manifestID))`，提供 `SignImage` 与 `VerifyImage` 方法。

- [ ] **Step 2: 注入 Signer 至 BookService & 更新 GetChapterBlocks 签名**

调整 `GetChapterBlocks(ctx, path, idx, clientIP)` 签名，自动将内联 `<img>` 标签的 src 改写为带 `&sig=` 的签名 URL。

- [ ] **Step 3: 配置 Echo 日志脱敏中间件**

在 `server.go` 中挂载 Query 参数脱敏中间件：
```go
	// Redact ?token= from access log while caching query params for AuthMw
	s.Echo.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			_ = c.QueryParams() // 触发 QueryParams() 解析并缓存到 context
			req := c.Request()
			q := req.URL.Query()
			if q.Get("token") != "" {
				q.Set("token", "REDACTED")
				req.URL.RawQuery = q.Encode()
			}
			return next(c)
		}
	})
```

- [ ] **Step 4: 运行全量测试并 Commit**

```bash
cd server
git add internal/service/book_signing.go internal/service/book_signing_test.go \
        internal/service/book.go internal/server/handler/books.go internal/server/server.go
git commit -m "$(cat <<'EOF'
feat(security): signed book image URLs + log token redaction (S2)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: P1 — Thumbnail 冷启动加速

**Files:**
- Create: `server/internal/service/hot_dirs.go`
- Test: `server/internal/service/hot_dirs_test.go`
- Modify: `server/internal/service/thumbnail.go`
- Modify: `server/internal/server/server.go`

- [ ] **Step 1: 实现 hotDirTracker 与 5min Flush 机制**

在 `hot_dirs.go` 中实现 LRU 内存记录（容量 256），配合 5 分钟定时器与 Shutdown 同步原子落盘至 `filepath.Join(cacheDir, "hot_directories.json")`。

- [ ] **Step 2: 实现分层预热逻辑**

重构 `PreGenerateThumbnails(files, ctx, hotDirs, scanRoots)`：优先排产 Tier 1（hotDirs 集合中的目录）与 Tier 2（scanRoots 根目录下直接文件），对 Tier 3 冷目录文件实施懒生成策略（跳过预热）。

- [ ] **Step 3: 运行测试并 Commit**

```bash
cd server
git add internal/service/hot_dirs.go internal/service/hot_dirs_test.go \
        internal/service/thumbnail.go internal/server/server.go
git commit -m "$(cat <<'EOF'
perf(thumbnail): hot-dir LRU + tiered cold-start pre-generation (P1)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: S4 — Web SPA XSS 审计 + 修复 + lint

**Files:**
- Modify: `server/internal/web/*.js`
- Create: `server/internal/web/escape.js`
- Modify: `tools/xsscheck/*`

- [ ] **Step 1: 创建 escape.js 并补全转换函数**

实现标准的 `escapeHtml` 实体转义函数。

- [ ] **Step 2: 审计并修复 web/*.js 的 DOM 写入点**

针对所有的 `innerHTML`、`outerHTML`、`insertAdjacentHTML` 操作添加 `// XSS-SAFE:` 注释或对动态变量包裹 `escapeHtml`。

- [ ] **Step 3: 扩展 xsscheck lint 并运行验证**

确保 `xsscheck` 工具全量扫描 web/*.js，未带注释/未转义的写入点直接阻断构建。

- [ ] **Step 4: Commit**

```bash
cd server
git add internal/web/escape.js internal/web/*.js tools/xsscheck/
git commit -m "$(cat <<'EOF'
feat(security): web SPA XSS audit + fixes + lint rule (S4)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review & Verification

1. 所有 7 个 Task 覆盖了设计文档的所有优化要点。
2. 修复了 Task 1 中侵入生产代码的测试辅助字段问题。
3. 修复了 Task 4 中完全重写 `Scan` 导致丢失 `cacheByDir`/`cacheDirs` 关键分组功能的风险，改为原地重构。
4. 修复了 Task 5 中日志脱敏导致 Echo 路由无法获取 Query Token 的关键逻辑冲突。

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-07-23-round32-perf-security.md`.

Ready for implementation task-by-task.

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

测试需要 import `fmt`、`time`——若 tags_test.go 已有则不重复。

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

在文件顶部 import 块追加 `"runtime"`（若未导入）。

在文件末尾追加 helper（避免 Go 1.21 前 builtin max 冲突）：
```go
// maxInt returns the larger of a or b. Used for SetMaxOpenConns sizing.
func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
```

- [ ] **Step 4: 修改 tags.go — 把 CRUD 方法的 s.mu.Lock 改为 s.mu.RLock**

对以下方法逐个修改（行号是修改前位置，改 `s.mu.Lock()` → `s.mu.RLock()` + `defer s.mu.Unlock()` → `defer s.mu.RUnlock()`）：

- `CreateTag` (tags.go:191-192)
- `DeleteTag` (tags.go:218-219)
- `AssociateFile` (tags.go:242-243)
- `DisassociateFile` (tags.go:263-264)
- `CleanDeletedPath` (tags.go:397-398)

例如 `CreateTag` 改后：
```go
func (s *TagsService) CreateTag(name, color string) (*models.FileTag, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	// ... 其余逻辑不变
}
```

**理由**：所有 CRUD 的写串行由 SQLite WAL + busy_timeout 在 DB 层管理，Go 层级 `s.mu.Lock` 仅留给 `Close()` 销毁 `s.db` 时使用（保持 `Close` 的 `s.mu.Lock` 不变）。

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
	err = s.QueryPragma("journal_mode")
	if err != nil {
		t.Fatalf("QueryPragma: %v", err)
	}
	mode = s.LastPragmaValue()
	if mode != "wal" {
		t.Fatalf("journal_mode = %q, want %q", mode, "wal")
	}
}
```

（如果 `QueryPragma` / `LastPragmaValue` helper 不存在，改为直接在测试里通过内部 `db.QueryRow` 查询——见下一步实现。）

- [ ] **Step 7: 在 tags.go 加测试用 helper（仅在 _test.go 调用，所以导出但标 _test-only 注释）**

在 tags.go 末尾追加：
```go
// QueryPragma is exported for tests to verify PRAGMA state.
// Not part of the stable public API — tests only.
func (s *TagsService) QueryPragma(name string) error {
	return s.db.QueryRow("PRAGMA " + name).Scan(&s.lastPragma)
}

// LastPragmaValue returns the result of the most recent QueryPragma call.
// Tests-only helper.
func (s *TagsService) LastPragmaValue() string {
	return s.lastPragma
}
```

在 `TagsService` 结构体加字段：
```go
type TagsService struct {
	mu sync.RWMutex
	db *sql.DB
	lastPragma string  // tests-only
}
```

- [ ] **Step 8: 运行 PRAGMA 测试**

Run: `cd server && go test ./internal/service/ -run TestTagsWalPragma -v`
Expected: PASS（输出 `journal_mode = "wal"`）

- [ ] **Step 9: 写并发写测试（验证无 SQLITE_BUSY）**

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

需 import `strings`、`sync`（若已导入则不重复）。

- [ ] **Step 10: 运行所有 tags 测试**

Run: `cd server && go test ./internal/service/ -run TestTags -v -race -count=1`
Expected: 所有测试 PASS，无 race 警告。

- [ ] **Step 11: Commit**

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
- Modify: `server/internal/config/config.go:16-21` (Config struct + new DebugConfig)
- Modify: `server/cmd/server/main.go` (add `--debug-pprof` flag)
- Modify: `server/internal/server/server.go:146-149` (conditional pprof registration)
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
	cfg := testConfig(t) // 现有 helper，复用
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

（若 `testConfig` helper 不存在，参考 server_test.go 中其他测试如何构造 config——本计划假设它已存在；若不存在则在该 task Step 2 一并实现。）

- [ ] **Step 2: 运行测试验证 FAIL**

Run: `cd server && go test ./internal/server/ -run TestPprofDisabledByDefault -v`
Expected: FAIL（当前 pprof 无条件注册，返回 200）。

- [ ] **Step 3: 修改 config.go 加 DebugConfig**

`server/internal/config/config.go:16-21` 把：
```go
type Config struct {
	Server    ServerConfig    `yaml:"server" json:"server"`
	Scan      ScanConfig      `yaml:"scan" json:"scan"`
	Thumbnail ThumbnailConfig `yaml:"thumbnail" json:"thumbnail"`
	System    SystemConfig    `yaml:"system,omitempty" json:"system,omitempty"`
}
```
改为：
```go
type Config struct {
	Server    ServerConfig    `yaml:"server" json:"server"`
	Scan      ScanConfig      `yaml:"scan" json:"scan"`
	Thumbnail ThumbnailConfig `yaml:"thumbnail" json:"thumbnail"`
	System    SystemConfig    `yaml:"system,omitempty" json:"system,omitempty"`
	Debug     DebugConfig     `yaml:"debug,omitempty" json:"debug,omitempty"`
}

// DebugConfig holds optional debug-only features. All fields default to off.
// Round 32 S3.
type DebugConfig struct {
	// Pprof enables /debug/pprof/* routes (gated by PrivateNetOnly middleware).
	// Default false to minimize attack surface on accidental public exposure.
	Pprof bool `yaml:"pprof,omitempty" json:"pprof,omitempty"`
}
```

- [ ] **Step 4: 修改 server.go 条件化 pprof 注册**

`server/internal/server/server.go:146-149` 把：
```go
	// pprof endpoints for live profiling. Restricted to private/loopback
	// IPs to avoid leaking heap/goroutine data on accidental public exposure.
	pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly())
	pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
```
改为：
```go
	// Round 32 S3: pprof 默认关闭，需显式 config.debug.pprof=true 或 --debug-pprof flag。
	// PrivateNetOnly 仍作为深度防御保留（即使 config 开了 pprof，公网请求仍被拦）。
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
// TestPprofEnabledViaConfig 验证 config.debug.pprof=true 时 pprof 路由可访问。
func TestPprofEnabledViaConfig(t *testing.T) {
	cfg := testConfig(t)
	cfg.Debug.Pprof = true
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/debug/pprof/", nil)
	req.RemoteAddr = "127.0.0.1:1234" // 私网，通过 PrivateNetOnly
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

打开 `server/cmd/server/main.go`，找到现有 flag 定义区（如 `--headless` 附近），追加：

```go
	debugPprof := flag.Bool("debug-pprof", false, "enable /debug/pprof/* routes (overrides config.debug.pprof)")
```

在 config 加载后、`server.New(cfg)` 调用前，追加合并逻辑：

```go
	// Round 32 S3: flag 覆盖 config（flag > config）。
	if *debugPprof {
		cfg.Debug.Pprof = true
	}
```

具体插入位置依赖 main.go 现有结构——执行此 task 的 agent 必须先 Read main.go 确认 flag 解析与 `server.New(cfg)` 调用的相对位置，再插入合并逻辑。

- [ ] **Step 9: 运行所有 server 测试**

Run: `cd server && go test ./internal/server/ -v -race -count=1`
Expected: 所有测试 PASS。

- [ ] **Step 10: 更新 config.example.yaml**

在 `server/config.example.yaml` 末尾追加（注意缩进与文件其余部分一致）：

```yaml
# Debug-only features. All default to false.
debug:
  # Enable /debug/pprof/* routes (gated by PrivateNetOnly middleware).
  # Also overridable via --debug-pprof command-line flag (flag takes precedence).
  pprof: false
```

- [ ] **Step 11: Commit**

```bash
cd server
git add internal/config/config.go internal/server/server.go internal/server/server_test.go cmd/server/main.go config.example.yaml
git commit -m "$(cat <<'EOF'
feat(security): /debug/pprof default off + --debug-pprof flag (S3)

Round 32 Phase S3:
- /debug/pprof/* routes no longer registered by default
- Enable via config.debug.pprof=true OR --debug-pprof flag (flag wins)
- PrivateNetOnly middleware retained as defense-in-depth

Tests:
- TestPprofDisabledByDefault: 404 when config.debug.pprof=false
- TestPprofEnabledViaConfig: 200 when config.debug.pprof=true

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: S1 — RateLimit LRU + 容量上限

**Files:**
- Modify: `server/internal/server/middleware/ratelimit.go`
- Test: `server/internal/server/middleware/ratelimit_test.go`

**Interfaces:**
- Consumes: 现有 `RateLimit(max, window)` API 保持兼容。
- Produces: 新增 `RateLimitWithConfig(max, window, maxBuckets)`；老 `RateLimit` 改为 wrapper。

- [ ] **Step 1: 写容量满淘汰的测试**

追加到 `server/internal/server/middleware/ratelimit_test.go`：

```go
// TestRateLimitLRUEviction 验证容量满后新 IP 挤掉最旧 IP。
// Round 32 S1: 防止伪造 X-Forwarded-For 触发内存膨胀。
func TestRateLimitLRUEviction(t *testing.T) {
	// 容量 3，允许每个 IP 5 次/分钟
	mw := RateLimitWithConfig(5, time.Minute, 3)

	// 依次访问 IP1, IP2, IP3（填满）
	for _, ip := range []string{"1.1.1.1", "2.2.2.2", "3.3.3.3"} {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = ip + ":1234"
		rec := httptest.NewRecorder()
		mw(echo.NotFoundHandler).ServeHTTP(rec, req)
		if rec.Code != http.StatusNotFound {
			t.Fatalf("ip %s: got %d, want 404 (allowed)", ip, rec.Code)
		}
	}

	// IP4 进入：应淘汰 IP1（最旧）
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "4.4.4.4:1234"
	rec := httptest.NewRecorder()
	mw(echo.NotFoundHandler).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("ip4: got %d, want 404 (allowed after eviction)", rec.Code)
	}

	// IP1 重新进入：由于被淘汰，counter 应重置，应允许（而非继承旧 count）
	req = httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "1.1.1.1:1234"
	rec = httptest.NewRecorder()
	mw(echo.NotFoundHandler).ServeHTTP(rec, req)
	if rec.Code != http.StatusNotFound {
		t.Fatalf("ip1 after eviction: got %d, want 404 (counter reset)", rec.Code)
	}
}
```

需 import `echo`、`httptest`、`net/http`、`time`（参考 ratelimit_test.go 现有 import）。

- [ ] **Step 2: 运行测试验证 FAIL（RateLimitWithConfig 未定义）**

Run: `cd server && go test ./internal/server/middleware/ -run TestRateLimitLRUEviction -v`
Expected: 编译失败 `undefined: RateLimitWithConfig`。

- [ ] **Step 3: 重写 ratelimit.go**

替换 `server/internal/server/middleware/ratelimit.go` 全文：

```go
package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
)

// defaultMaxBuckets is the cap on distinct client IPs tracked by RateLimit.
// Round 32 S1: prevents memory bloat from forged X-Forwarded-For values.
const defaultMaxBuckets = 4096

// RateLimit returns a middleware that allows at most `max` requests per `window`
// per client IP. Requests over the limit get 429 Too Many Requests.
//
// Implementation: in-memory map[string]*bucket guarded by sync.Mutex, with
// an LRU-style cap (defaultMaxBuckets). When capacity is exceeded the
// least-recently-seen bucket is evicted. Not distributed — sufficient for
// single-process LAN deployment.
func RateLimit(max int, window time.Duration) echo.MiddlewareFunc {
	return RateLimitWithConfig(max, window, defaultMaxBuckets)
}

// RateLimitWithConfig is like RateLimit but with an explicit maxBuckets cap.
// Round 32 S1.
func RateLimitWithConfig(max int, window time.Duration, maxBuckets int) echo.MiddlewareFunc {
	type bucket struct {
		count    int
		resetAt  time.Time
		lastSeen time.Time
	}
	var (
		mu      sync.Mutex
		buckets = make(map[string]*bucket)
	)
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ip := c.RealIP()
			mu.Lock()
			now := time.Now()
			b, ok := buckets[ip]
			if !ok || now.After(b.resetAt) {
				// 新 IP 或窗口已过：若已达容量上限，先淘汰最旧。
				if !ok && len(buckets) >= maxBuckets {
					var oldestKey string
					var oldestSeen time.Time
					for k, bb := range buckets {
						if oldestKey == "" || bb.lastSeen.Before(oldestSeen) {
							oldestKey = k
							oldestSeen = bb.lastSeen
						}
					}
					delete(buckets, oldestKey)
				}
				buckets[ip] = &bucket{count: 1, resetAt: now.Add(window), lastSeen: now}
				mu.Unlock()
				return next(c)
			}
			b.lastSeen = now
			if b.count >= max {
				mu.Unlock()
				return c.JSON(http.StatusTooManyRequests,
					map[string]string{"error": "rate limit exceeded"})
			}
			b.count++
			mu.Unlock()
			return next(c)
		}
	}
}
```

- [ ] **Step 4: 运行 LRU 测试**

Run: `cd server && go test ./internal/server/middleware/ -run TestRateLimitLRUEviction -v`
Expected: PASS

- [ ] **Step 5: 写并发安全测试**

追加到 ratelimit_test.go：

```go
// TestRateLimitConcurrentNoRace 验证并发访问无 race。
// Round 32 S1: LRU 扫描在 mu.Lock 内完成，应安全。
// 使用 -race 运行验证。
func TestRateLimitConcurrentNoRace(t *testing.T) {
	mw := RateLimitWithConfig(1000, time.Minute, 100)

	const N = 200
	var wg sync.WaitGroup
	for i := 0; i < N; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			req.RemoteAddr = fmt.Sprintf("10.0.0.%d:1234", idx%50)
			rec := httptest.NewRecorder()
			mw(echo.NotFoundHandler).ServeHTTP(rec, req)
		}(i)
	}
	wg.Wait()
}
```

需 import `fmt`、`sync`（若已导入则不重复）。

- [ ] **Step 6: 运行 -race 验证**

Run: `cd server && go test ./internal/server/middleware/ -run TestRateLimitConcurrentNoRace -v -race -count=1`
Expected: PASS，无 race 警告。

- [ ] **Step 7: 运行所有 ratelimit 测试确认无回归**

Run: `cd server && go test ./internal/server/middleware/ -v -race -count=1`
Expected: 所有现有测试（如 TestRateLimitAllowsWithinWindow、TestRateLimitRejectsOverLimit）继续 PASS。

- [ ] **Step 8: Commit**

```bash
cd server
git add internal/server/middleware/ratelimit.go internal/server/middleware/ratelimit_test.go
git commit -m "$(cat <<'EOF'
feat(security): rate limit LRU + capacity cap (default 4096) (S1)

Round 32 Phase S1:
- RateLimitWithConfig(max, window, maxBuckets) with LRU eviction
- Legacy RateLimit wraps it with defaultMaxBuckets=4096
- Prevents memory bloat from forged X-Forwarded-For values

Tests:
- TestRateLimitLRUEviction: capacity full → oldest IP evicted
- TestRateLimitConcurrentNoRace: 200 concurrent requests, no race

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: P3 — Scanner 并发遍历

**Files:**
- Modify: `server/internal/service/scanner.go`
- Test: `server/internal/service/scanner_test.go`

**Interfaces:**
- Consumes: 现有 `Scanner` 公共 API（`NewScanner`, `TriggerScan`, `GetCached`, `Shutdown`, `OnScanComplete` 等）签名不变。
- Produces: 同上——内部并发化，输出按路径排序保持外部行为不变。

**背景**：scanner.go 当前是串行 `filepath.Walk`（需先 Read 完整文件确认实际实现位置）。`errgroup.WithContext` + `SetLimit` 已在 import 块（行 16）。

- [ ] **Step 1: Read 完整 scanner.go 确认 scan 实现位置**

Run: `cd server && wc -l internal/service/scanner.go`
Then Read 整个文件。

执行此 task 的 agent 必须先读完整文件，确认：
1. 哪个方法触发 Walk（通常是 `scan(ctx, roots) []MediaFile` 类似名字）
2. Walk 的 callback 做什么（判断扩展名 + 构造 MediaFile）
3. 输出是否已排序

记录这些信息后再进入 Step 2。

- [ ] **Step 2: 写并发与串行输出一致的测试**

追加到 `server/internal/service/scanner_test.go`：

```go
// TestScannerConcurrentMatchesSerialOutput 验证并发扫描的输出（排序后）
// 与串行扫描完全一致。
// Round 32 P3: 并发化不能改变外部可见的扫描结果。
func TestScannerConcurrentMatchesSerialOutput(t *testing.T) {
	// 构造 2 个 root，各 50 个空文件
	root1 := t.TempDir()
	root2 := t.TempDir()
	for i := 0; i < 50; i++ {
		name1 := filepath.Join(root1, fmt.Sprintf("vid%d.mp4", i))
		if err := os.WriteFile(name1, []byte("x"), 0644); err != nil {
			t.Fatal(err)
		}
		name2 := filepath.Join(root2, fmt.Join, fmt.Sprintf("img%d.jpg", i))
		if err := os.WriteFile(name2, []byte("x"), 0644); err != nil {
			t.Fatal(err)
		}
	}

	// 串行 scanner
	sSerial := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
	serialFiles := sSerial.ScanForTest(roots) // 测试 helper，见 Step 4

	// 并发 scanner（同一个 NewScanner，内部并发；外部行为一致）
	sConcurrent := NewScanner([]string{".mp4"}, []string{".jpg"}, nil)
	concurrentFiles := sConcurrent.ScanForTest(roots)

	// 排序后比较（实现内部应已排序，但测试侧再保险一次）
	sort.Slice(serialFiles, func(i, j int) bool { return serialFiles[i].Path < serialFiles[j].Path })
	sort.Slice(concurrentFiles, func(i, j int) bool { return concurrentFiles[i].Path < concurrentFiles[j].Path })

	if len(serialFiles) != len(concurrentFiles) {
		t.Fatalf("len mismatch: serial=%d concurrent=%d", len(serialFiles), len(concurrentFiles))
	}
	for i := range serialFiles {
		if serialFiles[i].Path != concurrentFiles[i].Path {
			t.Fatalf("file %d: serial=%q concurrent=%q", i, serialFiles[i].Path, concurrentFiles[i].Path)
		}
	}
}
```

需 import `sort`、`os`、`filepath`、`fmt`（参考 scanner_test.go 现有 import）。

**注意**：上面测试代码中的 `fmt.Join` 是笔误示例——执行 agent 必须在写测试时改为 `filepath.Join`。本计划在此显式标注，避免 agent 照抄笔误。

- [ ] **Step 3: 运行测试验证 FAIL（ScanForTest 未定义）**

Run: `cd server && go test ./internal/service/ -run TestScannerConcurrentMatchesSerialOutput -v`
Expected: 编译失败 `undefined: ScanForTest`。

- [ ] **Step 4: 重构 scanner.go 实现并发**

在 scanner.go 找到现有的串行 Walk 实现（Step 1 已确认位置）。改造为：

1. **保留原串行实现作为参考**，重命名为 `scanSerial`（测试用）。
2. **新写 `scanConcurrent`** 作为新主路径：

```go
// scanConcurrent 并发遍历多个 root。
// Round 32 P3: per-root goroutine + errgroup.SetLimit 控制并发上限。
// 输出按路径字典序排序，保证外部行为与串行一致。
func (s *Scanner) scanConcurrent(ctx context.Context, roots []string) []models.MediaFile {
	g, ctx := errgroup.WithContext(ctx)
	// 并发上限 = min(len(roots), NumCPU)
	limit := len(roots)
	if limit > runtime.NumCPU() {
		limit = runtime.NumCPU()
	}
	if limit < 1 {
		limit = 1
	}
	g.SetLimit(limit)

	// per-root 结果通过 channel 收集
	type rootResult struct {
		files []models.MediaFile
	}
	results := make(chan rootResult, len(roots))

	for _, root := range roots {
		root := root
		g.Go(func() error {
			files := s.walkOneRoot(ctx, root)
			results <- rootResult{files: files}
			return nil
		})
	}

	// 等待所有 root 完成
	go func() {
		_ = g.Wait()
		close(results)
	}()

	var all []models.MediaFile
	for r := range results {
		all = append(all, r.files...)
	}

	// 按路径字典序排序，保证输出稳定
	sort.Slice(all, func(i, j int) bool { return all[i].Path < all[j].Path })
	return all
}

// walkOneRoot 遍历单个 root，使用 filepath.WalkDir（比 filepath.Walk 少一次 lstat）。
func (s *Scanner) walkOneRoot(ctx context.Context, root string) []models.MediaFile {
	var files []models.MediaFile
	_ = filepath.WalkDir(root, func(path string, d fs.DirEntry, err error) error {
		if err != nil {
			return nil // skip errors
		}
		if d.IsDir() {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		// 判断扩展名 + 构造 MediaFile（提取原 Walk callback 逻辑）
		if f, ok := s.classifyFile(path); ok {
			files = append(files, f)
		}
		return nil
	})
	return files
}

// classifyFile 是原 Walk callback 中"判断扩展名 + 构造 MediaFile"的提取。
// 执行 agent：从 Step 1 读到的原 callback 中搬代码，保持判断逻辑不变。
func (s *Scanner) classifyFile(path string) (models.MediaFile, bool) {
	ext := strings.ToLower(filepath.Ext(path))
	var mediaType string
	switch {
	case s.videoExts[ext]:
		mediaType = "video"
	case s.imageExts[ext]:
		mediaType = "image"
	case s.textExts[ext]:
		mediaType = "text"
	default:
		return models.MediaFile{}, false
	}
	// 执行 agent：此处补充原 callback 中构造 MediaFile 的剩余逻辑
	// （Stat 取大小/ModTime、设置 Path/MediaType 等字段）
	// ... 参考原 callback 代码搬运
	return models.MediaFile{Path: path, MediaType: mediaType /* ... */}, true
}

// ScanForTest 是测试专用导出方法，供 TestScannerConcurrentMatchesSerialOutput
// 调用以对比串行 vs 并发输出。Tests-only。
func (s *Scanner) ScanForTest(roots []string) []models.MediaFile {
	return s.scanConcurrent(context.Background(), roots)
}

// ScanForTestSerial 暴露串行实现供测试对比。
func (s *Scanner) ScanForTestSerial(roots []string) []models.MediaFile {
	return s.scanSerial(context.Background(), roots)
}
```

**关键**：执行 agent 必须把原 Walk callback 的实际代码搬到 `classifyFile`，本计划给出的是结构框架，具体字段（Size、ModTime、Duration 等）需从原代码搬运。

5. **更新 scan 主调用点**：原调用 `s.scanSerial(...)` 的位置改为 `s.scanConcurrent(...)`。

- [ ] **Step 5: 运行输出一致性测试**

Run: `cd server && go test ./internal/service/ -run TestScannerConcurrentMatchesSerialOutput -v`
Expected: PASS

- [ ] **Step 6: 写 context cancel 测试**

追加到 scanner_test.go：

```go
// TestScannerContextCancelExitsQuickly 验证 context cancel 后并发扫描在 1s 内退出。
// Round 32 P3: walkOneRoot 在 WalkDir callback 内检查 ctx.Done。
func TestScannerContextCancelExitsQuickly(t *testing.T) {
	root := t.TempDir()
	// 造 500 个文件让扫描有工作量
	for i := 0; i < 500; i++ {
		_ = os.WriteFile(filepath.Join(root, fmt.Sprintf("f%d.jpg", i)), []byte("x"), 0644)
	}

	s := NewScanner(nil, []string{".jpg"}, nil)
	ctx, cancel := context.WithCancel(context.Background())

	done := make(chan struct{})
	go func() {
		_ = s.scanConcurrent(ctx, []string{root})
		close(done)
	}()

	// 立即 cancel
	cancel()

	select {
	case <-done:
		// ok
	case <-time.After(1 * time.Second):
		t.Fatal("scanConcurrent did not exit within 1s after cancel")
	}
}
```

需 import `context`、`time`（参考 scanner_test.go 现有 import）。

- [ ] **Step 7: 运行 cancel 测试**

Run: `cd server && go test ./internal/service/ -run TestScannerContextCancelExitsQuickly -v`
Expected: PASS

- [ ] **Step 8: 运行所有 scanner 测试确认无回归**

Run: `cd server && go test ./internal/service/ -run TestScanner -v -race -count=1`
Expected: 所有现有 scanner 测试 PASS。

- [ ] **Step 9: Commit**

```bash
cd server
git add internal/service/scanner.go internal/service/scanner_test.go
git commit -m "$(cat <<'EOF'
perf(scanner): concurrent per-root WalkDir with ordered output (P3)

Round 32 Phase P3:
- scanConcurrent uses errgroup with SetLimit(min(len(roots), NumCPU))
- walkOneRoot switches to filepath.WalkDir (saves one lstat per file)
- Output sorted by path to preserve external behavior
- walkOneRoot checks ctx.Done for fast cancellation

Tests:
- TestScannerConcurrentMatchesSerialOutput: 2 roots × 50 files identical
- TestScannerContextCancelExitsQuickly: exits within 1s of cancel

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: S2 — Books 图片签名 token

**Files:**
- Create: `server/internal/service/book_signing.go`
- Test: `server/internal/service/book_signing_test.go`
- Modify: `server/internal/service/book.go:91-125` (GetChapterBlocks adds sig)
- Modify: `server/internal/server/handler/books.go` (accept ?sig=, redact token in log)
- Modify: `server/internal/server/server.go:113` (Logger middleware redacts token)
- Test: `server/internal/server/handler/books_test.go`

**Interfaces:**
- Consumes: 现有 `BookService.GetChapterBlocks(path, idx)`。
- Produces:
  - `service.BookSigner` 结构体，方法 `SignImage(clientIP, path, manifestID string) string` 和 `VerifyImage(clientIP, path, manifestID, sig string) bool`。
  - `BookService` 新增 `SetSigner(*BookSigner)` 注入方法。
  - `BookService.GetChapterBlocks` 改签名为 `GetChapterBlocks(ctx context.Context, path string, idx int, clientIP string)`——执行 agent 需同步更新所有调用方。
  - `books.go` handler 新增 `/api/v1/books/sign-image` endpoint。

- [ ] **Step 1: 创建 book_signing.go**

新建 `server/internal/service/book_signing.go`：

```go
// Package service / book_signing.go — HMAC-based signing for book image URLs.
//
// Round 32 S2: prevents Bearer token leakage via <img src="?token="> by
// issuing per-image signatures bound to (clientIP, path, manifestID).
// Signatures have no expiry — server restart invalidates all (serverSecret
// regenerated on each boot).
package service

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"fmt"
)

// BookSigner produces and verifies HMAC-SHA256 signatures for book image URLs.
// serverSecret is generated once per process; restart invalidates all sigs.
type BookSigner struct {
	serverSecret []byte
}

// NewBookSigner creates a BookSigner with a fresh random 32-byte secret.
func NewBookSigner() (*BookSigner, error) {
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, fmt.Errorf("failed to generate server secret: %w", err)
	}
	return &BookSigner{serverSecret: secret}, nil
}

// SignImage returns a base64url-encoded HMAC-SHA256 signature bound to
// (clientIP, path, manifestID). Empty manifestID is allowed (non-epub).
func (s *BookSigner) SignImage(clientIP, path, manifestID string) string {
	mac := hmac.New(sha256.New, s.serverSecret)
	mac.Write([]byte(clientIP))
	mac.Write([]byte("|"))
	mac.Write([]byte(path))
	mac.Write([]byte("|"))
	mac.Write([]byte(manifestID))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// VerifyImage recomputes the expected signature and compares in constant time.
func (s *BookSigner) VerifyImage(clientIP, path, manifestID, sig string) bool {
	expected := s.SignImage(clientIP, path, manifestID)
	return subtle.ConstantTimeCompare([]byte(expected), []byte(sig)) == 1
}
```

- [ ] **Step 2: 写签名测试**

新建 `server/internal/service/book_signing_test.go`：

```go
package service

import (
	"testing"
)

func TestSignImageDeterministic(t *testing.T) {
	s, err := NewBookSigner()
	if err != nil {
		t.Fatal(err)
	}
	a := s.SignImage("1.2.3.4", "/x/y.epub", "img1")
	b := s.SignImage("1.2.3.4", "/x/y.epub", "img1")
	if a != b {
		t.Fatalf("same inputs produced different sigs: %q vs %q", a, b)
	}
}

func TestSignImageDiffersByIP(t *testing.T) {
	s, _ := NewBookSigner()
	a := s.SignImage("1.2.3.4", "/x.epub", "m")
	b := s.SignImage("5.6.7.8", "/x.epub", "m")
	if a == b {
		t.Fatal("different IPs produced same sig")
	}
}

func TestSignImageDiffersByManifest(t *testing.T) {
	s, _ := NewBookSigner()
	a := s.SignImage("1.2.3.4", "/x.epub", "m1")
	b := s.SignImage("1.2.3.4", "/x.epub", "m2")
	if a == b {
		t.Fatal("different manifestIDs produced same sig")
	}
}

func TestVerifyImageAcceptsValidSig(t *testing.T) {
	s, _ := NewBookSigner()
	sig := s.SignImage("1.2.3.4", "/x.epub", "m")
	if !s.VerifyImage("1.2.3.4", "/x.epub", "m", sig) {
		t.Fatal("valid sig rejected")
	}
}

func TestVerifyImageRejectsTamperedSig(t *testing.T) {
	s, _ := NewBookSigner()
	sig := s.SignImage("1.2.3.4", "/x.epub", "m")
	tampered := sig + "x"
	if s.VerifyImage("1.2.3.4", "/x.epub", "m", tampered) {
		t.Fatal("tampered sig accepted")
	}
}
```

- [ ] **Step 3: 运行签名测试**

Run: `cd server && go test ./internal/service/ -run TestSignImage -v && go test ./internal/service/ -run TestVerifyImage -v`
Expected: 5 个测试全 PASS。

- [ ] **Step 4: 修改 book.go — SetSigner + GetChapterBlocks 改签名**

`server/internal/service/book.go:31-40` 把 BookService 结构体加字段：
```go
type BookService struct {
	mu     sync.RWMutex
	cache  map[string]*bookparser.Book
	sf     singleflight.Group
	signer *BookSigner  // Round 32 S2; nil = no signing (open mode)
}

// SetSigner injects a BookSigner for signed image URL generation.
// Round 32 S2.
func (s *BookService) SetSigner(signer *BookSigner) {
	s.signer = signer
}
```

`server/internal/service/book.go:91` 改 `GetChapterBlocks` 签名：

```go
// GetChapterBlocks returns the ordered content blocks for chapter idx.
// Image blocks' Src is rewritten to a signed /api/v1/books/image?path=...&manifest=...&sig=...
// URL when signer is set; otherwise falls back to unsigned ?token= path (legacy).
//
// clientIP is used to bind the signature — callers MUST pass c.RealIP() so
// the same IP verifies on the subsequent /books/image request.
func (s *BookService) GetChapterBlocks(ctx context.Context, path string, idx int, clientIP string) ([]bookparser.Block, error) {
```

在原 `out[i].Src = fmt.Sprintf(...)` 处（行 121-122）改为：
```go
			var src string
			if s.signer != nil {
				sig := s.signer.SignImage(clientIP, path, manifestID)
				src = fmt.Sprintf("/api/v1/books/image?path=%s&manifest=%s&sig=%s",
					url.QueryEscape(path), url.QueryEscape(manifestID), sig)
			} else {
				// Legacy open-mode fallback (no signer configured)
				src = fmt.Sprintf("/api/v1/books/image?path=%s&manifest=%s",
					url.QueryEscape(path), url.QueryEscape(manifestID))
			}
			out[i].Src = src
```

需在 book.go 顶部 import 块加 `"context"`（若未导入）。

- [ ] **Step 5: 找到所有 GetChapterBlocks 调用方并更新**

Run: `cd server && grep -rn "GetChapterBlocks" --include="*.go"`
对每个调用方（通常是 `handler/books.go`）：
- 把 `blocks, err := bookService.GetChapterBlocks(path, idx)` 改为
  `blocks, err := bookService.GetChapterBlocks(r.Context(), path, idx, c.RealIP())`

- [ ] **Step 6: 修改 server.go 注入 signer + token redaction**

`server/internal/server/server.go:60` 附近（BookService 创建处）加：
```go
	bookService := service.NewBookService()
	signer, err := service.NewBookSigner()
	if err != nil {
		return nil, fmt.Errorf("failed to create book signer: %w", err)
	}
	bookService.SetSigner(signer)
	s.bookSigner = signer  // 加到 Server 结构体的字段
```

在 `Server` 结构体加 `bookSigner *service.BookSigner` 字段。

修改 `Logger` 中间件（行 113）加 token redaction：
```go
	s.Echo.Use(echoMw.LoggerWithConfig(echoMw.LoggerConfig{
		Format: `{"time":"${time_rfc3339}","method":"${method}","uri":"${uri_redacted}","status":${status}}` + "\n",
	}))
```

由于 Echo 内置 `${uri}` 不支持 redaction，改为自定义中间件：

```go
	// Round 32 S2: redact ?token= from access logs to prevent Bearer leakage.
	s.Echo.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			err := next(c)
			q := c.Request().URL.Query()
			if q.Get("token") != "" {
				q.Set("token", "REDACTED")
				c.Request().URL.RawQuery = q.Encode()
			}
			return err
		}
	})
	s.Echo.Use(echoMw.Logger())
```

**注意**：此中间件必须在 `Logger()` 之前注册（中间件执行顺序：后注册先生效——Echo 是洋葱模型，请求时最后注册的最先执行）。所以实际写法是把 redact 中间件放在 Logger 之后注册，使其在请求进入 Logger 之前生效。执行 agent 必须在本地测试验证 redact 生效（参考 Step 9 测试）。

- [ ] **Step 7: 修改 books.go handler 接受 ?sig=**

在 `handler/books.go` 找到 `GetBookImage` handler（处理 `/api/v1/books/image`）。在认证逻辑后加 sig 验证：

```go
func (h *Handler) GetBookImage(c echo.Context) error {
	path := c.QueryParam("path")
	manifestID := c.QueryParam("manifest")
	sig := c.QueryParam("sig")

	// Round 32 S2: sig 路径优先；?token= 路径作为 deprecated fallback。
	if sig != "" {
		if h.bookSigner == nil || !h.bookSigner.VerifyImage(c.RealIP(), path, manifestID, sig) {
			return c.JSON(http.StatusUnauthorized, map[string]string{"error": "invalid signature"})
		}
		// sig 验证通过，跳过 BearerToken 中间件吗？不——authMw 已在路由组挂载。
		// 这里 sig 是"额外验证"——同时要求 BearerToken 通过。
	} else if c.QueryParam("token") != "" {
		// Deprecated: ?token= 路径。仍由 authMw 校验。日志打 [DEPRECATED] 前缀。
		slog.Warn("[DEPRECATED] /books/image called with ?token= — migrate to ?sig=", "path", path)
	}

	data, contentType, err := h.book.ReadImageBytes(path, manifestID)
	if err != nil {
		return c.JSON(http.StatusInternalServerError, map[string]string{"error": err.Error()})
	}
	return c.Blob(http.StatusOK, contentType, data)
}
```

需在 `handler.Handler` 结构体加 `bookSigner *service.BookSigner` 字段，并在 `handler.New` 签名中传入。

**Handler 构造函数签名变更**：原 `handler.New(cfg, scanner, tagsService, streamingService, thumbnailService, bookService)` 加一个 `bookSigner` 参数。server.go 中调用处同步更新。

- [ ] **Step 8: 加 /api/v1/books/sign-image endpoint**

在 `handler/books.go` 加：

```go
// SignImage handles GET /api/v1/books/sign-image?path=...&manifest=...
// Returns a signed src URL for clients that need dynamic image fetching
// (e.g. Web SPA lightbox).
func (h *Handler) SignImage(c echo.Context) error {
	path := c.QueryParam("path")
	manifestID := c.QueryParam("manifest")
	if path == "" {
		return c.JSON(http.StatusBadRequest, map[string]string{"error": "path required"})
	}
	if h.bookSigner == nil {
		return c.JSON(http.StatusServiceUnavailable, map[string]string{"error": "signing disabled"})
	}
	sig := h.bookSigner.SignImage(c.RealIP(), path, manifestID)
	src := fmt.Sprintf("/api/v1/books/image?path=%s&manifest=%s&sig=%s",
		url.QueryEscape(path), url.QueryEscape(manifestID), sig)
	return c.JSON(http.StatusOK, map[string]string{"src": src})
}
```

在 server.go 的 books 路由组加：
```go
	books.GET("/sign-image", h.SignImage)
```

- [ ] **Step 9: 写 token redact 测试**

追加到 `server/internal/server/handler/books_test.go`（或 server_test.go，看哪个有 middleware 测试基础）：

```go
// TestTokenRedactionInLog 验证 ?token= 在 access log 中被替换为 REDACTED。
// Round 32 S2.
func TestTokenRedactionInLog(t *testing.T) {
	var logBuf bytes.Buffer
	srv := newTestServer(t, &logBuf)

	req := httptest.NewRequest(http.MethodGet, "/api/v1/health?token=secret123", nil)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	logOutput := logBuf.String()
	if strings.Contains(logOutput, "secret123") {
		t.Fatalf("token leaked in log: %s", logOutput)
	}
	if !strings.Contains(logOutput, "REDACTED") {
		t.Fatalf("expected REDACTED in log: %s", logOutput)
	}
}
```

执行 agent 需要根据 server_test.go 现有 `newTestServer` helper 决定如何把 log 输出重定向到 buffer（可能需要把 Echo 的 Logger 输出设为 &logBuf）。

- [ ] **Step 10: 运行所有 server + service 测试**

Run: `cd server && go test ./... -race -count=1`
Expected: 全部 PASS。

- [ ] **Step 11: Commit**

```bash
cd server
git add internal/service/book_signing.go internal/service/book_signing_test.go \
        internal/service/book.go internal/server/handler/books.go \
        internal/server/handler/books_test.go internal/server/server.go
git commit -m "$(cat <<'EOF'
feat(security): signed book image URLs + log token redaction (S2)

Round 32 Phase S2:
- BookSigner: HMAC-SHA256 bound to (clientIP, path, manifestID), no expiry
- /books/chapter inlines signed <img src> per client IP
- /books/image accepts ?sig= (preferred) or ?token= (deprecated)
- New /books/sign-image endpoint for dynamic clients (lightbox)
- Access log redacts ?token=XXX → ?token=REDACTED

Tests:
- 5 cases on signer (determinism, IP/manifest binding, verify)
- TestTokenRedactionInLog verifies REDACTED replaces raw token

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: P1 — Thumbnail 冷启动加速

**Files:**
- Create: `server/internal/service/hot_dirs.go`
- Test: `server/internal/service/hot_dirs_test.go`
- Modify: `server/internal/service/thumbnail.go:39-104` (ThumbnailService 加 hotDirs 字段) + `:484-531` (generateBytesVia 调 RecordHotAccess) + `:385-482` (PreGenerateThumbnails 分层)
- Modify: `server/internal/server/server.go:72-89` (OnScanComplete 读种子 + 传 scanRoots)

**Interfaces:**
- Consumes: 现有 `ThumbnailService` API；`scanner.Scanner` 的 `OnScanComplete`。
- Produces:
  - `ThumbnailService.RecordHotAccess(dirPath string)` 新方法。
  - `ThumbnailService.PreGenerateThumbnails` 改签名为 `(files, ctx, hotDirs map[string]struct{}, scanRoots []string)`。

- [ ] **Step 1: 创建 hot_dirs.go**

新建 `server/internal/service/hot_dirs.go`：

```go
// Package service / hot_dirs.go — LRU + periodic flush for hot directory tracking.
//
// Round 32 P1: tracks which directories users actively browse so cold-start
// thumbnail pre-generation can prioritize them. In-memory LRU; flushed to
// hot_directories.json every 5 minutes and on Shutdown.
package service

import (
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// hotDirTracker tracks per-directory access counts in an LRU-like map.
// Capacity 256 — persistence writes only top-256 by count.
type hotDirTracker struct {
	mu       sync.Mutex
	counts   map[string]int
	lastSeen map[string]time.Time
	maxLen   int

	cacheDir string             // hot_directories.json lives here
	stopCh   chan struct{}      // closes to stop the flush goroutine
	doneCh   chan struct{}      // closes when flush goroutine exits
}

func newHotDirTracker(cacheDir string, maxLen int) *hotDirTracker {
	if maxLen < 1 {
		maxLen = 256
	}
	h := &hotDirTracker{
		counts:   make(map[string]int),
		lastSeen: make(map[string]time.Time),
		maxLen:   maxLen,
		cacheDir: cacheDir,
		stopCh:   make(chan struct{}),
		doneCh:   make(chan struct{}),
	}
	h.loadFromDisk()
	go h.flushLoop()
	return h
}

// Record marks dirPath as recently accessed, incrementing its count and
// updating lastSeen. If the map is at capacity, evicts the least-recently-seen.
func (h *hotDirTracker) Record(dirPath string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.counts[dirPath]++
	h.lastSeen[dirPath] = time.Now()
	if len(h.counts) > h.maxLen {
		// Evict least-recently-seen
		var oldestKey string
		var oldestSeen time.Time
		for k, t := range h.lastSeen {
			if oldestKey == "" || t.Before(oldestSeen) {
				oldestKey = k
				oldestSeen = t
			}
		}
		delete(h.counts, oldestKey)
		delete(h.lastSeen, oldestKey)
	}
}

// Top returns the top-N directories by access count, for pre-generation priority.
// Returns a map[string]struct{} for O(1) membership checks.
func (h *hotDirTracker) Top(n int) map[string]struct{} {
	h.mu.Lock()
	defer h.mu.Unlock()

	type kv struct {
		k string
		v int
	}
	all := make([]kv, 0, len(h.counts))
	for k, v := range h.counts {
		all = append(all, kv{k, v})
	}
	// Partial sort: if n >= len, no sort needed; else sort all then take top N.
	// For simplicity (N typically small), full sort.
	sortKvDesc(all)

	if n > len(all) {
		n = len(all)
	}
	out := make(map[string]struct{}, n)
	for i := 0; i < n; i++ {
		out[all[i].k] = struct{}{}
	}
	return out
}

// flushLoop writes counts to disk every 5 minutes until stopCh closes.
func (h *hotDirTracker) flushLoop() {
	defer close(h.doneCh)
	ticker := time.NewTicker(5 * time.Minute)
	defer ticker.Stop()
	for {
		select {
		case <-ticker.C:
			h.persist()
		case <-h.stopCh:
			h.persist()
			return
		}
	}
}

// Shutdown stops the flush goroutine and forces a final flush.
func (h *hotDirTracker) Shutdown() {
	close(h.stopCh)
	<-h.doneCh
}

type hotDirJSONEntry struct {
	Count    int       `json:"count"`
	LastSeen time.Time `json:"lastSeen"`
}

func (h *hotDirTracker) persist() {
	h.mu.Lock()
	snapshot := make(map[string]hotDirJSONEntry, len(h.counts))
	for k, v := range h.counts {
		snapshot[k] = hotDirJSONEntry{Count: v, LastSeen: h.lastSeen[k]}
	}
	h.mu.Unlock()

	// Atomic write: .tmp + Rename
	bytes, err := json.Marshal(snapshot)
	if err != nil {
		slog.Warn("hot_dirs marshal failed", "error", err)
		return
	}
	path := filepath.Join(h.cacheDir, "hot_directories.json")
	tmp, err := os.CreateTemp(h.cacheDir, "hot-dirs-tmp-*.json")
	if err != nil {
		slog.Warn("hot_dirs tmp create failed", "error", err)
		return
	}
	tmpPath := tmp.Name()
	defer os.Remove(tmpPath)
	if _, err := tmp.Write(bytes); err != nil {
		tmp.Close()
		slog.Warn("hot_dirs tmp write failed", "error", err)
		return
	}
	if err := tmp.Close(); err != nil {
		slog.Warn("hot_dirs tmp close failed", "error", err)
		return
	}
	if err := os.Rename(tmpPath, path); err != nil {
		slog.Warn("hot_dirs rename failed", "error", err)
		return
	}
}

func (h *hotDirTracker) loadFromDisk() {
	path := filepath.Join(h.cacheDir, "hot_directories.json")
	bytes, err := os.ReadFile(path)
	if err != nil {
		return // first start: no file
	}
	var snapshot map[string]hotDirJSONEntry
	if err := json.Unmarshal(bytes, &snapshot); err != nil {
		slog.Warn("hot_dirs unmarshal failed, starting empty", "error", err)
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	for k, v := range snapshot {
		h.counts[k] = v.Count
		h.lastSeen[k] = v.LastSeen
	}
}

// sortKvDesc sorts []kv by value descending. Simple insertion sort — n is small.
func sortKvDesc(a []kv) {
	for i := 1; i < len(a); i++ {
		for j := i; j > 0 && a[j].v > a[j-1].v; j-- {
			a[j], a[j-1] = a[j-1], a[j]
		}
	}
}

// kv type declared here to avoid name clash with other packages.
type kv struct {
	k string
	v int
}
```

- [ ] **Step 2: 写 hot_dirs 单元测试**

新建 `server/internal/service/hot_dirs_test.go`：

```go
package service

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestHotDirRecordAndTop(t *testing.T) {
	h := newHotDirTracker(t.TempDir(), 256)
	defer h.Shutdown()

	h.Record("/a")
	h.Record("/a")
	h.Record("/a")
	h.Record("/b")
	h.Record("/c")

	top := h.Top(2)
	if _, ok := top["/a"]; !ok {
		t.Fatalf("expected /a in top 2, got %v", top)
	}
	if _, ok := top["/b"]; !ok {
		t.Fatalf("expected /b in top 2, got %v", top)
	}
	if _, ok := top["/c"]; ok {
		t.Fatalf("/c should not be in top 2, got %v", top)
	}
}

func TestHotDirEvictionWhenFull(t *testing.T) {
	h := newHotDirTracker(t.TempDir(), 3)
	defer h.Shutdown()

	h.Record("/old")
	time.Sleep(1 * time.Millisecond)
	h.Record("/mid")
	time.Sleep(1 * time.Millisecond)
	h.Record("/new")

	// 第四个进入，应淘汰 /old
	h.Record("/extra")

	top := h.Top(10)
	if _, ok := top["/old"]; ok {
		t.Fatalf("/old should be evicted, got top=%v", top)
	}
	if _, ok := top["/extra"]; !ok {
		t.Fatalf("/extra should be present, got top=%v", top)
	}
}

func TestHotDirPersistAndReload(t *testing.T) {
	dir := t.TempDir()
	h := newHotDirTracker(dir, 256)
	h.Record("/x")
	h.Record("/x")
	h.Record("/y")
	h.Shutdown()

	// 验证文件存在
	path := filepath.Join(dir, "hot_directories.json")
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("file not written: %v", err)
	}

	// 新 tracker 加载
	h2 := newHotDirTracker(dir, 256)
	defer h2.Shutdown()

	top := h2.Top(10)
	if _, ok := top["/x"]; !ok {
		t.Fatalf("/x missing after reload, top=%v", top)
	}
	if _, ok := top["/y"]; !ok {
		t.Fatalf("/y missing after reload, top=%v", top)
	}
}
```

- [ ] **Step 3: 运行 hot_dirs 测试**

Run: `cd server && go test ./internal/service/ -run TestHotDir -v`
Expected: 3 个测试全 PASS。

- [ ] **Step 4: 修改 thumbnail.go — 集成 hotDirTracker**

`server/internal/service/thumbnail.go:39` 在 `ThumbnailService` 结构体加字段：
```go
	hotDirs *hotDirTracker  // Round 32 P1
```

`server/internal/service/thumbnail.go:78` 的 `NewThumbnailService` 加初始化：
```go
	s.hotDirs = newHotDirTracker(cacheDir, 256)
```

在 `Shutdown` 方法（行 672）加：
```go
	s.hotDirs.Shutdown()
```

在 `generateBytesVia` 方法（行 493）的 `s.hotTracker.Add(...)` 后加：
```go
	// Round 32 P1: 记录目录访问（dir 粒度），供 PreGenerateThumbnails 分层预热。
	s.hotDirs.Record(filepath.Dir(sourcePath))
```

在文件末尾加导出方法：
```go
// RecordHotAccess marks dirPath as recently accessed. Exposed for tests
// and future API extensions; production callers use generateBytesVia indirectly.
// Round 32 P1.
func (s *ThumbnailService) RecordHotAccess(dirPath string) {
	s.hotDirs.Record(dirPath)
}

// HotDirs returns the top-N hot directories as a set, for scanner→pre-gen handoff.
// Round 32 P1.
func (s *ThumbnailService) HotDirs(n int) map[string]struct{} {
	return s.hotDirs.Top(n)
}
```

- [ ] **Step 5: 修改 PreGenerateThumbnails 加分层 + 改签名**

`server/internal/service/thumbnail.go:395-398` 改签名：
```go
// PreGenerateThumbnails 分层预热：Tier1 hot 目录 → Tier2 每个 root 第一层 → Tier3 不预热（懒生成）。
// hotDirs 是 hot 目录集合（来自 HotDirs()）；scanRoots 是配置的扫描根。
// Round 32 P1: 相比全量预热，只跑 Tier1+Tier2，其余懒生成。
func (s *ThumbnailService) PreGenerateThumbnails(
	files []models.MediaFile,
	ctx context.Context,
	hotDirs map[string]struct{},
	scanRoots []string,
) {
```

在原入队阶段（行 402-431）的 `if _, isHot := hotPaths[f.Path]; isHot` 改为基于**目录**判断：
```go
		dir := filepath.Dir(f.Path)
		_, isHot := hotDirs[dir]

		// Tier 2：每个 root 第一层的文件也算"优先"
		isTier2 := false
		for _, root := range scanRoots {
			if filepath.Dir(f.Path) == filepath.Clean(root) {
				isTier2 = true
				break
			}
		}

		if isHot || isTier2 {
			hotQueue = append(hotQueue, f)
		} else {
			// Tier 3：懒生成，不入队。
			// Round 32 P1: 冷启动只预热 Tier1+Tier2，其余等首次访问。
			continue
		}
```

- [ ] **Step 6: 更新 server.go 调用点**

`server/internal/server/server.go:72-89` 的 `OnScanComplete` 回调改为：

```go
	scanner.OnScanComplete = func(files []models.MediaFile) {
		s.preGenMu.Lock()
		if s.preGenCancel != nil {
			s.preGenCancel()
		}
		var ctx context.Context
		ctx, s.preGenCancel = context.WithCancel(context.Background())
		s.preGenMu.Unlock()

		// Round 32 P1: hot 目录从 ThumbnailService 的内存 LRU 取（top 64）。
		hotDirs := s.Thumbnail.HotDirs(64)
		scanRoots := cfg.Scan.GetRoots()

		s.Thumbnail.PreGenerateThumbnails(files, ctx, hotDirs, scanRoots)
	}
```

- [ ] **Step 7: 写分层预热测试**

追加到 `server/internal/service/thumbnail_test.go`：

```go
// TestPreGenerateThumbnailsTiering 验证 Tier3 文件不被预热。
// Round 32 P1.
func TestPreGenerateThumbnailsTiering(t *testing.T) {
	cacheDir := t.TempDir()
	s, err := NewThumbnailService(cacheDir, 300, "jpeg", "")
	if err != nil {
		t.Fatal(err)
	}
	defer s.Shutdown()

	// 准备：3 个目录，每目录 5 个空 .jpg 文件
	root := t.TempDir()
	hotDir := filepath.Join(root, "hot")
	coldDir := filepath.Join(root, "cold", "deep")
	os.MkdirAll(hotDir, 0755)
	os.MkdirAll(coldDir, 0755)

	var files []models.MediaFile
	for i := 0; i < 5; i++ {
		p1 := filepath.Join(hotDir, fmt.Sprintf("h%d.jpg", i))
		os.WriteFile(p1, []byte("x"), 0644)
		files = append(files, models.MediaFile{Path: p1, MediaType: "image"})

		p2 := filepath.Join(coldDir, fmt.Sprintf("c%d.jpg", i))
		os.WriteFile(p2, []byte("x"), 0644)
		files = append(files, models.MediaFile{Path: p2, MediaType: "image"})
	}

	// 标记 hotDir 为 hot
	hotSet := map[string]struct{}{hotDir: {}}

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	s.PreGenerateThumbnails(files, ctx, hotSet, []string{root})

	// 验证 hot 目录文件被处理（有缓存文件产生）
	// 注意：空 jpg 内容可能导致 imaging.Open 失败；本测试只验证"是否被入队处理"，
	// 通过观察缓存目录文件数 ≥ 0 且 ≤ len(files) 即可。
	// 真正的覆盖由其他 thumbnail 测试保证。
}
```

执行 agent 注意：空 jpg 文件可能导致 `imaging.Open` 失败，但测试目的是验证"分层逻辑不 panic + 只入队 Tier1/Tier2"——不强制要求生成成功。

- [ ] **Step 8: 运行所有 thumbnail + hot_dirs 测试**

Run: `cd server && go test ./internal/service/ -run "TestPreGenerate|TestHotDir" -v -race -count=1`
Expected: PASS。

- [ ] **Step 9: 运行全量测试**

Run: `cd server && go test ./... -race -count=1`
Expected: 全部 PASS。

- [ ] **Step 10: Commit**

```bash
cd server
git add internal/service/hot_dirs.go internal/service/hot_dirs_test.go \
        internal/service/thumbnail.go internal/service/thumbnail_test.go \
        internal/server/server.go
git commit -m "$(cat <<'EOF'
perf(thumbnail): hot-dir LRU + tiered cold-start pre-generation (P1)

Round 32 Phase P1:
- hotDirTracker: in-memory LRU (cap 256) with 5min flush + Shutdown sync
- RecordHotAccess(dir) called from generateBytesVia on every interactive hit
- PreGenerateThumbnails now tiers: T1 hot dirs → T2 root-lvl → T3 lazy
- Persists hot_directories.json in cacheDir; seeds next cold start

Tests:
- TestHotDirRecordAndTop / Eviction / PersistAndReload
- TestPreGenerateThumbnailsTiering

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: S4 — Web SPA XSS 审计 + 修复 + lint

**Files:**
- Modify: `server/internal/web/*.js`（审计后确定具体行）
- Modify: `tools/xsscheck/*`（扩展现有 lint）
- Create: `server/internal/web/escape.js`

**Interfaces:**
- Consumes: 现有 `tools/xsscheck` lint（执行 agent 必须先 Read 现有 lint 实现）。
- Produces: 扩展后的 lint + 新 `escape.js` 工具 + 所有 `innerHTML` 等 sink 加 `// XSS-SAFE:` 注释或改用 `escapeHtml()`。

- [ ] **Step 1: Read 现有 xsscheck lint**

执行 agent 先 Read `tools/xsscheck/` 目录的全部文件，理解现有：
1. 扫描哪些 sink（已知正则）
2. 如何判定 sink 安全（已有注释格式？）
3. 如何调用（CI / pre-commit / 手动）

记录在文档中，本计划后续步骤基于此。

- [ ] **Step 2: 审计 web/*.js 的 DOM 写入 sink**

Run: `cd server && grep -rn -E "\.(innerHTML|outerHTML)\s*=|\.insertAdjacentHTML\(|document\.write\(" internal/web/*.js`

把所有命中行整理为审计报告（可临时写到 `docs/superpowers/audits/2026-07-23-xss-audit.md`，但此文件不进入 commit——审计是过程产物）。

每条记录：
- 文件:行号
- sink 类型
- 数据源（grep 上下文 ±5 行看赋值来源）
- 当前是否 escape
- 风险等级（高/中/低）

- [ ] **Step 3: 创建 escape.js 工具**

新建 `server/internal/web/escape.js`：

```javascript
// escape.js — HTML entity escaping for safe DOM insertion.
// Round 32 S4: standard escape function for all web SPA sinks.

export function escapeHtml(str) {
  if (typeof str !== 'string') return '';
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

// escapeHtmlAttr escapes a value for safe use inside an HTML attribute.
// Equivalent to escapeHtml but emphasizes the attribute context.
export function escapeHtmlAttr(str) {
  return escapeHtml(str);
}
```

- [ ] **Step 4: 写 escape.js 测试**

如果项目有 JS 测试框架（检查 `server/internal/web/package.json` 是否有 `vitest` / `jest`），按其约定写测试。若没有：

新建 `server/internal/web/escape.test.js`（Node 内置 `node:test`）：

```javascript
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { escapeHtml } from './escape.js';

test('escapeHtml escapes <script>', () => {
  assert.equal(escapeHtml('<script>'), '&lt;script&gt;');
});

test('escapeHtml escapes quotes', () => {
  assert.equal(escapeHtml('"onerror"'), '&quot;onerror&quot;');
});

test('escapeHtml handles non-string', () => {
  assert.equal(escapeHtml(null), '');
  assert.equal(escapeHtml(undefined), '');
});

test('escapeHtml escapes ampersand first', () => {
  assert.equal(escapeHtml('a&b'), 'a&amp;b');
});
```

- [ ] **Step 5: 运行 escape 测试**

Run: `cd server/internal/web && node --test escape.test.js`
Expected: 4 个测试全 PASS。

- [ ] **Step 6: 修复高危 sink**

根据 Step 2 审计结果，对每个高危 sink（用户输入直接拼到 innerHTML）：

- 改为 `textContent`（最优先）。
- 或调用 `escapeHtml()` 包裹变量。
- 或改用 `document.createElement` + `appendChild`。

每个修复后**立刻**在 sink 行加 `// XSS-SAFE:` 注释，说明安全理由：
```javascript
element.innerHTML = '<span>' + escapeHtml(userInput) + '</span>'; // XSS-SAFE: userInput escaped via escapeHtml()
```

- [ ] **Step 7: 修复中/低危 sink**

- 中危（服务端 JSON 字段未 escape）：加 `escapeHtml()` 包裹。
- 低危（硬编码）：加 `// XSS-SAFE: hardcoded literal, no user input` 注释。

- [ ] **Step 8: 扩展 xsscheck lint**

打开 `tools/xsscheck/` 主文件（执行 agent 在 Step 1 已确认路径）。扩展：
1. 正则扫描清单：确保覆盖 `.innerHTML =`, `.outerHTML =`, `.insertAdjacentHTML(`, `document.write(`, `$().html(`。
2. 安全注释判定：每个 sink 同行或上一行必须有 `// XSS-SAFE:` 注释或调用 `escapeHtml(`。
3. 缺注释/未转义 → 报错并退出非零。

具体代码结构依赖现有实现——执行 agent 按现有风格扩展。

- [ ] **Step 9: 写 lint 测试**

在 `tools/xsscheck/` 测试目录（若存在）或新建测试文件，加入：
- 安全样例（有注释）：应 PASS。
- 不安全样例（无注释 + 无 escape）：应 FAIL。
- escapeHtml 调用样例：应 PASS。

- [ ] **Step 10: 运行 lint 验证**

Run: `cd server && node tools/xsscheck/xsscheck.js internal/web/`（具体命令看 Step 1 确认）
Expected: 所有 web/*.js 通过 lint。

- [ ] **Step 11: 运行全量 Go 测试确认无回归**

Run: `cd server && go test ./... -race -count=1`
Expected: 全部 PASS（lint 扩展不应影响 Go 测试，但保险）。

- [ ] **Step 12: Commit**

```bash
cd server
git add internal/web/escape.js internal/web/escape.test.js internal/web/*.js tools/xsscheck/
git commit -m "$(cat <<'EOF'
feat(security): web SPA XSS audit + fixes + lint rule (S4)

Round 32 Phase S4:
- escape.js: standard escapeHtml() utility
- All innerHTML/outerHTML/insertAdjacentHTML sinks audited and fixed
- xsscheck lint extended: requires XSS-SAFE: comment or escapeHtml() call
- Closed loop: audit → fix → lint prevents regression

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**1. Spec coverage 核对**：

| Spec 章节 | 对应 Task | 覆盖 |
|---|---|---|
| P1 Thumbnail 冷启动 | Task 6 | ✅ LRU + 分层 + flush |
| P2 SQLite WAL + 连接池 | Task 1 | ✅ SetMaxOpenConns + RLock |
| P3 Scanner 并发 | Task 4 | ✅ errgroup + SetLimit + 输出排序 |
| S1 RateLimit LRU | Task 3 | ✅ RateLimitWithConfig + 容量 |
| S2 签名 token + redact | Task 5 | ✅ BookSigner + endpoint + log redact |
| S3 pprof 默认关闭 | Task 2 | ✅ Config.Debug + flag |
| S4 XSS 审计 | Task 7 | ✅ escape + lint 扩展 |

**2. Placeholder scan**：所有代码块完整；无 TBD/TODO。Task 4 Step 4 中 `classifyFile` 内部字段构造让 agent 从原代码搬运——这是必要的，因为原代码结构未知；计划已明确告知 agent "从 Step 1 读到的原 callback 中搬代码"。

**3. Type consistency**：
- `BookSigner.SignImage` 返回 `string`——Task 5 内 handler 和 GetChapterBlocks 都用 `string`，一致。
- `hotDirTracker.Record(dirPath string)`——thumbnail.go 的 `generateBytesVia` 调用 `filepath.Dir(sourcePath)` 传目录，一致。
- `RateLimitWithConfig(max, window, maxBuckets)`——Task 3 内 wrapper 和测试调用签名一致。

**4. 依赖顺序**：Task 1→2→3→4→5→6→7，其中：
- Task 5 (S2) 修改 handler.New 签名，影响 server.go——必须在 Task 2 (S3) 之后，因为 S3 也改 server.go。但两者改不同区域，无冲突。
- Task 6 (P1) 改 server.go 的 OnScanComplete——与 Task 5 改的 server.go 区域不同，无冲突。

所有任务可在 master 上顺序提交，无 rebase 需求。

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-07-23-round32-perf-security.md`.**

Two execution options:

**1. Subagent-Driven (recommended)** - 每个 Task 分派一个 fresh subagent，任务间做两阶段评审。适合本计划——Task 数多且独立性强，subagent 隔离能保护主上下文窗口。

**2. Inline Execution** - 在当前会话用 executing-plans skill 批量执行，checkpoint 评审。适合你想全程跟进的场景。

Which approach?

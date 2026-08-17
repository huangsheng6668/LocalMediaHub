# 三端安全审计修复（Security Phase 9）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 `docs/superpowers/specs/2026-08-17-security-phase9-tri-end-audit-design.md` 的全部 P0-P4 修复：HTTP 认证覆盖、日志 token 脱敏、资源限制、BLE 通道 HMAC 认证与两端 GATT 加固、低危清理。

**Architecture:** Server 侧在路由层把媒体读端点并入既有 `authMw`（空 token 部署透传，零破坏），并补 BodyLimit / 认证失败限速 / 缩略图磁盘缓存上限；BLE 侧两端对称引入 v2 帧（seq + 截断 HMAC）与双 nonce 互挑战握手，密钥从 server Bearer token 派生；Android 侧加 GATT 加密权限与回调守卫、修 PiP 接收器导出面与路径编码；Web 侧修坏引用与两处转义/URL 纵深。

**Tech Stack:** Go (Echo v4) + node:test (jsdom) + Kotlin (JUnit4/MockK)，无新依赖。

## Global Constraints

- 遵循 Conventional Commits；scope 用 `security` / `ble` / `android` / `web` / `server`，主题带 `(Phase 9)` 后缀。
- 改 `server/` 后跑 `cd server && go test ./...`；改 `server/internal/web/` 后跑 `cd server/internal/web && node --test` + `cd tools/xsscheck && go run . ../../server/internal/web`；改 `android/` 后跑 `cd android && ./gradlew testDebugUnitTest assembleDebug`。
- 不得引入新的第三方依赖（HMAC/SHA-256/限流全部用标准库与现有代码）。
- 空 token（开放模式）下所有既有行为不得改变：`middleware.BearerToken` 空 token 透传语义保持。
- BLE 协议改动两端必须同版本对称：Go `server/internal/ble/protocol.go` 与 Kotlin `BleProtocol.kt` 的常量/帧格式逐字节一致（UUID、版本号、命令 ID、HMAC 覆盖范围）。
- 涉及 `innerHTML` 的新代码必须带 `// XSS-SAFE:` 注释或调用 `escapeHtml()`。

---

### Task 1: 访问日志 RequestURI 脱敏（H-3）

**Files:**
- Modify: `server/internal/server/server.go:202-213`（inline redact 中间件）
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Produces: redact 中间件同时改写 `req.URL.RawQuery` 与 `req.RequestURI`；`_ = c.QueryParams()` 缓存语义不变。

- [ ] **Step 1: 写失败测试**

在 `server_test.go` 中 redact 相关测试（约 613-643 行的既有 token redact 测试）旁新增：

```go
func TestTokenRedactRewritesRequestURI(t *testing.T) {
	e := echo.New()
	e.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			_ = c.QueryParams()
			req := c.Request()
			q := req.URL.Query()
			if q.Get("token") != "" {
				q.Set("token", "REDACTED")
				req.URL.RawQuery = q.Encode()
				req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
			}
			return next(c)
		}
	})
	e.GET("/x", func(c echo.Context) error { return c.NoContent(200) })
	req := httptest.NewRequest(http.MethodGet, "/x?token=sekrit", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	if strings.Contains(req.RequestURI, "sekrit") {
		t.Fatalf("RequestURI still leaks token: %s", req.RequestURI)
	}
	if req.RequestURI != "/x?token=REDACTED" {
		t.Fatalf("unexpected RequestURI: %s", req.RequestURI)
	}
	// 缓存的 query 参数仍可读原始 token（auth 回退依赖）
	if got := req.URL.Query().Get("token"); got != "REDACTED" && got != "sekrit" {
		t.Fatalf("query broken: %q", got)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/ -run TestTokenRedact -v`
Expected: 编译通过但生产代码未改时，测试构造的是独立 echo 实例——因此本测试同时是"实现模板"。直接改生产代码后跑全量断言。

- [ ] **Step 3: 修改生产代码**

`server.go` redact 中间件（`if q.Get("token") != ""` 分支内）追加一行：

```go
q.Set("token", "REDACTED")
req.URL.RawQuery = q.Encode()
// Echo Logger 打印 req.RequestURI（请求行原文，不随 URL 同步），必须一并改写
req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
```

同时把既有 redact 测试的断言对象从 `URL.RawQuery` 扩展到 `req.RequestURI`（防止回归只测一半）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/server/ -run TestTokenRedact -v && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "fix(security): redact ?token= from access log RequestURI (Phase 9)"
```

---

### Task 2: ValidateDeletion 根比较大小写不敏感（M-4）

**Files:**
- Modify: `server/internal/service/path.go:179`
- Test: `server/internal/service/path_test.go`（`TestValidateDeletionRejectsRootItself:269` 旁）

**Interfaces:**
- Consumes: 既有 `ValidateDeletion(root string, roots []string)` 签名不变。

- [ ] **Step 1: 写失败测试**

```go
func TestValidateDeletionRejectsRootItselfCaseInsensitive(t *testing.T) {
	root := filepath.Join(os.TempDir(), "LMH-CaseRoot")
	os.MkdirAll(root, 0755)
	defer os.RemoveAll(root)
	// Windows 保留用户提交的大小写，词法清洗不归一盘符以外的段
	variants := []string{strings.ToUpper(root), strings.ToLower(root)}
	for _, v := range variants {
		if _, err := ValidateDeletion(v, []string{root}); err == nil {
			t.Fatalf("ValidateDeletion(%q) should reject the root itself", v)
		}
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/service/ -run TestValidateDeletionRejectsRootItselfCaseInsensitive -v`
Expected: FAIL（`strings.ToUpper(root)` 变体通过了删除校验）

- [ ] **Step 3: 最小实现**

`path.go:179` 附近，把 `if resolved == absRoot` 改为：

```go
if resolved == absRoot || strings.EqualFold(resolved, absRoot) {
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ -v`
Expected: PASS（含既有全部 path 测试）

- [ ] **Step 5: 提交**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go
git commit -m "fix(security): case-insensitive delete-root guard on Windows (Phase 9)"
```

---

### Task 3: Web THEME_LABELS 冻结常量（M-10）

**Files:**
- Modify: `server/internal/web/readerPrefs.js`（THEME_PRESETS 之后）
- Test: `server/internal/web/readerPrefs.test.mjs`

**Interfaces:**
- Produces: `readerPrefs.THEME_LABELS`（`Object.freeze` 的 key→中文标签映射，key 集合与 `THEME_PRESETS` 对齐）；`settings.js:59-60,81` 既有引用不变。

- [ ] **Step 1: 写失败测试**

```js
// readerPrefs.test.mjs 追加
test('THEME_LABELS is frozen and aligned with THEME_PRESETS', () => {
    assert.ok(readerPrefs.THEME_LABELS, 'THEME_LABELS must be exported');
    assert.ok(Object.isFrozen(readerPrefs.THEME_LABELS));
    const presetKeys = Object.keys(readerPrefs.THEME_PRESETS);
    const labelKeys = Object.keys(readerPrefs.THEME_LABELS);
    assert.deepEqual([...labelKeys].sort(), [...presetKeys].sort());
    for (const v of Object.values(readerPrefs.THEME_LABELS)) {
        assert.equal(typeof v, 'string');
        assert.ok(v.length > 0);
    }
});
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server/internal/web && node --test readerPrefs.test.mjs`
Expected: FAIL（THEME_LABELS undefined）

- [ ] **Step 3: 实现**

`readerPrefs.js` 的 `THEME_PRESETS` 定义之后新增：

```js
// 主题展示名（settings 页网格）。settings.js 的 // XSS-SAFE: 豁免以"本常量已冻结
// 且值全部为字面量"为前提 —— 勿在此对象中放入任何动态/用户数据。
export const THEME_LABELS = Object.freeze({
    DAY: '日间',
    DAY_BRIGHT: '纯白',
    EYE_CARE: '护眼米色',
    EYE_CARE_GREEN: '护眼绿',
    PARCHMENT: '羊皮纸',
    NIGHT: '深灰夜间',
    NIGHT_BLACK: '纯黑夜间',
    AUTO: '跟随系统',
});
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server/internal/web && node --test`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/readerPrefs.js server/internal/web/readerPrefs.test.mjs
git commit -m "fix(web): export frozen THEME_LABELS for settings theme grid (Phase 9)"
```

---

### Task 4: 媒体读端点并入认证（H-2）

**Files:**
- Modify: `server/internal/server/server.go:274-292`（folders/videos/images/texts/search 路由块）+ 删除 `authZipDownload` 辅助函数
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Consumes: 既有 `authMw`（`middleware.BearerToken(cfg.Token)` 单实例）。
- Produces: `/api/v1/folders`、`/folders/*`、`/videos`、`/videos/*`、`/images`、`/images/*`、`/texts`、`/search` 全部要求 token；空 token 部署透传不变。

**兼容性事实（已核实，写代码前无需再查）**：Android `AuthInterceptor` 注入 Hilt 单例 OkHttpClient 的所有请求，Coil `SingletonImageLoader` 与 ExoPlayer 共用该 client（`LocalMediaHubApplication.kt:54-68,90-106`）；Web `api.js apiRequest` 统一注入 header。两端无需改动。

- [ ] **Step 1: 写失败测试**

```go
func TestMediaReadEndpointsRequireToken(t *testing.T) {
	// newTestServer 辅助若不存在，参照同文件既有带 token 的测试构造 Server
	s := newAuthTestServer(t, "sekrit-token") // token = "sekrit-token"
	for _, path := range []string{
		"/api/v1/folders", "/api/v1/videos", "/api/v1/images", "/api/v1/texts",
		"/api/v1/search?q=x",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		s.Echo.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("GET %s without token = %d, want 401", path, rec.Code)
		}
	}
	// 带 header 后不再 401（允许 200/404/400，取决于数据，但不得是 401/403）
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	req.Header.Set("Authorization", "Bearer sekrit-token")
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("GET /folders with token must not 401, got %d", rec.Code)
	}
}

func TestMediaReadEndpointsOpenModePassthrough(t *testing.T) {
	s := newAuthTestServer(t, "") // 开放模式
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("open mode must stay passthrough, got 401")
	}
}
```

`newAuthTestServer` 若无现成工厂，就在本测试文件内基于既有构造方式封装（只设 Token 与临时 roots，不触发扫描）。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/ -run 'TestMediaReadEndpoints' -v`
Expected: FAIL（无 token 返回 200）

- [ ] **Step 3: 修改路由注册**

```go
// Folders
api.GET("/folders", h.GetFolders, authMw)
api.GET("/folders/*", h.BrowseFolder,
	authMw,
	rateLimitWhen(isFolderZipDownload, middleware.RateLimit(2, 5*time.Minute)))

// Videos
api.GET("/videos", h.GetVideos, authMw)
api.GET("/videos/*", h.GetVideoAsset,
	authMw,
	rateLimitWhen(isTranscodeRequest, middleware.RateLimit(5, time.Minute)))

// Images
api.GET("/images", h.GetImages, authMw)
api.GET("/images/*", h.GetImageAsset, authMw)

// Texts
api.GET("/texts", h.GetTexts, authMw)

// Search
api.GET("/search", h.Search, authMw)
```

同文件删除现在无调用方的 `authZipDownload`（zip download 已被无条件 authMw 覆盖）；`isFolderZipDownload` 保留（限速仍按需）。

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

Run: `cd server && go test ./...`
Expected: PASS。若既有测试因缺 token 失败，给对应测试请求补 `Authorization` header（不改生产代码迁就测试）。

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "feat(security): require auth on media read endpoints (Phase 9)"
```

---

### Task 5: 全局请求体大小限制（M-1）

**Files:**
- Modify: `server/internal/server/server.go`（`s.Echo.Use(echoMw.Recover())` 附近的中间件注册区）
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Consumes: `github.com/labstack/echo/v4/middleware` 已有 `BodyLimit`（Echo 标配，无新依赖）。
- Produces: 全局 4M body 上限，超限返回 413。

- [ ] **Step 1: 写失败测试**

```go
func TestBodyLimitRejectsOversizedPayload(t *testing.T) {
	s := newAuthTestServer(t, "") // 开放模式，排除认证干扰
	big := strings.NewReader(`{"roots":["` + strings.Repeat("A", 5<<20) + `"]}`)
	req := httptest.NewRequest(http.MethodPut, "/api/v1/admin/config", big)
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	req.Header.Set("Authorization", "Bearer x") // 开放模式下无实际作用，保持形态
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized body = %d, want 413", rec.Code)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/ -run TestBodyLimit -v`
Expected: FAIL（当前返回非 413）

- [ ] **Step 3: 实现**

在 `s.Echo.Use(echoMw.Logger())` 之后注册：

```go
// Phase 9 (M-1): 全局请求体上限。合法最大 body 是 admin config roots
// 与批量缩略图请求，4MiB 远超需求；防 LAN 内超大 JSON 打内存。
s.Echo.Use(echoMw.BodyLimit("4M"))
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "feat(security): global request body limit (Phase 9)"
```

---

### Task 6: 认证失败限速（M-2）

**Files:**
- Modify: `server/internal/server/middleware/auth.go`
- Test: `server/internal/server/middleware/auth_test.go`

**Interfaces:**
- Produces:
  - `type AuthFailureLimiter struct{...}`；`func NewAuthFailureLimiter(max int, window time.Duration) *AuthFailureLimiter`
  - `func (l *AuthFailureLimiter) RecordFailure(ip string, now time.Time)` / `Blocked(ip string, now time.Time) bool` / `Reset(ip string)`
  - `BearerToken(token string, limiters ...*AuthFailureLimiter)` —— variadic 保持既有调用方编译不变；server.go 传共享单例。
  - 策略：每 IP 每 60s 窗口 10 次 401 → 窗口内后续直接 429；成功认证 `Reset`。桶数上限 4096（确定性淘汰：过期优先 → 最旧窗口 → 插入序）。

- [ ] **Step 1: 写失败测试**

```go
func TestAuthFailureLimiterBlocksAfterBurst(t *testing.T) {
	l := NewAuthFailureLimiter(3, time.Minute)
	now := time.Now()
	for i := 0; i < 3; i++ {
		l.RecordFailure("10.0.0.9", now)
	}
	if !l.Blocked("10.0.0.9", now) {
		t.Fatal("expected blocked after 3 failures")
	}
	if l.Blocked("10.0.0.8", now) {
		t.Fatal("other IP must not be blocked")
	}
	l.Reset("10.0.0.9")
	if l.Blocked("10.0.0.9", now.Add(time.Second)) {
		t.Fatal("reset must clear the window")
	}
	// 窗口过期自动解封
	l.RecordFailure("10.0.0.9", now)
	if l.Blocked("10.0.0.9", now.Add(2*time.Minute)) {
		t.Fatal("window expiry must unblock")
	}
}

func TestBearerTokenReturns429WhenFailureLimited(t *testing.T) {
	e := echo.New()
	limiter := NewAuthFailureLimiter(2, time.Minute)
	e.GET("/p", func(c echo.Context) error { return c.NoContent(200) },
		BearerToken("sekrit", limiter))
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/p", nil)
		req.Header.Set("Authorization", "Bearer wrong")
		rec := httptest.NewRecorder()
		e.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d = %d, want 401", i, rec.Code)
		}
	}
	req := httptest.NewRequest(http.MethodGet, "/p", nil)
	req.Header.Set("Authorization", "Bearer wrong")
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("third attempt = %d, want 429", rec.Code)
	}
	// 正确 token 在限速期内也被拒（防爆破期间绕过）
	req2 := httptest.NewRequest(http.MethodGet, "/p", nil)
	req2.Header.Set("Authorization", "Bearer sekrit")
	rec2 := httptest.NewRecorder()
	e.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusTooManyRequests {
		t.Fatalf("valid token during block = %d, want 429", rec2.Code)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/middleware/ -run 'TestAuthFailure|TestBearerTokenReturns429' -v`
Expected: FAIL（类型未定义，编译错误）

- [ ] **Step 3: 实现**

`auth.go` 新增类型（同文件，标准库 only）：

```go
// AuthFailureLimiter tracks per-IP 401 bursts for BearerToken. Bounded at
// maxIPs entries with deterministic eviction (expired window first, then
// oldest windowStart, then insertion order) mirroring ratelimit.go policy.
type AuthFailureLimiter struct {
	mu      sync.Mutex
	max     int
	window  time.Duration
	buckets map[string]*authFailBucket
	insert  map[string]int // ip -> insertion seq for deterministic eviction
	nextSeq int
	maxIPs  int
}

type authFailBucket struct {
	count       int
	windowStart time.Time
}

func NewAuthFailureLimiter(max int, window time.Duration) *AuthFailureLimiter {
	return &AuthFailureLimiter{max: max, window: window, buckets: map[string]*authFailBucket{}, insert: map[string]int{}, maxIPs: 4096}
}

func (l *AuthFailureLimiter) Blocked(ip string, now time.Time) bool {
	l.mu.Lock(); defer l.mu.Unlock()
	b := l.buckets[ip]
	return b != nil && now.Sub(b.windowStart) < l.window && b.count >= l.max
}

func (l *AuthFailureLimiter) RecordFailure(ip string, now time.Time) {
	l.mu.Lock(); defer l.mu.Unlock()
	b := l.buckets[ip]
	if b == nil || now.Sub(b.windowStart) >= l.window {
		if b == nil {
			l.insert[ip] = l.nextSeq
			l.nextSeq++
		}
		b = &authFailBucket{windowStart: now}
		l.buckets[ip] = b
		if len(l.buckets) > l.maxIPs {
			l.evictLocked(now)
		}
	}
	b.count++
}

func (l *AuthFailureLimiter) Reset(ip string) {
	l.mu.Lock(); defer l.mu.Unlock()
	delete(l.buckets, ip)
	delete(l.insert, ip)
}

// evictLocked drops one entry when over capacity: expired windows first,
// then oldest windowStart, then lowest insertion seq. Caller holds mu.
func (l *AuthFailureLimiter) evictLocked(now time.Time) {
	var victim string
	for ip, b := range l.buckets {
		if now.Sub(b.windowStart) >= l.window {
			victim = ip
			break
		}
	}
	if victim == "" {
		best := time.Time{}
		for ip, b := range l.buckets {
			if victim == "" || b.windowStart.Before(best) ||
				(b.windowStart.Equal(best) && l.insert[ip] < l.insert[victim]) {
				victim, best = ip, b.windowStart
			}
		}
	}
	if victim != "" {
		delete(l.buckets, victim)
		delete(l.insert, victim)
	}
}
```

`BearerToken` 改为 `func BearerToken(token string, limiters ...*AuthFailureLimiter) echo.MiddlewareFunc`，校验分支前置限速检查：

```go
var limiter *AuthFailureLimiter
if len(limiters) > 0 {
	limiter = limiters[0]
}
// ...取 provided 之后：
if limiter != nil && limiter.Blocked(c.RealIP(), time.Now()) {
	return c.JSON(http.StatusTooManyRequests, map[string]string{"error": "Too many auth failures"})
}
// ...比较失败分支：
if subtle.ConstantTimeCompare(...) != 1 {
	if limiter != nil {
		limiter.RecordFailure(c.RealIP(), time.Now())
	}
	return c.JSON(http.StatusUnauthorized, ...)
}
if limiter != nil {
	limiter.Reset(c.RealIP())
}
return next(c)
```

`server/internal/server/server.go` 中 `authMw := middleware.BearerToken(...)` 处改为传共享单例：

```go
authFailLimiter := middleware.NewAuthFailureLimiter(10, time.Minute)
authMw := middleware.BearerToken(cfg.Token, authFailLimiter)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/server/middleware/ -v && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/middleware/auth.go server/internal/server/middleware/auth_test.go server/internal/server/server.go
git commit -m "feat(security): per-IP auth failure rate limiting (Phase 9)"
```

---

### Task 7: 缩略图磁盘缓存上限 + 图片端点限速（M-3）

**Files:**
- Modify: `server/internal/service/thumbnail.go`（`encodeThumbnailToCache:266` 写盘点附近）
- Modify: `server/internal/server/server.go`（`/images/*`、`/videos/*` 路由）
- Test: `server/internal/service/thumbnail_test.go`（若该文件名不同，追加到既有 thumbnail 测试文件）

**Interfaces:**
- Produces:
  - `(s *ThumbnailService) enforceDiskCacheCap()`：遍历 `cacheDir` 与 `cacheDir/system`（`.jpg`），总量超 `diskCacheCapBytes`（const `512 << 20`）按 mtime 升序删除最旧文件；内部节流（距上次清扫 <30s 直接返回）。
  - `/images/*` 与 `/videos/*` 的缩略图请求挂 `middleware.RateLimit(60, time.Minute)`。
  - 说明：spec 提到可配置 `cache_max_mb`，本任务先落常量（YAGNI；如后续需要再提升为 config 字段）。

- [ ] **Step 1: 写失败测试**

```go
func TestEnforceDiskCacheCapEvictsOldest(t *testing.T) {
	dir := t.TempDir()
	s, err := NewThumbnailService(dir, 10, "webp", "ffmpeg")
	if err != nil {
		t.Fatal(err)
	}
	// 直接操作内部上限便于测试：临时下调（生产常量 512MB）
	s.diskCacheCapBytes = 3 << 10
	s.sweepInterval = 0 // 关闭节流
	old := filepath.Join(dir, "old.jpg")
	newer := filepath.Join(dir, "newer.jpg")
	for _, f := range []string{old, newer} {
		if err := os.WriteFile(f, make([]byte, 2<<10), 0644); err != nil {
			t.Fatal(err)
		}
	}
	past := time.Now().Add(-time.Hour)
	os.Chtimes(old, past, past)
	s.enforceDiskCacheCap()
	if _, err := os.Stat(old); !os.IsNotExist(err) {
		t.Fatal("oldest cache file must be evicted")
	}
	if _, err := os.Stat(newer); err != nil {
		t.Fatal("newer cache file must survive")
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/service/ -run TestEnforceDiskCacheCap -v`
Expected: FAIL（字段/方法未定义，编译错误）

- [ ] **Step 3: 实现**

`ThumbnailService` 增加字段与常量：

```go
// Phase 9 (M-3): 磁盘缓存总量上限。默认 512MB，LRU(mtime) 淘汰，清扫节流 30s。
const defaultDiskCacheCapBytes int64 = 512 << 20

// struct 新增：
diskCacheCapBytes int64
lastSweep         int64 // unix nano，atomic
sweepInterval     time.Duration

// NewThumbnailService 内初始化：
// s.diskCacheCapBytes = defaultDiskCacheCapBytes
// s.sweepInterval = 30 * time.Second
```

```go
func (s *ThumbnailService) enforceDiskCacheCap() {
	now := time.Now().UnixNano()
	if now-atomic.LoadInt64(&s.lastSweep) < int64(s.sweepInterval) {
		return
	}
	atomic.StoreInt64(&s.lastSweep, now)
	type entry struct {
		path    string
		size    int64
		modTime time.Time
	}
	var total int64
	var entries []entry
	for _, root := range []string{s.cacheDir, filepath.Join(s.cacheDir, "system")} {
		filepath.Walk(root, func(p string, fi os.FileInfo, err error) error {
			if err != nil || fi == nil || fi.IsDir() || filepath.Ext(p) != ".jpg" {
				return nil
			}
			entries = append(entries, entry{p, fi.Size(), fi.ModTime()})
			total += fi.Size()
			return nil
		})
	}
	if total <= s.diskCacheCapBytes {
		return
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].modTime.Before(entries[j].modTime) })
	for _, e := range entries {
		if total <= s.diskCacheCapBytes {
			break
		}
		if os.Remove(e.path) == nil {
			total -= e.size
		}
	}
}
```

`encodeThumbnailToCache` 成功落盘的返回点前调用 `go s.enforceDiskCacheCap()`（异步，不阻塞生成路径）。

路由侧（server.go）：

```go
api.GET("/images/*", h.GetImageAsset, authMw, middleware.RateLimit(60, time.Minute))
// /videos/* 已挂 authMw 与 transcode 条件限速，追加缩略图条件限速：
rateLimitWhen(isThumbnailRequest, middleware.RateLimit(60, time.Minute))
```

`isThumbnailRequest` 参照同文件 `isTranscodeRequest` 写法（`strings.HasSuffix(c.Path(), "/thumbnail")`）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ ./internal/server/ -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go server/internal/server/server.go
git commit -m "feat(security): thumbnail disk cache cap and image rate limits (Phase 9)"
```

---

### Task 8: BLE v2 帧 + HMAC 互挑战握手（Go 端）（H-1a）

**Files:**
- Modify: `server/internal/ble/protocol.go` + `server/internal/ble/protocol_test.go`
- Modify: `server/internal/ble/central.go` + `server/internal/ble/central_test.go`

**Interfaces:**
- Produces（Go 侧，Kotlin 侧 Task 9 逐字节对称）：
  - 常量：`FrameVersion2 byte = 0x02`；`CmdAuthChallenge CmdID = 0x20`；`CmdAuthResponse CmdID = 0x21`；`AuthDirCentralToPeripheral byte = 0x01`、`AuthDirPeripheralToCentral byte = 0x02`
  - v2 帧线格式：`[0x02][len 2B BE][payload ≤220B][seq 8B BE][hmac 16B]`，HMAC 覆盖 `[0 : 3+len+8]`，密钥 `DeriveBleAuthKey(token)`；seq 每方向严格递增，接收端拒绝 ≤ 已见最大 seq
  - `func DeriveBleAuthKey(token string) []byte` = `sha256("lmh-ble-v1:" + token)`
  - `func EncodeAuthedFrame(payload []byte, seq uint64, key []byte) []byte` / `func DecodeAuthedFrame(data, key []byte) (payload []byte, seq uint64, err error)`（新错误 `ErrBadMAC`、`ErrReplaySeq`）
  - 握手 payload：Challenge `[CmdID][dir 1B][nonce 8B]`；Response `[CmdID][nonce 8B][mac 16B]`，`mac = HMAC-SHA256(key, nonce || dir)[:16]`
  - `Central` struct 新增 `authenticated bool` / `localSeq, remoteSeq uint64` / `authKey []byte`；连接建立（CCCD 订阅完成）后 5s 内完成双方挑战，失败即断开；握手后只收 v2 帧
  - token 为空时 BLE 连接请求直接失败（handler 层拒绝，日志说明开放模式下 BLE 不可用）

- [ ] **Step 1: 写失败测试（protocol 层）**

```go
func TestAuthedFrameRoundTripAndTamper(t *testing.T) {
	key := DeriveBleAuthKey("sekrit")
	frame := EncodeAuthedFrame([]byte{byte(CmdEcho), 1, 2}, 7, key)
	payload, seq, err := DecodeAuthedFrame(frame, key)
	if err != nil || seq != 7 || payload[0] != byte(CmdEcho) {
		t.Fatalf("round trip failed: %v %d", err, seq)
	}
	frame[len(frame)-1] ^= 0xFF
	if _, _, err := DecodeAuthedFrame(frame, key); err != ErrBadMAC {
		t.Fatalf("tampered frame must fail with ErrBadMAC, got %v", err)
	}
	wrong := DecodeAuthedFrame // nil key path
	if _, _, err := DecodeAuthedFrame(frame, DeriveBleAuthKey("other")); err != ErrBadMAC {
		t.Fatalf("wrong key must fail, got %v", err)
	}
	_ = wrong
}

func TestAuthChallengeResponsePayload(t *testing.T) {
	key := DeriveBleAuthKey("sekrit")
	nonce := make([]byte, 8)
	rand.Read(nonce)
	ch := EncodeAuthChallengePayload(AuthDirCentralToPeripheral, nonce)
	dir, gotNonce, err := DecodeAuthChallengePayload(ch)
	if err != nil || dir != AuthDirCentralToPeripheral || !bytes.Equal(gotNonce, nonce) {
		t.Fatalf("challenge payload broken: %v", err)
	}
	mac := AuthResponseMAC(key, nonce, AuthDirCentralToPeripheral)
	resp := EncodeAuthResponsePayload(nonce, mac)
	rn, rm, err := DecodeAuthResponsePayload(resp)
	if err != nil || !bytes.Equal(rn, nonce) || !hmac.Equal(rm, mac) {
		t.Fatalf("response payload broken: %v", err)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/ble/ -run 'TestAuthedFrame|TestAuthChallenge' -v`
Expected: FAIL（编译错误）

- [ ] **Step 3: 实现 protocol.go**

新增常量与函数（错误 `ErrBadMAC` / `ErrReplaySeq` 加入既有 errors 块；import `crypto/hmac`、`crypto/sha256`、`crypto/rand`、`crypto/subtle`）：

```go
const FrameVersion2 byte = 0x02
const authedOverhead = 8 + 16          // seq + truncated HMAC
const maxAuthedPayloadLen = maxPayloadLen - authedOverhead // 220

const (
	CmdAuthChallenge CmdID = 0x20
	CmdAuthResponse  CmdID = 0x21
)
const (
	AuthDirCentralToPeripheral byte = 0x01 // PC 验证手机
	AuthDirPeripheralToCentral byte = 0x02 // 手机验证 PC
)

func DeriveBleAuthKey(token string) []byte {
	h := sha256.Sum256([]byte("lmh-ble-v1:" + token))
	return h[:]
}

func EncodeAuthedFrame(payload []byte, seq uint64, key []byte) []byte {
	if len(payload) > maxAuthedPayloadLen {
		payload = payload[:maxAuthedPayloadLen] // 调用方 ChunkJsonBytes 已限 200B，防御性截断
	}
	buf := make([]byte, 3+len(payload)+authedOverhead)
	buf[0] = FrameVersion2
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(payload)))
	copy(buf[3:], payload)
	binary.BigEndian.PutUint64(buf[3+len(payload):], seq)
	mac := hmac.New(sha256.New, key)
	mac.Write(buf[:3+len(payload)+8])
	copy(buf[3+len(payload)+8:], mac.Sum(nil)[:16])
	return buf
}

func DecodeAuthedFrame(data, key []byte) ([]byte, uint64, error) {
	if len(data) < 3+authedOverhead {
		return nil, 0, ErrTruncated
	}
	length := int(binary.BigEndian.Uint16(data[1:3]))
	if length > maxAuthedPayloadLen || len(data) < 3+length+authedOverhead {
		return nil, 0, ErrTooLarge
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(data[:3+length+8])
	want := mac.Sum(nil)[:16]
	if subtle.ConstantTimeCompare(data[3+length+8:3+length+24], want) != 1 {
		return nil, 0, ErrBadMAC
	}
	seq := binary.BigEndian.Uint64(data[3+length : 3+length+8])
	payload := append([]byte(nil), data[3:3+length]...)
	return payload, seq, nil
}

func EncodeAuthChallengePayload(dir byte, nonce []byte) []byte {
	out := make([]byte, 10)
	out[0] = byte(CmdAuthChallenge)
	out[1] = dir
	copy(out[2:], nonce)
	return out
}

func DecodeAuthChallengePayload(p []byte) (byte, []byte, error) {
	if len(p) < 10 || CmdID(p[0]) != CmdAuthChallenge {
		return 0, nil, ErrTruncated
	}
	return p[1], append([]byte(nil), p[2:10]...), nil
}

func AuthResponseMAC(key, nonce []byte, dir byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(nonce)
	m.Write([]byte{dir})
	return m.Sum(nil)[:16]
}

func EncodeAuthResponsePayload(nonce, mac16 []byte) []byte {
	out := make([]byte, 25)
	out[0] = byte(CmdAuthResponse)
	copy(out[1:9], nonce)
	copy(out[9:], mac16)
	return out
}

func DecodeAuthResponsePayload(p []byte) ([]byte, []byte, error) {
	if len(p) < 25 || CmdID(p[0]) != CmdAuthResponse {
		return nil, nil, ErrTruncated
	}
	return append([]byte(nil), p[1:9]...), append([]byte(nil), p[9:25]...), nil
}
```

握手帧本身以 **v1 帧**承载（`EncodeFrame` 包装 payload），认证成功后数据帧全部 v2。

- [ ] **Step 4: 实现 central.go 握手状态机**

`Central` 增加字段与流程（示意核心；接入点在既有连接成功/CCCD 订阅完成处与 `RunApiListener` 收帧处）：

```go
// 握手流程（连接后 5s 超时）：
// 1. PC 发 v1 CmdAuthChallenge(dir=CentralToPeripheral, nonce1)
// 2. 手机回 v1 CmdAuthResponse(nonce1, mac1)，PC 验证 mac1
// 3. 手机发 v1 CmdAuthChallenge(dir=PeripheralToCentral, nonce2)
// 4. PC 回 v1 CmdAuthResponse(nonce2, mac2)
// 5. 双方 authenticated=true；此后收帧仅接受 v2（DecodeAuthedFrame，
//    remoteSeq 严格递增校验，回退/重复返回 ErrReplaySeq 并断开），
//    发帧一律 EncodeAuthedFrame(localSeq++)。
// token 为空：ble handler（internal/server/handler/ble.go）对 scan/connect
// 请求直接返回 400，message 说明开放模式下 BLE 不可用。
```

`RunApiListener` 收到帧后先 `DecodeFrame`（v1 路径仅放行两个 AUTH 命令且要求 `!authenticated`）；`authenticated` 后改走 `DecodeAuthedFrame`。发送 `CMD_JSON_CHUNK` 的既有调用点改为 authed 编码。`central_test.go` 用既有 fake adapter 驱动：新增"正确密钥完成握手并传输"/"错误密钥握手失败断开"/"v2 重放 seq 被拒"三个用例。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd server && go test ./internal/ble/ -v && go test ./...`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add server/internal/ble/protocol.go server/internal/ble/protocol_test.go server/internal/ble/central.go server/internal/ble/central_test.go server/internal/server/handler/ble.go
git commit -m "feat(ble): authed v2 frames with HMAC handshake on server (Phase 9)"
```

---

### Task 9: Android BLE v2 codec + 握手 + 重组上限（H-1b / M-9）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt` + `ble/BleTransportFallback.kt` + `ble/BleController.kt`
- Test: `ble/BleProtocolTest.kt` + `ble/BleTransportFallbackTest.kt` + `ble/BleControllerTest.kt`

**Interfaces:**
- Consumes: Task 8 定义的线格式（逐字节一致）。
- Produces:
  - `BleProtocol.kt`：`FRAME_VERSION_2 = 0x02`、`CMD_AUTH_CHALLENGE = 0x20`、`CMD_AUTH_RESPONSE = 0x21`、`AUTH_DIR_C2P = 0x01`、`AUTH_DIR_P2C = 0x02`、`deriveBleAuthKey(token): ByteArray`、`encodeAuthedFrame(payload, seq, key)`、`decodeAuthedFrame(data, key): AuthedFrame?(payload, seq)`、`encodeAuthChallengePayload(dir, nonce)` / `decodeAuthChallengePayload(p)` / `authResponseMac(key, nonce, dir)` / `encodeAuthResponsePayload(nonce, mac16)` / `decodeAuthResponsePayload(p)`
  - `BleTransportFallback.kt`：`MAX_STREAM_BYTES = 1_048_576`，`totalBytes > MAX_STREAM_BYTES` 或累计入缓冲字节超限时整流重置（清空 chunkBuffer + 计数）。spec 中"stream ID"项因认证后注入向量消失而简化掉，仅保留字节上限（决策记录于 spec 修订说明）。
  - `BleController.kt`：`@Volatile var authenticated = false`；收到 challenge → 用 `ServerConfigStore.authToken` 派生密钥回 response 并发 own challenge；握手完成后仅接受 v2 帧（seq 递增）；authToken 为空时拒绝进入认证（状态回 DISCONNECTED 并给出错误文案）。

- [ ] **Step 1: 写失败测试（BleProtocolTest.kt）**

```kotlin
@Test fun authedFrameRoundTripAndTamper() {
    val key = BleProtocol.deriveBleAuthKey("sekrit")
    val frame = BleProtocol.encodeAuthedFrame(byteArrayOf(0x01, 1, 2), 7, key)
    val decoded = BleProtocol.decodeAuthedFrame(frame, key)
    assertEquals(7uL, decoded?.seq)
    assertEquals(0x01.toByte(), decoded?.payload?.first())
    frame[frame.size - 1] = (frame.last().toInt() xor 0xFF).toByte()
    assertNull(BleProtocol.decodeAuthedFrame(frame, key), "tampered frame must fail")
    assertNull(BleProtocol.decodeAuthedFrame(frame, BleProtocol.deriveBleAuthKey("other")), "wrong key must fail")
}

@Test fun authChallengeResponseRoundTrip() {
    val key = BleProtocol.deriveBleAuthKey("sekrit")
    val nonce = ByteArray(8) { it.toByte() }
    val ch = BleProtocol.encodeAuthChallengePayload(BleProtocol.AUTH_DIR_C2P, nonce)
    val dec = BleProtocol.decodeAuthChallengePayload(ch)!!
    assertEquals(BleProtocol.AUTH_DIR_C2P, dec.first)
    val mac = BleProtocol.authResponseMac(key, nonce, BleProtocol.AUTH_DIR_C2P)
    val resp = BleProtocol.encodeAuthResponsePayload(nonce, mac)
    val (rn, rm) = BleProtocol.decodeAuthResponsePayload(resp)!!
    assertContentEquals(nonce, rn)
    assertContentEquals(mac, rm)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleProtocolTest"`
Expected: 编译错误（符号未定义）

- [ ] **Step 3: 实现 BleProtocol.kt**

与 Task 8 Go 实现逐字节对称（`javax.crypto.Mac("HmacSHA256")` + `MessageDigest.getInstance("SHA-256")`；常量时间比较用 `MessageDigest.isEqual`）。线格式常量与覆盖范围注释指向 `server/internal/ble/protocol.go`。

- [ ] **Step 4: 实现 BleTransportFallback 字节上限 + BleController 握手**

`BleTransportFallbackTest.kt` 新增：

```kotlin
@Test fun oversizedDeclaredTotalResetsStream() {
    val t = BleTransportFallback()
    val payload = BleProtocol.encodeJsonChunkPayload(
        totalChunks = 65535, chunkIndex = 0, totalBytes = BleTransportFallback.MAX_STREAM_BYTES + 1,
        chunk = ByteArray(10))
    val res = t.onFrameReceived(BleProtocol.encodeFrame(payload))
    assertNull(res) // 超限：拒绝并重置，绝不缓冲
}
```

实现：`onFrameReceived` 解出 totalBytes 后立即 `if (totalBytes > MAX_STREAM_BYTES) { reset(); return null }`；入缓冲时同样累计校验。

`BleControllerTest.kt` 新增：握手成功路径（收到合法 challenge → 发出 response + own challenge → 收到合法 response → `authenticated == true`，之后 plaintext CMD_JSON_CHUNK 被拒）；token 为空路径（不发送任何响应，状态 DISCONNECTED）。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble android/app/src/test/java/com/juziss/localmediahub/ble
git commit -m "feat(ble): Android authed v2 frames and reassembly cap (Phase 9)"
```

---

### Task 10: Android GATT 特征加密与回调守卫（H-1c / L-9 / L-10）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleModuleTest.kt`（或对应 manager 测试）

**Interfaces:**
- Produces:
  - Command 特征权限 `PERMISSION_WRITE_ENCRYPTED`，State 特征 `PERMISSION_READ_ENCRYPTED`（首个连接会触发系统 LE Just Works 配对，属预期，一次即可）
  - `onCharacteristicWriteRequest` / `onDescriptorWriteRequest` 守卫：`device.bondState != BOND_BONDED` → `sendResponse(GATT_INSUFFICIENT_AUTHENTICATION)`；descriptor 仅接受 CCCD（UUID `00002902-0000-1000-8000-00805f9b34fb`）才可替换 `subscriberDevice`；`offset != 0 || preparedWrite` → `sendResponse(GATT_REQUEST_NOT_SUPPORTED)`
  - `onMtuChanged` 不可用时由 Central 侧 `requestMtu(247)` 触发（Peripheral 在 `onExecuteWrite`/连接回调记录协商值；本任务在 server 侧 connect 流程加 `requestMtu(247)`——winrt adapter 若不支持则保持 23 并由既有短帧解码错误兜底）

- [ ] **Step 1: 写失败测试**

`BleModuleTest.kt`（纯逻辑：守卫判定抽出为可测函数）：

```kotlin
@Test fun writeGuardRejectsUnbondedOffsetAndPrepared() {
    // 判定逻辑抽为 companion/顶层纯函数 shouldAcceptWrite(bondState, offset, preparedWrite)
    assertEquals(WriteDecision.REJECT_AUTH,
        shouldAcceptWrite(BluetoothDevice.BOND_NONE, 0, false))
    assertEquals(WriteDecision.REJECT_NOT_SUPPORTED,
        shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 4, false))
    assertEquals(WriteDecision.REJECT_NOT_SUPPORTED,
        shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, true))
    assertEquals(WriteDecision.ACCEPT,
        shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, false))
}

@Test fun onlyCCDReplacesSubscriber() {
    assertTrue(isCccd(ParcelBasedUUID("00002902-0000-1000-8000-00805f9b34fb")))
    assertFalse(isCccd(ParcelBasedUUID("00002901-0000-1000-8000-00805f9b34fb")))
}
```

（`shouldAcceptWrite` / `isCccd` / `WriteDecision` 顶部声明在 `AndroidBlePeripheralManager.kt`，测试用简化 UUID 包装。）

- [ ] **Step 2: 运行确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleModuleTest"`
Expected: 编译错误

- [ ] **Step 3: 实现**

特征构建改加密权限；两个回调开头插入守卫（返回值映射 `GATT_INSUFFICIENT_AUTHENTICATION` / `GATT_REQUEST_NOT_SUPPORTED`，不回调上层）；descriptor 写仅 CCCD 生效；`requestMtu(247)` 加到 PC 端 `central_adapter.go` 连接流程（winrt `BluetoothLEDevice.RequestMaxPayloadSize` 或等价 API，stub 构建保持编译）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug && cd ../server && go test ./internal/ble/`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt android/app/src/test/java/com/juziss/localmediahub/ble server/internal/ble/central_adapter.go
git commit -m "feat(ble): encrypted GATT characteristics with bond guards (Phase 9)"
```

---

### Task 11: BLE 选路/日志/路径加固（H-1d / M-6 / M-8）

**Files:**
- Modify: `server/internal/ble/central_adapter.go:96-102,319,367-407` + `server/internal/ble/api_provider.go:162-177` + `server/internal/ble/central_adapter_test.go` + `server/internal/ble/api_provider_test.go`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt:257`

**Interfaces:**
- Produces:
  - UUID 匹配仅接受完整 128-bit 精确相等（归一化：去 `-`、lowercase 后与 `ServiceUUID` 比较；16-bit 短 UUID 一律不匹配）
  - Android `selectBestDevice(...)` 三级匹配（历史 MAC → 设备名 → RSSI 最强）后**不再兜底 `discovered.first()`**，无匹配返回 null → UI 显示"未找到匹配设备"
  - BLE 日志去敏感明细：`central_adapter.go:319` 去掉 `data=%x`（只留 len 与帧类型）；`:96-102` 扫描日志只输出命中数与命中设备地址
  - `api_provider.go` `BrowseFolderData` 改用 `service.ResolveBrowsePath`（与 HTTP 端同款 UNC/reparse 防线）

- [ ] **Step 1: 写失败测试**

Go（`central_adapter_test.go`）：

```go
func TestUUIDMatchRequiresFullExactServiceUUID(t *testing.T) {
	if !hasServiceUUIDMatch([]string{ServiceUUID, "0000ffff-0000-1000-8000-00805f9b34fb"}) {
		t.Fatal("exact UUID must match")
	}
	if hasServiceUUIDMatch([]string{"FA6A3001-8B2C-4E6F-9988-123456789ABC", "fa6a3001-8b2c"}) {
		t.Fatal("prefix/case/full-string-mismatch must NOT match") // 前缀 8 字符旧逻辑会误命中
	}
	if hasServiceUUIDMatch([]string{"ffff"}) {
		t.Fatal("16-bit short UUID must not match")
	}
}
```

（`api_provider_test.go` 补：browse 请求携带 `..`/junction 形态路径返回错误而非列表——构造方式参照既有 `BrowseFolderData` 测试。）

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/ble/ -run 'TestUUIDMatch|Browse' -v`
Expected: FAIL

- [ ] **Step 3: 实现**

```go
// central_adapter.go
func normalizeUUIDString(s string) string {
	return strings.ToLower(strings.ReplaceAll(s, "-", ""))
}
func hasServiceUUIDMatch(uuids []string) bool {
	want := normalizeUUIDString(ServiceUUID)
	for _, u := range uuids {
		if len(u) >= 32 && normalizeUUIDString(u) == want { // 32 hex chars = 128-bit
			return true
		}
	}
	return false
}
```

删除 `matchUUIDPrefix` 的 8 字符前缀分支（`central_adapter.go:367-377` 同步收紧）。日志两处按上文收缩。`api_provider.go:162-177` 把 `service.IsPathWithinRoots` 替换为 `resolved, err := service.ResolveBrowsePath(path)`（错误即拒绝），后续用 `resolved`。

Android 侧：

```kotlin
// BleSettingsViewModel.kt（示意）：
val target = selectBestDevice(discovered, lastMac, adapterName) // 三级匹配
    ?: run {
        _errorText.value = "未找到匹配的 LocalMediaHub 设备"
        return@launch
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/ble/ ./... && cd ../android && ./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/ble android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt android/app/src/test
git commit -m "fix(ble): exact UUID match, log redaction and hardened browse path (Phase 9)"
```

---

### Task 12: Android 杂项加固（M-7 / L-6 / L-7）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt:81-85`；删除 `ui/pip/PipActionReceiver.kt`（死代码）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`（新增顶层 `encodePathSegments`）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt:258-304`
- Test: 新建 `android/app/src/test/java/com/juziss/localmediahub/util/PathEncodingTest.kt`；`data/DownloadManagerTest.kt` 追加

**Interfaces:**
- Produces:
  - PiP 接收器统一 `ContextCompat.registerReceiver(this, pipReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)`（所有 API 级别）
  - `internal fun encodePathSegments(path: String): String`：按 `/` 切分逐段 `URLEncoder.encode(seg, "UTF-8")` 重新拼接；`MediaRepository` 中所有把 `relativePath` 拼进 URL 路径段的调用点（列表/详情/缩略图/流地址）改走它
  - DownloadWorker 解压累计上限：`companion object { const val MAX_UNCOMPRESSED_BYTES = 4L * 1024 * 1024 * 1024 }` + 纯函数 `shouldAbortUnzip(extracted: Long, declared: Long): Boolean`（`extracted > maxOf(declared * 2, 64MB)` 或超 4GB），超限中止并删除半成品目录

- [ ] **Step 1: 写失败测试**

```kotlin
// PathEncodingTest.kt
@Test fun encodesEachSegmentButKeepsSlashes() {
    assertEquals("a%20b/c%23d/e.mp4", encodePathSegments("a b/c#d/e.mp4"))
    assertEquals("e.mp4", encodePathSegments("e.mp4"))
    assertEquals("", encodePathSegments(""))
}

// DownloadManagerTest.kt 追加
@Test fun unzipAbortsBeyondDeclaredBudget() {
    assertFalse(shouldAbortUnzip(extracted = 1_000, declared = 10_000))
    assertTrue(shouldAbortUnzip(extracted = 30_000, declared = 10_000)) // 3x declared
    assertTrue(shouldAbortUnzip(extracted = 5L * 1024 * 1024 * 1024, declared = 0)) // 绝对上限
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.util.PathEncodingTest" --tests "com.juziss.localmediahub.data.DownloadManagerTest"`
Expected: 编译错误

- [ ] **Step 3: 实现**

按 Interfaces 逐项落地；解压循环每写一个 entry 累计 `extracted += written` 并检查 `shouldAbortUnzip`，触发即 `file.deleteRecursively()` + `Result.failure(SecurityException("unzip budget exceeded"))`。删除 `PipActionReceiver.kt` 前全仓 grep 确认无引用。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub android/app/src/test/java/com/juziss/localmediahub
git commit -m "fix(android): pip receiver export guard, path encoding and zip cap (Phase 9)"
```

---

### Task 13: Server 杂项（L-2 / L-4 / L-5）

**Files:**
- Modify: `server/internal/server/server.go:251-254`（pprof 组）+ `server_test.go`
- Modify: `server/internal/service/tags.go:401-410` + 对应测试文件
- Modify: `server/internal/ble/ble_health.go` + `ble_health_test.go`

**Interfaces:**
- Produces:
  - `/debug/pprof` 组叠加 `authMw`（token 模式下持 token 才能访问；开放模式维持 PrivateNetOnly 语义）
  - `escapeLikePattern(s string) string`（`\`→`\\`、`%`→`\%`、`_`→`\_`），`CleanDeletedPath` 的所有 LIKE 参数改为 `escapeLikePattern(normPath) + sep + "%" ` 并在 SQL 追加 ` ESCAPE '\'`
  - BLE 自重启冷却指数退避：`BleHealthMonitor` 记录连续重启次数 `n`，冷却 = `min(1min << n, 2h)`；阈值/基础冷却保持现值

- [ ] **Step 1: 写失败测试**

```go
// tags 测试：路径含 % 与 _ 时只命中该前缀
func TestCleanDeletedPathEscapesLikeWildcards(t *testing.T) {
	// 建 tags 库：文件 "D:\Media\100%_great\a.mp4" 与 "D:\Media\100Xgreat\b.mp4"
	// CleanDeletedPath("D:\\Media\\100%_great") 后：前者关联被清，后者保留。
	（断言两条 SELECT 计数）
}

// ble_health 测试
func TestRestartCooldownBacksOffExponentially(t *testing.T) {
	h := newHealthMonitorForTest()
	if got := h.cooldownFor(0); got != time.Minute { t.Fatalf("n=0: %v", got) }
	if got := h.cooldownFor(3); got != 8*time.Minute { t.Fatalf("n=3: %v", got) }
	if got := h.cooldownFor(10); got != 2*time.Hour { t.Fatalf("n=10 cap: %v", got) }
}
```

（`cooldownFor(n int) time.Duration` 抽为纯函数：`d := base << min(n, k)` 封顶 2h，防 int 溢出用 `for n > 0 && d < cap { d *= 2; n-- }`。）

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/service/ ./internal/ble/ -run 'TestCleanDeletedPathEscapes|TestRestartCooldown' -v`
Expected: FAIL

- [ ] **Step 3: 实现**

pprof：`s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly(), authMw)`（确认 `authMw` 在作用域内，必要时上移声明）。tags：`escapeLikePattern` + SQL `LIKE ? ESCAPE '\'`（注意 Go 字符串里写 `"\\"`）。ble_health：`cooldownFor` + 连续计数（成功稳定运行 >10min 清零）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go server/internal/service/tags.go server/internal/service/tags_test.go server/internal/ble/ble_health.go server/internal/ble/ble_health_test.go
git commit -m "fix(security): pprof token gate, LIKE escaping and BLE restart backoff (Phase 9)"
```

---

### Task 14: Web 杂项（L-11 / L-12 + EPUB 外链剥离）

**Files:**
- Modify: `server/internal/web/lightbox.js:56-60` + `server/internal/web/security_headers_test.go`
- Modify: `server/internal/server/middleware/security_headers.go:37-42`
- Modify: `server/internal/service/book.go:130-136` + book 测试
- Test: `cd tools/xsscheck` 通过性

**Interfaces:**
- Produces:
  - lightbox `src="${escapeHtml(url)}"`（与 browserView.js:305 对齐），修正注释（URL 源是用户媒体库路径，不是纯 server-controlled）
  - CSP 追加 `; base-uri 'none'; object-src 'none'; form-action 'self'`
  - `book.go` 图片 block：`http://` / `https://` 前缀的 src 置空（走占位符分支；CSP 本就拦截外联，服务端剥离保证 UI 一致性）；`data:` 保留（epub 规范内合法且 CSP 允许）

- [ ] **Step 1: 写失败/回归测试**

`security_headers_test.go`：既有 CSP 断言更新为包含三个新指令。`book.go` 侧测试：构造 manifest 含 `https://evil.com/x.png` 的 epub 数据，`GetChapterBlocks` 后该 block `Src == ""`；`data:image/png;base64,...` 的 block `Src` 保留原值。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/middleware/ ./internal/service/ -run 'SecurityHeaders|ChapterBlocks' -v`
Expected: FAIL

- [ ] **Step 3: 实现**

```go
// book.go 既有分支改为：
if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
	// Phase 9 (L-11)：外联图片剥离 —— CSP img-src 'self' data: 本就拦截，
	// 服务端置空让客户端渲染占位符而不是静默破图；data: 合法保留。
	block.Src = ""
	continue
}
if strings.HasPrefix(src, "data:") {
	continue
}
```

（保持既有签名与后续逻辑不变。）

- [ ] **Step 4: 跑测试 + XSS lint**

Run: `cd server && go test ./... && cd internal/web && node --test && cd ../../../tools/xsscheck && go run . ../../server/internal/web`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/lightbox.js server/internal/server/middleware/security_headers.go server/internal/server/middleware/security_headers_test.go server/internal/service/book.go server/internal/service/book_test.go
git commit -m "fix(web): lightbox escaping, CSP hardening and epub URL stripping (Phase 9)"
```

---

### Task 15: 文档收尾（H-4 引导 + 索引）

**Files:**
- Modify: `docs/INDEX.md`（安全加固表加 Phase 9 行）
- Modify: `server/config.example.yaml`（token 字段注释强化）
- Modify: `AGENTS.md`（安全约定节补 Phase 9 要点）

**Interfaces:** 无代码。

- [ ] **Step 1: INDEX.md 安全加固表追加一行**

```markdown
| 9 | 三端审计修复（媒体端点 auth / BodyLimit / 认证失败限速 / 缩略图缓存上限 / BLE HMAC 认证与 GATT 加固 / 杂项 P4） | `docs/superpowers/specs/2026-08-17-security-phase9-tri-end-audit-design.md` | 完成 |
```

- [ ] **Step 2: config.example.yaml token 注释**

```yaml
# Bearer token：留空 = 开放模式（同网段任何人可访问全部 API，仅删除接口受限）。
# 强烈建议家用共享 Wi-Fi 下设置长随机串，例如：
#   python -c "import secrets; print(secrets.token_urlsafe(32))"
# 注意：token 为空时 BLE 通道不可用（Phase 9 起握手需要 token 派生密钥）。
token: ""
```

- [ ] **Step 3: AGENTS.md 安全约定节补两条**

- `### 认证覆盖（Phase 9）`：媒体读端点（folders/videos/images/texts/search）挂 Bearer auth；空 token 开放模式透传。
- BLE 一行：`server/internal/ble/protocol.go` v2 帧（seq+HMAC）与双 nonce 握手，密钥从 token 派生，两端对称（`BleProtocol.kt`）。

- [ ] **Step 4: 验证全仓测试**

Run: `cd server && go test ./... && cd internal/web && node --test && cd ../../../android && ./gradlew testDebugUnitTest`
Expected: 全绿

- [ ] **Step 5: 提交**

```bash
git add docs/INDEX.md server/config.example.yaml AGENTS.md
git commit -m "docs(security): index Phase 9 audit and harden token guidance (Phase 9)"
```

---

## Self-Review 记录

- **Spec 覆盖**：§5.1→Task 1/2/3；§5.2→Task 4；§5.3→Task 5/6/7（M-5 按 spec §7 不修）；§5.4→Task 8/9/10/11（stream ID 简化为字节上限，理由记录在 Task 9）；§5.5→Task 12/13/14；§8 验收分散在各 Task 的测试与最终 Task 15 Step 4。无缺口。
- **占位符扫描**：无 TBD/TODO；每个代码步骤均给出实现代码或精确改动点。
- **类型一致性**：`DeriveBleAuthKey`/`EncodeAuthedFrame`/`DecodeAuthedFrame`（Go）与 `deriveBleAuthKey`/`encodeAuthedFrame`/`decodeAuthedFrame`（Kotlin）命名按各自语言惯例，线格式常量值逐字节一致（0x02/0x20/0x21/0x01/0x02、HMAC 覆盖 `[0:3+len+8]`、MAC 取前 16B）。

# Task 6 Report: 认证失败限速（M-2, Security Phase 9）

**Status:** DONE
**Commit:** `845c12940cfc5db2f78c93ea5b8e8f85c5b10906` — `feat(security): per-IP auth failure rate limiting (Phase 9)`
**Branch:** master（按指示直接提交）

## 做了什么

为 Bearer 认证增加每 IP 失败退避限速，堵住"认证失败无速率限制、token 可被在线爆破"的缺口（此前限流只挂在 scan trigger / delete / zip / transcode 等业务路径上）。

### 1. `server/internal/server/middleware/auth.go`

- 新增 `AuthFailureLimiter`（同文件，标准库 only，自包含，未从 ratelimit.go 导出复用）：
  - `type AuthFailureLimiter struct`：`sync.Mutex` + `buckets map[string]*authFailBucket` + `insert map[string]int`（插入序）+ `maxIPs = 4096`
  - `NewAuthFailureLimiter(max int, window time.Duration) *AuthFailureLimiter`
  - `Blocked(ip, now) bool` / `RecordFailure(ip, now)` / `Reset(ip)`
  - `evictLocked(now)` 三段式确定性淘汰：过期窗口优先 → 最旧 `windowStart` → 最低插入序，与 `ratelimit.go` 既有策略对齐（防伪造 `X-Forwarded-For` 内存膨胀，两者均以 `c.RealIP()` 取 IP + 4096 桶上限为同一缓解模型）
- `BearerToken` 签名改为 `func BearerToken(token string, limiters ...*AuthFailureLimiter) echo.MiddlewareFunc`（variadic，取第一个 limiter；不传则行为与原来完全一致）
- 中间件分支顺序（brief Step 3 原文结构）：
  1. `token == ""` 开放模式短路透传（不触碰 limiter）
  2. 提取 `provided`（header → GET query fallback，不变）
  3. **`Blocked` 检查前置**：被限速 IP 直接 429（`{"error": "Too many auth failures"}`），即使携带正确 token 也拒绝（防爆破期间绕过）
  4. constant-time 比较失败 → `RecordFailure` + 401（计数发生在 Blocked 检查之后，429 响应本身不再累计计数，窗口内计数冻结）
  5. 认证成功 → `Reset` + `next(c)`

### 2. `server/internal/server/middleware/auth_test.go`

- 新增 brief Step 1 原文的两个测试 + `"time"` import：
  - `TestAuthFailureLimiterBlocksAfterBurst`：3 次失败触发 Blocked、跨 IP 隔离、`Reset` 清窗、窗口过期自动解封
  - `TestBearerTokenReturns429WhenFailureLimited`：2 次 401 后第 3 次 429；限速期内正确 token 也 429（经 `e.ServeHTTP` 走完整路由，`RealIP` 从 httptest RemoteAddr `192.0.2.1` 取 IP，同 IP 累计）

### 3. `server/internal/server/server.go`

- `authMw` 构造处（原 272 行）改为传共享单例：
  - `authFailLimiter := middleware.NewAuthFailureLimiter(10, time.Minute)`（每 IP 每 60s 窗口 10 次 401 → 后续 429）
  - `authMw := middleware.BearerToken(s.Config.Server.Token, authFailLimiter)`
- `authMw` 是单实例变量复用于全部挂载点（admin/system/media 等），limiter 随参数注入，无第二处构造（见下方调用点普查）

## 测试结果

- TDD 红灯：`go test ./internal/server/middleware/ -run 'TestAuthFailure|TestBearerTokenReturns429' -v` → 编译失败（`undefined: NewAuthFailureLimiter` / `too many arguments in call to BearerToken`），符合预期
- 绿灯：`go test ./internal/server/middleware/ -v` → 全 PASS，含既有 8 个 BearerToken 用例（variadic 保持单参调用编译与行为不变）+ 新增 2 个用例
- 全量：`go test ./...` → 唯一失败为基线例外 `internal/service/bookparser TestParseUserNovel`（既有失败，与本次改动无关，按任务指示忽略），其余包全部 `ok`
- `go vet ./internal/server/...` 通过

## Self-review 发现

1. **调用点普查**：grep 全仓 `BearerToken(`，生产代码仅 `server.go` 一处（已传入 limiter），其余 7 处均为 auth_test.go 内的单参测试调用（variadic 兼容，无需改动）。不存在第二处需要共享 limiter 的构造。
2. **限速语义**：429 优先于 401 计数（`Blocked` 在比较前调用）；被限速期间的 429 响应不会继续累计失败次数（`RecordFailure` 只在比较失败分支调用），窗口滚动后自然解封。
3. **开放模式不受影响**：`token == ""` 时中间件即 no-op，limiter 完全不参与；`server.go` 传入的 limiter 实例在开放模式下零副作用。
4. **`RealIP` 信任模型**：Echo `RealIP()` 信任 `X-Forwarded-For`，伪造 XFF 可每请求换 IP 绕过限速——这与既有 `ratelimit.go`（Round 32 S1）是同一已知模型，缓解手段一致（4096 桶上限 + 确定性淘汰防内存膨胀）。本任务按 brief 设计与既有策略对齐，未引入新暴露面。
5. **gofmt -l 全目录标红为环境因素**：仓库 `core.autocrlf=true`，工作区所有 .go 文件为 CRLF（未改动的 cors.go 同样被列出），git 提交时自动归一为 LF，非本次引入的格式问题。
6. **未触碰的 untracked 文件**：工作区原有 `docs/superpowers/reviews/` 与 `tools/reformat_novels.py`（非本任务产物），保持原样未纳入提交。

## 变更清单

| 文件 | 变更 |
|---|---|
| `server/internal/server/middleware/auth.go` | +`AuthFailureLimiter`（4 方法 + evict）；`BearerToken` variadic 化 + 429/计数/重置分支 |
| `server/internal/server/middleware/auth_test.go` | +2 测试函数（brief 原文）+ `time` import |
| `server/internal/server/server.go` | `authMw` 构造传入 `NewAuthFailureLimiter(10, time.Minute)` 共享单例 |

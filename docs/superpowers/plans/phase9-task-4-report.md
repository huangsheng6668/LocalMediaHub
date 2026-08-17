# Task 4 Report: 媒体读端点并入认证（H-2）

**Status: DONE**
**Commit: `0b99fe7a3d4d14309a152659f962f5ed5d236bcf` — `feat(security): require auth on media read endpoints (Phase 9)`**

## 做了什么

### Step 1 — 失败测试（`server/internal/server/server_test.go`）

- 新增 `newAuthTestServer(t, token)` 辅助工厂：通过生产构造路径 `New(cfg)` 启动真实 Server（`cfg.Server.Token` 喂给 `registerRoutes` 里的 `middleware.BearerToken`），Roots 指向 `t.TempDir()`，`t.Cleanup` 里 `s.Stop()`。仿照同文件既有 `newGzipTestServer` 模式。`New()` 只启动 fsnotify watcher 不触发扫描，无需真实媒体文件。
- 新增 `TestMediaReadEndpointsRequireToken`：brief Step 1 代码按原文使用。无 token 请求下列路径必须 401：
  - brief 原有 5 条：`/api/v1/folders`、`/api/v1/videos`、`/api/v1/images`、`/api/v1/texts`、`/api/v1/search?q=x`
  - **补充 3 条 wildcard 路由**（超出 brief 测试代码、但属于 brief "Produces" 契约明确列出的 gated 端点）：`/api/v1/folders/C%3A/tmp/browse`、`/api/v1/videos/foo/thumbnail`、`/api/v1/images/foo/thumbnail` —— 否则 `/folders/*`、`/videos/*`、`/images/*` 三条路由的 401 行为完全没有测试覆盖
  - 正向断言：带 `Authorization: Bearer sekrit-token` 后不得 401
- 新增 `TestMediaReadEndpointsOpenModePassthrough`：brief 代码原文，空 token（开放模式）`/api/v1/folders` 不 401。

### Step 2 — 确认 RED

```
server_test.go:710: GET /api/v1/folders without token = 200, want 401
--- FAIL: TestMediaReadEndpointsRequireToken (0.04s)
--- PASS: TestMediaReadEndpointsOpenModePassthrough (0.03s)
```

失败原因正确（媒体库无认证可匿名枚举）。OpenMode 测试改动前后都通过（钉住既有行为）。

### Step 3 — 路由注册（`server/internal/server/server.go`）

按 brief Step 3 代码原文修改路由块：

| 路由 | 改动 |
|---|---|
| `GET /folders` | + `authMw` |
| `GET /folders/*` | `authZipDownload(authMw)` → 无条件 `authMw`；保留 `rateLimitWhen(isFolderZipDownload, ...)` |
| `GET /videos` | + `authMw` |
| `GET /videos/*` | + `authMw`（置于 `rateLimitWhen(isTranscodeRequest, ...)` 之前）；限速保留 |
| `GET /images` | + `authMw` |
| `GET /images/*` | + `authMw` |
| `GET /texts` | + `authMw` |
| `GET /search` | + `authMw` |

- 删除 `authZipDownload` 辅助函数（grep 全仓确认唯一调用方就是旧 line 278；它与 `isFolderZipDownload` 都定义在 server.go，无其它文件牵连）。
- `isFolderZipDownload` 保留（仍被 `/folders/*` 的条件限速使用）。
- 路由块上方新增 Phase 9 (H-2) 说明注释。

### Step 4 — 测试结果

```
cd server && go test ./internal/server/ -run 'TestMediaReadEndpoints' -v
--- PASS: TestMediaReadEndpointsRequireToken (0.05s)
--- PASS: TestMediaReadEndpointsOpenModePassthrough (0.03s)

cd server && go test ./...
ok  internal/server, internal/server/handler, internal/server/middleware, internal/service, internal/ble, internal/config ...
FAIL internal/service/bookparser  →  仅 TestParseUserNovel（已知基线例外，与本次改动无关：bookparser 章节标题解析，本任务未触碰该包）
```

`go vet ./internal/server/` 干净。

### Step 5 — 提交

- 只暂存 brief 指定的两个文件；仓库里无关的 untracked（`docs/superpowers/reviews/`、`tools/reformat_novels.py`）未动。
- Commit message 用 brief Step 5 原文：`feat(security): require auth on media read endpoints (Phase 9)`。

## 兼容性核对

- **空 token 部署**：`middleware.BearerToken`（auth.go:27-29）token 为空时直接 `next(c)` 透传 → 行为不变，且有 `TestMediaReadEndpointsOpenModePassthrough` 钉住。
- **Android**：`AuthInterceptor` 注入 Hilt 单例 OkHttpClient 所有请求（Coil / ExoPlayer 共用）→ 零改动（协调方已核实，本任务未再验证客户端）。
- **Web SPA**：`api.js apiRequest` 统一注入 Bearer header → 零改动。且 `BearerToken` 对 GET 保留 `?token=` query fallback，`<img>`/`<video>` 场景也有兜底。
- **既有测试**：无需补 header —— 逐一核查了所有穿过生产 router 的测试：
  - `TestRegisterRoutesJsonCacheControl` 打 `/folders`、`/search`、`/videos`、`/images`，但其 cfg 无 token（开放模式透传），不受影响。
  - `TestRegisterRoutesServesThumbnailEndpoint` 手动 `registerRoutes` + 空 token cfg，不受影响。
  - `folders_test.go` / `search_test.go` 等 handler 测试在裸 Echo 实例上自行注册路由（无 auth 中间件），不经过生产 router，不受影响。
  - web jsdom 测试不打真服务器，无关。
- **BLE**：`internal/ble/api_provider.go` 进程内直调 service（非 HTTP），不受影响。

## Self-Review 发现

1. **中间件顺序**：`/folders/*` 与 `/videos/*` 上 `authMw` 排在 `rateLimitWhen` 之前 → auth 先执行（401 优先于 429，不给未认证调用者暴露限流行为差异）。与 brief 代码一致。
2. **测试超出 brief 的部分**：无 token 循环里加了 3 条 wildcard 路径（见 Step 1）。这是对 brief "Produces" 契约（`/folders/*`、`/videos/*`、`images/*` 全部要求 token）的直接验证，非需求变更；brief 测试代码本身按原文保留。
3. **仍公开的端点（超出本任务范围，未动）**：`GET /tags`、`/tags/:tag_id/files`、`/tags/:tag_id/media`、`/tags/file-tags`（server.go 内有 "reads stay public (documented design)" 注释）；`/api/v1/books/image`（Round 32 设计为 sig-only，故意在 auth 组之外）；`/api/v1/health`（探活）。
4. **gofmt -l 假阳性**：两个改动文件在 Windows 工作区被 `gofmt -l` 列出，原因是 CRLF（`core.autocrlf=true`，index=LF / worktree=CRLF）。已用 `git stash` 验证改动前的原文件同样被列出 → 仓库既有状况，非本次引入；提交内容经 git 归一化为 LF，无格式问题。
5. **Windows 测试运行器噪音**：测试二进制退出后偶现 `go: unlinkat ...server.test.exe: The process cannot access...` —— BLE listener goroutine 短暂持有进程所致，纯清理警告，不影响 PASS/FAIL 判定。
6. **行为变化确认**：配置了 token 的部署中，LAN 匿名客户端从"可枚举+串流整个媒体库"变为 401 —— 这正是 H-2 要修的问题，无其它行为差异（handler / 服务层零改动）。

## 改动文件

- `server/internal/server/server.go`（+15/−21 行净：路由块 8 处 + 注释 + 删 `authZipDownload`）
- `server/internal/server/server_test.go`（+72 行：`newAuthTestServer` + 两个测试）

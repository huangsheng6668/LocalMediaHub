# Task 1 Report: 访问日志 RequestURI 脱敏（H-3）

**Status:** DONE_WITH_CONCERNS（本任务范围内全部验证通过；唯一 concern 是全量套件中存在一个与本改动无关的 pre-existing 失败，见下文）

**Commit:** `4e68a5a` — `fix(security): redact ?token= from access log RequestURI (Phase 9)`

## 做了什么

### 1. 生产代码（`server/internal/server/server.go`）

`registerRoutes` 中的 inline redact 中间件（`if q.Get("token") != ""` 分支内）追加一行，与 brief Step 3 原文一致：

```go
q.Set("token", "REDACTED")
req.URL.RawQuery = q.Encode()
// Echo Logger 打印 req.RequestURI（请求行原文，不随 URL 同步），必须一并改写
req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
```

`_ = c.QueryParams()` 缓存语义不变（下游 `BearerToken` 的 `?token=` fallback 仍读到原始 token，由测试断言 3 保证）。

### 2. 测试（`server/internal/server/server_test.go`）

**新增 `TestTokenRedactRewritesRequestURI`（RED→GREEN gate）**：brief Step 1 给的是独立 echo 实例模板；按协调要求贴合本文件既有风格升级为**真实 Server 版**——参照 `newGzipTestServer` 的构造方式 `New(cfg)` + `s.Echo.ServeHTTP`，直接命中生产 `registerRoutes` 中间件链（含真实 `echoMw.Logger()`）：

- `s.Echo.Logger.SetOutput(&logBuf)` 捕获真实 Logger 输出（echoMw.Logger 写到 `e.Logger`），直接断言"echo.Logger 不再泄露"
- 断言 1：`req.RequestURI` 不含 `sekrit`，且精确等于 `/api/v1/__test_redact_probe?token=REDACTED`
- 断言 2：真实 access log 行不含 `sekrit`、含 `token=REDACTED`
- 断言 3：downstream `c.QueryParam("token")` 仍读到原始 `sekrit`（auth fallback 不破坏，且比 brief 模板的宽松断言 `!= REDACTED && != sekrit` 更强）

**扩展既有 `TestRedactMiddleware_TokenRedactedFromLogButVisibleToDownstream`**（brief Step 3 要求，防回归只测一半）：

- replica 中间件同步加上 RequestURI 改写（该 replica 注释声明须与生产保持 lockstep）
- 断言对象从 `URL.RawQuery` 扩展到 `req.RequestURI`（含 `token=REDACTED` 且不含 `secret123` 双向断言）

### 3. 根因确认（证据链）

- Echo v4.15.1 `middleware/logger.go:346`：`${uri}` tag 直接 `writeString(buf, req.RequestURI)`——请求行原文，改 `URL.RawQuery` 不会同步它。
- RED 阶段（生产代码未改、新测试已加）实测捕获的日志行：
  `"uri":"/api/v1/__test_redact_probe?token=sekrit"` —— 泄露在生产路由上真实复现。
- 修复后同测试 GREEN，日志行变为 `token=REDACTED`。

## 测试结果

| 命令 | 结果 |
|---|---|
| `go test ./internal/server/ -run TestTokenRedact -v` | PASS（RED 阶段确认 FAIL：RequestURI 泄露 + 日志泄露，两条断言均命中） |
| `go test ./internal/server/ -run 'TestTokenRedact\|TestRedactMiddleware' -v` | PASS（新旧两个 redact 测试均绿） |
| `go test ./...` | `internal/server` / `handler` / `middleware` / `service` 全部 ok；**唯一 FAIL：`internal/service/bookparser` 的 `TestParseUserNovel`** |

## Self-review 发现

1. **pre-existing 失败（与本改动无关，已验证）**：`TestParseUserNovel`（`txt_test.go:172`，期望章节标题 `第一章　龙回故乡` 实得 `第一章，谢了。`）。已用 `git stash` 摘掉本任务两文件改动后在干净 master 上复跑，同样失败——系 master 既有问题（工作区另有未跟踪的 `tools/reformat_novels.py`，疑似此前小说规则改动遗留）。本任务改动仅触及 `internal/server`，与 bookparser 零交集。这就是标 DONE_WITH_CONCERNS 的唯一原因。
2. **中间件时序**：RED 阶段证明改 `RawQuery` 不影响日志（Logger 读 RequestURI）；GREEN 阶段证明加了 RequestURI 改写后 Logger 打出 REDACTED——两个方向都由真实 Logger 输出端到端锁定，不依赖对 Echo 中间件顺序的纸面推理。
3. **边界**：无 `?token=` 的请求不进分支、RequestURI 原样；token 非空保证 Encode 后 RawQuery 非空，不会出现尾随 `?`；多参数场景由既有 replica 测试的 `other=keep` 覆盖；`?sig=`（books 图片签名）不在本 redact 范围，与 Round 32 S2 设计一致。
4. **Windows 环境噪音**：`go test` 后偶发 `unlinkat ... The process cannot access the file`（go-build 临时目录清理被文件锁占用），在 PASS 与 FAIL 运行中都会出现，与测试结果无关。
5. **改动范围**：严格限于 brief 列出的两个文件（server.go +2 行，server_test.go +88 行）；未动工作区中其它未跟踪文件。

## 交付物

- Commit `4e68a5a`（master，含两个文件）
- 本报告

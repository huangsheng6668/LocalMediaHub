# Task 5 Report: 全局请求体大小限制（M-1）

**Status: DONE**
**Commit: `62ad74b` — `feat(security): global request body limit (Phase 9)`**
**Files: `server/internal/server/server.go` (+3), `server/internal/server/server_test.go` (+18)**

## 做了什么

按 brief TDD 流程完成：

1. **Step 1（RED）**：在 `server_test.go` 添加 `TestBodyLimitRejectsOversizedPayload`（brief 原文代码，置于 `TestMediaReadEndpointsOpenModePassthrough` 之后）。复用 Task 4 的 `newAuthTestServer(t, "")`（真实 `New(cfg)` 构造，开放模式），向 `PUT /api/v1/admin/config` 发送 ~5MiB JSON（`strings.Repeat("A", 5<<20)`），断言 413。所需 import（`strings` / `httptest` / `http` / `echo`）文件里已有，无新增依赖。
2. **Step 2（确认失败）**：`go test ./internal/server/ -run TestBodyLimit -v` → FAIL，`oversized body = 400, want 413`（当前 handler 先 Bind 整个 body 进内存，再因相对路径 root 返回 400）。
3. **Step 3（实现）**：在 `registerRoutes` 的 `s.Echo.Use(echoMw.Logger())` 之后、redact 中间件之前注册（brief 原文注释 + 调用）：
   ```go
   s.Echo.Use(echoMw.BodyLimit("4M"))
   ```
4. **Step 4（GREEN）**：单测 PASS（413）；`go test ./...` 全绿，唯一失败为既有基线例外 `internal/service/bookparser TestParseUserNovel`（任务说明中已声明忽略）。
5. **Step 5（提交）**：`git add` 两个文件，commit message 用 brief Step 5 原文。commit 只含这两个文件（`git show --stat` 验证）。

## 测试结果

| 命令 | 结果 |
|---|---|
| `go test ./internal/server/ -run TestBodyLimit -v`（实现前） | FAIL（400 ≠ 413，符合预期 RED） |
| `go test ./internal/server/ -run TestBodyLimit -v`（实现后） | PASS（413） |
| `go test ./internal/server/` | ok |
| `go test ./...`（server 全量） | 仅 `TestParseUserNovel` 失败（既有基线例外，与本次改动无关） |
| `go vet ./internal/server/` | clean |

## Self-review 发现

1. **4M 上限安全性论证（上传端点排查）**：grep 整个 `server/internal/` 确认 **零** `multipart` / `FormFile` / `ParseForm` 调用 —— 不存在大 body 上传端点。所有 body 读取均走 `c.Bind()` 的小 JSON（admin config roots、批量缩略图 ≤64 路径、BLE connect/send、system delete、tag create/associate），4MiB 远超合法需求。brief 的 4M 论证成立。
2. **Echo v4.15.1 BodyLimit 双路径语义（读了 module cache 源码确认）**：
   - `req.ContentLength > limit` → 直接返回 `echo.ErrStatusRequestEntityTooLarge`（413），handler 不执行 —— 测试命中的是这条路径（`httptest.NewRequest` + `strings.Reader` 会设置 ContentLength）。
   - 实际读取超限（chunked / ContentLength=-1 / 伪造小 Content-Length）→ `limitedReader.Read` 返回同一个 `echo.ErrStatusRequestEntityTooLarge` HTTPError，经 `c.Bind` 错误传播 + Echo 默认 HTTPErrorHandler 映射为 413。**两条路径都产出 413，无绕过缺口**。
3. **GET / 流媒体端点不受影响**：BodyLimit 只检查 ContentLength 并包装非 nil 的 request body；GET 请求 body 为 nil。视频流 / zip 下载是 response body，完全不受限。
4. **挂载顺序核对**：位于 `Logger()` 之后、redact / gzip / SecurityHeaders / CORS 之前。Echo LIFO 下请求侧 redact→gzip→…→BodyLimit→handler 依次执行；这些中间件都不读 body，顺序无冲突。redact 的 query-param 缓存逻辑不受影响（已被既有 `TestTokenRedactRewritesRequestURI` 等测试回归覆盖，全绿）。
5. **测试无副作用**：5MiB 的 `"AAAA..."` root 是相对路径，即使 middleware 缺失也会被 `UpdateConfig` 的 `filepath.IsAbs` 校验拒绝在 `cfg.Save("config.yaml")` 之前；加上 middleware 后 handler 根本不执行（ContentLength 短路）。无 `config.yaml` 写盘风险。
6. **gofmt -l 误报**：`gofmt -l` 列出两个文件，但 diff 为整文件重写 —— 是本机 CRLF checkout 的既有现象（HEAD 基线上 `internal/server/` 全部 30+ 文件都被列出，含未触碰文件），非本次引入。新增代码本身用 tab 缩进、风格一致。
7. **改动范围纪律**：仅 `server.go` + `server_test.go` 两个文件，共 +21 行；工作区里既有的 untracked 文件（`docs/superpowers/reviews/`、`tools/reformat_novels.py`）未被纳入 commit。

## Concerns

无阻塞项。两条备忘（非缺陷）：

- 413 响应体是 Echo 默认 JSON（`{"message":"Request Entity Too Large"}`），Web SPA 的 `api.js` 未对 413 做专门 UI 处理 —— 但浏览器端正常操作远小于 4MiB，无实际 UX 影响。
- `BodyLimit` 为硬编码 `"4M"`，未做成 config 可调 —— brief 明确定为全局常量，符合范围。

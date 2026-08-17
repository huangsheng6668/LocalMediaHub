# Task 7 Report: 缩略图磁盘缓存上限 + 图片端点限速（Phase 9 / M-3）

**Commit**: `9bb6e97` — `feat(security): thumbnail disk cache cap and image rate limits (Phase 9)`（master，brief Step 5 原文）
**状态**: DONE_WITH_CONCERNS（功能完整、测试全绿；两处与 brief 字面示意代码的有意偏差，见 self-review）

## 做了什么

### 1. `server/internal/service/thumbnail.go`

- 新增常量 `defaultDiskCacheCapBytes int64 = 512 << 20`（512MB，YAGNI 常量而非 config 字段，附威胁模型注释）。
- `ThumbnailService` 新增字段：`diskCacheCapBytes int64` / `lastSweep int64`（unix nano，atomic）/ `sweepInterval time.Duration`，带说明注释（只清 `.jpg`，durations.json / hot_directories.json 不在清扫范围）。
- `NewThumbnailService` 初始化 `diskCacheCapBytes = defaultDiskCacheCapBytes`、`sweepInterval = 30 * time.Second`。
- 新增 `enforceDiskCacheCap()`：30s atomic 节流 → `filepath.Walk` 遍历 `cacheDir` 与 `cacheDir/system`（只收集 `.jpg`）→ 总量 > cap 时 `sort.Slice` 按 mtime 升序删除最旧文件直到 ≤ cap。`filepath.Walk` 返回值显式 `_ =` 丢弃（root 不存在/遍历中被并发删除均为良性，跳过该 root）。
- `encodeThumbnailToCache` 成功 `os.Rename` 落盘后、`return cachePath, nil` 前异步调用 `go s.enforceDiskCacheCap()`，不阻塞生成热路径。

### 2. `server/internal/server/server.go`

- `/images/*`：`api.GET("/images/*", h.GetImageAsset, authMw, middleware.RateLimit(60, time.Minute))`（brief 原文）。
- `/videos/*`：在既有 authMw + transcode 条件限速之后追加 `rateLimitWhen(isThumbnailRequest, middleware.RateLimit(60, time.Minute))`。
- 新增 `isThumbnailRequest` helper：`strings.HasSuffix(c.Param("*"), "/thumbnail")`。

### 3. `server/internal/service/thumbnail_test.go`

- `TestEnforceDiskCacheCapEvictsOldest`：brief Step 1 原文（cap 下调 3KB、两个 2KB 文件、旧文件 mtime -1h → 旧者被删、新者存活）。
- `TestEnforceDiskCacheCap_Throttled`（brief 之外补充的一个小测试）：验证 Interfaces 里规定但 brief 测试未覆盖的节流行为——首次清扫记录 `lastSweep` 后，窗口内（测试拉到 1h）的第二次调用即使超限也不删任何文件。

## 测试结果

| 命令 | 结果 |
|---|---|
| `go test ./internal/service/ -run TestEnforceDiskCacheCap -v` | Step 2 确认 FAIL（编译错误：字段/方法未定义）→ 实现后 2/2 PASS |
| `go test ./internal/service/ ./internal/server/`（brief Step 4） | ok / ok |
| `go test ./...` | 除既有基线失败 `internal/service/bookparser TestParseUserNovel`（coordinator 已声明忽略，与本任务无关）外全部 ok |
| `go vet ./internal/service/ ./internal/server/` | clean |

说明：`go test -race` 本环境不可用（`-race requires cgo; CGO_ENABLED=1`），以代码审查替代——新代码唯一共享状态 `lastSweep` 全部经 atomic 访问，`diskCacheCapBytes`/`sweepInterval` 仅在构造函数（或测试串行段）赋值后才可能有 goroutine 读取，无数据竞争面。测试输出末尾偶发的 `go: unlinkat ...service.test.exe`（Windows 文件句柄清理）出现在 `ok` 之后，非失败。

## Self-review 发现（concerns）

1. **`isThumbnailRequest` 用 `c.Param("*")` 而非 brief 括号里的 `c.Path()`（有意偏差，必须说明）**：
   brief 写 "`strings.HasSuffix(c.Path(), "/thumbnail")`"，但 `c.Path()` 返回的是**路由模板**（`/api/v1/videos/*`），永远不会以 `/thumbnail` 结尾——按字面实现该条件限速将是永不触发的死代码。server.go 自身注释（约 :224 "Route templates like /api/v1/videos/* do NOT contain /stream"）、`isFolderZipDownload` 的既有写法（`c.Param("*")` + suffix `/download`）、以及 `GetVideoAsset` 的分发逻辑（videos.go:44 `strings.HasSuffix(rawPath, "/thumbnail")`）三者一致指向 `c.Param("*")`。已按此实现并在 helper 注释里写明原因，保留 brief 的意图（`/videos/*` 的缩略图请求被限速）。

2. **双 walk 导致 `cacheDir/system` 文件在 `total` 中计双份（brief 原文代码，保守偏差）**：
   `filepath.Walk(s.cacheDir)` 是递归的，已覆盖 `system/` 子目录；brief 又显式 walk `cacheDir/system`，system 下的 `.jpg` 因此在 `entries` 与 `total` 中各出现两次。后果：system 缩略图占比高时，cap 实际按 ~2 倍名义值提前触发（多删而非少删，对"撑爆磁盘"的威胁模型是安全方向的偏差）；重复 entry 的二次 `os.Remove` 会失败并被 `== nil` 守卫吞掉，`total` 只减一次，无正确性问题。按 coordinator "代码按原文使用" 指示保留 brief 原文；若后续要精确记账，删掉循环里的第二个 root 即是一行修复。

3. **`/images/*` 的 60/min 限速同时覆盖 `/original` 子路径**：`GetImageAsset` 按 `/original` 后缀分发原图、否则走缩略图（images.go:50-54）。brief 的路由行原文就是对整条 `/images/*` 挂限速（未按子路径区分），对全尺寸原图限速同样是合理的 DoS 缓解，已按原文实现。

4. **其余走查结论**：`go s.enforceDiskCacheCap()` 每次 encode 落盘都会 spawn 一个 goroutine，绝大多数被 30s 节流立即退出，最坏两个 goroutine 同时通过 check 重复扫一遍（幂等，无害）；现有 encode 相关测试（默认 512MB cap）不受异步清扫影响；既有 server 测试对这些路由的请求次数远低于 60/min，无 429 冲突；RateLimit 机制本身已有 middleware + integration 测试钉住。

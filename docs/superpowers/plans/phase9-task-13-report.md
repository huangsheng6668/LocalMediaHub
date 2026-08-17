# Task 13 Report — Server 杂项（L-2 pprof token 门禁 / L-4 LIKE 转义 / L-5 BLE 重启退避）

**Status**: DONE
**Commit**: `4bcfee0` — `fix(security): pprof token gate, LIKE escaping and BLE restart backoff (Phase 9)`
**Files changed** (exactly the brief's 6-file list, 326 insertions / 25 deletions):
- `server/internal/server/server.go`
- `server/internal/server/server_test.go`
- `server/internal/service/tags.go`
- `server/internal/service/tags_test.go`
- `server/internal/ble/ble_health.go`
- `server/internal/ble/ble_health_test.go`

---

## 1. 做了什么

### L-2: pprof 加 token 门禁（server.go）

- `/debug/pprof` 组从 `middleware.PrivateNetOnly()` 改为 `s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly(), authMw)`。
- `authMw`（Task 6 后已含 per-IP 失败限速：10 次/60s → 429）原声明在 `api := s.Echo.Group("/api/v1")` 之下、pprof 组之后，作用域不可见——将 `authFailLimiter` + `authMw` 两行声明**原样上移**到 pprof 组之前，原位置留一行指向注释。authMw 的构造参数与语义零改动，仅移动位置。
- 效果：token 模式下 pprof 需要 Bearer token（header 或 GET `?token=` fallback）；开放模式（token 为空）BearerToken 为 passthrough，维持 PrivateNetOnly-only 语义。中间件顺序：PrivateNetOnly 先执行（非私网 IP 先被拒），authMw 后执行。

### L-4: tags LIKE 通配符转义（tags.go）

- 新增 `escapeLikePattern(s string) string`：`\`→`\\`（先转义，防止注入的转义符被再次解释）、`%`→`\%`、`_`→`\_`。
- `CleanDeletedPath` 的 LIKE 参数改为 `escapeLikePattern(normPath + string(filepath.Separator)) + "%"`，SQL 追加 ` ESCAPE '\'`（Go 源码写作 `"ESCAPE '\\'"`）。
- **实现中发现并被测试拦下的一个关键 pitfall**：最初按直觉写 `escapeLikePattern(normPath) + sep + "%"`——在 Windows 上 `filepath.Separator` 本身就是 `\`（即 ESCAPE 字符），尾部裸 sep 会把后面的 `%` 通配符转义回**字面量 `%`**，导致 LIKE 分支什么都删不掉（RED→第一次实现后测试失败：目标文件关联未被清）。修正为把**整个字面前缀（路径+分隔符）一起转义后，再追加裸 `%` 通配符**，通配符由构造保证不被转义。
- 等值分支 `file_path = ?` 保持原始 normPath（等值匹配无通配符问题），不变。

### L-5: BLE 自重启指数退避（ble_health.go）

- 新增 `cooldownFor(n int) time.Duration`（`*BleHealthMonitor` 方法，纯函数、不触碰状态）：
  ```go
  d := time.Minute
  for n > 0 && d < maxCooldown { d *= 2; n-- }   // 有界循环防溢出
  if d > maxCooldown { d = maxCooldown }          // 精确封顶 2h（一次倍增可能略过 120min → 128min）
  ```
  n=0 → 1min（即原固定 60s 基础冷却，保持现值）；n=3 → 8min；n≥7 → 精确 2h cap。注意：brief 给的裸循环在 n≥7 时会落到 128min ≠ 2h，故补了最终 clamp 以满足 `cooldownFor(10) == 2*time.Hour` 断言。
- `BleHealthMonitor` 新增字段：`consecutiveRestarts`（本链连续重启计数）、`lastRestartAt` / `lastSuccessAt`（时间戳）、`now func() time.Time`（测试 seam，构造时默认 `time.Now`，公共 API 不变）。
- 触发路径：达到阈值且非 coolDown 时 fire —— `consecutiveRestarts++`、`lastRestartAt = now()`；之后同 episode 内的失败在 `restartAlreadyFired` 分支里被 `now - lastRestartAt < cooldownFor(consecutiveRestarts-1)` 抑制，**冷却期满后才 re-arm 允许再次 fire**（第 1 次重启后等 cooldownFor(0)=60s、第 2 次后等 2min、……封顶 2h）。
- 阈值语义不变：连续失败 2 次（`ConnectFailThreshold=2`）才触发；既有 4 个测试原样通过（立即连发的失败全部落在冷却窗口内，仍恰好 fire 一次）。

## 2. LIKE 其它用法清单（是否一并处理）

对 `server/` 全量 grep `LIKE|GLOB|REGEXP`（含非测试代码）：

| 位置 | 用法 | 处理 |
|---|---|---|
| `service/tags.go` `CleanDeletedPath`（原 408 行） | 用户路径做 LIKE 前缀 | **已转义 + ESCAPE 子句** |
| `service/tags_test.go:123` | 测试内字面量 `LIKE 'idx_%'`（非用户输入） | 无需处理 |

**结论：生产代码中唯一的 LIKE 谓词就是 `CleanDeletedPath`**。tags.go 其余查询全部是 `=` / `IN (...)` / `LOWER(name) = LOWER(?)` 等值匹配（无通配符语义）；server 其它包无 LIKE/GLOB/REGEXP。无需额外转义点。

## 3. 稳定期实现说明

现有结构原本没有稳定期概念，按消歧要求加了**最小事件驱动实现**（无后台 timer、无新公共 API）：

- `stableRunInterval = 10 * time.Minute` 常量。
- `RecordConnect(true)`：记录 `lastSuccessAt`；若 `now - lastRestartAt >= stableRunInterval`（即上次重启后已稳定运行 ≥10min 且观察到成功 Connect），则 `consecutiveRestarts = 0` 并复位 `restartAlreadyFired`（让新 episode 从基础冷却重新开始），打一条 `slog.Info("BLE stable after restart; auto-restart backoff reset")`。
- 之所以同时复位 `restartAlreadyFired`：生产中成功 fire 会 `os.Exit(0)`，monitor 存活到稳定期之后只可能发生在 restarter spawn 失败返回的场景；BLE 恢复稳定 10min 后再次卡死应能重新触发重启，否则会被旧标志永久抑制。
- `lastRestartAt.IsZero()` 守卫防止未重启过的 monitor 误入重置分支。
- `now` 为可注入 seam：测试用 `m.now = func() time.Time { return fakeNow }` 推进虚拟时钟验证退避时序，无需 sleep。

跨进程说明（诚实记录）：跨重启的冷却传递仍由既有 `LMH_BLE_RESTART_TS` env + `parseRestartCooldown()`（固定 60s）承担；按 brief 的 6 文件范围约束未动 `restart_windows.go` / `ble_autorestart_windows.go`（改 env 契约会超出范围）。本任务的指数退避落在 monitor 进程内语义上：spawn 失败导致的进程内重启循环按 1min/2min/4min/…/2h 退避，稳定 10min 清零。

## 4. 测试结果

TDD 顺序：先写测试（RED）→ 实现（GREEN）。RED 证据：
- `TestCleanDeletedPathEscapesLikeWildcards`： bystander 被误删（got 0, want 1）——精确复现 L-4 bug（`100%_great` 未转义模式能匹配 `100Xgreat`）。
- `TestPprofRequiresTokenInTokenMode`：无 token 请求返回 200 而非 401。
- BLE：编译失败（`cooldownFor`/`stableRunInterval`/`consecutiveRestarts`/`now` 未定义）。

GREEN / 验证命令与结果：
- `go test -tags bluetooth ./internal/ble/ ./internal/service/ -run 'TestCleanDeletedPathEscapes|TestRestartCooldown|TestRecordConnect_' -v` → **PASS**（含既有 4 个 RecordConnect 测试 + brief 的 `TestRestartCooldownBacksOffExponentially`（0→1min、3→8min、10→2h cap）+ 新增 `TestRecordConnect_BackoffDelaysRefireUntilCooldownElapses`：30s/59s 抑制、60s re-arm fire 第二次、稳定 10min+1s 后计数清零、新 episode 立即 fire 第三次）。
- `go test ./internal/server/ -run 'TestPprof' -v` → **PASS**（新增 `TestPprofRequiresTokenInTokenMode`：无 token 401 / 错 token 401 / 对 token 200；`TestPprofOpenModeStillPrivateNetOnly`：开放模式 loopback 仍 200；既有 3 个 pprof 测试不受影响）。
- 任务指定命令 `go test ./internal/ble/ ./internal/service/ ./internal/server/ -v` → 全部 `ok`（注意：ble_health*.go 带 `//go:build windows && bluetooth`，**必须加 `-tags bluetooth` 才会执行其测试**，已额外跑过）。
- `go build ./... && go vet ./...` → **OK**（含 `go vet -tags bluetooth ./internal/ble/`）。
- `go test ./...` → 仅 `internal/service/bookparser` `TestParseUserNovel` 失败——**已在 HEAD（stash 我的改动后重跑）确认为既有基线失败**，与本任务无关，按任务说明忽略；其余全部 `ok`。

## 5. Self-review

- **范围纪律**：只改 brief 列出的 6 个文件；未动认证协议（`middleware/auth.go` 零改动）、未动 BLE central（`central*.go` 零改动）、未动 `restart_windows.go` / `ble_autorestart_windows.go` / env 契约。
- **authMw 上移安全性**：两行声明原样上移，唯一行为差异是 pprof 组现在共享同一实例（同一 AuthFailureLimiter——pprof 爆破与 API 爆破共享 per-IP 限速桶，属正向收益）；下游所有路由引用不变。
- **边界与防御**：`cooldownFor(n)` 对 n≤0 / 超大 n 均安全（有界循环 + clamp）；`consecutiveRestarts-1` 只在 `lastRestartAt` 非零时求值（短路守卫，非法状态下也不会 panic）；所有字段访问在 `mu` 临界区内。
- **语义保持**：连续失败 2 次阈值、coolDown bool（session 级禁用）、同 episode 立即连发只 fire 一次——均有既有测试背书且原样通过。
- **gofmt 说明**：`gofmt -l` 标记本仓库 66+ 文件（含未触碰的 cors.go/admin.go 等），为 CRLF 行尾的仓库级既有现象（diff 显示整文件每行替换）；已在 HEAD 验证为存量状态，非本次引入，未做仓库级 reformat 以免污染 diff。`go vet` 全绿。
- **git 状态**：提交后工作区干净（仅 2 个既有 untracked：`docs/superpowers/reviews/`、`tools/reformat_novels.py`，与本任务无关，未纳入提交）。

# Security Phase 9 — 最终全分支审查修复报告（final fix wave）

日期：2026-08-17 · 分支：master（4 个 commit，Conventional Commits）· 修复人：修复工程师 agent

## 总览

| 项 | 级别 | 状态 | Commit |
|---|---|---|---|
| C-1 BLE 认证状态被 markConnected 时序抹除 | Critical | 已修复 + 集成测试 | `2781945` fix(ble): drive BLE auth reset from GATT connection events (Phase 9) |
| I-3 /tags 四个读端点缺 authMw | Important | 已修复 + 测试 | `a2a5e62` feat(security): require auth on tags read endpoints (Phase 9) |
| I-1 / L-8 签名守卫 taskNames 字符串匹配 | Important | 已修复 + 行为验证 | `6230467` fix(android): harden release signing guard via task graph (Phase 9) |
| I-2 + M-B 文档同步 | Minor | 已同步 | `a750b25` docs(security): sync token column and rate limit spec note (Phase 9) |

## C-1：BLE auth reset 从 HTTP 协调回调移到 GATT 连接事件

### 根因（复述确认）

Go `Central.Connect`（`server/internal/ble/central.go`）在 HTTP handler 内同步跑完整互挑战握手后才返回 `{connected:true}`；Android 侧 `BleSettingsViewModel`（274/366 行）收到 HTTP 响应后调 `BleController.markConnected()`，而旧 `markConnected()` 无条件 `resetAuthLocked()`——把手机在 HTTP 往返期间已完成的 `authenticated=true` 打回未认证。结果：PC 已认证 / 手机未认证，手机 `requestApi` 前置拒绝（BleController 旧 435-437 行），PC 的 v2 帧在手机 pre-auth 路径被当作违规丢弃。生产流程下 BLE 数据通道完全不可用。

### Seam 设计（本次落地）

- **接口**（`ble/BlePeripheralManager.kt`）：新增两个零参回调 seam——
  - `setOnPeerConnected(cb: () -> Unit)` — GATT 链路建立时触发；
  - `setOnPeerDisconnected(cb: () -> Unit)` — GATT 链路断开时触发。
- **生产实现**（`ble/AndroidBlePeripheralManager.kt`）：两个回调在 `onConnectionStateChange` 的 `STATE_CONNECTED` / `STATE_DISCONNECTED` 分支触发。既有行为不动：`subscriberDevice` 的无条件赋值保留（已知递延项）；bond/CCCD/offset 三守卫不动。
- **控制器**（`ble/BleController.kt`）：
  - `init` 里注册两个 seam，各自 `synchronized(authLock) { resetAuthLocked() }`。在 GATT 链路建立瞬间，PC 的 challenge 必然尚未到达（PC 是 connect 返回后才发 challenge），顺序天然正确；
  - `markConnected()` 只做 `machine.onConnected()`，**不再 reset**；
  - reset 保留在：peer-disconnect 事件、`markDisconnected()`、fatal 违规（`fatalLocked`）、BLE disable（`evaluateAvailability(false)`）。
- **协议零改动**：常量、线格式、握手流程、Go 端 `central.go` 均未触碰（Go 端本来就在 `Connect` 入口 reset，语义已正确）。
- 五个 manager 实现/fake 同步加 seam：生产实现 + `TestBleFixtures.NoopPeripheralManager` + `BleModuleTest.NoopPeripheralManager` + `BleSettingsViewModelTest.FakePeripheralManager` + `MediaRepositoryFailoverTest.SimulatingPeripheralManager` + `BleControllerTest.FakePeripheralManager`（后两者含测试钩子）。

### 测试证据（`BleControllerTest.kt` 新增 4 例，全绿）

1. `markConnected_afterCompletedHandshake_preservesAuthentication` — **生产时序集成测试**：GATT 连接事件 → 完整握手（fake GATT write 路径）→ 迟到的 `markConnected()`（模拟 HTTP 响应晚到）→ 断言 `authenticated == true` 不被破坏、`requestApi` 成功发出 v2 帧。旧代码在该用例下必挂（C-1 复现）。
2. `peerDisconnectEvent_clearsAuthentication_requiresRehandshake` — 反向用例：GATT 断开事件后认证被清，`requestApi` 拒绝，下次连接需重新握手。
3. `peerReconnectEvent_resetsAuth_thenRehandshakeSucceeds` — 新 GATT 链路使旧握手失效，重新握手可再度认证。
4. `markDisconnected_stillResetsAuthentication` — 回归：HTTP 协调断开仍 reset。
（既有 fatal/disable reset 测试全部原样通过；`pcChallengeAndExtractOwnNonce` 增加相对基线计数参数以支持同 manager 二次握手。）

## I-3：/tags 读端点补挂 authMw

`server/internal/server/server.go`：`GET /tags`、`GET /tags/:tag_id/files`、`GET /tags/:tag_id/media`、`GET /tags/file-tags` 全部补 `authMw`（写端点原本已挂）。空 token 透传论证与 H-2 媒体读一致（Android `AuthInterceptor` / Web `apiRequest` 全覆盖请求头）。

测试：`server_test.go` 新增 `TestTagReadEndpointsRequireToken`（独立 server 实例——**原因**：BearerToken 内嵌每 IP 10×401/min 失败退避，若追加进 `TestMediaReadEndpointsRequireToken` 的循环，第 11 个请求会变 429 而非 401）。四路径无 token 均 401，带 token 放行。既有 `TestMediaReadEndpointsRequireToken` / `TestMediaReadEndpointsOpenModePassthrough` 不变且通过。

## I-1 / L-8：签名守卫改 task graph

`android/app/build.gradle.kts`：

- fail-fast 从 `gradle.startParameter.taskNames` 字符串匹配改为 `gradle.taskGraph.whenReady { ... }`：图解析完成后、任何 task 执行前，若**实际将执行**的 task 名含 "release" 且无有效 keystore 且未传 `-PallowDebugSigning=true` → 抛出与 Phase 7 相同文案的 `GradleException`。GUI（Android Studio）驱动的 release 构建同样被拦截。
- debug 密钥兜底收敛：仅在 `-PallowDebugSigning=true` 时填充（保留三条 warn banner）；其余情况 release signingConfig 留空，正常 release 无 keystore 即 fail-fast（不再被静默武装成 debug 签名）。

行为验证（本机有真实 keystore，用临时改写 keystore.properties 的方式验证 fail-fast 路径）：

- `./gradlew tasks` → EXIT 0（配置阶段不受影响）；
- 有效 keystore + `:app:assembleRelease --dry-run` → 通过（守卫正确放行）；
- 无效 keystore + `:app:assembleRelease --dry-run` → `BUILD FAILED`，错误信息即守卫文案（图解析即触发，未执行任何 task）；
- `./gradlew testDebugUnitTest assembleDebug` → 全绿（debug 构建完全不读 release signingConfig）。

## I-2 + M-B：文档同步

- `docs/INDEX.md`：媒体表 folders/browse/videos(+stream/thumbnail)/images(+original)/search 的"需 Token"列改为"是（空 token 开放模式透传）"并加说明引注；补 `/texts` 遗漏行；books 表 info/chapter 两行同样过时，一并改为"是"；标签节标题去掉"无需 Token"、表格补 Token 列（I-3 后八个端点全部需 Token）。
- spec §5.3：加修订说明——缩略图限速实际落地 **60/min/IP**（`server.go` `rateLimitWhen(isThumbnailRequest, RateLimit(60, time.Minute))` 与 `/images/*`），原文 30/min 会误伤网格页并发批量加载。

## 测试与验证汇总

| 命令 | 结果 |
|---|---|
| `cd server && go build ./...` | PASS |
| `cd server && go vet ./...` | PASS |
| `cd server && go test ./internal/server/ ./internal/ble/ -count=1` | ok / ok（全绿；无 bookparser 基线触碰） |
| `cd android && ./gradlew testDebugUnitTest` | 284 tests, 0 failures, 0 errors（含 C-1 新增 4 例） |
| `cd android && ./gradlew assembleDebug` | BUILD SUCCESSFUL |
| 签名守卫行为（见 I-1 节） | fail-fast / 放行 / debug 不受影响均验证 |

Web 未涉及（`node --test` 未跑，按任务约定豁免）。

## Concerns

1. **【需要用户行动】keystore.properties 内容被误覆盖**：验证 I-1 fail-fast 时临时改写 `android/keystore.properties`，恢复用的 `git checkout` 对 gitignored 文件无效，原始内容（store/key 两个密码）丢失。**签名密钥本体 `android/localmediahub.keystore` 完好无损**，只需重建 4 行 properties（已留 recovery 模板 + 说明注释在文件顶部；可用 `keytool -list -keystore localmediahub.keystore -storepass <pw>` 验证密码）。debug 构建/测试不受影响；下次 release 前必须补回真实密码。
2. `git push` 失败（本机当前连不上 github.com:443），4 个 commit 已在本地 master，网络恢复后 `git push origin master` 即可。
3. `AndroidBlePeripheralManager.onConnectionStateChange` 的 `subscriberDevice` 无条件赋值行为按任务约束保留（已知递延项，未扩大范围）。
4. 工作区仍有两个与本任务无关的 untracked 文件（`docs/superpowers/reviews/`、`tools/reformat_novels.py`），未触碰。

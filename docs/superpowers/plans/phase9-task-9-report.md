# Task 9 Report: Android BLE v2 codec + 握手 + 重组上限（Phase 9）

- **Commit**: `752fa9d` — `feat(ble): Android authed v2 frames and reassembly cap (Phase 9)`（基线 `eb252fd`，master 直提）
- **验证**: `cd android && ./gradlew testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL；单测汇总 **270 tests, 0 failures, 0 errors, 0 skipped**（含 BLE 全部 72 项）
- **状态**: DONE_WITH_CONCERNS（功能全绿；concerns 为范围外文件触碰与 Task 10 依赖项，见文末）

---

## 1. 半成品续用 / 修正清单

### 续用（核对通过，未改语义）

| 文件 | 半成品内容 | 核对结论 |
|---|---|---|
| `ble/BleProtocol.kt` | v2 codec 全套（+229 行） | **逐字节核对通过**。我用临时 Go 测试实际运行 `server/internal/ble/protocol.go`（commit `eb252fd`）生成基准向量，与半成品 `BleProtocolTest.authedCodec_matchesGoReferenceVectors` 中的 7 组 hex **全部一致**（derive key、v2 帧 seq=7 / seq=MaxUint64、challenge/response payload、MAC、221B→220B 截断帧的 lenfield=0x00DC + 尾 MAC `b60f5ac22576b3777b78ff5e1f4cb79e`）。常量（0x02/0x20/0x21/0x01/0x02 dir）、HMAC 覆盖范围 `[0:3+len+8]`、截断 16B、`MessageDigest.isEqual` 常量时间比较均与 Go 对称。仅修 1 处编译错误（见下）。 |
| `ble/BleTransportFallback.kt` | 构造参数 `maxStreamBytes`（+7 行） | 参数与 KDoc 续用；上限实现本体缺失，由本次补齐（见下）。 |
| `BleProtocolTest.kt` / `BleTransportFallbackTest.kt` / `BleControllerTest.kt` | 三个测试文件 | 按任务要求作为规格实现生产代码。两处测试自身缺陷已修（见下）。 |

### 修正（半成品缺陷）

1. **`BleProtocol.kt:303` 编译错误**：`readUint64BE` 中 `buf[..].toLong() and 0xFFuL`（Long 与 ULong 混型）→ 改为 `(buf[off + i].toLong() and 0xFF).toULong()`。
2. **`BleProtocolTest.kt` assertNull 参数序**：brief Step 1 代码片段用了 `assertNull(object, message)`——JUnit 4 只有 `(message, object)` 重载，不编译。已交换参数序（语义不变，测试内注释说明）。
3. **`BleTransportFallbackTest.accumulatedBufferOverCapResetsStream` 逻辑矛盾**：原稿 `totalBlocks=120`（即线上 TotalBytes）> 注入 cap 64，按 brief "解出 totalBytes 后立即超限即重置" 语义第 1 帧就会被 declared 检查拒绝，永远积累不到断言的 60 字节。修正为 `totalBlocks=60`（≤ cap），6×20B 实发——这正是 accumulated 检查要防的"低报 TotalBytes 绕过 declared 检查"的谎言场景，测试意图（`assertEquals(60, ...)`）反而更成立。
4. `BleTransportFallbackTest.oversizedDeclaredTotalResetsStream`（brief 原文形状）保留：TotalBytes 字段是 uint16，`MAX_STREAM_BYTES+1` 在线上截断为 1，实际走"流永不完成→null"路径；半成品已加注释说明并补充两个注入小 cap 的专用用例——该解读正确，续用。

---

## 2. BleController 握手落点（本次实现核心）

`ble/BleController.kt`（+385 行重构）：

- **新入口 `onCommandWrite(rawFrame)`**：Command characteristic 写入的统一认证门（raw 物理帧）。`init` 里旧的 payload 回调改为 re-frame 成 v1 帧后汇入此门（`AndroidBlePeripheralManager` 现交付 v1 解码 payload；v2 raw 直通由 Task 10 接管 manager 后生效）。
- **PRE-AUTH 策略**（镜像 Go `handleNotifyFrame`）：
  - `CMD_AUTH_CHALLENGE(dir=C2P)` → ① token 为空：**不发任何响应**，fatal（DISCONNECTED + 错误文案，open-auth 无 BLE 数据通道）；② dir 非 C2P：fatal；③ 合法：回 v1 response（`MAC=HMAC-SHA256(key, nonce||dir)[:16]`）+ **紧接着**自发 v1 challenge(dir=P2C, SecureRandom 8B nonce)。`pendingOwnNonce` 在 notify **之前**登记（同步重入安全的顺序）。
  - `CMD_AUTH_RESPONSE` → nonce 匹配 pending + MAC 常量时间验证通过 → `authenticated=true`、`authErrorText=null`、双方向 seq 归零；任一不符 fatal。
  - 其它命令 pre-auth → fatal（旧明文 echo 行为移除）；undecodable-as-v1 → 静默丢弃（Go listener 对称）。
- **POST-AUTH 策略**：仅接受 v2 帧——明文 v1 帧天然过不了 `decodeAuthedFrame` 的结构/MAC 检查 → fatal（降级防护）；`seq <= maxRxSeq` → fatal（回放/回退）；验证过的 `CMD_JSON_CHUNK` payload re-wrap v1 后进 `BleTransportFallback`；`CMD_ECHO` 等其它命令以 v2 回显（连通性环）；认证后再收握手帧 → fatal。
- **出向**：`requestApi` / echo 走 `notifyAuthedFrameLocked`——seq 预留 + notify 在 `authLock` 单临界区（Go `sendAuthedFrame`/sendMu 对称，防并发乱序）；每连接首帧 seq=0，`markConnected`/`markDisconnected`/fatal 均重置。`requestApi` 未认证直接 false（Go `ErrNotAuthenticated` 对称）。
- **Go 实施者注意 (a) 落地**：握手 notify 带短重试（5 次 × 25ms ≈ 100ms，远低于 PC 侧 5s 兜底），CCCD 订阅与挑战写入的微秒级窗口由此桥接；重试耗尽 → fail closed。
- **fatal 路径**：`authenticated=false` + 状态机 `onAuthFailure()` → **DISCONNECTED**（区别于 HTTP 协调驱动的 `markDisconnected` → ADVERTISING）+ `authErrorText` 文案。
- **token 注入**：构造参数 `authTokenProvider: () -> String`，每次握手现读（跟随现有 lambda 注入模式）；DI 侧 `BleModule` 用 `MutableStateFlow("")` 缓存 `ServerConfigStore.authToken` 的最新 emission（DataStore 仅异步；缓存初始为空 = fail closed）。

### BleTransportFallback（M-9）

- `companion object` 公开 `MAX_STREAM_BYTES = 1_048_576`；`onFrameReceived` 返回类型 Unit → `ByteArray?`（本帧完成流时返回重组字节，其余 null）。
- 两道闸：① 解析后立即 `declaredTotalBytes > maxStreamBytes` → 整流重置（chunkBuffer + 计数）且拒缓冲；② 入缓冲前 `buffered + chunkLen > maxStreamBytes` → 同样整流重置（防低报谎报）。新增 `bufferedByteCount()` 访问器 + `bufferedBytes` 运行计数（resetLocked 同步清零）。
- spec 的 "stream ID" 项按 brief 决策省略（认证后注入向量消失），仅保留字节上限——注释中已记录。

---

## 3. 测试结果

- BLE 定向：`testDebugUnitTest --tests "com.juziss.localmediahub.ble.*"` + 受影响两个套件 → 全绿。
- 全量：`testDebugUnitTest assembleDebug` → BUILD SUCCESSFUL，**270/270**。
- Go 侧：未改任何 server 代码；基准向量由真实 Go 实现运行产出后比对（临时 vectortest 目录已删除，`go test ./...` 未受影响）。

---

## 4. Self-review

### 超 brief 6 文件清单的触碰（均为编译/DI 必然连锁，逐项说明）

| 文件 | 原因 |
|---|---|
| `ble/BleConnectionStateMachine.kt`（+16 行） | 新增**纯增量**方法 `onAuthFailure()` → DISCONNECTED。半成品测试（= 规格）要求 fatal 后可观察 DISCONNECTED 态，而原状态机**没有任何转移能产生 DISCONNECTED**（该枚举值原本无 producer，仅 UI 消费端在 map）。替代方案是在 BleController 里复制一套 StateFlow——状态所有权分裂，劣于给状态机加一个转移方法。 |
| `di/BleModule.kt`（+11 行） | `BleController` 新构造参数 `authTokenProvider` 的 Hilt 装配（缓存 token 的 appScope collect，模式与既有 `bleEnabled` collect 一致）。不加则 app 编译失败。 |
| `ble/BleModuleTest.kt` / `ble/TestBleFixtures.kt` / `viewmodel/BleSettingsViewModelTest.kt`（共 +8 行） | 三处 `BleController(...)` 构造的机械补参（`authTokenProvider`），纯编译修复，无行为断言变化。 |
| `data/MediaRepositoryFailoverTest.kt`（+165/-72 重构） | **行为性连锁**：仓库 failover 走 `requestApi` → 认证门 + v2 出向帧，旧 fake（v1 明文回 chunk）必然全挂。重构 `SimulatingPeripheralManager`：握手驱动（`simulateCentralHandshake`，P2C 应答同步回注——依赖上文 pendingOwnNonce 先登记的顺序）、v2 解码 + 线格式 parity 断言保留、chunk 以 raw v2 帧（PC 侧严格递增 seq）经新 `rawWriteSink` 直连 `onCommandWrite`（旧 payload seam 会 re-frame 成 v1 = post-auth fatal）。chunk 投递仍异步（delay 10ms），保留原测试的 async 纪律目的。 |

### 已知边界 / 遗留给 Task 10

1. **`AndroidBlePeripheralManager` 未动**（按指令属 Task 10）：它仍用 v1 `decodeFrame` 解码写入再回调，**v2 帧到不了 `onCommandWrite`**——真机端到端数据流需 Task 10 把 write 回调改为 raw 字节直通。本任务的 `onCommandWrite` 即为该接缝预留的落点。
2. **fatal 不主动断 GATT 链**：`BlePeripheralManager` 接口无 cancelConnection 语义（接口改动属 Task 10）；当前 fail closed 表现为状态/数据面死亡，PC 侧靠其握手/echo 超时收尸。Go `failConnection` 的 `Disconnect()` 对称项留待 Task 10。
3. **`authErrorText` 尚无 UI 暴露**：controller 属性已就绪，`BleSettingsViewModel`/`ConnectionScreen` 的展示接线属后续任务。
4. **DI token 缓存冷启动窗口**：app 启动后 DataStore 首次 emission 前缓存的 token 为空 → fail closed（握手被拒）。实际时序上握手远晚于首 emission，无实际风险；如需彻底消除可后续加 DataStore 首读阻塞预取（不在本任务范围）。
5. `onFrameReceived` exhaustion 分支保留基线的"capture hook 后直接 return（不 invoke）"行为（调用方靠自身 `withTimeoutOrNull` 兜底）——未顺手改语义，避免无关行为漂移。

### 提交卫生

- 仅提交 12 个修改文件；工作区原有他人未跟踪的 `docs/superpowers/reviews/` 与 `tools/reformat_novels.py` 未纳入。
- commit message 用 brief Step 6 原文：`feat(ble): Android authed v2 frames and reassembly cap (Phase 9)`。

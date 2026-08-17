# Task 8 Report — BLE v2 认证帧 + HMAC 双 nonce 互挑战握手（Go 端）（H-1a）

**Status: DONE_WITH_CONCERNS**（功能与测试全部完成；concerns 为生产环境既有架构限制的说明，非本任务缺陷，见 §5）

**Commit:** `31edce3f1ec16ea3fad98e0a958a2c32dc291bf9` — `feat(ble): authed v2 frames with HMAC handshake on server (Phase 9)`

**改动文件（9 个，+1063/−151）：**

| 文件 | 改动 |
|---|---|
| `server/internal/ble/protocol.go` | v2 线格式 + 握手 payload 编解码 + `DeriveBleAuthKey`（brief Step 3 代码原样落地） |
| `server/internal/ble/protocol_test.go` | brief Step 1 两个测试原样落地 |
| `server/internal/ble/central.go` | 握手状态机（详见 §2） |
| `server/internal/ble/central_test.go` | `blePeerFake`（模拟 Android Peripheral）+ 3 个 brief 用例 + authKey 空拒绝用例 + 既有测试适配 |
| `server/internal/ble/central_chapter_test.go` | 多 chunk 流 / listener 派发测试适配 v2 认证流 |
| `server/internal/ble/api_provider_test.go` | `newCentralWithProvider` 改为带握手的 peer fake，断言改 `DecodeAuthedFrame` |
| `server/internal/server/handler/ble.go` | scan/connect 空 token → 400（开放模式说明） |
| `server/internal/server/handler/ble_test.go` | 既有测试补 cfg+token；新增 2 个开放模式 400 用例 |
| `server/internal/server/server.go` | **超范围必要改动**：`bleCentral.SetAuthToken(cfg.Server.Token)` 一行接线（见 §4） |

---

## 1. protocol 层（严格按 brief 原文）

- 常量：`FrameVersion2 = 0x02`、`CmdAuthChallenge = 0x20`、`CmdAuthResponse = 0x21`、`AuthDirCentralToPeripheral = 0x01`、`AuthDirPeripheralToCentral = 0x02`、`authedOverhead = 24`、`maxAuthedPayloadLen = 220`。
- v2 线格式 `[0x02][len 2B BE][payload ≤220B][seq 8B BE][hmac 16B]`，HMAC 覆盖 `[0 : 3+len+8]`，`subtle.ConstantTimeCompare` 比较；`ErrBadMAC` / `ErrReplaySeq` 加入既有 errors 块。
- `EncodeAuthedFrame` / `DecodeAuthedFrame` / `EncodeAuthChallengePayload` / `DecodeAuthChallengePayload` / `AuthResponseMAC` / `EncodeAuthResponsePayload` / `DecodeAuthResponsePayload` / `DeriveBleAuthKey`（`sha256("lmh-ble-v1:" + token)`）函数体逐字采用 brief 代码（仅补文档注释与 gofmt 对齐）。
- 包文档补充 v1/v2 双格式说明；握手帧以 v1 承载、认证后数据帧全 v2，与 brief 一致。
- **一个刻意的语义补充（非偏离）**：`DeriveBleAuthKey` 的文档注明不得用空 token 调用 —— `sha256("lmh-ble-v1:")` 是公开可计算的常量。Central 侧由 `SetAuthToken("")` 直接存 nil key 兜底（见 §2），线格式与校验语义未变。

## 2. central 层握手状态机落点选择（本报告核心）

### 落点：握手整体放在 `Central.Connect` 内同步驱动，`RunApiListener` 实现收帧策略门

`Central` 新增字段：`authenticated bool` / `localSeq, remoteSeq uint64` / `authKey []byte`（brief 要求）+ 两个实现细节字段 `haveRemoteSeq bool`（首帧任意 seq 可接受、其后严格递增 —— "拒绝 ≤ 已见最大 seq" 在"尚未见过帧"时无比较对象，需要哨兵）与 `handshaking bool`（listener 暂停标志，见下）。

**流程（brief Step 4 五步，顺序与语义一致）：**

1. `Connect` 先做 authKey 空拒绝（`ErrNoAuthKey`，不碰无线电）→ `scanner.Connect` 成功后立即写 v1 `CmdAuthChallenge(dir=CentralToPeripheral, nonce1)`（crypto/rand 8B）；
2. 读回 v1 `CmdAuthResponse(nonce1, mac1)`，校验 nonce 回显 + `hmac.Equal(AuthResponseMAC(key, nonce1, dir))`；
3. 读回 v1 `CmdAuthChallenge(dir=PeripheralToCentral, nonce2)`（**顺序无关**：响应与对方挑战按任意到达顺序处理，两个条件都满足才置位）；
4. 回写 v1 `CmdAuthResponse(nonce2, mac2)`；
5. `authenticated = true`；此后 `RunApiListener` 只走 `DecodeAuthedFrame`（bad MAC/结构错 → 断开）+ `acceptRemoteSeqLocked`（回退/重复 → `ErrReplaySeq` 并断开）；发帧一律 `EncodeAuthedFrame(localSeq++)`（`Send` 与 `ServeApiRequest` 的 CMD_JSON_CHUNK 写点都已切换，写失败留下 seq 空隙不影响严格递增语义）。
- 5s 超时：`context.WithTimeout(ctx, bleHandshakeTimeout)`（`ctx` 派生自调用方），超时/坏 MAC/认证前非握手命令 → `scanner.Disconnect()` + 状态复位 + 返回 `ErrHandshakeFailed` 包装错误（fail closed）。
- pre-auth 收帧策略（listener 侧 `handleNotifyFrame`）：`!authenticated` 时仅 v1 且仅放行两个 AUTH 命令（到达此处说明是 raced/stale 帧，静默丢弃）；**其他任何命令认证前一律断开**（brief "v1 只放行握手命令" 的 fail-closed 落地）。

**为什么握手放 `Connect` 而不是 listener（任务指令要求的说明）：**

- **没有显式 "CCCD 订阅完成" 回调** —— 本代码库唯一的 CCCD 写发生在 adapter 的 `WaitNotify → EnableNotifications` 内部，而 adapter（central_adapter.go）本任务禁止修改。最接近的既有钩子有两个候选：`Central.Connect`（连接建立被观测之处）与 listener 的 pre-arm 时刻。
- 选 `Connect` 的决定性理由是 **notify 丢失窗口**：listener 在无连接时处于 1s backoff 重试循环（`apiListenerRetryBackoff`）。若由 listener 在"发现已连接后"再补发挑战，连接建立到挑战发出之间有最长 1s 的未订阅窗口；若由 `Connect` 发挑战、listener 收帧（brief 字面流程），手机响应可能在 listener 尚未 arm CCCD 时到达而永久丢失 → 首次连接几乎必然 5s 超时断开。放在 `Connect` 内则挑战写入与第一次 `WaitNotify`（即第一次 CCCD 订阅）背靠背执行，窗口压缩到微秒级。
- **`handshaking` 暂停标志**解决双消费者竞争：握手期间 listener 在循环顶检测到 `handshaking=true` 就 sleep 20ms 轮询、不调用 `WaitNotify`，避免两个 `EnableNotifications` 互相抢占 CCCD handler（adapter 的 one-shot 语义）。残余 TOCTOU（listener 恰好在标志检查与 arm 之间）概率极小，且后果只是握手超时断开、客户端重连重试，不产生安全漏洞。
- 附带收益：`Connect` 的返回值诚实反映认证结果（HTTP `/ble/connect` 在握手失败时返回 `connected:false`），且测试无需跨 goroutine 编排即可用 fake 驱动。

**空 token 防线（三层）：**

1. handler 层：`/api/v1/ble/scan|connect` 在 `cfg.Server.Token == ""` 时返回 400 `ble unavailable in open-auth mode: set server.token to enable the BLE channel` + `slog.Warn`（按 brief "handler 层拒绝，日志说明开放模式下 BLE 不可用"）；
2. `SetAuthToken("")` 存 **nil** key（绝不派生空 token 的可预测 key）；
3. `Connect` 对 nil authKey 返回 `ErrNoAuthKey`（"central 侧 authKey 为空必须拒绝进入数据阶段"），`Send`/`ServeApiRequest` 对未认证状态返回 `ErrNotAuthenticated`。

## 3. 测试

**新增（brief 指定）：**
- `TestAuthedFrameRoundTripAndTamper` / `TestAuthChallengeResponsePayload`（protocol 层，brief Step 1 原文，round trip / tamper→ErrBadMAC / wrong key→ErrBadMAC / challenge-response payload）；
- `TestCentralHandshakeSuccessAndChunkTransfer`（正确密钥完成握手，CMD_JSON_CHUNK 以 v2 落线、seq 严格递增、`DecodeFrame` 拒绝为 ErrBadVersion）；
- `TestCentralHandshakeWrongKeyDisconnects`（错误密钥 → `ErrHandshakeFailed` + 状态 disconnected + adapter Disconnect 被调）；
- `TestCentralV2ReplaySeqRejected`（v2 重放同 seq → 断开 + 重放请求不被二次服务；分两阶段注入保证确定性：先等首个请求的 chunk 写出、再注入重放帧）；
- 附加：`TestCentralConnectRequiresAuthKey`、`TestScanBLEOpenAuthModeRejected`、`TestConnectBLEOpenAuthModeRejected`（空 token 400）。

**测试基建：** `blePeerFake`（central_test.go）模拟 Android Peripheral：收到 PC 的 v1 挑战后排队 [合法响应, 自己的挑战(nonce 固定)]，校验 PC 响应 MAC，v2 数据帧经内嵌 `collectScanner` 记录；`WaitNotify` 按序弹出 + 耗尽后 5ms 轮询支持延迟注入（重放测试用）。Task 9 的 Kotlin 端可直接对照此 fake 实现行为。

**既有测试适配（行为变化所致，均最小改动）：** `TestCentralConnectSetsState` / `TestCentralConnectSerializesConcurrentCalls` / `TestCentralSendEncodesAndReturnsEcho`（改为 v2 echo）/ `TestServeApiRequestStreamsMultipleChunks` / `TestRunApiListenerDispatchesApiRequests`（v2 帧 + seq 递增断言）/ api_provider_test.go 全部走 `newCentralWithProvider`（现在驱动真实握手）。

**结果：** `go test ./internal/ble/ -v` → 39/39 PASS；`go build ./...` ✓；`go vet ./...` ✓；`go test ./...` 除既有基线例外 `internal/service/bookparser TestParseUserNovel`（任务指令明确忽略）外全 PASS；`go build -tags bluetooth ./internal/ble/`（winrt adapter 构建）✓。gofmt：改动行 clean（仓库存在全局性的 Go 1.19 doc-comment 风格与 CRLF 历史问题，非本任务引入，未触碰）。

## 4. 与 brief 文件清单的偏差（已声明的超范围改动）

1. **`server/internal/server/server.go`（+5 行）**：`NewCentral` 后调用 `bleCentral.SetAuthToken(cfg.Server.Token)`。brief 的 Files 未列 server.go，但不接线则生产环境 authKey 永远为 nil、带 token 时 BLE 全不可用 —— 这是让功能成立的必要最小接线。
2. **`central_chapter_test.go` / `api_provider_test.go` / `handler/ble_test.go`**：同包/同目录的既有测试因新门禁必须适配（Connect 现在要求握手成功）。brief git add 清单外，随本次提交。

## 5. Self-review 发现与 concerns（供 Task 9 / 后续任务参考）

1. **（重要，Task 9 需对称实现）notify 早到丢失的窄窗口**：挑战写入与 CCCD 订阅是相邻两次调用，但理论上手机响应仍可能早于 `EnableNotifications` 生效。建议 Android 端 `sendNotification` 失败（CCCD 未订阅）时做短重试；PC 端已有 5s 超时断开 + 客户端重连作为兜底。
2. **（既有架构限制，非本任务引入）`Send` 的 echo 应答与 listener 的 WaitNotify 竞争**：`/api/v1/ble/send` 的一次性 `WaitNotify` 可能被 listener 抢走 echo 帧（改造前即存在）。adapter 的订阅模型（one-shot EnableNotifications）是根因，本任务按指令未动 adapter。
3. **（既有）重连时 listener 可能悬挂在旧连接的已 arm WaitNotify 上**：`Connect → connectLocked` 清空特征指针不会唤醒已阻塞的 listener 调用。改造前同样存在，后果是重连后 CMD_API_REQ 派发停摆直至进程重启；如需修复属 adapter 任务。
4. **协议设计固有限制（brief 定义如此，如实记录）**：authKey 由 server.token 派生、token 不轮转时跨连接不变，seq 空间每连接重置 —— 上一连接的合法 v2 帧在新连接首帧位置重放可通过 MAC 与首帧任意 seq 规则。缓解：两端握手后首帧应从固定小 seq 开始（双方均从 0 起，Android 端对称实现后，重放旧帧的 seq 几乎必然 ≥ 新连接已见值而被拒；若首帧即重放帧则窗口理论存在）。如需彻底关闭可后续把 seq 种子绑定握手 nonce（协议变更，需两端同步，超出本任务）。
5. `DecodeAuthedFrame` 不显式校验 version 字节（brief 代码原样）：HMAC 覆盖 version 字节且长度校验兜底，任何 v1 结构帧要么长度不足报错要么 MAC 失败，安全语义等价。
6. `Connect` 全程持锁（最长 5s）：期间 `State()`/`Send()` 会短暂阻塞，与 HTTP connect 的 11s 预算相容，无死锁路径（listener 的锁获取均为瞬时）。

---

# Fix Report（审查 NEEDS_FIX 回应）

**Commit:** `eb252fdd3b3a86ee0b1829ac4055ae056cf3a5b4` — `fix(ble): serialize authed frame writes and close auth gate test gaps (Phase 9)`（2 files, +203/−16：`central.go` + `central_test.go`）

## I-1 修复：seq 预留与写出的原子性（必改项）

**问题确认**：原 `ServeApiRequest` 循环内 "c.mu 下预留 seq → 释放 → WriteCommand" 两步分离，两个 dispatch goroutine 交错时线上出现 [1,0] 物理乱序；也会与 `Send` 的写路径互相同类竞争。审查复现的 flaky（`frame 1 seq 0 not strictly greater than 1`）即此根因。

**修法（采用审查建议的专用 send mutex 方案）**：
- `Central` 新增 `sendMu sync.Mutex`，新 helper `sendAuthedFrame(ctx, scanner, payload, key)`：在 **sendMu 单一临界区内** 完成 "c.mu 短暂取 localSeq 并 ++ → WriteCommand"，seq 预留与无线电写原子化；
- `ServeApiRequest` chunk 循环与 `Send` 的数据写全部改走 `sendAuthedFrame` —— 全部出站 v2 写路径共享同一串行化点，线序 == seq 序（写失败消耗 seq 留空隙，严格递增语义允许空隙）；
- `Send` 重构：不再整方法持有 `c.mu`（否则与 sendMu→c.mu 锁序死锁），改为快照 state/authKey/scanner 后释放，再经 `sendAuthedFrame` 写、`sendMu` 释放后 WaitNotify 等 echo（不在写锁下等回包，避免 echo 往返阻塞无关 chunk 流）；回包的 MAC/seq 校验失败路径维持原语义（断开+复位，显式 c.mu 包裹）；
- **锁序纪律**：sendMu → c.mu，绝不反向。`Connect` 握手写（v1、无 seq）不取 sendMu 且不可能与数据写重叠（握手期间 authenticated=false，而 `Send`/`ServeApiRequest` 在 c.mu 快照处被挡到握手结束），无死锁路径。
- **取舍（按审查要求在代码注释中说明）**：`c.mu` 绝不跨 `WriteCommand` 持有（GATT 流控可阻塞，`c.mu` 保护 State/listener 门等短路径）；sendMu 只覆盖单次写调用时长，与 BLE 单链路"同一时刻仅一个 GATT 写在飞"的物理事实一致。`sendMu`/`sendAuthedFrame` 的字段与函数注释均写明了 I-1 背景、锁序与该取舍。

## M-3 测试缺口补齐（随 I-1）

| 用例（central_test.go） | 覆盖 |
|---|---|
| `TestCentralV1FrameAfterAuthRejected` (M-3a) | authenticated 后对端发裸 v1 `CmdApiReq` → `DecodeAuthedFrame` 失败（v1 帧无 seq/MAC，长度即不足）→ 断开、零 chunk 写出（防降级） |
| `TestCentralListenerPreAuthApiReqDropsLink` (M-3b) | 未认证状态下 listener 收到裸 v1 `CmdApiReq` → pre-auth 策略门 fail-closed：`failConnection`（peer fake 观察到 Disconnect）+ 零派发 |
| `TestCentralHandshakeTimeoutDisconnects` (M-3c) | `blePeerFake.silent`（收到挑战不应答）+ 250ms 父 ctx → `ErrHandshakeFailed` 包装错误 + scanner Disconnect + state disconnected；并断言耗时 ≤5s。说明：`context.WithTimeout(parent, bleHandshakeTimeout)` 有效期限为 min(parent, 5s)，短父 ctx 走的 deadline-exceeded 分支与 5s 上限自身完全同路径（不引入可测性 hook 改动协议常量） |

测试基建增补：`blePeerFake.silent bool`（挑战写成功但不排队响应，模拟不应答的手机）。

## 验证（命令与结果）

- `go test ./internal/ble/ -v -count=1` → **42/42 PASS**（39 原有 + 3 新增 M-3 用例），`ok github.com/localmediahub/server/internal/ble 1.212s`
- 竞态定向压力：`go test ./internal/ble/ -count=30 -run 'TestRunApiListenerDispatchesApiRequests|TestCentralV2ReplaySeqRejected|TestServeApiRequestStreamsMultipleChunks|TestServeApiRequestLargeResponseSplitsChunks|TestCentralSendEncodesAndReturnsEcho'` → `ok 1.799s`（150 次运行 0 复现）
- 整包压力：`go test ./internal/ble/ -count=10` → `ok 8.494s`
- `go build ./... && go vet ./...` → 通过（BUILD_VET_OK）
- `go test -count=1 ./...` → 除既有基线例外 `internal/service/bookparser TestParseUserNovel`（指令明确忽略）外全 ok
- `go build -tags bluetooth ./internal/ble/` → 通过（BT_OK，winrt adapter 构建不受锁改动影响）

## Minor 项确认延后（本轮未处理，遵审查指令）

M-1（handshaking 轮询分支不可达）、M-2（pre-auth 垃圾帧 drop 不断开的策略不对称）、M-4（brief 死代码 `wrong := DecodeAuthedFrame` 照抄）、M-5（Send 失败路径未复用 failConnection）、M-6（seq 种子未绑定 nonce 的跨连接重放理论窗口）—— 均保持原状，留待后续轮次。

# Task 10 Report: Android GATT 特征加密与回调守卫（H-1c / L-9 / L-10）

**Commit:** `77bcc7a` — `feat(ble): encrypted GATT characteristics with bond guards (Phase 9)`
**Status:** DONE_WITH_NOTED_RISKS（两条已记录风险见下，均不阻塞）
**Date:** 2026-08-17

---

## 1. 做了什么

### 1.1 特征加密权限（H-1c，brief 核心要求）

`android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt`：

- Command 特征：`PERMISSION_WRITE` → **`PERMISSION_WRITE_ENCRYPTED`**
- State 特征：`PERMISSION_READ` → **`PERMISSION_READ_ENCRYPTED`**
- CCCD descriptor 保持 `PERMISSION_WRITE`（brief 只要求 Command/State 两个特征加密；未绑定设备的 CCCD 写由回调层 bond 守卫拦截，这正是 brief 设计的守卫生效点——若 CCCD 也设为 ENCRYPTED，未绑定写在栈层就被吞掉，brief 要求的 `onDescriptorWriteRequest` 守卫反而永远无法触发）
- 首个连接触发系统 LE Just Works 配对，一次即可（brief 预期行为，未做任何 UI 提示，按 brief 不扩大范围）

### 1.2 回调守卫 + 可测纯函数（H-1c / L-9）

manager 同文件新增顶层声明：

- `enum class WriteDecision { ACCEPT, REJECT_AUTH, REJECT_NOT_SUPPORTED }`
- `fun shouldAcceptWrite(bondState, offset, preparedWrite): WriteDecision` — 先 bond 后形状的判定顺序：`bondState != BOND_BONDED` → `REJECT_AUTH`；`offset != 0 || preparedWrite` → `REJECT_NOT_SUPPORTED`；否则 `ACCEPT`
- `fun isCccd(uuid: UUID): Boolean` — 仅 `00002902-0000-1000-8000-00805f9b34fb` 为 true
- CCCD_UUID 从 private companion 移为文件级 `private const val`（供 `isCccd` 与 descriptor 构造共用）

`onCharacteristicWriteRequest` / `onDescriptorWriteRequest` 两个回调开头插入守卫：

- `REJECT_AUTH` → `sendResponse(GATT_INSUFFICIENT_AUTHENTICATION)`（该错误响应即 LE 栈自动发起 Just Works 配对的触发器）
- `REJECT_NOT_SUPPORTED` → `sendResponse(GATT_REQUEST_NOT_SUPPORTED)`
- 被拒写**不回调上层**（`onRawFrameReceived` 不触发、subscriber 不替换）
- descriptor 路径额外规则：**仅 CCCD 写可替换 `subscriberDevice`**，非 CCCD descriptor 写回 `GATT_REQUEST_NOT_SUPPORTED`

### 1.3 Raw 直通收口（Task 9 遗留 #1）

- `AndroidBlePeripheralManager.onCharacteristicWriteRequest`：删除 `BleProtocol.decodeFrame(value)`，改为 `onRawFrameReceived?.invoke(value)` —— 原样字节（v1 握手帧 + v2 数据帧）直通 controller，manager 侧零解码/零重组
- `BlePeripheralManager` 接口：`setOnPayloadReceived` 更名 **`setOnRawFrameReceived`**（契约变更写进名字，避免再次出现 payload vs raw frame 的接缝误解），KDoc 注明 raw 直通契约
- `BleController.init`：删除 Task 9 的 `encodeFrame(payload)` re-frame 兜底，`onCommandWrite(rawFrame)` 直接消费 raw 字节；v1/v2 分派仍由 controller 收帧入口统一负责（未动认证逻辑）
- `MediaRepositoryFailoverTest` 的 `rawWriteSink`（Task 9 因接缝无法传 v2 而开的"直连 onCommandWrite"旁路）随之删除——v2 帧现在走与真机完全相同的 cb 接缝，测试反而更贴近真实链路

### 1.4 Fatal 主动断链（Task 9 遗留 #2，最小接线）

- `BlePeripheralManager` 接口新增 **`disconnectPeer()`**；Android 实现调 `BluetoothGattServer.cancelConnection(device)`（`disconnect` 在 GattServer 上是 hidden API；`cancelConnection` 是 public 等价物，且同时能取消 pending connect），随后立即置空 `subscriberDevice`（fail closed：即便链路短暂残留，fatal 后也不可能再发出任何 notify；`onConnectionStateChange(STATE_DISCONNECTED)` 到来后重复置空无害）
- `BleController.fatalLocked` 末尾追加一行 `peripheralManager.disconnectPeer()` —— 状态机逻辑零改动（`machine.onAuthFailure()` 语义不变），边界严格限定在 fatal 路径：`markConnected` / `markDisconnected` / `evaluateAvailability(false)` / 成功握手均不触发（有负向测试锁定）
- 5 个测试 fake（BleModuleTest / TestBleFixtures / BleControllerTest / MediaRepositoryFailoverTest / BleSettingsViewModelTest）全部同步接口变更

### 1.5 PC 端 MTU 步骤（L-10）

`server/internal/ble/central_adapter.go` 连接流程（`connectLocked` 找到双特征之后）新增 `requestMtu247()`：

- 实现采用 **`cmdChar.GetMTU()` 回读协商值 + 分级日志**（≥247 / <247 / 读取失败按 23 假设），任何错误不 fatal
- **为什么不是 `BluetoothLEDevice.RequestMaxPayloadSize(247)`**（brief 允许"或等价 API"）：
  1. vendored winrt-go（`github.com/saltosolutions/winrt-go@9c2fec5`）**未绑定**该方法（已核对 `bluetoothledevice.go` 的 vtable 生成：IBluetoothLEDevice1–6 均无）
  2. 手写 IBluetoothLEDevice5 COM vtable 调用需要对 slot 序号 ~85% 置信度，错一位 = 原生 crash 全 server 挂掉——正是 `disconnectLocked` 注释里记录过的 0xc0000005 故障类别，风险不可接受
  3. WinRT 本身在 GATT session 建立时**自动向 peripheral 请求最大 PDU**（Win10 1703+ 行为），`GattSession.MaxPduSize` 即协商结果，显式 request 在现代 Windows 上是冗余的
- brief 的兜底条款原文即为此留了后路："若 adapter 不支持则保持 23 并由既有短帧解码错误兜底"——协商值 <247 时日志明确提示 single-PDU 上限与短帧兜底路径
- stub 构建（`central_adapter_stub.go`）零改动，默认构建不受影响

## 2. 测试

### 新增测试

| 文件 | 测试 | 锁定内容 |
|---|---|---|
| `BlePeripheralGuardsTest.kt`（新文件） | `writeGuardRejectsUnbondedOffsetAndPrepared` | brief Step 1 模板：BOND_NONE/BONDING→REJECT_AUTH、offset 4→REJECT_NOT_SUPPORTED、prepared→REJECT_NOT_SUPPORTED、(BONDED,0,false)→ACCEPT |
| 同上 | `onlyCccdReplacesSubscriber` | 0x2902 true；0x2901 / 自定义 UUID false |
| `BleControllerTest.kt` | `rawSeam_endToEnd_v1HandshakeAndV2DataReachController` | **Task 9 遗留 #1 的回归锁**：raw v1 握手帧完成 mutual auth + raw v2 数据帧穿过同一 fake-manager 接缝进入重组引擎完成 fetchJson（Task 10 之前 v2 帧死在 manager 的 v1-only decode） |
| 同上 | `fatalAuthViolation_cancelsGattLinkViaManager` | **Task 9 遗留 #2 的接线锁**：fatal 恰好触发 1 次 disconnectPeer |
| 同上 | `nonFatalPaths_doNotCancelGattLink` | 负向锁：握手成功 + markDisconnected + disable 均不误杀链路 |

### TDD 过程

Step 1–2 按 brief 执行：先写 `BlePeripheralGuardsTest`，运行确认编译失败（`Unresolved reference 'shouldAcceptWrite' / 'isCccd'`，brief 预期的失败形态），再实现转绿。

### 验证结果（全绿）

```
cd android && ./gradlew testDebugUnitTest assembleDebug
  → BUILD SUCCESSFUL；275 tests, 0 failures, 0 errors, 0 skipped
    （BleControllerTest 22 / BlePeripheralGuardsTest 2 / BleModuleTest 1 /
     MediaRepositoryFailoverTest 8 / BleSettingsViewModelTest 17 …）

cd server && go build ./... && go vet ./...          → PASS
cd server && go build -tags bluetooth ./...           → PASS
cd server && go vet -tags bluetooth ./internal/ble/   → PASS
cd server && go test ./internal/ble/                  → ok 0.841s
```

## 3. Self-Review

- **改动边界**：`AndroidBlePeripheralManager.kt` + `BlePeripheralManager.kt` + `BleController.kt`（仅 init 接缝与 fatalLocked 末尾一行 + 文档）+ 5 个测试文件 + 1 个新测试文件 + `central_adapter.go`（纯增量 34 行）。**未动** Go 认证逻辑（central.go / protocol.go 零改动）、未动 `BleProtocol.kt`、未做 authErrorText 的 UI 暴露（后续任务）。
- **守卫完备性**：bond 检查用 `BOND_BONDED`（brief 原文）；descriptor 与 characteristic 两个回调共用同一 `shouldAcceptWrite`，判定逻辑单一来源；被拒写在任何路径下都到不了 controller 的 auth gate。
- **raw 直通一致性**：manager、接口 KDoc、controller init、全部 fake 四处契约表述一致（"exact on-air bytes, no decode/re-frame"）；`MediaRepositoryFailoverTest` 现在端到端走真实接缝（比 Task 9 的 rawWriteSink 旁路更强）。
- **fatal 断链最小性**：不动状态机、不改 markConnected/markDisconnected 语义、disconnectPeer 在无 peer 时是 no-op；`stopAdvertising` 路径 `gattServer.close()` 本身就会断所有连接，无需额外调用。
- **grep 复查**：`setOnPayloadReceived` / `rawWriteSink` 全仓库零残留。

## 4. 已记录风险（不放宽、不阻塞）

1. **bondState 时序窗口**（按指示以 brief 原文为准，未自作主张放宽）：若链路已加密但 `bondState` 尚未翻为 `BOND_BONDED`，守卫仍会拒绝；对端在 bond 完成后重试即成功。安全方向错误（fail closed），代价是最多一次写重试。已写入 `shouldAcceptWrite` KDoc。真机若观察到"配对完成后首写偶发被拒后立即恢复"，即为该窗口，属预期。
2. **MTU 未做显式 request**：采用 WinRT 自动协商 + `GetMTU` 回读诊断。若某适配器/OS 组合不自动协商（停留在 23），行为退化为 brief 允许的"短帧解码错误兜底"，日志已给出可辨识的提示行。真机联调时看 `BLE negotiated ATT MTU=` 日志即可确认实际协商值。
3. **真机验证待做**（本任务仅 fake-manager 层单测）：Just Works 配对弹窗、加密特征首访问、CCCD 订阅在 bond 后的完整握手，需 PC(-tags bluetooth) ↔ 真机联调确认。

## 5. Task 9 接缝收口清单

| 遗留 | 处置 |
|---|---|
| #1 manager 在 decodeFrame(v1) 后回调，v2 帧到不了 controller | raw 直通 + 接口更名 `setOnRawFrameReceived` + 删除两侧解码/re-frame + `rawSeam_endToEnd` 测试锁死 |
| #2 fatal 只回 DISCONNECTED，不主动断链 | `disconnectPeer()`（接口 + cancelConnection 实现）+ fatalLocked 一行接线 + 正负向测试 |
| #3 authErrorText UI 暴露 | 按任务边界**未做**（后续任务），authErrorText 本身已在 Task 9 就绪 |

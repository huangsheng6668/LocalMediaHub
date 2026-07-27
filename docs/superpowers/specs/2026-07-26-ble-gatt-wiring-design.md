# BLE GATT 硬件接线设计（范围 2 续：最小连通性验证）

**日期**: 2026-07-26
**范围**: server 端 (`server/`) + Android 端 (`android/app/src/main/java/com/juziss/localmediahub/`)
**目标**: 把上期搭建的 BLE 协议层 + 状态机 + 门控 + UI 接到真实 GATT 硬件，实现 Android（Peripheral）↔ PC server（Central）端到端连通，完成双向 echo 验证。
**前置**: `docs/superpowers/specs/2026-07-26-ble-control-channel-design.md`（上期 spec）。

---

## 1. 背景：为什么要反转角色

上期 spec 把 server 定为 Peripheral（广播），Android 定为 Central（扫描）。本期的核心现实约束推翻了这个分配：

- **目标平台 = Windows 优先**（用户决策）。
- `tinygo-org/bluetooth` 在 Windows 用 winrt-go 后端，**Central 路径成熟，Peripheral 路径不稳定**（Windows 对 BLE Peripheral 模式历史支持差）。
- 因此**反转角色**：Android 当 Peripheral（广播），PC 当 Central（扫描连接）。

Linux 支持留作后续（BlueZ 双角色都成熟，到时无需再反转）。

---

## 2. 定位重定义：BLE 是并行低延迟控制通道，不是 Wi-Fi fallback

上期 spec 把 BLE 定位为"Wi-Fi 挂了还能用"的 fallback。本期因 A 方案（PC 无 UI，BLE 连接由 Android 通过 Wi-Fi/HTTP 协调）的现实约束，**这个定位不成立**——BLE 连接的建立本身依赖 Wi-Fi 在线。

**新定位**：

- BLE 是**并行低延迟控制通道**：Wi-Fi 在线时，控制类小信令走 BLE 获得更低延迟/更稳的响应。
- **放弃户外无 Wi-Fi fallback 场景**（本来就不是核心需求）。
- BLE 的真实价值 = 控制信令的低延迟路径（相对 Wi-Fi 的优势），不是离线兜底。

> 上期 spec 的"零退化"原则仍然成立：BLE 关/不可用 → 现有 Wi-Fi/HTTP 行为完全不变。BLE 是纯增量。

---

## 3. 架构：角色与职责

| 设备 | BLE 角色 | 职责 |
|---|---|---|
| **Android app** | **Peripheral**（广播方） | 广播 SERVICE_UUID；BluetoothGattServer 提供 Command (Write C→S) + State (Notify S→C) 特征；echo 回声 |
| **PC server**（Go） | **Central**（扫描连接方） | 扫描过滤 UUID；通过 HTTP 暴露扫描结果 + 执行连接；持有 BLE 连接，双向收发 |

**协调通道**：BLE 连接由 Android 通过 **Wi-Fi/HTTP** 协调——Android 调 PC HTTP 拿扫描结果 → 用户选 → Android 调 PC HTTP 触发连接 → PC 发起 GATT 连接。

**数据中转**：Android 的"发送测试"先到 PC HTTP，PC 写 BLE → Android 收 → Android Notify 回 → PC 收 → HTTP 响应回 Android。BLE 连接由 PC 持有，Android 触发都经 PC HTTP。

---

## 4. 组件清单与文件边界

### server 端（Go，PC 当 Central）

| 文件 | 职责 | 动作 |
|---|---|---|
| `server/internal/ble/central.go` | Central 封装：扫描、过滤 SERVICE_UUID、连接、GATT 发现、读写特征、状态、并发串行化 | 新建 |
| `server/internal/ble/central_test.go` | Central 纯逻辑测试（Adapter 接口 mock） | 新建 |
| `server/internal/ble/central_adapter.go` | `bluetooth` build tag：tinygo 实现的 Central Adapter | 新建 |
| `server/internal/ble/central_adapter_stub.go` | 默认构建 stub（无蓝牙时不崩） | 新建 |
| `server/internal/server/handler/ble.go` | HTTP handler：`GET /api/ble/scan`、`POST /api/ble/connect`、`POST /api/ble/send` | 新建 |
| `server/cmd/server/main.go` | 启动时初始化 Central（非致命），注册 ble handler | 修改 |
| `server/internal/ble/peripheral.go` / `peripheral_test.go` / `tinygo_adapter.go` / `tinygo_adapter_stub.go` / `tinygo_adapter_test.go` | 上期 Peripheral 代码 | **删除**（角色反转后 PC 不再当 Peripheral） |
| `server/internal/ble/protocol.go` / `protocol_test.go` | 帧编解码（双向复用） | **保留** |

### Android 端（Kotlin，当 Peripheral）

| 文件 | 职责 | 动作 |
|---|---|---|
| `ble/AndroidBlePeripheralManager.kt` | `BluetoothGattServer` 实现广播 + GATT service + 收发 | 新建 |
| `ble/BleCentralManager.kt` 接口 + `AndroidBleCentralManager.kt` | 上期 Central 骨架 | **改为** `BlePeripheralManager` 接口（删除 Central 版） |
| `ble/BleController.kt` | 上期门控逻辑 | 改造：不再扫描，状态由 HTTP 协调结果驱动 |
| `ble/BleConnectionStateMachine.kt` | 上期状态机 | 改名 SCANNING→ADVERTISING，CONNECTING 本期不产生但保留 |
| `ble/BleProtocol.kt` | 帧编解码 | **保留** |
| `ble/BleToggleRule.kt` | UI 门控规则 | **保留** |
| `data/BleApi.kt`（或并入 MediaRepository） | 调 PC 的 `/api/ble/*` endpoint | 新增 |
| `viewmodel/BleSettingsViewModel.kt` | 上期 VM | 扩展：scan/list/connect/sendTest |
| `ui/screen/ConnectionScreen.kt` | 上期开关 UI | 扩展：开关下方加扫描→列表→连接→发送测试按钮 + echo 回显 |
| `di/BleModule.kt` | 上期 Hilt 装配 | 改造：提供 PeripheralManager 而非 CentralManager |

---

## 5. 端到端数据流

### 阶段 1：Android 开启 BLE 广播

```
用户打开"BLE 实验性通道"开关
  → BleSettingsViewModel.setEnabled(true)
  → BleController.evaluateAvailability(enabled=true)
  → AndroidBlePeripheralManager.startAdvertising()
      - 广播 SERVICE_UUID (0000fc01-...)
      - BluetoothGattServer 注册 Command (Write) + State (Notify) 特征
  → 状态机 → ADVERTISING（等被扫到）
```

### 阶段 2：PC 扫描 + Android 选设备

```
Android 用户点"扫描设备"按钮
  → HTTP GET http://<pc>/api/ble/scan
  → PC Central.Scan(过滤 SERVICE_UUID) 持续 3 秒
  → 返回 [{id, name, rssi}, ...]（只含带 UUID 的 = 运行本 app 的 Android）
  → Android UI 显示列表
  → 用户选中一台
  → POST /api/ble/connect {id}
  → PC Central.Connect → 发现 service → 订阅 State 特征 → Command 就绪
  → 返回 {connected: true}
  → Android 状态 → CONNECTED（通过 HTTP 响应得知）
```

### 阶段 3：双向 echo 验证

```
Android 用户点"发送测试"按钮
  → POST /api/ble/send {payload: "ping"}
  → PC Central.Write(Command 特征, EncodeFrame("ping"))
  → Android onCharacteristicWriteRequest → 解码 → 回调收到 "ping"
  → Android Notify State 特征 = EncodeFrame("pong")
  → PC Central 收到 Notify → 解码 → HTTP 响应 {echo: "pong"}
  → Android UI 显示"收到回声：pong"
```

---

## 6. HTTP API 契约（PC 端，复用现有 Bearer Token 鉴权）

### `GET /api/ble/scan`
触发 PC Central 扫描（过滤 SERVICE_UUID），阻塞 ~3 秒后返回。

**响应 200**：
```json
{"devices": [{"id": "AA:BB:CC:DD:EE:FF", "name": "Pixel-7", "rssi": -45}]}
```
- 只含广播 SERVICE_UUID 的设备
- 扫描失败/蓝牙不可用 → 200 + 空 devices 数组（不报错）

### `POST /api/ble/connect`
**请求**：`{"id": "AA:BB:CC:DD:EE:FF"}`
**响应 200**：`{"connected": true}`
**响应 200**：`{"connected": false, "error": "device not in range"}`
- 幂等：已连同一设备 → 返回 true；连着别的 → 先断再连
- 超时 ~10 秒

### `POST /api/ble/send`
**请求**：`{"payload": "ping"}`（server 端 EncodeFrame）
**响应 200**：`{"echo": "pong"}`（等待 Notify 回声，超时 5 秒）
**响应 200**：`{"echo": null, "error": "no echo within 5s"}`

### 安全
- 复用 `middleware/auth.go` Bearer Token（与其他 `/api/*` 一致）
- payload ≤ 244 字节（MAX_PAYLOAD_LEN），超出 → 400
- 不加新鉴权（YAGNI）

---

## 7. 错误处理与状态边界

### server 端
- **蓝牙不可用**（无硬件/蓝牙关/无 `bluetooth` tag）：3 endpoint 返回明确错误（`{"devices":[]}` / `{"connected":false,"error":"ble unavailable"}` / `{"echo":null,"error":"ble unavailable"}`），server 不崩（零退化）
- **超时**：scan 3s / connect 10s / send echo 5s——超时返回错误，不挂起
- **断连检测**：Central 监听断开事件，内部状态标记断开，下次 `/connect` 重连
- **并发**：同一时刻只允许一个 scan/connect/send（mutex 串行化，避免 BLE 栈状态混乱）

### Android 端
- **开关关 → 广播停**：`BluetoothGattServer.stopAdvertising()` + `close()`，状态回 DISABLED
- **PC 断连**：Android 作为 Peripheral 不主动知道；通过下次"发送测试"HTTP 失败推断（PC 返回 connected:false / echo:null）→ 状态回 IDLE，提示重连
- **权限拒绝**：开关无法打开（`BleToggleRule` 已实现），引导去系统授权
- **广播失败**（蓝牙关/占用）：状态 → DISABLED，UI 提示"广播失败，请检查蓝牙"

### 状态机调整（上期 6 状态基础）

| 状态 | 上期语义（Central） | 本期语义（Peripheral） |
|---|---|---|
| DISABLED | 蓝牙不可用 | 同 |
| IDLE | 待机 | 开关开了但还没被 PC 连 |
| SCANNING | Android 在扫 | **改名 ADVERTISING**（Android 在广播等被连） |
| CONNECTING | Android 在连 PC | 本期不产生（连接由 PC 发起），枚举保留 |
| CONNECTED | 已连 | 已连（通过 PC HTTP 响应得知） |
| DISCONNECTED | 断开 | 断开（通过 HTTP 失败推断） |

---

## 8. 测试策略

- **server**：
  - Central 逻辑用 Adapter 接口 mock 测试（扫描过滤、连接、读写、超时、并发串行化）
  - HTTP handler 用现有 Echo test 模式（`handler/*_test.go`）
- **Android**：
  - Peripheral manager 用接口抽象 mock 测试
  - 状态机纯逻辑测试（改名 SCANNING→ADVERTISING 后）
  - HTTP 调用用 MockWebServer（已是测试依赖）测试 scan/connect/send
  - UI 用 Compose 测试开关/列表/按钮状态
- **真机验证**：本期必须真机跑通（A 方案核心目的）——plan 末尾留手动验证清单

---

## 9. 不做什么（YAGNI / 本期边界）

- ❌ 自动连接（用户必须从列表选）
- ❌ 断线自动重连（断了手动重连——本期只验证首次连接）
- ❌ MTU 协商优化（默认 MTU，能 echo 即可）
- ❌ 任何业务信令（播放控制/进度/选书——更下一期）
- ❌ 文本降级传输
- ❌ Wi-Fi 健康探针/自动降级路由
- ❌ Linux 支持（Windows 优先，Linux 后续）
- ❌ 户外无 Wi-Fi 场景（定位已重定义，放弃）
- ❌ 显示非本 app 的 BLE 设备（扫描列表只含带 SERVICE_UUID 的）

---

## 10. 与上期 spec 的关系

本设计**修订**上期 spec 的两点：

1. **角色反转**（§3）：server Peripheral → Central；Android Central → Peripheral。原因：Windows 优先 + winrt Peripheral 不稳。
2. **定位重定义**（§2）：BLE 不再是 Wi-Fi fallback，改为并行低延迟控制通道；放弃户外无 Wi-Fi 场景。

上期 spec 的以下内容**仍然有效，本期复用**：
- 帧编解码协议（`protocol.go` / `BleProtocol.kt`）+ UUID 常量
- 零退化原则（BLE 不可用 → 现有行为不变）
- DataStore `bleEnabled` 开关（默认 false）
- `BleToggleRule` UI 门控
- 设置项触发权限请求的策略

上期 spec §10 的"留作下一期"清单中，**本期完成"真实 GATT 硬件接线"**这一项；其余（业务信令、文本降级、Wi-Fi 探针）仍留后续。

---

## 11. 实施完成状态（2026-07-26）

**已完成（本期）：**

- **server**（Go，PC 当 Central）：
  - `ble.Central`（Scan/Connect/Send 状态机，mutex 串行化，ctx 超时）+ `CentralScanner` 接口。
  - `tinygo.org/x/bluetooth` v0.15.0 Central adapter（`bluetooth` build tag）+ stub（默认构建）。3 处 v0.15.0 API 适配（`ServiceUUIDs` 是方法、`Address` 嵌套 `MACAddress`、`DisableNotifications` 不存在）。
  - `/api/v1/ble/scan|connect|send` HTTP handler（挂在 `api.Group("/ble", authMw)`，复用 Bearer Token；超时 4s/11s/6s；payload ≤244 字节）。
  - 删除上期 Peripheral 代码（`peripheral.go`/`tinygo_adapter*.go`）。
- **Android**（Kotlin，当 Peripheral）：
  - `BlePeripheralManager` 接口 + `AndroidBlePeripheralManager`（`BluetoothGattServer` + `BluetoothLeAdvertiser`：广播 SERVICE_UUID，Command Write + State Notify + CCCD；echo 用 `notifyCharacteristicChanged`）。
  - `BleController` 重构（Peripheral 语义，`markConnected`/`markDisconnected` public，echo via notifyPayload，无 send 方法）。
  - `BleApi`（HTTP 调 `/api/v1/ble/*`，Bearer header，Gason 解析）。
  - `BleSettingsViewModel` 扩展（`devices`/`echoResult`/`scanning` StateFlow + `scan`/`connect`/`sendTest`）。
  - `ConnectionScreen` 加 `BleDeviceScanCard`（扫描按钮 + 设备列表 + 选中连接 + 发送测试 + echo 回显）。
  - 删除上期 Central 骨架；状态机 `SCANNING`→`ADVERTISING`，`onDisconnected` 返回 ADVERTISING（Peripheral 可被重新发现）。
- **测试**：server 10 + Android 24 = **34 个 BLE 测试，全绿**；server default + `-tags bluetooth` 构建通过；Android assembleDebug 通过。

**留作下一期（spec §9 边界）：**

- 业务信令语义（播放控制/进度/选书——需先决策 server 角色）
- 文本降级传输（分章 + 优先级队列 + 断点续传）
- Wi-Fi 健康探针 + 自动降级路由
- 断线自动重连
- MTU 协商优化
- Linux 适配（BlueZ 双角色都成熟，到时无需角色反转）
- BluetoothAdapter 运行时状态监听（当前快照式，YAGNI）

**已知 API 限制（非缺陷，v0.15.0 约束）：**

- server Central 的 `Connect`/`WriteCommand` 不传播 ctx（tinygo v0.15.0 API 无 ctx 参数；靠 handler 层超时兜底）。
- `WaitNotify` 每次 Send 重新订阅（v0.15.0 无 `DisableNotifications`），长连接多次 Send 会累积订阅 handler——MVP 单次 echo 不触发，下期优化。

**手动真机验证清单（需 Windows PC + Android 13 真机）：**

- [ ] PC server 以 `go build -tags bluetooth` 构建，启动后日志显示 "BLE Central ready"。
- [ ] Android 开"BLE 实验性通道"开关 → 授权蓝牙权限 → 状态"广播中"。
- [ ] Android 点"扫描设备" → 列表显示自己的设备（带 SERVICE_UUID）。
- [ ] 选中设备 → POST /connect → 状态变"已连接"。
- [ ] 点"发送测试" → UI 显示"收到回声：pong"（验证双向 GATT 通）。
- [ ] 关开关 → 广播停，PC 端连接断开，现有功能不受影响（零退化）。
- [ ] PC 无蓝牙模块或未启 `bluetooth` tag → 3 个 endpoint 返回明确错误，server 不崩。

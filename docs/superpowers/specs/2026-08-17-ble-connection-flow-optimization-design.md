# BLE 蓝牙连接流程与体验优化设计

**日期**: 2026-08-17  
**范围**: `android/` (Kotlin / Compose)  
**目标**: 解决 BLE 控制/降级通道依赖手动在设置页触发、断线后无法自动恢复、多设备下可能误连、以及阅读中无法感知与就地修复蓝牙状态的痛点，提供**静默预连冷备、精准设备匹配、断线智能重连、阅读器就地自愈**的全流程体验。

---

## 1. 背景与核心痛点

当前系统的 BLE 通道作为 Wi-Fi 故障时的降级数据传输备用链路（支持目录浏览与小说章节文本获取）。但在使用流程中存在以下体验与机制瓶颈：

1. **“先有鸡还是先有蛋”**：BLE 的建立依赖手机通过 Wi-Fi 调用 PC 的 `/api/v1/ble/scan` 和 `/api/v1/ble/connect` 接口。若用户在 Wi-Fi 完好时未进入设置页手动点击连接，一旦离开 Wi-Fi 覆盖或网络断开，手机便无法再让 PC 发起蓝牙连接，导致降级通道无法发挥作用。
2. **多设备环境下的误连隐患**：当前 `doAutoConnectOnce` 连接扫描结果列表中的第一个设备（`discovered.first()`），当周边有多台蓝牙设备或同类设备同时广播时可能发生错连。
3. **断线后缺乏自动恢复机制**：当手机短暂远离 PC 或因休眠导致 GATT 断开后，状态停留在 `DISCONNECTED`/`ADVERTISING`，用户必须退出阅读回到设置页手动重新连接。
4. **阅读器中状态不可见且无法就地重试**：在小说阅读界面无法获知蓝牙冷备是否处于就绪状态；当 Wi-Fi 加载章节失败时，若蓝牙未连接，缺乏一键重连并重试的就地引导。

---

## 2. 详细设计方案

### 2.1 智能设备匹配器（Device Matcher & Memory）

为避免多设备环境下误连，并加速重连选路，引入三级设备匹配策略：

```
[PC 返回扫描设备列表 discovered: List<BleDevice>]
                   │
                   ▼
  [1. 优先匹配 DataStore 记录的上次配对 MAC: lastConnectedBleAddress]
         │ (命中 -> 连接 target)
         ▼ (未命中)
  [2. 匹配本机蓝牙设备名称: BluetoothAdapter.name / 设备型号]
         │ (命中 -> 连接 target)
         ▼ (未命中)
  [3. 兜底回退: 选择 RSSI 信号最强设备 discovered.maxByOrNull { it.rssi }]
                   │
                   ▼
  [连接成功 -> 将目标 MAC 持久化到 ServerConfigStore.lastConnectedBleAddress]
```

**数据持久化**：
- `ServerConfigStore.kt` 新增 `lastConnectedBleAddress: Flow<String?>` 键值（`stringPreferencesKey("last_connected_ble_address")`）；
- 新增 `saveLastConnectedBleAddress(address: String)` 与 `clearLastConnectedBleAddress()`。

---

### 2.2 后台静默预建联（Silent Pre-connect）

在用户已开启 BLE 开关的前提下，当手机处于 Wi-Fi 在线且广播就绪时，App 在后台低优先级静默发起一次握手，作为断网备用通道：

* **触发时机**：
  1. App 启动时 / 从后台恢复到前台时（通过 `DefaultLifecycleObserver` 监听 `ON_START` / `ON_RESUME`）；
  2. 条件守卫：`bleEnabled == true` 且 `hardwareAvailable() == true` 且 `connectionState != CONNECTED && connectionState != CONNECTING` 且 `serverConfig` 已配置。
* **执行约束与防抖**：
  - 在独立后台协程运行，**绝不阻塞** UI 与主 Wi-Fi HTTP 请求；
  - 静默模式下执行失败不弹出任何错误 Toast 或在界面显示红色错误，仅记录 `slog/Log.d`；
  - 限制最大重试次数为 3 次，采用指数退避（0s ➔ 5s ➔ 15s），3 次全部失败后进入 60 秒冷却期（避免频繁触发后台 HTTP 扫描消耗 PC 资源）。

---

### 2.3 异常断线智能退避重连（Smart Auto-Reconnect）

* **触发时机**：
  - 当底层 GATT 连接异常断开（`onConnectionStateChange` 触发 `STATE_DISCONNECTED`，状态机回到 `ADVERTISING` / `DISCONNECTED`）；
  - 条件守卫：`bleEnabled == true`、App 处于前台（Foreground）、Wi-Fi 服务端地址有效。
* **退避策略**：
  - 触发自动重连协程：等待 3s ➔ 10s ➔ 30s（最多尝试 3 次）；
  - 若在重试过程中 Wi-Fi 断开或连接成功，则立即终止重试流程；
  - 用户在 UI 上手动点击“连接”时，立即取消正在进行的退避等待，直接发起即时连接并重置重试计数。

---

### 2.4 阅读器与界面交互增强（沉浸无感 + 就地自愈）

#### A. 阅读设置弹窗集成（`ReaderSettingsSheet.kt`）
在 `ReaderSettingsSheet` 底部控制区增设紧凑的蓝牙备用状态组件：
- **🟢 已就绪**：展示绿色指示图标与“蓝牙备用链路已就绪”文本；
- **⚪ 待机中 / 未连接**：展示“蓝牙备用链路未连接”，右侧附带小尺寸“立即连接”按钮，点击后就地触发握手；
- **灰色未启用**：当 `bleEnabled == false` 时提示“蓝牙备用通道未启用”。

#### B. 网络异常就地自愈（`TextReaderScreen.kt`）
- 当阅读器加载章节失败（Wi-Fi 不通）时：
  - 若当前蓝牙处于 `CONNECTED`，系统已自动走降级通道并显示“BLE 降级传输中”徽标；
  - 若当前蓝牙处于未连接状态（`bleEnabled == true` 且未连接），错误面板在常规“点击重试”旁提供“一键连接蓝牙并重试”主按钮；
  - 用户轻触后，先发起快速蓝牙握手，握手成功后自动重拉该章节数据，无需跳转返回设置页。

#### C. 连接设置页联动（`BleChannelSection.kt`）
- 在设备列表与状态卡片中展示当前记忆的已配对设备（如：“已绑定设备: Pixel 8 (AA:BB:CC:DD:EE:FF)”）；
- 保持手动扫描与连接调试功能完整。

---

## 3. 架构与组件职责清单

| 模块 / 文件 | 变更职责 |
|---|---|
| `data/ServerConfigStore.kt` | 添加 `lastConnectedBleAddress` DataStore Key 与读写方法。 |
| `ble/BleController.kt` | 暴露断线事件信号流 / 增强状态转换通知，支持就地重连驱动。 |
| `viewmodel/BleSettingsViewModel.kt` | 1. 实现 `selectBestDevice` 智能选路算法。<br>2. 接入 App 前后台生命周期与断线退避重连逻辑。<br>3. 区分静默预连（`silent = true`）与显式用户连接，失败时不干扰全局错误状态。 |
| `ui/component/reader/ReaderSettingsSheet.kt` | 底部嵌入蓝牙备用通道状态展示胶囊与“立即连接”操作。 |
| `ui/screen/TextReaderScreen.kt` | 章节加载失败错误界面适配“连接蓝牙并重试”就地自愈按钮。 |
| `ui/component/BleChannelSection.kt` | 展示已记忆设备及状态联动。 |
| `server/` | 零改动，完全复用现有 `/api/v1/ble/scan` 和 `/api/v1/ble/connect`。 |

---

## 4. 验证与测试计划

### 4.1 单元测试（`BleSettingsViewModelTest.kt`）
1. **设备匹配算法测试**：
   - 验证 `selectBestDevice` 优先匹配 `lastConnectedBleAddress`；
   - 验证无 MAC 匹配时按设备名称匹配；
   - 验证无名称匹配时按最高 RSSI 匹配。
2. **静默预连与退避测试**：
   - 验证 App 启动时静默预连被触发且失败时不污染 `_errorText`；
   - 验证连续 3 次失败后进入冷却，不再无休止发送 HTTP 请求；
   - 验证手动调用 `autoConnect()` 立即重置计数并更新 UI 状态。
3. **断线重连测试**：
   - 模拟连接断开，验证在退避延迟后触发重连尝试。

### 4.2 端到端与构建验证
1. 运行 `./gradlew testDebugUnitTest` 确保 Android 单元测试 100% 通过。
2. 运行 `./gradlew assembleDebug` 确保编译无警告与错误。

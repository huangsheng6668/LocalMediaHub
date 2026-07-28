# BLE 自动连接方案设计

**日期**: 2026-07-28
**范围**: `android/` (Kotlin)
**目标**: BLE 稳定通道开关打开时，App 连上 server 后自动建立 BLE 通道（扫描+连接），无需用户手动点「自动连接」。这样 Wi-Fi 断开时降级方案才总是自动就绪。

**前置**: BLE 降级链路（章节/列表/图片占位/视频禁用）已全部完成（spec `2026-07-28-ble-list-image-video-extension-design.md`）。`BleSettingsViewModel.autoConnect()` 一键扫描+连接逻辑已存在；`BleConnectionStateMachine` 状态机已就位。本设计只是给自动触发加一条「server Connected + BLE 开 + 广播就绪 → autoConnect（带重试）」的链路。

---

## 1. 需求边界

### 1.1 触发条件（全部满足才自动连接）
1. server 进入 `ConnectionState.Connected`（无论是 App 启动自动重连，还是用户手动连接——方案 C：覆盖所有进入 Connected 的路径）。
2. `bleEnabled == true`（BLE 稳定通道开关打开）。
3. **广播已就绪**（手机端 GATT `onStartSuccess` 回调收到——方案 B：等广播确认起来再连，避免在广播没就绪时空跑扫描）。

### 1.2 自动连接失败处理（方案 C）
- 自动触发时带**重试**：最多 3 次，每次间隔 3 秒（总窗口 ~9 秒，能吸收 BLE 初始化慢/首次扫描扑空等 transient 问题）。
- 3 次仍失败 → 弹一次轻提示（写入 `_errorText`，UI 已有展示），不阻塞使用：`BLE 自动连接失败，降级通道暂不可用（可手动重试）`。
- 手动点「自动连接」按钮仍走单次逻辑（用户意图，不重试）。

### 1.3 去重
- 同一会话内，三个条件都满足时只自动触发**一次**，避免条件反复抖动导致重复连接。
- 复位时机：BLE 断开（`connectionState` 回到非 CONNECTED）或 BLE 开关关闭后再次满足条件时，允许重新触发。

### 1.4 不做的事（YAGNI）
- 不监听 `BluetoothAdapter` 硬件开关运行时变化（沿用现有 MVP 约束，下次设置 emission / App 重启时重评估）。
- 不做跨 App 重启的持久化自动连接记录（每次 App 启动重新走触发逻辑即可）。
- 不改 server 端（BLE scan/connect 接口已存在）。
- 不改 wire 协议。

---

## 2. 关键组件与职责

### 2.1 广播就绪信号链路（新增）

**`android/.../ble/BlePeripheralManager.kt`**（接口）:
- 新增 `fun setOnAdvertisingStarted(cb: (success: Boolean) -> Unit)`。

**`android/.../ble/AndroidBlePeripheralManager.kt`**:
- `startAdvertising` 内的 `AdvertiseCallback.onStartSuccess` 不再只打日志，额外调用 `onAdvertisingStarted?.invoke(true)`。
- `onStartFailure` 调用 `onAdvertisingStarted?.invoke(false)`。
- 新增 `private var onAdvertisingStarted: ((Boolean) -> Unit)? = null` + 实现 setter。

**`android/.../ble/BleController.kt`**:
- 新增 `val advertisingStarted: SharedFlow<Boolean>`（`MutableSharedFlow`，replay=0）。
- `init` 里注册 `peripheralManager.setOnAdvertisingStarted { success -> advertisingStarted.tryEmit(success) }`。

### 2.2 自动连接触发器（BleSettingsViewModel）

**触发联动**：监听三个信号同时满足：
- server 连接状态（需要从 `ConnectionViewModel`/`ServerConfig` 或一个 app 级连接状态源获取 `ConnectionState.Connected`——确认 App 里是否已有 app 级连接 StateFlow；若没有，从 `serverConfig.getBaseUrl()` 非空 + 一次健康检查代理，或直接复用 `ConnectionViewModel` 暴露的 state）。
- `bleEnabled`（已有）。
- `controller.advertisingStarted` 收到 `true`。

实现：`combine(connectionState, bleEnabledFlow)` + collect `advertisingStarted`，三者满足且未在本会话触发过 → 调 `autoConnectWithRetry()`。

**去重标志**：`private var autoConnectArmed: Boolean`，触发后置 true；BLE 断开（`connectionState != CONNECTED`）时复位为 false。

### 2.3 重试逻辑（方案 C）

抽出 `autoConnect()` 的核心为 `private suspend fun doAutoConnectOnce(): Boolean`（返回是否成功 CONNECTED）：

```kotlin
private suspend fun autoConnectWithRetry() {
    autoConnectArmed = true
    repeat(3) { attempt ->
        if (controller.connectionState.value == BleConnState.CONNECTED) return // 已连上
        val ok = doAutoConnectOnce()
        if (ok) return
        delay(3000)
    }
    _errorText.value = "BLE 自动连接失败，降级通道暂不可用（可手动重试）"
}
```

现有 `fun autoConnect()`（手动按钮）改为调 `doAutoConnectOnce()` 一次（保持单次语义）。

---

## 3. server 连接状态来源（需实施时确认）

触发条件 #1 需要「server 是否 Connected」。候选来源（实施时选最小侵入的）：
- 若 `ConnectionViewModel.connectionState` 是 app 级单例 StateFlow → 直接 collect。
- 若不是，把连接成功状态提升到一个 Hilt 单例（如 `ConnectionStateHolder`）或复用 `ServerConfig` + 标志。

**约束**：不引入重复的连接状态源；优先复用现有 `ConnectionViewModel` 的状态或其底层 store。

---

## 4. 测试策略

1. **触发条件单测**（`BleSettingsViewModelTest`）：
   - 三条件全满足 → `doAutoConnectOnce` 被调用 / 状态变 CONNECTED。
   - 缺 server Connected / 缺 bleEnabled / 缺 advertisingStarted → 不触发。
2. **重试单测**（`runTest` 虚拟时间）：
   - 前 2 次 scan 失败、第 3 次成功 → 最终 CONNECTED，`autoConnectArmed=true`。
   - 3 次全失败 → `_errorText` 含「BLE 自动连接失败」+ 状态非 CONNECTED。
3. **去重单测**：条件反复满足只触发一次；BLE 断开后复位可再次触发。
4. **手动按钮单次语义**：`autoConnect()` 手动调用不触发重试（即使失败也只试一次）。

测试用现有的 fake `BlePeripheralManager` + fake `BleApi`（`MediaRepositoryFailoverTest` 里有 `SimulatingPeripheralManager` 模式可参考），注入 `onAdvertisingStarted` 信号。

# Android 端 BLE 手动请求连接设计文档

**日期**: 2026-07-30  
**目标**: 将 Android 端 BLE 控制通道的连接机制从“自动并发重试”重构为“完全由 Android 端用户手动点击请求连接”。

---

## 1. 背景与动机

此前 BLE GATT 实现中，Android 端在开启“蓝牙稳定通道”开关或 App 启动且服务端已配置时，`BleSettingsViewModel` 会通过 `init` 协程自动监听并启动 `autoConnectWithRetry()` 重试逻辑。这导致：
- 只要开启 BLE 开关，Android 就会在后台持续自动向 PC 服务端发送 `/api/v1/ble/scan` 和 `/api/v1/ble/connect` 请求。
- 自动连接缺少显式的用户意图把控，给用户“后台一直在广播与频繁建立连接”的不确定感。

本设计调整连接控制流：**保持 BLE Peripheral 待机广播，取消一切后台自动连接触发器，连接建立 100% 由用户在 UI 上显式点击触发。**

---

## 2. 变更范围与组件职责

### 2.1 Android 端 (`android/app/src/main/java/com/juziss/localmediahub/`)

| 文件 | 变更内容 |
|---|---|
| `viewmodel/BleSettingsViewModel.kt` | 1. 移除 `init` 代码块中监听 `advertisingReady` / `serverConfigured` 并自动调起 `autoConnectWithRetry()` 的协程。<br>2. 移除 `autoConnectWithRetry()` 重试函数及 `autoConnectArmed` / `autoConnectLoopActive` 重试防抖标记。<br>3. 保留 `autoConnect()` 方法作为用户手动点击“一键建立 BLE 控制通道”时的单次连接发起入口。 |
| `ble/BleController.kt` | 清理与更新与 Task 2 自动连接相关的代码注释，保持硬件广播状态控制不变。 |
| `ui/component/BleChannelSection.kt` | UI 视图组件保持不变，其“一键建立 BLE 控制通道”按钮继续调用 `bleViewModel.autoConnect()`。 |
| `viewmodel/BleSettingsViewModelTest.kt` | 更新单测：验证开启 `bleEnabled` 后不再有任何自动 HTTP 连接调用，验证手动调用 `autoConnect()` 能正常发起单次连接。 |

### 2.2 服务端 (`server/`)

- 无需修改。PC Central 现有的 `/api/v1/ble/scan` 与 `/api/v1/ble/connect` 端点完全满足 Android 端手动请求的需求。

---

## 3. 端到端流程

```
[用户在 ConnectionScreen 开启 BLE 实验开关]
  │
  ▼
Android 开启蓝牙广播 (BleConnState -> ADVERTISING)
（注：此时不再自动向 PC 发起 scan/connect HTTP 请求）
  │
  ▼
[用户在 UI 点击“一键建立 BLE 控制通道”]
  │
  ▼
BleSettingsViewModel.autoConnect() 被手动触发
  │
  ▼
Android 发送 HTTP GET /api/v1/ble/scan 到 PC 服务端
  │
  ▼
PC Central 扫描蓝牙设备 (3秒) 并返回设备列表
  │
  ▼
Android 选定匹配设备并发送 HTTP POST /api/v1/ble/connect
  │
  ▼
PC Central 与 Android 建立 BLE GATT 通道
  │
  ▼
Android 标记 markConnected()，UI 状态更新为“已连接”
```

---

## 4. 验证计划

1. **单元测试**：
   - 运行 `./gradlew testDebugUnitTest` 确保 `BleSettingsViewModelTest` 及所有 Android 单元测试全绿。
2. **构建校验**：
   - 运行 `./gradlew assembleDebug` 验证代码无编译错误。

# Task 4 Implementation Report: 阅读器就地状态感知与错误自愈（ReaderSettingsSheet, TextReaderScreen, BleChannelSection）

## 1. 概述
在 Task 1-3 实现了 DataStore 持久化、智能选路、静默预建联与退避重连的基础上，Task 4 完成了阅读器端的就地感知与自愈闭环：
- 在 `ReaderSettingsSheet` 底部增加低功耗蓝牙通道状态指示胶囊与就地“立即连接”操作。
- 在 `TextReaderScreen` 章节加载失败的错误界面中，并列提供“重试”与“连接蓝牙并重试”自愈按钮。
- 在 `BleChannelSection` 中展示已记忆的上次连接设备 MAC 地址。

---

## 2. 修改文件清单

1. **`android/app/src/main/res/values/strings.xml`**
   - 新增 `ble_status_capsule_connected`（“蓝牙备用通道已就绪”）
   - 新增 `ble_status_capsule_idle`（“蓝牙备用通道未连接”）
   - 新增 `ble_status_capsule_connecting`（“蓝牙通道连接中…”）
   - 新增 `ble_status_capsule_disabled`（“蓝牙备用通道未启用”）
   - 新增 `ble_connect_now`（“立即连接”）
   - 新增 `ble_connect_and_retry`（“连接蓝牙并重试”）
   - 新增 `ble_remembered_device_fmt`（“已记忆设备：%1$s”）
   - 新增 `retry`（“重试”）

2. **`android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`**
   - 暴露 `lastConnectedMac: StateFlow<String?>`，通过 `store.lastConnectedBleAddress.stateIn(viewModelScope, SharingStarted.Eagerly, null)` 提供响应式上次连接设备 MAC 状态。

3. **`android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`**
   - `ReaderSettingsSheet` 与 `ReaderSettingsSheetContent` 签名扩展 `bleEnabled: Boolean = false`, `bleConnState: BleConnState = BleConnState.DISABLED`, `onBleConnect: () -> Unit = {}`。
   - 新增 `BleStatusCapsuleRow` 组件，根据蓝牙启用状态与连接状态渲染指示圆点（绿/黄/灰）、状态文本以及就地“立即连接” OutlinedButton（在 `bleEnabled && bleConnState != CONNECTED` 时可见，CONNECTING 时禁用）。

4. **`android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`**
   - 接入 `BleSettingsViewModel`（支持可选注入或自动从 Hilt 获取）。
   - 错误面板增强：当 `error != null` 时，渲染错误描述、“重试”按钮；当 `bleEnabled == true` 且非 `CONNECTED` 时展示“连接蓝牙并重试”自愈按钮，点击后触发 `bleVm.autoConnect()` 与 `viewModel.loadChapter(idx, resetScroll = true)`。
   - 打开 `ReaderSettingsSheet` 时透传 `bleEnabled`、`bleConnState` 与 `onBleConnect = { bleVm?.autoConnect() }`。

5. **`android/app/src/main/java/com/juziss/localmediahub/ui/component/BleChannelSection.kt`**
   - 收集 `lastConnectedMac` 并传递给 `BleDeviceScanCard`，在卡片顶部展示已记忆的 MAC 地址（`已记忆设备：%s`）。

6. **测试用例扩展**
   - `ReaderSettingsSheetTest.kt`: 覆盖蓝牙未启用（DISABLED）、待机（IDLE）、连接中（CONNECTING）、已连接（CONNECTED）四种状态下的胶囊渲染与“立即连接”点击交互。
   - `BleSettingsViewModelTest.kt`: 增加 `lastConnectedMac_reflectsSavedAddressFromStore` 校验 StateFlow 能够实时反映 DataStore 中持久化的 MAC 地址。
   - `TextReaderScreenThemeTest.kt`: 增加 `error_recovery_panel_renders_retry_and_ble_connect_buttons` 验证错误自愈按钮布局与事件回调。

---

## 3. 测试与验证结果

- **单元测试**：`./gradlew testDebugUnitTest` 100% 通过（BUILD SUCCESSFUL）。
- **APK 构建**：`./gradlew assembleDebug` 100% 通过（BUILD SUCCESSFUL）。

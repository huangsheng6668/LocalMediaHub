# Task 4 Brief: 阅读器就地状态感知与错误自愈（ReaderSettingsSheet, TextReaderScreen, BleChannelSection）

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BleChannelSection.kt`
- Test: `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`

**Interfaces:**
- Consumes: `BleSettingsViewModel.connectionState`, `BleSettingsViewModel.bleEnabled`, `BleSettingsViewModel.autoConnect()`, `ServerConfigStore.lastConnectedBleAddress`
- Produces:
  - `ReaderSettingsSheet` 底部嵌入蓝牙状态指示胶囊与就地“立即连接”操作。
  - `TextReaderScreen` 章节加载失败错误界面适配“连接蓝牙并重试”就地自愈按钮。
  - `BleChannelSection` 已记忆设备信息展示。

**Steps to execute:**

1. **Step 1: 在 `strings.xml` 中添加文案资源**
```xml
<string name="ble_status_capsule_connected">蓝牙备用通道已就绪</string>
<string name="ble_status_capsule_idle">蓝牙备用通道未连接</string>
<string name="ble_status_capsule_connecting">蓝牙通道连接中…</string>
<string name="ble_status_capsule_disabled">蓝牙备用通道未启用</string>
<string name="ble_connect_now">立即连接</string>
<string name="ble_connect_and_retry">连接蓝牙并重试</string>
<string name="ble_remembered_device_fmt">已记忆设备：%s</string>
<string name="retry">重试</string>
```

2. **Step 2: 在 `BleSettingsViewModel.kt` 中暴露 `lastConnectedMac` StateFlow**
```kotlin
val lastConnectedMac: StateFlow<String?> = store.lastConnectedBleAddress
    .stateIn(viewModelScope, SharingStarted.Eagerly, null)
```

3. **Step 3: 更新 `ReaderSettingsSheet.kt` 嵌入蓝牙状态组件**
在 `ReaderSettingsSheet` 与 `ReaderSettingsSheetContent` 中支持 `bleEnabled: Boolean = false`, `bleConnState: BleConnState = BleConnState.DISABLED`, `onBleConnect: () -> Unit = {}`。
在设置底部添加蓝牙通道状态行，包含状态指示圆点（绿/黄/灰）、状态文本以及“立即连接”按钮。

4. **Step 4: 更新 `TextReaderScreen.kt` 错误面板与 Settings 弹窗传参**
- 注入/获取 `BleSettingsViewModel` 的 `bleEnabled` 与 `bleConnState`。
- 当 `error != null` 时，渲染包含错误文本、常规“重试”按钮以及“连接蓝牙并重试”按钮的自愈面板。
- 打开 `ReaderSettingsSheet` 时传入 `bleEnabled`、`bleConnState` 与 `onBleConnect = { bleViewModel.autoConnect() }`。

5. **Step 5: 更新 `BleChannelSection.kt`**
在卡片中展示记忆的上次连接 MAC/设备信息（若存在）。

6. **Step 6: 运行全量单元测试与构建验证**
Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL.

7. **Step 7: 提交代码**
Commit message: `feat(reader): in-place BLE status capsule and error auto-recovery`

# BLE 蓝牙连接流程与体验优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 优化 Android 端 BLE 控制与降级传输通道的连接流程，实现 Wi-Fi 在线时的后台静默预建联冷备、基于 MAC 与设备名的三级精准匹配、断线智能退避重连自愈，以及在阅读器内的就地状态感知与错误重试。

**Architecture:** 
- 在 `ServerConfigStore` 中使用 DataStore 持久化上次成功连接的蓝牙 MAC 地址。
- 在 `BleSettingsViewModel` 中实现三级设备匹配算法（历史 MAC ➔ 本机设备名 ➔ 最高 RSSI），以及区分静默预连（silent）与显式用户连接的生命周期与退避重试状态机。
- 在 `ReaderSettingsSheet` 中嵌入紧凑的蓝牙备用状态胶囊与就地连接按钮，在 `TextReaderScreen` 章节加载失败面板中提供一键连接蓝牙并重试的就地自愈引导。

**Tech Stack:** Kotlin, Jetpack Compose, Coroutines, Flow, AndroidX DataStore, Hilt, JUnit4, MockK.

## Global Constraints

- Android 单元测试全绿（`./gradlew testDebugUnitTest`），编译无错误（`./gradlew assembleDebug`）。
- 服务端 Go 代码零修改，接口保持完全兼容（`/api/v1/ble/scan` 和 `/api/v1/ble/connect`）。
- 绝不阻塞主 UI 渲染和 Wi-Fi HTTP 网络请求；静默预连失败不得污染全局用户可见错误状态（`_errorText`）。
- 严格遵循 Conventional Commits 提交规范。

---

### Task 1: DataStore 持久化支持（`ServerConfigStore`）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreTest.kt`

**Interfaces:**
- Consumes: AndroidX DataStore `Preferences`
- Produces:
  - `ServerConfigStore.lastConnectedBleAddress: Flow<String?>`
  - `suspend fun ServerConfigStore.saveLastConnectedBleAddress(address: String)`
  - `suspend fun ServerConfigStore.clearLastConnectedBleAddress()`

- [ ] **Step 1: 编写 DataStore 新增字段的单元测试**

在 `ServerConfigStoreTest.kt` 中添加测试：
```kotlin
@Test
fun lastConnectedBleAddress_saveAndClear_flowEmitsCorrectValues() = runTest {
    val store = ServerConfigStore(context)
    store.saveLastConnectedBleAddress("AA:BB:CC:DD:EE:FF")
    assertEquals("AA:BB:CC:DD:EE:FF", store.lastConnectedBleAddress.first())
    store.clearLastConnectedBleAddress()
    assertNull(store.lastConnectedBleAddress.first())
}
```

- [ ] **Step 2: 运行测试以确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ServerConfigStoreTest`  
Expected: 编译错误，`lastConnectedBleAddress` / `saveLastConnectedBleAddress` 未定义。

- [ ] **Step 3: 在 `ServerConfigStore.kt` 中实现持久化字段**

```kotlin
companion object {
    // ...
    private val KEY_LAST_CONNECTED_BLE_ADDRESS = stringPreferencesKey("last_connected_ble_address")
}

open val lastConnectedBleAddress: Flow<String?> = context.dataStore.data.map { prefs ->
    prefs[KEY_LAST_CONNECTED_BLE_ADDRESS]
}

suspend fun saveLastConnectedBleAddress(address: String) {
    context.dataStore.edit { prefs ->
        prefs[KEY_LAST_CONNECTED_BLE_ADDRESS] = address
    }
}

suspend fun clearLastConnectedBleAddress() {
    context.dataStore.edit { prefs ->
        prefs.remove(KEY_LAST_CONNECTED_BLE_ADDRESS)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ServerConfigStoreTest`  
Expected: PASS

- [ ] **Step 5: 提交代码**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreTest.kt
git commit -m "feat(ble): add lastConnectedBleAddress to ServerConfigStore"
```

---

### Task 2: 智能设备匹配算法（`BleSettingsViewModel`）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `BleDevice`, `ServerConfigStore.lastConnectedBleAddress`, `BluetoothAdapter.name`
- Produces:
  - `fun selectBestDevice(discovered: List<BleDevice>, lastConnectedMac: String?, localDeviceName: String?): BleDevice?`

- [ ] **Step 1: 编写三级设备匹配算法单元测试**

在 `BleSettingsViewModelTest.kt` 中添加测试用例：
```kotlin
@Test
fun selectBestDevice_prioritizesLastConnectedMac() {
    val devices = listOf(
        BleDevice("11:22:33:44:55:66", "Pixel 8", -40),
        BleDevice("AA:BB:CC:DD:EE:FF", "Pixel 7", -60),
    )
    val best = BleSettingsViewModel.selectBestDevice(
        discovered = devices,
        lastConnectedMac = "AA:BB:CC:DD:EE:FF",
        localDeviceName = "Pixel 8"
    )
    assertEquals("AA:BB:CC:DD:EE:FF", best?.id)
}

@Test
fun selectBestDevice_fallsBackToDeviceNameMatch_whenNoMacMatch() {
    val devices = listOf(
        BleDevice("11:22:33:44:55:66", "Unknown", -40),
        BleDevice("77:88:99:AA:BB:CC", "My Phone", -70),
    )
    val best = BleSettingsViewModel.selectBestDevice(
        discovered = devices,
        lastConnectedMac = "FF:EE:DD:CC:BB:AA",
        localDeviceName = "My Phone"
    )
    assertEquals("77:88:99:AA:BB:CC", best?.id)
}

@Test
fun selectBestDevice_fallsBackToMaxRssi_whenNoMacOrNameMatch() {
    val devices = listOf(
        BleDevice("11:22:33:44:55:66", "Device A", -80),
        BleDevice("77:88:99:AA:BB:CC", "Device B", -45),
    )
    val best = BleSettingsViewModel.selectBestDevice(
        discovered = devices,
        lastConnectedMac = null,
        localDeviceName = "My Phone"
    )
    assertEquals("77:88:99:AA:BB:CC", best?.id)
}
```

- [ ] **Step 2: 运行测试以确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`  
Expected: FAIL, `selectBestDevice` 未定义。

- [ ] **Step 3: 在 `BleSettingsViewModel.kt` 中实现 `selectBestDevice` 并接入连接逻辑**

```kotlin
companion object {
    /**
     * 智能选路算法：
     * 1. 优先匹配上次成功连接的 MAC 地址；
     * 2. 匹配当前设备名称（忽略大小写）；
     * 3. 兜底选择 RSSI 信号最强的设备；
     * 4. 列表为空返回 null。
     */
    fun selectBestDevice(
        discovered: List<BleDevice>,
        lastConnectedMac: String?,
        localDeviceName: String?
    ): BleDevice? {
        if (discovered.isEmpty()) return null
        if (!lastConnectedMac.isNullOrBlank()) {
            val byMac = discovered.find { it.id.equals(lastConnectedMac, ignoreCase = true) }
            if (byMac != null) return byMac
        }
        if (!localDeviceName.isNullOrBlank()) {
            val byName = discovered.find { it.name.equals(localDeviceName, ignoreCase = true) }
            if (byName != null) return byName
        }
        return discovered.maxByOrNull { it.rssi } ?: discovered.firstOrNull()
    }
}
```
并在 `doAutoConnectOnce` 中使用 `selectBestDevice` 选出目标设备，连接成功后调用 `store.saveLastConnectedBleAddress(target.id)`。

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`  
Expected: PASS

- [ ] **Step 5: 提交代码**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt
git commit -m "feat(ble): implement selectBestDevice matching and MAC memory"
```

---

### Task 3: 后台静默预建联与断线智能退避重连（`BleSettingsViewModel`）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `controller.connectionState`, `store.bleEnabled`, `serverConfig.getBaseUrl()`, `store.lastConnectedBleAddress`
- Produces:
  - `suspend fun doAutoConnectOnce(silent: Boolean = false): Boolean`
  - `fun triggerSilentAutoConnect()`
  - `lastMatchedDevice: StateFlow<BleDevice?>` / `lastConnectedMac: StateFlow<String?>`

- [ ] **Step 1: 编写静默预连与断线退避的单元测试**

在 `BleSettingsViewModelTest.kt` 中添加测试：
```kotlin
@Test
fun silentAutoConnect_onFailure_doesNotPolluteErrorText() = runTest {
    val api = fakeApi(scanFailCount = 1)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    runCurrent()

    val success = vm.doAutoConnectOnce(silent = true)
    runCurrent()

    assertFalse(success)
    assertNull(vm.errorText.value) // 静默模式下错误文本保持 null
}

@Test
fun manualAutoConnect_resetsBackoffAndSetsErrorTextOnFailure() = runTest {
    val api = fakeApi(scanFailCount = 1)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    runCurrent()

    vm.autoConnect()
    runCurrent()

    assertTrue(vm.errorText.value != null) // 手动模式下更新 errorText
}
```

- [ ] **Step 2: 运行测试以确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`  
Expected: FAIL, `silent` 参数或行为不匹配。

- [ ] **Step 3: 完善 `BleSettingsViewModel.kt` 的静默预连与退避重试机制**

1. 修改 `doAutoConnectOnce(silent: Boolean = false): Boolean`：
   - 增加 `silent` 标识，当 `silent = true` 时，scan/connect 失败不写入 `_errorText`，避免弹窗/红字打扰；
   - 成功连接时更新 `store.saveLastConnectedBleAddress(target.id)` 并清除 `_errorText`。
2. 在 ViewModel 中监听 `connectionState`、`bleEnabled` 与 `serverUrl`：
   - 当 `bleEnabled == true` 且处于 `ADVERTISING` 状态且 Wi-Fi 地址有效时，触发带退避的后台静默预连（0s ➔ 5s ➔ 15s，最多 3 次，失败后进入冷却期）；
   - 当 GATT 连接由 `CONNECTED` 异常转为 `DISCONNECTED` 时，触发断线重连协程；
   - 手动调用 `autoConnect()` 时，取消当前退避协程，重置计数器，立即执行一次 `doAutoConnectOnce(silent = false)`。

- [ ] **Step 4: 运行单元测试确认全部通过**

Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`  
Expected: PASS

- [ ] **Step 5: 提交代码**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt
git commit -m "feat(ble): add silent pre-connect and auto-reconnect backoff"
```

---

### Task 4: 阅读器就地状态感知与错误自愈（`ReaderSettingsSheet`, `TextReaderScreen`, `BleChannelSection`）

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BleChannelSection.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt` (如有) / 全量单元测试

**Interfaces:**
- Consumes: `BleSettingsViewModel.connectionState`, `BleSettingsViewModel.bleEnabled`, `BleSettingsViewModel.autoConnect()`
- Produces:
  - `ReaderSettingsSheet` 底部蓝牙状态指示胶囊与就地“立即连接”操作。
  - `TextReaderScreen` 网络错误面板上的“一键连接蓝牙并重试”就地自愈按钮。
  - `BleChannelSection` 已记忆设备信息展示。

- [ ] **Step 1: 在 `strings.xml` 中添加所需的多语言资源**

```xml
<string name="ble_status_capsule_connected">蓝牙备用通道已就绪</string>
<string name="ble_status_capsule_idle">蓝牙备用通道未连接</string>
<string name="ble_status_capsule_connecting">蓝牙通道连接中…</string>
<string name="ble_status_capsule_disabled">蓝牙备用通道未启用</string>
<string name="ble_connect_now">立即连接</string>
<string name="ble_connect_and_retry">连接蓝牙并重试</string>
<string name="ble_remembered_device_fmt">已记忆设备：%s</string>
```

- [ ] **Step 2: 更新 `ReaderSettingsSheet.kt` 嵌入蓝牙状态组件**

在 `ReaderSettingsSheetContent` 底部区域添加蓝牙通道状态行：
- 渲染状态图标（🟢 绿色圆点表示 CONNECTED，🟡 橙色表示 CONNECTING，⚪ 灰色表示 ADVERTISING/DISCONNECTED）；
- 显示对应状态文案；
- 当 `bleEnabled == true` 且非 `CONNECTED` 时展示小型“立即连接” OutlinedButton，点击直接触发 `bleViewModel.autoConnect()`。

- [ ] **Step 3: 更新 `TextReaderScreen.kt` 增强错误重试面板**

当 `error != null` 时：
- 展示错误说明文本；
- 显示常规“重试”按钮；
- 若 `bleEnabled == true` 且 `bleConnState != CONNECTED`，并列展示“连接蓝牙并重试”按钮，点击后先唤起 `bleViewModel.autoConnect()` 并触发 `viewModel.reloadChapter()`。

- [ ] **Step 4: 更新 `BleChannelSection.kt`**

在卡片中展示当前记忆的上次连接 MAC/设备信息（若存在），优化连接过渡态交互。

- [ ] **Step 5: 运行全量单元测试与构建验证**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`  
Expected: BUILD SUCCESSFUL, 所有测试 100% 通过。

- [ ] **Step 6: 提交代码**

```bash
git add android/app/src/main/res/values/strings.xml android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/main/java/com/juziss/localmediahub/ui/component/BleChannelSection.kt
git commit -m "feat(reader): in-place BLE status capsule and error auto-recovery"
```

---

## 验证与验收方案

### 自动化验证
```bash
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### 端到端手动验证流程
1. **静默预连验证**：开启 BLE 开关，重启 App 或切出后台再进入，观察控制台或进入设置页，确认无需手动点击即自动建联至 `CONNECTED`。
2. **多设备防误连验证**：在有其他蓝牙设备广播的环境下，确认优先匹配本机名称及历史记录 MAC。
3. **断线自愈验证**：关闭 PC 蓝牙或走远使连接断开，恢复后观察前台是否自动在退避周期内完成重连。
4. **阅读器就地自愈验证**：打开小说阅读器，在阅读设置底部弹窗中查看蓝牙状态胶囊；断开 Wi-Fi 模拟章节加载失败，点击“连接蓝牙并重试”，验证就地自愈。

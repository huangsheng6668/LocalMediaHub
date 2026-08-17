# Task 2 Brief: 智能设备匹配算法（BleSettingsViewModel）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `BleDevice`, `ServerConfigStore.lastConnectedBleAddress`, `BluetoothAdapter.name` / `Build.MODEL`
- Produces:
  - `fun selectBestDevice(discovered: List<BleDevice>, lastConnectedMac: String?, localDeviceName: String?): BleDevice?` (in `BleSettingsViewModel.Companion`)
  - Inside `doAutoConnectOnce()`, retrieve `lastConnectedMac` from `store.lastConnectedBleAddress.firstOrNull()`, local device name from BluetoothAdapter/Build.MODEL, and connect to `selectBestDevice(...)`. On success, persist `target.id` to `store.saveLastConnectedBleAddress(target.id)`.

**Steps to execute:**

1. **Step 1: 编写三级设备匹配算法单元测试**
在 `BleSettingsViewModelTest.kt` 中添加单元测试：
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

@Test
fun autoConnect_savesLastConnectedMacOnSuccess() = runTest {
    val api = fakeApi(scanFailCount = 0)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    runCurrent()

    vm.autoConnect(); runCurrent()

    assertEquals("AA:BB", vm.store.lastConnectedBleAddress.first())
}
```

2. **Step 2: 运行测试以确认失败**
Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`
Expected: FAIL (`selectBestDevice` not found).

3. **Step 3: 在 `BleSettingsViewModel.kt` 中实现 `selectBestDevice` 并接入连接流程**
```kotlin
companion object {
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
并在 `doAutoConnectOnce` 中：
- 读取 `lastMac` 和 `localName`
- 通过 `selectBestDevice` 选定目标
- 连接成功后调用 `store.saveLastConnectedBleAddress(target.id)`

4. **Step 4: 运行测试确认通过**
Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`
Expected: PASS

5. **Step 5: 提交代码**
Commit message: `feat(ble): implement selectBestDevice matching and MAC memory`

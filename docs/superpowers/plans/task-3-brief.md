# Task 3 Brief: 后台静默预建联与断线智能退避重连（BleSettingsViewModel）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `controller.connectionState`, `store.bleEnabled`, `serverConfig.getBaseUrl()`, `store.lastConnectedBleAddress`
- Produces:
  - `suspend fun doAutoConnectOnce(silent: Boolean = false): Boolean`
  - Lifecycle & State triggers:
    1. **静默预建联 (Silent Pre-connect)**:
       - 监听 `combine(bleEnabled, store.serverUrl, controller.connectionState)`，当满足 `bleEnabled && hardwareAvailable() && (connectionState == ADVERTISING || connectionState == DISCONNECTED) && serverUrl.isNotEmpty()` 时触发后台静默预建联；
       - 最多尝试 3 次（0s ➔ 5s ➔ 15s 退避），3 次全部失败后进入 60s 冷却期；
       - `silent = true` 时，scan/connect 失败不写入 `_errorText`，不污染 UI 错误状态。
    2. **断线智能退避重连 (Auto-reconnect on Disconnect)**:
       - 当连接状态由 `CONNECTED` 转为 `DISCONNECTED`/`ADVERTISING` 时，若 `bleEnabled` 且 Wi-Fi 地址有效，触发后台自动重连（3s ➔ 10s ➔ 30s 退避，最多 3 次）。
    3. **手动连接优先级**:
       - 用户手动点击 `autoConnect()` 时，取消当前后台退避协程，重置重试计数器与冷却标志，立即执行 `doAutoConnectOnce(silent = false)`；失败时正常写入 `_errorText`。

**Steps to execute:**

1. **Step 1: 编写静默预连与断线退避的单元测试**
在 `BleSettingsViewModelTest.kt` 中添加测试用例：
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

@Test
fun silentAutoConnect_triggersAutomaticallyOnStartupWhenConfigured() = runTest {
    val api = fakeApi(scanFailCount = 0)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    runCurrent()

    // 验证初始启动已触发静默预建联并成功连接
    assertEquals(1, api.scanCallCount)
    assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
}

@Test
fun silentAutoConnect_entersCooldownAfterThreeFailures() = runTest {
    val api = fakeApi(scanFailCount = 10) // 总是失败
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    runCurrent()
    advanceTimeBy(30_000); runCurrent()

    // 最多尝试 3 次退避，随后进入冷却期
    assertEquals(3, api.scanCallCount)
    assertNull(vm.errorText.value) // 静默模式下不污染 errorText
}
```

2. **Step 2: 运行测试以确认失败**
Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`
Expected: FAIL.

3. **Step 3: 在 `BleSettingsViewModel.kt` 中实现静默预建联与断线退避重连**
- 修改 `doAutoConnectOnce(silent: Boolean = false): Boolean`，处理 `silent` 标志；
- 在 `init` 协程中监听 `combine(bleEnabled, store.serverUrl, controller.connectionState)`，实现带退避和冷却防抖的 `triggerSilentPreConnect()` 和断线重连；
- 在 `autoConnect()` 中取消现有自动协程并重置重试计数。

4. **Step 4: 运行测试确认通过**
Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`
Expected: PASS

5. **Step 5: 提交代码**
Commit message: `feat(ble): add silent pre-connect and auto-reconnect backoff`

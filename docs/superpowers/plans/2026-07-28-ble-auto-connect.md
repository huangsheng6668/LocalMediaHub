# BLE 自动连接 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** BLE 稳定通道开关打开时，App 连上 server + 广播就绪后自动建立 BLE 通道（扫描+连接，带重试），让 Wi-Fi 断开降级方案自动就绪，无需用户手动点「自动连接」。

**Architecture:** 给 `AndroidBlePeripheralManager` 的 `onStartSuccess` 回调加一个对外信号，经 `BleController.advertisingStarted: SharedFlow<Boolean>` 暴露。`BleSettingsViewModel` 监听三信号联动（server 已配置 + bleEnabled + advertisingStarted==true）触发 `autoConnectWithRetry()`：最多 3 次、间隔 3s，仍失败写一条轻提示。手动「自动连接」按钮保持单次语义。

**Tech Stack:** Kotlin (Coroutines / StateFlow / SharedFlow / Jetpack Compose), Hilt, Android BLE GATT.

## Global Constraints

- **Spec source of truth:** `docs/superpowers/specs/2026-07-28-ble-auto-connect-design.md`
- **触发条件（全满足才自动连接）：** (1) server 已配置（`serverConfigStore.serverUrl` 非空 且 `serverConfig.baseUrl` 非空——作为「已连过 server」的代理信号，不额外跑 HTTP 健康检查，因为 Wi-Fi 断开时健康检查注定失败，不能作为 BLE 触发前置）；(2) `bleEnabled == true`；(3) `advertisingStarted` 收到 `true`（广播就绪，方案 B）。
- **重试（方案 C）：** 自动触发最多 3 次，每次间隔 `3000ms`；3 次仍失败 → `_errorText.value = "BLE 自动连接失败，降级通道暂不可用（可手动重试）"`。
- **去重：** 同会话三条件满足只触发一次（`autoConnectArmed` 标志）；BLE 断开（`connectionState.value != BleConnState.CONNECTED`）时复位。
- **手动按钮单次：** 现有 `autoConnect()` 手动调用走单次（不重试），与自动路径共用核心 `doAutoConnectOnce()`。
- **YAGNI：** 不监听 BluetoothAdapter 运行时开关变化；不做跨重启持久化；不改 server 端；不改 wire 协议。

---

### Task 1: 广播就绪信号（PeripheralManager 接口 + 实现 + BleController 暴露）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BlePeripheralManager.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt` (extend)

**Interfaces:**
- Consumes: existing `AndroidBlePeripheralManager.startAdvertising` AdvertiseCallback (`onStartSuccess` / `onStartFailure`); existing `BlePeripheralManager` interface.
- Produces: `BlePeripheralManager.setOnAdvertisingStarted(cb: (success: Boolean) -> Unit)`; `AndroidBlePeripheralManager` impl; `BleController.advertisingStarted: SharedFlow<Boolean>`.

- [ ] **Step 1: Write failing test that advertising-start signal flows to BleController**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/ble/BleControllerTest.kt
@Test
fun advertisingStarted_emitsTrueOnPeripheralStartSuccess() = runTest {
    val fake = FakePeripheralManager()   // existing test fake implementing BlePeripheralManager
    val controller = BleController(fake, BleTransportFallback(), /* bleEnabledFlow */ emptyFlow(), { true }, {})
    val collected = mutableListOf<Boolean>()
    val job = launch { controller.advertisingStarted.collect { collected.add(it) } }
    runCurrent()
    fake.fireAdvertisingStarted(true)   // new fake hook simulating onStartSuccess
    runCurrent()
    job.cancel()
    assertEquals(listOf(true), collected)
}
```
If `FakePeripheralManager` does not yet implement `setOnAdvertisingStarted`, the test fails to compile — that is the RED.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleControllerTest.advertisingStarted_emitsTrueOnPeripheralStartSuccess*"`
Expected: FAIL (unresolved reference `advertisingStarted` / `setOnAdvertisingStarted` / `fireAdvertisingStarted`).

- [ ] **Step 3: Add `setOnAdvertisingStarted` to the interface + impl**

`BlePeripheralManager.kt` — add to the interface:
```kotlin
/** Register callback invoked when advertising actually starts (onStartSuccess)
 * or fails (onStartFailure). Used by [BleController] to surface a
 * "broadcast ready" signal so callers do not attempt BLE connect before the
 * peripheral is discoverable. */
fun setOnAdvertisingStarted(cb: (success: Boolean) -> Unit)
```

`AndroidBlePeripheralManager.kt`:
```kotlin
private var onAdvertisingStarted: ((Boolean) -> Unit)? = null

override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) {
    onAdvertisingStarted = cb
}
```
In `startAdvertising`'s `AdvertiseCallback`:
```kotlin
override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
    android.util.Log.i("BlePeripheral", "advertise onStartSuccess")
    onAdvertisingStarted?.invoke(true)
}
override fun onStartFailure(errorCode: Int) {
    android.util.Log.e("BlePeripheral", "advertise onStartFailure errorCode=$errorCode")
    onAdvertisingStarted?.invoke(false)
}
```

- [ ] **Step 4: Expose `advertisingStarted` from BleController**

`BleController.kt` — add field + register in `init`:
```kotlin
private val _advertisingStarted = kotlinx.coroutines.flow.MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
val advertisingStarted: kotlinx.coroutines.flow.SharedFlow<Boolean> = _advertisingStarted.asSharedFlow()
```
In `init { ... }` (alongside the existing `setOnPayloadReceived` registration):
```kotlin
peripheralManager.setOnAdvertisingStarted { success ->
    _advertisingStarted.tryEmit(success)
}
```
Add imports: `kotlinx.coroutines.flow.MutableSharedFlow`, `kotlinx.coroutines.flow.SharedFlow`, `kotlinx.coroutines.flow.asSharedFlow`.

- [ ] **Step 5: Add `fireAdvertisingStarted` to the test fake**

In whatever fake implements `BlePeripheralManager` for tests (`FakePeripheralManager` or the existing one in `TestBleFixtures.kt` — locate via `grep -rn "BlePeripheralManager" android/app/src/test`), add:
```kotlin
private var onAdvertisingStarted: ((Boolean) -> Unit)? = null
override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) { onAdvertisingStarted = cb }
fun fireAdvertisingStarted(success: Boolean) { onAdvertisingStarted?.invoke(success) }
```
Update ALL other test fakes implementing `BlePeripheralManager` to also override `setOnAdvertisingStarted` (no-op body) so they still compile.

- [ ] **Step 6: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleControllerTest.advertisingStarted*"`
Expected: PASS.

- [ ] **Step 7: Commit Task 1**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BlePeripheralManager.kt android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt android/app/src/test/java/com/juziss/localmediahub/ble/
git commit -m "feat(ble): expose advertising-started signal from PeripheralManager to BleController"
```

---

### Task 2: 抽出 doAutoConnectOnce + 自动触发联动 + 重试

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt` (extend)

**Interfaces:**
- Consumes: Task 1 `controller.advertisingStarted: SharedFlow<Boolean>`; existing `api.scan()` / `api.connect(id)`; existing `controller.connectionState: StateFlow<BleConnState>`; `serverConfigStore.serverUrl` + `serverConfig.baseUrl` for the server-configured signal; `store.bleEnabled` for the toggle.
- Produces: `BleSettingsViewModel.doAutoConnectOnce(): Boolean` (private suspend); `autoConnectWithRetry()` (private); automatic trigger wired in `init`.

- [ ] **Step 1: Write failing test for retry-then-success**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt
@Test
fun autoConnect_retriesThenSucceeds() = runTest {
    val fakeApi = FakeBleApi(scanFailCount = 2)  // first 2 scan() return Error, 3rd returns Success + connect Success
    val vm = buildVm(api = fakeApi, serverConfigured = true, bleEnabled = true)
    vm.fireAdvertisingReady(true)  // new test hook triggering the auto path
    runCurrent()
    // advance through 2 retry delays (3s each)
    advanceTimeBy(3_001); runCurrent()
    advanceTimeBy(3_001); runCurrent()
    assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
    assertTrue(fakeApi.scanCallCount >= 3)
}
```
If `buildVm` / `FakeBleApi` / `fireAdvertisingReady` helpers do not yet exist, create minimal versions (see Step 5). The test fails to compile → RED.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleSettingsViewModelTest.autoConnect_retriesThenSucceeds*"`
Expected: FAIL (unresolved `doAutoConnectOnce` / retry / `fireAdvertisingReady`).

- [ ] **Step 3: Refactor existing `autoConnect()` into `doAutoConnectOnce()`**

In `BleSettingsViewModel.kt`, rename the body of the current `autoConnect()` (the scan→pick first→connect flow at ~:123) to:
```kotlin
/**
 * One scan+connect attempt. Returns true if the BLE link reached CONNECTED.
 * Shared by the manual button ([autoConnect], single call) and the automatic
 * path ([autoConnectWithRetry], 3 attempts). Sets _scanning for the duration.
 */
private suspend fun doAutoConnectOnce(): Boolean {
    _errorText.value = null
    _scanning.value = true
    try {
        return when (val scanResult = api.scan()) {
            is NetworkResult.Success -> {
                val discovered = scanResult.data
                _devices.value = discovered
                if (discovered.isEmpty()) {
                    _errorText.value = "未发现当前手机的 BLE 广播（请确保手机蓝牙已开启且距离电脑较近）"
                    controller.markDisconnected()
                    return false
                }
                val target = discovered.first()
                when (val connResult = api.connect(target.id)) {
                    is NetworkResult.Success -> if (connResult.data) {
                        controller.markConnected(); return true
                    } else {
                        controller.markDisconnected()
                        _errorText.value = "连接失败：服务端未能建立 BLE GATT 连接"; return false
                    }
                    is NetworkResult.Error -> {
                        controller.markDisconnected()
                        _errorText.value = "连接失败: ${connResult.message}"; return false
                    }
                    else -> { controller.markDisconnected(); return false }
                }
            }
            is NetworkResult.Error -> {
                _devices.value = emptyList(); controller.markDisconnected()
                val cause = if (scanResult.message.contains("ble unavailable")) {
                    "服务端蓝牙未就绪（请确认 PC 已配有蓝牙且服务端使用 go build -tags bluetooth 编译）"
                } else scanResult.message
                _errorText.value = "建立连接失败: $cause"; return false
            }
            else -> { _devices.value = emptyList(); controller.markDisconnected(); return false }
        }
    } finally {
        _scanning.value = false
    }
}
```
Replace the public `fun autoConnect()` (manual button) with a single-call wrapper:
```kotlin
fun autoConnect() { viewModelScope.launch { doAutoConnectOnce() } }
```

- [ ] **Step 4: Add retry + automatic trigger**

Add fields + the retry function:
```kotlin
private var autoConnectArmed: Boolean = false

private fun autoConnectWithRetry() {
    if (autoConnectArmed) return
    autoConnectArmed = true
    viewModelScope.launch {
        repeat(3) {
            if (controller.connectionState.value == BleConnState.CONNECTED) return@launch
            if (doAutoConnectOnce()) return@launch
            kotlinx.coroutines.delay(3_000)
        }
        // exhausted
        if (controller.connectionState.value != BleConnState.CONNECTED) {
            _errorText.value = "BLE 自动连接失败，降级通道暂不可用（可手动重试）"
        }
    }
}
```
In `init { ... }`, add the three-signal trigger:
```kotlin
viewModelScope.launch {
    val serverConfigured = combine(serverConfigStore.serverUrl, serverConfig.baseUrl) { url, base ->
        url.isNotBlank() && base.isNotBlank()
    }
    combine(serverConfigured, bleEnabledFlow) { srv, ble -> srv to ble }
        .distinctUntilChanged()
        .collect { (srv, ble) ->
            if (!srv || !ble) {
                autoConnectArmed = false  // reset when a precondition drops
            }
        }
}
viewModelScope.launch {
    controller.advertisingStarted.collect { started ->
        if (started && serverConfigStore.serverUrl.value.isNotBlank()
            && serverConfig.baseUrl.value.isNotBlank()
            && bleEnabledFlow.first()  // current toggle value
        ) {
            autoConnectWithRetry()
        }
    }
}
viewModelScope.launch {
    controller.connectionState.collect { st ->
        if (st != BleConnState.CONNECTED) autoConnectArmed = false
    }
}
```
(If `bleEnabledFlow` is not already a field on the VM, inject `store.bleEnabled` as `bleEnabledFlow` via the constructor — see Step 5. The `combine` + `advertisingStarted` + `connectionState` triple ensures preconditions are rechecked; `autoConnectArmed` dedupes.)

- [ ] **Step 5: Add/extend test helpers**

In `BleSettingsViewModelTest.kt` (and `TestBleFixtures` if present), add:
- `FakeBleApi(scanFailCount: Int)` implementing the same `BleApi` surface the VM calls (`scan()`, `connect(id)`, `send(payload)`); `scan()` returns `Error` for the first `scanFailCount` calls then `Success(listOf(BleDevice("AA:BB","x",-50)))`; `connect` returns `Success(true)`. Expose `scanCallCount`.
- `buildVm(...)` constructing `BleSettingsViewModel` with the fake api + a fake `BleController` whose `advertisingStarted` is a `MutableSharedFlow` and whose `connectionState` reflects `markConnected/markDisconnected`.
- `vm.fireAdvertisingReady(success)` = push into the fake controller's `advertisingStarted`.
- A fake `BleController` exposing a `connectionState` `MutableStateFlow` the test can read.

If `BleSettingsViewModel` does not currently inject `ServerConfigStore`/`ServerConfig`/`bleEnabledFlow`, add them as constructor params (Hilt-injected; update any existing test constructions + the `@HiltViewModel` constructor). Confirm via `grep -n "class BleSettingsViewModel" android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`.

- [ ] **Step 6: Run tests to verify they pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleSettingsViewModelTest*"`
Expected: PASS (retry-then-success, plus existing manual autoConnect tests still pass with the refactor).

- [ ] **Step 7: Add failing tests for precondition-missing + dedup, then make them pass**

```kotlin
@Test fun autoConnect_notTriggeredWhenBleDisabled() = runTest {
    val fakeApi = FakeBleApi(scanFailCount = 0)
    val vm = buildVm(api = fakeApi, serverConfigured = true, bleEnabled = false)
    vm.fireAdvertisingReady(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
    assertEquals(0, fakeApi.scanCallCount)
}

@Test fun autoConnect_notTriggeredWhenServerNotConfigured() = runTest {
    val fakeApi = FakeBleApi(scanFailCount = 0)
    val vm = buildVm(api = fakeApi, serverConfigured = false, bleEnabled = true)
    vm.fireAdvertisingReady(true); runCurrent(); advanceTimeBy(10_000); runCurrent()
    assertEquals(0, fakeApi.scanCallCount)
}

@Test fun autoConnect_dedupesRepeatedAdvertisingReady() = runTest {
    val fakeApi = FakeBleApi(scanFailCount = 0)
    val vm = buildVm(api = fakeApi, serverConfigured = true, bleEnabled = true)
    vm.fireAdvertisingReady(true); runCurrent()
    vm.fireAdvertisingReady(true); runCurrent()
    vm.fireAdvertisingReady(true); runCurrent()
    assertEquals(1, fakeApi.scanCallCount)  // armed → only one attempt path starts
}

@Test fun autoConnect_rearmsAfterBleDisconnect() = runTest {
    val fakeApi = FakeBleApi(scanFailCount = 0)
    val vm = buildVm(api = fakeApi, serverConfigured = true, bleEnabled = true)
    vm.fireAdvertisingReady(true); runCurrent()
    assertEquals(1, fakeApi.scanCallCount)
    vm.fakeController.markDisconnected(); runCurrent()  // resets armed
    vm.fireAdvertisingReady(true); runCurrent()
    assertEquals(2, fakeApi.scanCallCount)
}
```
These should pass once Step 4's wiring is in place; if any fail, fix the wiring (do NOT weaken the assertions).

- [ ] **Step 8: Run full build + commit**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BleSettingsViewModelTest*" && ./gradlew assembleDebug`
Expected: PASS + BUILD SUCCESSFUL.

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt android/app/src/test/java/com/juziss/localmediahub/ble/TestBleFixtures.kt
git commit -m "feat(ble): auto-connect BLE on server+toggle+advertising-ready with 3x retry"
```

---

## Self-Review Notes

- **Spec coverage:** §1.1 触发三条件 → Task 2 init 联动（serverConfigured + bleEnabled + advertisingStarted）。§1.2 重试 3 次/3s + 轻提示 → Task 2 `autoConnectWithRetry`。§1.3 去重 + 断开复位 → Task 2 `autoConnectArmed` + connectionState collect。§1.4 不做项 → Global Constraints YAGNI。§2.1 信号链路 → Task 1。§2.2/2.3 触发器+重试 → Task 2。§4 测试 → Task 1 Step 1 + Task 2 Steps 1/7。
- **Type consistency:** `setOnAdvertisingStarted(cb: (Boolean) -> Unit)` 一致出现在 Task 1 接口/impl/测试 fake。`advertisingStarted: SharedFlow<Boolean>` 一致出现在 Task 1 BleController + Task 2 collect。`doAutoConnectOnce(): Boolean` 在 Task 2 定义并复用。`autoConnectArmed` 一致。
- **Spec §3 决策落定：** 用 `serverConfigStore.serverUrl` + `serverConfig.baseUrl` 双非空作为「server 已配置」代理（不跑 HTTP 健康检查），写进 Global Constraints，Task 2 init 用此判定。
- **跨任务依赖：** Task 2 依赖 Task 1 的 `advertisingStarted`；Task 1 必须先完成。手动 `autoConnect()` 单次语义保留（Step 3 wrapper）。

# Manual BLE Connection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor Android BLE connection logic so that BLE GATT connection is strictly initiated manually by the user clicking the connection button in the UI, eliminating automatic background scan and connection triggers.

**Architecture:** Remove the `init` observer block in `BleSettingsViewModel` that automatically listened for advertising readiness and initiated auto-connect retries. Retain `autoConnect()` and `doAutoConnectOnce()` strictly for manual user actions. Update ViewModel unit tests to reflect the manual connection requirement.

**Tech Stack:** Kotlin, Coroutines, StateFlow, Hilt, MockK, JUnit4.

## Global Constraints

- **Single Manual Entrypoint**: `autoConnect()` in `BleSettingsViewModel` is invoked only when the user clicks the "一键建立 BLE 控制通道" button.
- **Zero Automatic HTTP Calls**: Enabling the BLE toggle must NOT trigger any HTTP requests (`/api/v1/ble/scan` or `/api/v1/ble/connect`) automatically.

---

### Task 1: Refactor `BleSettingsViewModel.kt` to Remove Automatic Connection Triggers

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt:182-287`

**Interfaces:**
- Consumes: `BleApi.scan()`, `BleApi.connect(id)`, `BleController.markConnected()`, `BleController.markDisconnected()`
- Produces: Cleaned `BleSettingsViewModel` without `init` auto-connect observer and without `autoConnectWithRetry()`

- [ ] **Step 1: Write failing/updated unit test in `BleSettingsViewModelTest.kt`**

Add test to verify enabling BLE does NOT trigger automatic scan:

```kotlin
@Test
fun bleEnabled_doesNotTriggerAutoConnect() = runTest {
    val api = fakeApi(scanFailCount = 0)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    runCurrent()
    vm.fireAdvertisingReady(true); runCurrent()
    advanceTimeBy(10_000); runCurrent()
    assertEquals(0, api.scanCallCount)
}
```

- [ ] **Step 2: Run test to verify failure**

Run: `cd android && ./gradlew test --tests "com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest.bleEnabled_doesNotTriggerAutoConnect"`
Expected: FAIL (because existing `init` code still triggers scan automatically).

- [ ] **Step 3: Implement minimal code changes in `BleSettingsViewModel.kt`**

Remove the `autoConnectWithRetry()` method, `autoConnectArmed`, `autoConnectLoopActive`, and the entire `init` block in `BleSettingsViewModel.kt` (lines 182-287):

```kotlin
// Remove lines 182-287 (autoConnectWithRetry & init block) from BleSettingsViewModel.kt
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew test --tests "com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest.bleEnabled_doesNotTriggerAutoConnect"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt
git commit -m "refactor(ble): remove automatic BLE connection triggers in BleSettingsViewModel"
```

---

### Task 2: Clean up Comments and Update Unit Tests

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt:50-78`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt:89-220`

**Interfaces:**
- Consumes: `BleSettingsViewModel`
- Produces: Updated unit test suite asserting manual connection behavior

- [ ] **Step 1: Update `BleController.kt` docstrings**

Clean up comments in `BleController.kt` that refer to Task 2 auto-connect trigger:

```kotlin
// Update comments in BleController.kt to note that connection trigger is manual via UI action.
```

- [ ] **Step 2: Update `BleSettingsViewModelTest.kt` to replace old auto-connect tests**

Replace old automatic trigger tests (`autoConnect_retriesThenSucceeds`, `autoConnect_notTriggeredWhenBleDisabled`, `autoConnect_notTriggeredWhenServerNotConfigured`, `autoConnect_dedupesRepeatedAdvertisingReady`, `autoConnect_rearmsAfterBleDisconnect`, `autoConnect_firesWhenServerConnectsAfterAdvertisingStarted`, `autoConnect_doesNotStartSecondLoopDuringRetryDelay`) with unit tests focused on manual `autoConnect()` and manual button interactions:

```kotlin
@Test
fun autoConnect_manualCall_triggersSingleScanAndConnect() = runTest {
    val api = fakeApi(scanFailCount = 0)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    vm.autoConnect()
    runCurrent()
    assertEquals(1, api.scanCallCount)
    assertEquals(BleConnState.CONNECTED, vm.connectionState.value)
}

@Test
fun autoConnect_manualCall_handlesScanFailureCleanly() = runTest {
    val api = fakeApi(scanFailCount = 1)
    val vm = buildVm(api = api, serverConfigured = true, bleEnabled = true)
    vm.autoConnect()
    runCurrent()
    assertEquals(1, api.scanCallCount)
    assertEquals(BleConnState.DISCONNECTED, vm.connectionState.value)
    assertTrue(vm.errorText.value?.contains("建立连接失败") == true)
}
```

- [ ] **Step 3: Run full Android unit test suite**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS (all tests green).

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleController.kt android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt
git commit -m "test(ble): update BLE unit tests for manual connection flow"
```

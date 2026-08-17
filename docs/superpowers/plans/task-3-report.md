# Task 3 Implementation Report: 后台静默预建联与断线智能退避重连（BleSettingsViewModel）

## 1. Overview
- **Task**: Task 3 - 后台静默预建联与断线智能退避重连（BleSettingsViewModel）
- **Status**: DONE
- **Files Modified**:
  - `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt`
  - `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt`
- **Target Commit Message**: `feat(ble): add silent pre-connect and auto-reconnect backoff`

---

## 2. Implementation Summary

### 2.1 `doAutoConnectOnce(silent: Boolean = false)`
- Made `doAutoConnectOnce` public/internal with a `silent` parameter (defaults to `false`).
- When `silent = true`:
  - Errors during scan and connect do not write into `_errorText`, keeping the UI error message clean during background attempts.
  - Does not reset existing user error text on entry.
- When `silent = false`:
  - Resets `_errorText` to `null` on start.
  - Normal error messages are surfaced to `_errorText` on any failure.

### 2.2 Background Silent Pre-connect (静默预建联)
- In `init`, observes `combine(bleEnabled, store.serverUrl, controller.connectionState)`.
- When eligible (`bleEnabled && hardwareAvailable() && url.isNotBlank() && (connState == ADVERTISING || connState == DISCONNECTED)`):
  - Pre-connect loop attempts connection with delays: `0s ➔ 5s ➔ 15s`.
  - If all 3 attempts fail, enters a `60s` cooldown before restarting the attempt sequence.
  - Silent mode is used so background pre-connection never pollutes UI error states.

### 2.3 Auto-reconnect on Disconnect (断线智能退避重连)
- Detects state transition from `CONNECTED` to `ADVERTISING`/`DISCONNECTED`.
- If eligible, triggers auto-reconnect with backoff delays: `3s ➔ 10s ➔ 30s` (max 3 attempts).
- Automatically cancels upon successful re-connection or if BLE/Wi-Fi is disabled.

### 2.4 Manual Connection Priority (手动连接优先级)
- `autoConnect()` cancels any ongoing background auto-connect / reconnect coroutine job, resetting retry counters and cooldown.
- Immediately executes `doAutoConnectOnce(silent = false)`, surfacing errors directly to `_errorText`.

---

## 3. Test Verification (TDD Workflow)

### 3.1 Red Stage (TDD Step 1 & 2)
- Added new unit test cases covering silent pre-connect, error suppression, startup trigger, 3-attempt cooldown, and disconnect backoff in `BleSettingsViewModelTest.kt`.
- Executed `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest` and verified compilation and assertions failed as expected.

### 3.2 Green Stage (TDD Step 3 & 4)
- Implemented the background loops and `silent` logic in `BleSettingsViewModel.kt`.
- Updated test fixture `buildVm` to mock `BluetoothManager` for JVM test runner compatibility.
- Executed `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`:
  - **Result**: `BUILD SUCCESSFUL`, all 16 unit tests passed.

### 3.3 Full Suite Verification
- Executed `./gradlew testDebugUnitTest` across the entire Android module.
  - **Result**: `BUILD SUCCESSFUL`, 40 actionable tasks, all test suites passed without regression.

---

## 4. Test Cases Added / Updated
1. `silentAutoConnect_onFailure_doesNotPolluteErrorText`: Verifies `silent = true` does not populate `errorText` on scan/connect failure.
2. `manualAutoConnect_resetsBackoffAndSetsErrorTextOnFailure`: Verifies manual `autoConnect()` cancels background retries and sets `errorText` on failure.
3. `silentAutoConnect_triggersAutomaticallyOnStartupWhenConfigured`: Verifies pre-connect triggers automatically on startup when configured.
4. `silentAutoConnect_entersCooldownAfterThreeFailures`: Verifies 3 attempts (0s, 5s, 15s) are made and the loop enters 60s cooldown without polluting `errorText`.
5. `silentAutoConnect_doesNotTrigger_whenBleDisabled`: Verifies pre-connect does not trigger when BLE toggle is off.
6. `silentAutoConnect_doesNotTrigger_whenServerNotConfigured`: Verifies pre-connect does not trigger when server URL is empty.
7. `autoReconnectOnDisconnect_triggersBackoffWhenDisconnected`: Verifies disconnect triggers 3s backoff and successfully reconnects.

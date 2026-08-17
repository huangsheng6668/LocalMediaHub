# Task 1 Report: DataStore 持久化支持（ServerConfigStore）

**Status:** DONE

## Summary of Changes
1. **`android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`**:
   - Added `KEY_LAST_CONNECTED_BLE_ADDRESS` to `ServerConfigStore.Companion`.
   - Exposed `lastConnectedBleAddress: Flow<String?>` backed by DataStore preferences.
   - Implemented `saveLastConnectedBleAddress(address: String)` to store the MAC address.
   - Implemented `clearLastConnectedBleAddress()` to remove the stored MAC address.

2. **`android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreTest.kt`**:
   - Added unit test `lastConnectedBleAddress_saveAndClear_flowEmitsCorrectValues` validating save, emission, and clear behavior.

## TDD Verification
1. **Red Phase**: Added unit test in `ServerConfigStoreTest.kt` and ran `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ServerConfigStoreTest`.
   - Observed expected compiler errors (`Unresolved reference` for `saveLastConnectedBleAddress`, `lastConnectedBleAddress`, and `clearLastConnectedBleAddress`).
2. **Green Phase**: Implemented minimal code in `ServerConfigStore.kt` and re-ran the test.
   - Result: `BUILD SUCCESSFUL` (all assertions passed).
3. **Regression Test**: Ran `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ServerConfigStore*`.
   - Result: `BUILD SUCCESSFUL` across all `ServerConfigStore` test suites.

## Commit Details
- Commit: `530b9851a68eb1420279637a4b12657836a73cd8`
- Message: `feat(ble): add lastConnectedBleAddress to ServerConfigStore`

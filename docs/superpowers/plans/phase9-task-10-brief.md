### Task 10: Android GATT 特征加密与回调守卫（H-1c / L-9 / L-10）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ble/BleModuleTest.kt`（或对应 manager 测试）

**Interfaces:**
- Produces:
  - Command 特征权限 `PERMISSION_WRITE_ENCRYPTED`，State 特征 `PERMISSION_READ_ENCRYPTED`（首个连接会触发系统 LE Just Works 配对，属预期，一次即可）
  - `onCharacteristicWriteRequest` / `onDescriptorWriteRequest` 守卫：`device.bondState != BOND_BONDED` → `sendResponse(GATT_INSUFFICIENT_AUTHENTICATION)`；descriptor 仅接受 CCCD（UUID `00002902-0000-1000-8000-00805f9b34fb`）才可替换 `subscriberDevice`；`offset != 0 || preparedWrite` → `sendResponse(GATT_REQUEST_NOT_SUPPORTED)`
  - `onMtuChanged` 不可用时由 Central 侧 `requestMtu(247)` 触发（Peripheral 在 `onExecuteWrite`/连接回调记录协商值；本任务在 server 侧 connect 流程加 `requestMtu(247)`——winrt adapter 若不支持则保持 23 并由既有短帧解码错误兜底）

- [ ] **Step 1: 写失败测试**

`BleModuleTest.kt`（纯逻辑：守卫判定抽出为可测函数）：

```kotlin
@Test fun writeGuardRejectsUnbondedOffsetAndPrepared() {
    // 判定逻辑抽为 companion/顶层纯函数 shouldAcceptWrite(bondState, offset, preparedWrite)
    assertEquals(WriteDecision.REJECT_AUTH,
        shouldAcceptWrite(BluetoothDevice.BOND_NONE, 0, false))
    assertEquals(WriteDecision.REJECT_NOT_SUPPORTED,
        shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 4, false))
    assertEquals(WriteDecision.REJECT_NOT_SUPPORTED,
        shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, true))
    assertEquals(WriteDecision.ACCEPT,
        shouldAcceptWrite(BluetoothDevice.BOND_BONDED, 0, false))
}

@Test fun onlyCCDReplacesSubscriber() {
    assertTrue(isCccd(ParcelBasedUUID("00002902-0000-1000-8000-00805f9b34fb")))
    assertFalse(isCccd(ParcelBasedUUID("00002901-0000-1000-8000-00805f9b34fb")))
}
```

（`shouldAcceptWrite` / `isCccd` / `WriteDecision` 顶部声明在 `AndroidBlePeripheralManager.kt`，测试用简化 UUID 包装。）

- [ ] **Step 2: 运行确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleModuleTest"`
Expected: 编译错误

- [ ] **Step 3: 实现**

特征构建改加密权限；两个回调开头插入守卫（返回值映射 `GATT_INSUFFICIENT_AUTHENTICATION` / `GATT_REQUEST_NOT_SUPPORTED`，不回调上层）；descriptor 写仅 CCCD 生效；`requestMtu(247)` 加到 PC 端 `central_adapter.go` 连接流程（winrt `BluetoothLEDevice.RequestMaxPayloadSize` 或等价 API，stub 构建保持编译）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug && cd ../server && go test ./internal/ble/`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/AndroidBlePeripheralManager.kt android/app/src/test/java/com/juziss/localmediahub/ble server/internal/ble/central_adapter.go
git commit -m "feat(ble): encrypted GATT characteristics with bond guards (Phase 9)"
```

---


# Task 1 Brief: DataStore 持久化支持（ServerConfigStore）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreTest.kt`

**Interfaces:**
- Consumes: AndroidX DataStore `Preferences`
- Produces:
  - `ServerConfigStore.lastConnectedBleAddress: Flow<String?>`
  - `suspend fun ServerConfigStore.saveLastConnectedBleAddress(address: String)`
  - `suspend fun ServerConfigStore.clearLastConnectedBleAddress()`

**Steps to execute:**

1. **Step 1: 编写 DataStore 新增字段的单元测试**
在 `ServerConfigStoreTest.kt` 中添加测试用例：
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

2. **Step 2: 运行测试以确认失败**
Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ServerConfigStoreTest`
Expected: 编译错误，`lastConnectedBleAddress` / `saveLastConnectedBleAddress` 未定义。

3. **Step 3: 在 `ServerConfigStore.kt` 中实现持久化字段**
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

4. **Step 4: 运行测试确认通过**
Run: `cd android && ./gradlew testDebugUnitTest --tests com.juziss.localmediahub.data.ServerConfigStoreTest`
Expected: PASS

5. **Step 5: 提交代码**
Commit message: `feat(ble): add lastConnectedBleAddress to ServerConfigStore`

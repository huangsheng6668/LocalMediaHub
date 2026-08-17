# Task 2 Execution Report: 智能设备匹配算法（BleSettingsViewModel）

## 1. 任务概述

- **任务名称**: Task 2: 智能设备匹配算法（BleSettingsViewModel）
- **目标**: 
  - 在 `BleSettingsViewModel.Companion` 中实现三级智能匹配算法（1. 历史连接 MAC 优先 ➔ 2. 本机设备名匹配 ➔ 3. 最大 RSSI 信号最强兜底）；
  - 在 `doAutoConnectOnce()` 和 `connect()` 中接入设备选择算法，并在连接成功后通过 `ServerConfigStore.saveLastConnectedBleAddress` 持久化 MAC 地址；
  - 遵循 TDD 流程编写全覆盖单元测试并验证全量测试绿灯。

## 2. 修改文件清单

| 文件 | 变更说明 |
| --- | --- |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt` | 增加 `Companion.selectBestDevice`，在 `doAutoConnectOnce` 接入选路并保存 MAC，在 `connect` 成功后保存 MAC，开放 `store` 字段可见性供测试使用 |
| `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt` | 将 `saveLastConnectedBleAddress` 与 `clearLastConnectedBleAddress` 标记为 `open`，便于测试桩重写 |
| `android/app/src/test/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModelTest.kt` | 增加三级匹配算法单测（MAC 优先、设备名回退、最大 RSSI 回退）与连接成功 MAC 持久化单测，更新 `buildVm` 中的 fake store |

## 3. TDD 执行过程

### 3.1 编写失败测试（Red Stage）
在 `BleSettingsViewModelTest.kt` 中添加了以下测试：
1. `selectBestDevice_prioritizesLastConnectedMac`: 验证在历史 MAC 匹配存在时优先选用历史 MAC；
2. `selectBestDevice_fallsBackToDeviceNameMatch_whenNoMacMatch`: 验证历史 MAC 不匹配但本机名称匹配时选用同名设备；
3. `selectBestDevice_fallsBackToMaxRssi_whenNoMacOrNameMatch`: 验证无 MAC 和名称匹配时，按 RSSI 信号强度最大值进行兜底选择；
4. `autoConnect_savesLastConnectedMacOnSuccess`: 验证 `autoConnect` 执行成功后，成功连接的设备 MAC 会保存到 `ServerConfigStore`。

执行 `./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest`，观察到预期的编译/测试失败（`Unresolved reference 'selectBestDevice'`）。

### 3.2 算法实现与流程集成（Green Stage）
- 在 `BleSettingsViewModel.Companion` 实现 `selectBestDevice(discovered, lastConnectedMac, localDeviceName)`：
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
- 在 `doAutoConnectOnce` 中安全读取 `BluetoothAdapter.name` / `Build.MODEL`，并从 `store.lastConnectedBleAddress` 获取历史 MAC 进行最佳设备选择；连接成功后调用 `store.saveLastConnectedBleAddress(target.id)`。
- 在手动 `connect(device)` 成功后同样持久化 `store.saveLastConnectedBleAddress(device.id)`。

### 3.3 验证结果
- 单测套件：`./gradlew testDebugUnitTest --tests com.juziss.localmediahub.viewmodel.BleSettingsViewModelTest` -> **BUILD SUCCESSFUL**
- 全量单测：`./gradlew testDebugUnitTest` -> **BUILD SUCCESSFUL**（全套单测 100% 通过）

## 4. Git 提交记录

- **Commit SHA**: `2346948`
- **Commit Message**: `feat(ble): implement selectBestDevice matching and MAC memory`
- **变更统计**: 3 files changed, 106 insertions(+), 4 deletions(-)

## 5. 结论

Task 2 智能设备匹配算法已圆满完成，完全满足三级选路与持久化需求，代码结构清晰健壮，测试全绿。

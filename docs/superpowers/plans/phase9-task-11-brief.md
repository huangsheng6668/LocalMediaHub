### Task 11: BLE 选路/日志/路径加固（H-1d / M-6 / M-8）

**Files:**
- Modify: `server/internal/ble/central_adapter.go:96-102,319,367-407` + `server/internal/ble/api_provider.go:162-177` + `server/internal/ble/central_adapter_test.go` + `server/internal/ble/api_provider_test.go`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt:257`

**Interfaces:**
- Produces:
  - UUID 匹配仅接受完整 128-bit 精确相等（归一化：去 `-`、lowercase 后与 `ServiceUUID` 比较；16-bit 短 UUID 一律不匹配）
  - Android `selectBestDevice(...)` 三级匹配（历史 MAC → 设备名 → RSSI 最强）后**不再兜底 `discovered.first()`**，无匹配返回 null → UI 显示"未找到匹配设备"
  - BLE 日志去敏感明细：`central_adapter.go:319` 去掉 `data=%x`（只留 len 与帧类型）；`:96-102` 扫描日志只输出命中数与命中设备地址
  - `api_provider.go` `BrowseFolderData` 改用 `service.ResolveBrowsePath`（与 HTTP 端同款 UNC/reparse 防线）

- [ ] **Step 1: 写失败测试**

Go（`central_adapter_test.go`）：

```go
func TestUUIDMatchRequiresFullExactServiceUUID(t *testing.T) {
	if !hasServiceUUIDMatch([]string{ServiceUUID, "0000ffff-0000-1000-8000-00805f9b34fb"}) {
		t.Fatal("exact UUID must match")
	}
	if hasServiceUUIDMatch([]string{"FA6A3001-8B2C-4E6F-9988-123456789ABC", "fa6a3001-8b2c"}) {
		t.Fatal("prefix/case/full-string-mismatch must NOT match") // 前缀 8 字符旧逻辑会误命中
	}
	if hasServiceUUIDMatch([]string{"ffff"}) {
		t.Fatal("16-bit short UUID must not match")
	}
}
```

（`api_provider_test.go` 补：browse 请求携带 `..`/junction 形态路径返回错误而非列表——构造方式参照既有 `BrowseFolderData` 测试。）

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/ble/ -run 'TestUUIDMatch|Browse' -v`
Expected: FAIL

- [ ] **Step 3: 实现**

```go
// central_adapter.go
func normalizeUUIDString(s string) string {
	return strings.ToLower(strings.ReplaceAll(s, "-", ""))
}
func hasServiceUUIDMatch(uuids []string) bool {
	want := normalizeUUIDString(ServiceUUID)
	for _, u := range uuids {
		if len(u) >= 32 && normalizeUUIDString(u) == want { // 32 hex chars = 128-bit
			return true
		}
	}
	return false
}
```

删除 `matchUUIDPrefix` 的 8 字符前缀分支（`central_adapter.go:367-377` 同步收紧）。日志两处按上文收缩。`api_provider.go:162-177` 把 `service.IsPathWithinRoots` 替换为 `resolved, err := service.ResolveBrowsePath(path)`（错误即拒绝），后续用 `resolved`。

Android 侧：

```kotlin
// BleSettingsViewModel.kt（示意）：
val target = selectBestDevice(discovered, lastMac, adapterName) // 三级匹配
    ?: run {
        _errorText.value = "未找到匹配的 LocalMediaHub 设备"
        return@launch
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/ble/ ./... && cd ../android && ./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/ble android/app/src/main/java/com/juziss/localmediahub/viewmodel/BleSettingsViewModel.kt android/app/src/test
git commit -m "fix(ble): exact UUID match, log redaction and hardened browse path (Phase 9)"
```

---


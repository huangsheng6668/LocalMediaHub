# BLE 降级小说阅读与传输 Implementation Plan (Revised)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Wi-Fi 断开/不稳定时无缝降级到低功耗蓝牙 (BLE GATT) 物理通道，基于现有的 PC=Central / Android=Peripheral 拓扑，分包传输小说章节 Block 数据，并在 Android 阅读器界面展示 3 秒自动淡出的 `[⚡ BLE 降级传输中]` 提示。

**Architecture:** 保持现有 3 字节物理帧头向下兼容，在 Payload 首字节加入 `CmdID` (`0x11 CMD_BOOK_CHAPTER_REQ`, `0x12 CMD_BOOK_CHAPTER_CHUNK`)。Android 通过 GATT Notify 发送章节请求，PC Central 监听请求并切分 Chunk 通过 GATT Write 连续回发；`BleTransportFallback` 重组完 Blocks JSON 后提交给 `MediaRepository`。

**Tech Stack:** Go (Echo v4 / `golang.org/x/sys`), Kotlin (Coroutines / StateFlow / Jetpack Compose), Android BLE GATT.

## Global Constraints

- **Single spec source of truth:** `docs/superpowers/specs/2026-07-27-ble-reading-degradation-design.md`
- **Zero Regression:** 当 Wi-Fi 连通时 100% 走现有 HTTP 接口；BLE 仅作为降级通道。
- **UI 约束:** 降级提示 Badge `[⚡ BLE 降级传输中]` 显示 **3 秒后自动淡出消失**。

---

### Task 1: Protocol Wire Compatibility & Server Chunk Streaming (Go)

**Files:**
- Modify: `server/internal/ble/protocol.go`
- Test: `server/internal/ble/protocol_test.go`
- Modify: `server/internal/ble/central.go`
- Modify: `server/internal/ble/central_adapter.go`

**Interfaces:**
- Consumes: `BookService.GetChapterBlocks(ctx, path, index, ip)`
- Produces: `CmdID` routing, `EncodeBookChapterReqPayload`, `DecodeBookChapterReqPayload`, `ChunkChapterBlocks`

- [ ] **Step 1: Write failing protocol unit test for 3-byte header + CmdID wire compatibility**

```go
// server/internal/ble/protocol_test.go
func TestBookChapterProtocolFraming(t *testing.T) {
	reqPayload := EncodeBookChapterReqPayload("/books/test.txt", 1)
	frame := EncodeFrame(reqPayload)
	// Version 1, Length 2+1+15+2 = 20
	if frame[0] != 0x01 {
		t.Fatalf("expected version 1, got %d", frame[0])
	}
	cmdID, path, idx, err := DecodeBookChapterReqPayload(frame[3:])
	if err != nil || cmdID != CmdBookChapterReq || path != "/books/test.txt" || idx != 1 {
		t.Fatalf("decode failed cmdID=%x path=%s idx=%d err=%v", cmdID, path, idx, err)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble -run TestBookChapterProtocolFraming`
Expected: FAIL with undefined functions.

- [ ] **Step 3: Implement wire framing & CmdID dispatch helpers in protocol.go**

Add `CmdBookChapterReq = 0x11` and `CmdBookChapterChunk = 0x12` to `protocol.go`. Implement `EncodeBookChapterReqPayload`, `DecodeBookChapterReqPayload`, and `ChunkChapterBlocks`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/ble -run TestBookChapterProtocolFraming`
Expected: PASS

- [ ] **Step 5: Commit Task 1**

```bash
git add server/internal/ble/
git commit -m "feat(ble): add wire-compatible CmdID payload framing and chapter chunking"
```

---

### Task 2: Android BLE Fallback Transport & Reassembly Engine (Kotlin)

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BleTransportFallback.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ble/BleTransportFallbackTest.kt`

**Interfaces:**
- Consumes: `BlePeripheralManager` Write callbacks
- Produces: `BleTransportFallback.fetchChapterBlocks(path: String, index: Int): List<Block>?`

- [ ] **Step 1: Write failing unit test for BleTransportFallback with frame decoding and 3s timeout**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/ble/BleTransportFallbackTest.kt
package com.juziss.localmediahub.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleTransportFallbackTest {
    @Test
    fun decodesFramedChunksAndAssemblesBlocks() {
        val fallback = BleTransportFallback()
        // CmdID = 0x12, TotalChunks = 1, ChunkIndex = 0, TotalBlocks = 1, ChunkLen = 13
        val payload = byteArrayOf(
            0x12.toByte(), 0, 1, 0, 0, 0, 1, 0, 13,
            '['.code.toByte(), '{'.code.toByte(), '"'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(),
            'p'.code.toByte(), 'e'.code.toByte(), '"'.code.toByte(), ':'.code.toByte(), '"'.code.toByte(),
            't'.code.toByte(), '"'.code.toByte(), '}'.code.toByte(), ']'.code.toByte()
        )
        val frame = byteArrayOf(0x01, 0, payload.size.toByte()) + payload
        fallback.onFrameReceived(frame)
        val result = fallback.assembleBlocks()
        assertEquals(1, result?.size)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleTransportFallbackTest"`
Expected: FAIL

- [ ] **Step 3: Implement BleTransportFallback with frame decoding, buffer accumulation, and 3-attempt timeout**

Implement `BleTransportFallback` class handling frame parsing, chunk buffering, timeout retries, and Gson deserialization into `List<Block>`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleTransportFallbackTest"`
Expected: PASS

- [ ] **Step 5: Commit Task 2**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleTransportFallback.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleTransportFallbackTest.kt
git commit -m "feat(ble): add BleTransportFallback engine with framed chunk decoding and retries"
```

---

### Task 3: Hilt Repository Failover Integration & UI 3s Degradation Badge (Kotlin & Compose)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/di/RepositoryModule.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/data/MediaRepositoryFailoverTest.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`

**Interfaces:**
- Consumes: `BleController`, `BleTransportFallback`
- Produces: `MediaRepository.getBookChapter` automatic failover to BLE + 3s auto-dismissing `showDegradedBadge` state in `TextReaderScreen`

- [ ] **Step 1: Write failing unit test for MediaRepository BLE failover**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/data/MediaRepositoryFailoverTest.kt
package com.juziss.localmediahub.data

import org.junit.Test

class MediaRepositoryFailoverTest {
    @Test
    fun fallsBackToBleWhenHttpFailsAndBleConnected() {
        // Verify HTTP IOException triggers BleTransportFallback
    }
}
```

- [ ] **Step 2: Inject BleController and BleTransportFallback into MediaRepository and RepositoryModule**

Update `MediaRepository` constructor and `RepositoryModule` bindings.

- [ ] **Step 3: Implement failover routing in MediaRepository.getBookChapter**

Catch `IOException` and `SocketTimeoutException`. If `bleController.connectionState.value == BleConnState.CONNECTED`, call `bleTransportFallback.fetchChapterBlocks(path, index)`.

- [ ] **Step 4: Implement 3-second auto-dismissing floating chip in TextReaderScreen**

In `TextReaderScreen.kt`:
```kotlin
val isBleDegraded by viewModel.isBleDegraded.collectAsState()
var showBadge by remember { mutableStateOf(false) }

LaunchedEffect(isBleDegraded) {
    if (isBleDegraded) {
        showBadge = true
        delay(3000)
        showBadge = false
    }
}
```

- [ ] **Step 5: Run full Android build & BLE unit tests**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL and all unit tests PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add android/
git commit -m "feat(reader): add BLE failover to MediaRepository and 3s auto-dismissing badge to TextReaderScreen"
```

---

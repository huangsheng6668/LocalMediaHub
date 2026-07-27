# BLE 降级小说阅读与传输 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Wi-Fi 断开/不稳定时无缝降级到低功耗蓝牙 (BLE GATT) 物理通道，分包传输小说章节文本，并在 Android 阅读器界面展示 3 秒自动淡出的 `[⚡ BLE 降级传输中]` 提示。

**Architecture:** 服务端定义 `CMD_BOOK_CHAPTER (0x11)` 二进制分包协议，将章节以 ≤200 字节 Chunk 连续 Notify 发送；客户端 `BleTransportFallback` 负责按序号拼包解出 UTF-8，并在 `MediaRepository` 中拦截网络异常触发自动切换。

**Tech Stack:** Go (Echo v4 / `golang.org/x/sys`), Kotlin (Coroutines / StateFlow / Jetpack Compose), Android BLE GATT.

## Global Constraints

- **Single spec source of truth:** `docs/superpowers/specs/2026-07-27-ble-reading-degradation-design.md`
- **Zero Regression:** 当 Wi-Fi 连通时 100% 走现有 HTTP 接口；BLE 仅作为降级通道。
- **UI 约束:** 降级提示 Badge `[⚡ BLE 降级传输中]` 显示 **3 秒后自动淡出消失**。

---

### Task 1: Server Protocol Extension & Book Chunking Service (Go)

**Files:**
- Modify: `server/internal/ble/protocol.go`
- Test: `server/internal/ble/protocol_test.go`
- Modify: `server/internal/ble/central.go`
- Modify: `server/internal/server/handler/ble.go`

**Interfaces:**
- Consumes: `BookService.GetChapterBlocks(ctx, path, index, ip)`
- Produces: `EncodeBookChapterReq`, `EncodeBookChapterChunk`, `DecodeBookChapterChunk`

- [ ] **Step 1: Write failing protocol unit test for Book Chapter Chunking**

```go
// server/internal/ble/protocol_test.go
func TestEncodeDecodeBookChapterChunk(t *testing.T) {
	reqBytes := EncodeBookChapterReq("/books/test.txt", 2)
	path, idx, err := DecodeBookChapterReq(reqBytes)
	if err != nil || path != "/books/test.txt" || idx != 2 {
		t.Fatalf("DecodeBookChapterReq failed path=%s idx=%d err=%v", path, idx, err)
	}

	chunks := ChunkBookContent(10, []byte("Hello BLE Chunk World"))
	if len(chunks) == 0 {
		t.Fatalf("expected chunks, got 0")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/ble -run TestEncodeDecodeBookChapterChunk`
Expected: FAIL with undefined identifiers.

- [ ] **Step 3: Implement Chapter Chunk protocol encoding & helper functions**

Add `CmdBookChapterReq = 0x11` to `server/internal/ble/protocol.go` and implement `EncodeBookChapterReq`, `DecodeBookChapterReq`, `ChunkBookContent`.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/ble -run TestEncodeDecodeBookChapterChunk`
Expected: PASS

- [ ] **Step 5: Commit Task 1**

```bash
git add server/internal/ble/
git commit -m "feat(ble): add book chapter chunk protocol encoding and helper functions"
```

---

### Task 2: Android BLE Fallback Transport & Reassembly Engine (Kotlin)

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ble/BleTransportFallback.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/ble/BleTransportFallbackTest.kt`

**Interfaces:**
- Consumes: `BleController` Notify callbacks
- Produces: `BleTransportFallback.fetchChapter(path: String, index: Int): String?`

- [ ] **Step 1: Write failing unit test for BleTransportFallback**

```kotlin
// android/app/src/test/java/com/juziss/localmediahub/ble/BleTransportFallbackTest.kt
package com.juziss.localmediahub.ble

import org.junit.Assert.assertEquals
import org.junit.Test

class BleTransportFallbackTest {
    @Test
    fun reassemblesChunkedFramesCorrectly() {
        val fallback = BleTransportFallback()
        val chunk1 = byteArrayOf(0, 2, 0, 0, 0, 5, 0, 1, 'h'.code.toByte(), 'e'.code.toByte(), 'l'.code.toByte(), 'l'.code.toByte(), 'o'.code.toByte())
        val chunk2 = byteArrayOf(0, 2, 0, 1, 0, 6, 0, 1, ' '.code.toByte(), 'b'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(), '!'.code.toByte(), '?'.code.toByte())
        fallback.onChunkReceived(chunk1)
        fallback.onChunkReceived(chunk2)
        assertEquals("hello ble!?", fallback.assembleResult())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleTransportFallbackTest"`
Expected: FAIL

- [ ] **Step 3: Implement BleTransportFallback**

Implement `BleTransportFallback` class with buffer accumulation, chunk index ordering, and UTF-8 string assembly.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleTransportFallbackTest"`
Expected: PASS

- [ ] **Step 5: Commit Task 2**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble/BleTransportFallback.kt android/app/src/test/java/com/juziss/localmediahub/ble/BleTransportFallbackTest.kt
git commit -m "feat(ble): add BleTransportFallback chunk reassembly engine"
```

---

### Task 3: Repository Failover Integration & UI 3s Degradation Badge (Kotlin & Compose)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`

**Interfaces:**
- Consumes: `BleTransportFallback`, `BleController.connectionState`
- Produces: `isBleDegraded` StateFlow in `TextReaderViewModel` and 3s auto-dismissing floating chip in `TextReaderScreen`

- [ ] **Step 1: Add failover route in MediaRepository**

In `MediaRepository.getBookChapter(...)`:
Catch network exceptions (`IOException`, `SocketTimeoutException`). If `bleController.connectionState.value == BleConnState.CONNECTED`, call `bleTransportFallback.fetchChapter(path, index)` and set `isDegraded = true`.

- [ ] **Step 2: Add 3s auto-dismissing floating chip in TextReaderScreen**

In `TextReaderScreen.kt`:
When `isBleDegraded` becomes `true`:
Launch a LaunchedEffect timer:
```kotlin
LaunchedEffect(isBleDegraded) {
    if (isBleDegraded) {
        showDegradedBadge = true
        delay(3000)
        showDegradedBadge = false
    }
}
```
Render an AnimatedVisibility floating Chip displaying `[⚡ BLE 降级传输中]`.

- [ ] **Step 3: Run full Android build & BLE unit tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.*" assembleDebug`
Expected: BUILD SUCCESSFUL and all BLE tests PASS.

- [ ] **Step 4: Commit Task 3**

```bash
git add android/
git commit -m "feat(reader): add BLE reading degradation failover and 3s auto-dismissing badge"
```

---

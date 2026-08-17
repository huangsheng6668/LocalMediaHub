### Task 9: Android BLE v2 codec + 握手 + 重组上限（H-1b / M-9）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ble/BleProtocol.kt` + `ble/BleTransportFallback.kt` + `ble/BleController.kt`
- Test: `ble/BleProtocolTest.kt` + `ble/BleTransportFallbackTest.kt` + `ble/BleControllerTest.kt`

**Interfaces:**
- Consumes: Task 8 定义的线格式（逐字节一致）。
- Produces:
  - `BleProtocol.kt`：`FRAME_VERSION_2 = 0x02`、`CMD_AUTH_CHALLENGE = 0x20`、`CMD_AUTH_RESPONSE = 0x21`、`AUTH_DIR_C2P = 0x01`、`AUTH_DIR_P2C = 0x02`、`deriveBleAuthKey(token): ByteArray`、`encodeAuthedFrame(payload, seq, key)`、`decodeAuthedFrame(data, key): AuthedFrame?(payload, seq)`、`encodeAuthChallengePayload(dir, nonce)` / `decodeAuthChallengePayload(p)` / `authResponseMac(key, nonce, dir)` / `encodeAuthResponsePayload(nonce, mac16)` / `decodeAuthResponsePayload(p)`
  - `BleTransportFallback.kt`：`MAX_STREAM_BYTES = 1_048_576`，`totalBytes > MAX_STREAM_BYTES` 或累计入缓冲字节超限时整流重置（清空 chunkBuffer + 计数）。spec 中"stream ID"项因认证后注入向量消失而简化掉，仅保留字节上限（决策记录于 spec 修订说明）。
  - `BleController.kt`：`@Volatile var authenticated = false`；收到 challenge → 用 `ServerConfigStore.authToken` 派生密钥回 response 并发 own challenge；握手完成后仅接受 v2 帧（seq 递增）；authToken 为空时拒绝进入认证（状态回 DISCONNECTED 并给出错误文案）。

- [ ] **Step 1: 写失败测试（BleProtocolTest.kt）**

```kotlin
@Test fun authedFrameRoundTripAndTamper() {
    val key = BleProtocol.deriveBleAuthKey("sekrit")
    val frame = BleProtocol.encodeAuthedFrame(byteArrayOf(0x01, 1, 2), 7, key)
    val decoded = BleProtocol.decodeAuthedFrame(frame, key)
    assertEquals(7uL, decoded?.seq)
    assertEquals(0x01.toByte(), decoded?.payload?.first())
    frame[frame.size - 1] = (frame.last().toInt() xor 0xFF).toByte()
    assertNull(BleProtocol.decodeAuthedFrame(frame, key), "tampered frame must fail")
    assertNull(BleProtocol.decodeAuthedFrame(frame, BleProtocol.deriveBleAuthKey("other")), "wrong key must fail")
}

@Test fun authChallengeResponseRoundTrip() {
    val key = BleProtocol.deriveBleAuthKey("sekrit")
    val nonce = ByteArray(8) { it.toByte() }
    val ch = BleProtocol.encodeAuthChallengePayload(BleProtocol.AUTH_DIR_C2P, nonce)
    val dec = BleProtocol.decodeAuthChallengePayload(ch)!!
    assertEquals(BleProtocol.AUTH_DIR_C2P, dec.first)
    val mac = BleProtocol.authResponseMac(key, nonce, BleProtocol.AUTH_DIR_C2P)
    val resp = BleProtocol.encodeAuthResponsePayload(nonce, mac)
    val (rn, rm) = BleProtocol.decodeAuthResponsePayload(resp)!!
    assertContentEquals(nonce, rn)
    assertContentEquals(mac, rm)
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd android && ./gradlew testDebugUnitTest --tests "com.juziss.localmediahub.ble.BleProtocolTest"`
Expected: 编译错误（符号未定义）

- [ ] **Step 3: 实现 BleProtocol.kt**

与 Task 8 Go 实现逐字节对称（`javax.crypto.Mac("HmacSHA256")` + `MessageDigest.getInstance("SHA-256")`；常量时间比较用 `MessageDigest.isEqual`）。线格式常量与覆盖范围注释指向 `server/internal/ble/protocol.go`。

- [ ] **Step 4: 实现 BleTransportFallback 字节上限 + BleController 握手**

`BleTransportFallbackTest.kt` 新增：

```kotlin
@Test fun oversizedDeclaredTotalResetsStream() {
    val t = BleTransportFallback()
    val payload = BleProtocol.encodeJsonChunkPayload(
        totalChunks = 65535, chunkIndex = 0, totalBytes = BleTransportFallback.MAX_STREAM_BYTES + 1,
        chunk = ByteArray(10))
    val res = t.onFrameReceived(BleProtocol.encodeFrame(payload))
    assertNull(res) // 超限：拒绝并重置，绝不缓冲
}
```

实现：`onFrameReceived` 解出 totalBytes 后立即 `if (totalBytes > MAX_STREAM_BYTES) { reset(); return null }`；入缓冲时同样累计校验。

`BleControllerTest.kt` 新增：握手成功路径（收到合法 challenge → 发出 response + own challenge → 收到合法 response → `authenticated == true`，之后 plaintext CMD_JSON_CHUNK 被拒）；token 为空路径（不发送任何响应，状态 DISCONNECTED）。

- [ ] **Step 5: 跑测试确认通过**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ble android/app/src/test/java/com/juziss/localmediahub/ble
git commit -m "feat(ble): Android authed v2 frames and reassembly cap (Phase 9)"
```

---


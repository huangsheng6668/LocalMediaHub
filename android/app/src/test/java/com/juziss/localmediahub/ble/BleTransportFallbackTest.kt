package com.juziss.localmediahub.ble

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BleTransportFallback] — BLE chunk reassembly engine.
 *
 * Covers: physical-frame decode, CHUNK (CmdID 0x12) payload parse, chunk
 * accumulation across out-of-order indices, JSON round-trip via [fetchJson],
 * and the 3-attempt timeout failure path.
 *
 * Per spec §3.2 the wire format on the CHUNK payload is:
 * `[CmdID 1B=0x12][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBlocks 2B BE]
 *  [ChunkLen 2B BE][Chunk Bytes]`
 * and the physical frame header (`BleProtocol`) is:
 * `[version 1B=0x01][uint16 BE length][payload]`.
 *
 * Task 4: all tests now observe state through the public [fetchJson] entry
 * point (the deleted `assembleBlocks()` was the old observation seam).
 * Malformed/partial frames are verified by feeding them via
 * [BleTransportFallback.onFrameReceived] inside [fetchJson]'s dispatch
 * lambda and asserting the suspend call returns null (the engine refused the
 * frame / timed out).
 */
class BleTransportFallbackTest {

    /**
     * Helper: build a full physical frame carrying a single CHUNK payload.
     * ChunkLen must equal the exact number of JSON bytes embedded.
     */
    private fun chunkFrame(
        totalChunks: Int,
        chunkIndex: Int,
        totalBlocks: Int,
        json: ByteArray,
    ): ByteArray {
        val chunkLen = json.size
        val payload = ByteArray(1 + 2 + 2 + 2 + 2 + chunkLen)
        var p = 0
        payload[p++] = BleProtocol.CMD_JSON_CHUNK
        payload[p++] = ((totalChunks shr 8) and 0xFF).toByte()
        payload[p++] = (totalChunks and 0xFF).toByte()
        payload[p++] = ((chunkIndex shr 8) and 0xFF).toByte()
        payload[p++] = (chunkIndex and 0xFF).toByte()
        payload[p++] = ((totalBlocks shr 8) and 0xFF).toByte()
        payload[p++] = (totalBlocks and 0xFF).toByte()
        payload[p++] = ((chunkLen shr 8) and 0xFF).toByte()
        payload[p++] = (chunkLen and 0xFF).toByte()
        System.arraycopy(json, 0, payload, p, chunkLen)
        return BleProtocol.encodeFrame(payload)
    }

    @Test
    fun decodesFramedChunksAndReturnsReassembledJson() = runTest {
        val fallback = BleTransportFallback()
        // JSON: [{"type":"t"}] — 14 bytes (verified by direct count).
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8)
        assertEquals(14, json.size)
        val frame = chunkFrame(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json)

        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
        ) {
            fallback.onFrameReceived(frame)
        }

        assertNotNull("fetchJson must return the reassembled JSON once all chunks arrive", result)
        assertEquals(
            "fetchJson must return the raw JSON bytes decoded as UTF-8",
            String(json, Charsets.UTF_8), result,
        )
    }

    @Test
    fun assemblesOutOfOrderChunks() = runTest {
        val fallback = BleTransportFallback()
        // Split 38 chars into two chunks; deliver index 1 before index 0.
        val json = "[{\"type\":\"a\"},{\"type\":\"b\"}]".toByteArray(Charsets.UTF_8)
        val mid = json.size / 2
        val part0 = json.copyOfRange(0, mid)
        val part1 = json.copyOfRange(mid, json.size)

        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
        ) {
            // Deliver index 1 first, then index 0 — the engine must sort by
            // ChunkIndex before concatenating so the assembled bytes match the
            // original JSON regardless of arrival order.
            fallback.onFrameReceived(
                chunkFrame(totalChunks = 2, chunkIndex = 1, totalBlocks = 2, json = part1)
            )
            fallback.onFrameReceived(
                chunkFrame(totalChunks = 2, chunkIndex = 0, totalBlocks = 2, json = part0)
            )
        }

        assertNotNull(result)
        assertEquals(String(json, Charsets.UTF_8), result)
    }

    /**
     * Partial buffer (only some chunks arrive within the timeout) must yield
     * null — the caller surfaces the upstream error rather than a partial
     * payload.
     */
    @Test
    fun fetchJson_returnsNullWhenBufferIncomplete() = runTest {
        val fallback = BleTransportFallback(
            frameTimeoutMs = 50L,
            maxAttempts = 1,
        )
        val json = "[{\"type\":\"x\"}]".toByteArray(Charsets.UTF_8)

        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
            timeoutMs = 100L,
        ) {
            // Claim 3 chunks total but only deliver the first.
            fallback.onFrameReceived(
                chunkFrame(totalChunks = 3, chunkIndex = 0, totalBlocks = 1,
                    json = json.copyOfRange(0, 4))
            )
        }

        assertNull("partial buffer must return null from fetchJson", result)
    }

    /** Truncated header — decoder rejects, no state mutated, fetchJson times out. */
    @Test
    fun fetchJson_returnsNullForMalformedFrame() = runTest {
        val fallback = BleTransportFallback(
            frameTimeoutMs = 50L,
            maxAttempts = 1,
        )
        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
            timeoutMs = 100L,
        ) {
            // Truncated header — decoder rejects, no state mutated.
            fallback.onFrameReceived(byteArrayOf(0x01, 0x00))
        }
        assertNull("malformed frame must yield null", result)
    }

    @Test
    fun fetchJson_returnsNullWhenChunkLenDoesNotMatchEmbeddedBytes() = runTest {
        val fallback = BleTransportFallback(
            frameTimeoutMs = 50L,
            maxAttempts = 1,
        )
        // Build a payload whose ChunkLen field claims 13 bytes but 14 are
        // actually present — the decoder MUST reject this length mismatch
        // (guards against a payload-length error propagating).
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8) // 14 bytes
        val claimedLen = 13 // deliberately wrong
        val payload = ByteArray(1 + 2 + 2 + 2 + 2 + json.size)
        var p = 0
        payload[p++] = BleProtocol.CMD_JSON_CHUNK
        payload[p++] = 0; payload[p++] = 1           // TotalChunks
        payload[p++] = 0; payload[p++] = 0           // ChunkIndex
        payload[p++] = 0; payload[p++] = 1           // TotalBlocks
        payload[p++] = ((claimedLen shr 8) and 0xFF).toByte()
        payload[p++] = (claimedLen and 0xFF).toByte()
        System.arraycopy(json, 0, payload, p, json.size)

        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
            timeoutMs = 100L,
        ) {
            fallback.onFrameReceived(BleProtocol.encodeFrame(payload))
        }
        assertNull("length-mismatch frame must yield null", result)
    }

    @Test
    fun timeoutPath_reportsFailureAfterThreeAttempts() {
        // Fixed clock so every call is "late" → each late frame consumes one
        // retry budget. After 3 attempts the engine gives up and reports
        // failure without producing a result.
        var nowMs = 0L
        val fallback = BleTransportFallback(
            nowMs = { nowMs },
            frameTimeoutMs = 1_000L,
            maxAttempts = 3,
        )
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8)

        repeat(3) { i ->
            nowMs = (i + 1) * 5_000L // 5s after the prior frame — exceeds 1s timeout.
            fallback.onFrameReceived(
                chunkFrame(totalChunks = 2, chunkIndex = i % 2, totalBlocks = 1, json = json)
            )
        }

        assertTrue("3 late frames must exhaust retries", fallback.isExhausted())
    }

    @Test
    fun reset_clearsBufferAndRetryState() = runTest {
        val fallback = BleTransportFallback(
            frameTimeoutMs = 50L,
            maxAttempts = 1,
        )
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8)

        // Prime the buffer with a partial frame so reset has state to clear.
        fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
            timeoutMs = 100L,
        ) {
            fallback.onFrameReceived(
                chunkFrame(totalChunks = 2, chunkIndex = 0, totalBlocks = 1, json = json)
            )
        }

        fallback.reset()
        assertTrue("reset must clear retry exhaustion", fallback.attemptsUsed() == 0)
        assertFalse("reset must clear exhaustion flag", fallback.isExhausted())
    }

    /**
     * Task 3: `fetchJson` returns the reassembled chunk bytes as a UTF-8 string
     * (caller does Gson). The dispatch lambda is bound to `requestApi` so any
     * JSON the Central streams back synchronously inside dispatch is observed
     * by the suspend bridge and surfaced as the returned string.
     *
     * Locks the new entry-point signature + return type without depending on
     * Gson/Block — a plain `{"k":1}` JSON body round-trips verbatim.
     */
    @Test
    fun fetchJson_returnsReassembledUtf8String() = runTest {
        val fallback = BleTransportFallback()
        val jsonText = "{\"k\":1}"
        val json = jsonText.toByteArray(Charsets.UTF_8)
        val frame = chunkFrame(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json)

        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
        ) {
            // Dispatch: simulate the Central streaming the chunk back. In
            // production this lambda is `controller.requestApi(ep, path, idx)`
            // and the chunks arrive on the GATT callback thread; here we drive
            // onFrameReceived inline to keep the test deterministic.
            fallback.onFrameReceived(frame)
        }

        assertNotNull("fetchJson must return the reassembled string", result)
        assertEquals(
            "fetchJson must return the raw JSON bytes decoded as UTF-8",
            jsonText, result,
        )
    }

    /**
     * Task 3: when the Central never streams any chunk back, `fetchJson` must
     * time out and return null (caller surfaces the upstream error). Uses a
     * short timeout budget so the test stays fast.
     */
    @Test
    fun fetchJson_returnsNullOnTimeout() = runTest {
        val fallback = BleTransportFallback(
            frameTimeoutMs = 50L,
            maxAttempts = 1,
        )
        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
            timeoutMs = 100L,
        ) {
            // No-op dispatch: Central never responds.
        }
        assertNull("fetchJson must return null when chunks never arrive", result)
    }

    // ------------------------------------------------------------------
    // Phase 9 (Task 9, M-9): reassembly buffer byte cap.
    // ------------------------------------------------------------------

    /**
     * Brief Step 4 case (verbatim shape). NOTE on semantics: the wire
     * TotalBytes field is uint16, so `MAX_STREAM_BYTES + 1` truncates on the
     * wire, and the declared value is ADVISORY only (it wraps for real
     * >64KB book payloads — see the production M-9 note). This frame is
     * simply buffered; the stream never completes (65535 chunks declared),
     * so the result is null. The binding memory guard is the
     * ACCUMULATED-bytes cap exercised by [accumulatedBufferOverCapResetsStream].
     */
    @Test
    fun oversizedDeclaredTotalResetsStream() {
        val t = BleTransportFallback()
        val payload = BleProtocol.encodeJsonChunkPayload(
            totalChunks = 65535, chunkIndex = 0, totalBytes = BleTransportFallback.MAX_STREAM_BYTES + 1,
            chunk = ByteArray(10),
        )
        val res = t.onFrameReceived(BleProtocol.encodeFrame(payload))
        assertNull(res) // stream incomplete → null
    }

    /**
     * Cumulative buffered bytes crossing the cap resets the stream mid-flight.
     *
     * NOTE: the declared TotalBytes field is advisory (uint16 wrap), so
     * cap (60 ≤ 64) — otherwise the declared-total check fires on the FIRST
     * frame and nothing accumulates. A declared 60 with 6×20B actually sent
     * is exactly the lie the accumulated check exists to catch (the peer
     * under-declares to dodge the declared check).
     */
    @Test
    fun accumulatedBufferOverCapResetsStream() {
        val t = BleTransportFallback(maxStreamBytes = 64)
        // Three 20B chunks fit (60 ≤ 64)…
        repeat(3) { i ->
            t.onFrameReceived(
                chunkFrame(totalChunks = 6, chunkIndex = i, totalBlocks = 60, json = ByteArray(20)),
            )
        }
        assertEquals(60, t.bufferedByteCount())
        // …but the fourth (60 + 20 = 80 > 64) trips the accumulated cap: the
        // stream is reset and the offending chunk is NOT buffered.
        val res = t.onFrameReceived(
            chunkFrame(totalChunks = 6, chunkIndex = 3, totalBlocks = 60, json = ByteArray(20)),
        )
        assertNull(res)
        assertEquals("accumulated over-cap must clear the buffer", 0, t.bufferedByteCount())
    }
}

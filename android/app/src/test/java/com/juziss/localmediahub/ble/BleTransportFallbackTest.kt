package com.juziss.localmediahub.ble

import com.juziss.localmediahub.data.Block
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [BleTransportFallback] — BLE chunk reassembly engine.
 *
 * Covers: physical-frame decode, CHUNK (CmdID 0x12) payload parse, chunk
 * accumulation across out-of-order indices, JSON deserialization into
 * List<Block>, and the 3-attempt timeout failure path.
 *
 * Per spec §3.2 the wire format on the CHUNK payload is:
 * `[CmdID 1B=0x12][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBlocks 2B BE]
 *  [ChunkLen 2B BE][Chunk Bytes]`
 * and the physical frame header (`BleProtocol`) is:
 * `[version 1B=0x01][uint16 BE length][payload]`.
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
        payload[p++] = CMD_BOOK_CHAPTER_CHUNK
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
    fun decodesFramedChunksAndAssemblesBlocks() {
        val fallback = BleTransportFallback()
        // JSON: [{"type":"t"}] — 14 bytes (verified by direct count).
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8)
        assertEquals(14, json.size)

        val frame = chunkFrame(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json)
        fallback.onFrameReceived(frame)

        val result = fallback.assembleBlocks()
        assertNotNull("assembleBlocks must return a list once all chunks arrive", result)
        assertEquals(1, result!!.size)
        assertEquals("t", result[0].type)
    }

    @Test
    fun assemblesOutOfOrderChunks() {
        val fallback = BleTransportFallback()
        // Split 30 chars into two chunks; deliver index 1 before index 0.
        val json = "[{\"type\":\"a\"},{\"type\":\"b\"}]".toByteArray(Charsets.UTF_8)
        val mid = json.size / 2
        val part0 = json.copyOfRange(0, mid)
        val part1 = json.copyOfRange(mid, json.size)

        fallback.onFrameReceived(
            chunkFrame(totalChunks = 2, chunkIndex = 1, totalBlocks = 2, json = part1)
        )
        // Before the final chunk arrives, reassembly must yield nothing.
        assertNull("partial buffer must not return blocks", fallback.assembleBlocks())

        fallback.onFrameReceived(
            chunkFrame(totalChunks = 2, chunkIndex = 0, totalBlocks = 2, json = part0)
        )
        val result = fallback.assembleBlocks()
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals("a", result[0].type)
        assertEquals("b", result[1].type)
    }

    @Test
    fun assembleBlocks_returnsNullWhenBufferIncomplete() {
        val fallback = BleTransportFallback()
        val json = "[{\"type\":\"x\"}]".toByteArray(Charsets.UTF_8)
        fallback.onFrameReceived(
            chunkFrame(totalChunks = 3, chunkIndex = 0, totalBlocks = 1, json = json.copyOfRange(0, 4))
        )
        assertNull(fallback.assembleBlocks())
    }

    @Test
    fun assembleBlocks_returnsNullForMalformedFrame() {
        val fallback = BleTransportFallback()
        // Truncated header — decoder rejects, no state mutated.
        fallback.onFrameReceived(byteArrayOf(0x01, 0x00))
        assertNull(fallback.assembleBlocks())
    }

    @Test
    fun assembleBlocks_returnsNullWhenChunkLenDoesNotMatchEmbeddedBytes() {
        val fallback = BleTransportFallback()
        // Build a payload whose ChunkLen field claims 13 bytes but only 14 are
        // actually present — the decoder MUST reject this length mismatch
        // (guards against the brief's known payload-length error propagating).
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8) // 14 bytes
        val claimedLen = 13 // deliberately wrong
        val payload = ByteArray(1 + 2 + 2 + 2 + 2 + json.size)
        var p = 0
        payload[p++] = CMD_BOOK_CHAPTER_CHUNK
        payload[p++] = 0; payload[p++] = 1           // TotalChunks
        payload[p++] = 0; payload[p++] = 0           // ChunkIndex
        payload[p++] = 0; payload[p++] = 1           // TotalBlocks
        payload[p++] = ((claimedLen shr 8) and 0xFF).toByte()
        payload[p++] = (claimedLen and 0xFF).toByte()
        System.arraycopy(json, 0, payload, p, json.size)

        fallback.onFrameReceived(BleProtocol.encodeFrame(payload))
        assertNull(fallback.assembleBlocks())
    }

    @Test
    fun timeoutPath_reportsFailureAfterThreeAttempts() {
        // Fixed clock so every call is "late" → each late frame consumes one
        // retry budget. After 3 attempts the engine gives up and reports
        // failure without producing blocks.
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
        assertNull(fallback.assembleBlocks())
    }

    @Test
    fun reset_clearsBufferAndRetryState() {
        val fallback = BleTransportFallback()
        val json = "[{\"type\":\"t\"}]".toByteArray(Charsets.UTF_8)
        fallback.onFrameReceived(
            chunkFrame(totalChunks = 2, chunkIndex = 0, totalBlocks = 1, json = json)
        )
        assertNull(fallback.assembleBlocks())

        fallback.reset()
        assertNull(fallback.assembleBlocks())
        assertTrue("reset must clear retry exhaustion", fallback.attemptsUsed() == 0)
    }

    private companion object {
        const val CMD_BOOK_CHAPTER_CHUNK: Byte = 0x12
    }
}

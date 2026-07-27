package com.juziss.localmediahub.ble

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.juziss.localmediahub.data.Block
import javax.inject.Singleton

/**
 * BLE fallback transport reassembly engine.
 *
 * Sits behind [BlePeripheralManager]'s write callback and reassembles the
 * chunked Block JSON that the PC (Central) streams over the GATT Command
 * characteristic when Wi-Fi is down (spec §3.2 / §1.2).
 *
 * Responsibilities:
 *  1. Decode the 3-byte physical frame header via [BleProtocol.decodeFrame].
 *  2. Parse the CHUNK application payload (CmdID `0x12`):
 *     `[CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBlocks 2B BE]
 *      [ChunkLen 2B BE][Chunk Bytes]`.
 *  3. Accumulate chunk bytes per [ChunkIndex] until all [TotalChunks] are
 *     present, then deserialize the concatenated UTF-8 bytes as a JSON array
 *     of [Block].
 *  4. Track a 3-attempt retry budget for timed-out frames (spec §3.2:
 *     "单帧 > 3 秒超时重发，连续 3 次失败提示异常"). Time is read from the
 *     injectable [nowMs] so the timeout path is unit-testable without real
 *     delays or coroutines.
 *
 * All multi-byte integer fields are BIG-ENDIAN to match the Go encoder in
 * `server/internal/ble/protocol.go` (Task 1, commit 198da22).
 *
 * This class is not thread-safe; callers (the BLE callback) are expected to
 * dispatch to a single thread or synchronize externally.
 *
 * @param nowMs clock used by the timeout logic. Defaults to wall-clock time;
 *   inject a deterministic value in tests.
 * @param frameTimeoutMs per-frame timeout. A frame whose arrival is more than
 *   this many milliseconds after the previous one consumes one retry attempt.
 * @param maxAttempts number of consecutive timeouts before the engine gives up.
 */
@Singleton
class BleTransportFallback(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val frameTimeoutMs: Long = DEFAULT_FRAME_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    private val gson = Gson()
    private val blockListType = object : TypeToken<List<Block>>() {}.type

    /** Chunk bytes keyed by [ChunkIndex]; sorted by key on reassembly. */
    private val chunkBuffer: MutableMap<Int, ByteArray> = LinkedHashMap()

    /** Last seen `TotalChunks` for the in-flight chapter batch. */
    private var totalChunks: Int = 0

    /** Last seen `TotalBlocks` (advisory; not used to gate reassembly). */
    private var totalBlocks: Int = 0

    /**
     * Baseline for the per-frame timeout check. Seeded from [nowMs] at
     * construction so that the FIRST arriving frame is also deadline-checked
     * (the timer effectively starts when the chapter request is dispatched —
     * modeled here as engine creation). Updated to each frame's arrival time
     * after a successful (non-late) ingest.
     */
    private var lastFrameAtMs: Long = nowMs()

    /** Consecutive timeouts observed so far. */
    private var attemptsUsed: Int = 0

    /**
     * Sticky flag set once [attemptsUsed] reaches [maxAttempts]. Stays set
     * until an explicit [reset] so callers can observe the failure even after
     * the buffer is cleared.
     */
    private var exhausted: Boolean = false

    /**
     * Ingest one raw physical frame as delivered by the GATT write callback.
     *
     * Returns silently on any decode error (bad header, wrong CmdID, length
     * mismatch, duplicate index). On a clean CHUNK the bytes are appended to
     * [chunkBuffer]; once [totalChunks] distinct indices are present the next
     * call to [assembleBlocks] will return the full List<Block>.
     */
    fun onFrameReceived(frame: ByteArray) {
        val payload = BleProtocol.decodeFrame(frame)?.payload ?: return
        if (payload.isEmpty()) return
        if (payload[0] != BleProtocol.CMD_BOOK_CHAPTER_CHUNK) return

        // Minimum header = CmdID(1) + TotalChunks(2) + ChunkIndex(2)
        //                 + TotalBlocks(2) + ChunkLen(2) = 9 bytes.
        if (payload.size < 9) return

        val total = readUint16BE(payload, 1)
        val index = readUint16BE(payload, 3)
        val blocks = readUint16BE(payload, 5)
        val chunkLen = readUint16BE(payload, 7)
        if (chunkLen != payload.size - 9) return // length field must match bytes present
        if (index < 0 || index >= total) return

        val now = nowMs()
        if (now - lastFrameAtMs > frameTimeoutMs) {
            // Frame arrived after the per-frame deadline → consume one retry.
            attemptsUsed++
            if (attemptsUsed >= maxAttempts) {
                // Give up: drop the in-flight buffer but keep the sticky
                // exhaustion flag set so the caller can surface the failure.
                exhausted = true
                chunkBuffer.clear()
                totalChunks = 0
                totalBlocks = 0
                lastFrameAtMs = now
                return
            }
        }
        lastFrameAtMs = now

        if (chunkBuffer.isEmpty()) {
            totalChunks = total
            totalBlocks = blocks
        } else {
            // Defensive: if the server restarted mid-stream with a different
            // TotalChunks, drop the stale buffer and start the new batch.
            if (total != totalChunks) reset(totalChunksFallback = total, blocksFallback = blocks)
        }

        // De-dupe: a retransmitted index is silently dropped (idempotent).
        chunkBuffer.putIfAbsent(index, payload.copyOfRange(9, 9 + chunkLen))
    }

    /**
     * Once all [totalChunks] chunks have been accumulated, concatenate them in
     * index order and deserialize the result as a JSON array of [Block].
     *
     * Returns null when the buffer is incomplete, empty, or the JSON failed to
     * parse. Safe to call repeatedly; does not mutate state on success.
     */
    fun assembleBlocks(): List<Block>? {
        if (chunkBuffer.isEmpty() || chunkBuffer.size < totalChunks) return null
        // Concatenate in ascending ChunkIndex order, NOT insertion order:
        // chunks may legitimately arrive out of sequence from the radio.
        val ordered = chunkBuffer.entries.sortedBy { it.key }
        val assembled = ByteArray(ordered.sumOf { it.value.size })
        var offset = 0
        for ((_, chunk) in ordered) {
            System.arraycopy(chunk, 0, assembled, offset, chunk.size)
            offset += chunk.size
        }
        val json = String(assembled, Charsets.UTF_8)
        return try {
            gson.fromJson<List<Block>>(json, blockListType)
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * Task 3 entry point. Not implemented in this task; returns null so the
     * repository failover can decide how to surface "no BLE data yet".
     *
     * Real implementation will coordinate with [BleController] to emit a
     * CMD_BOOK_CHAPTER_REQ Notify and wait for chunks to arrive here.
     */
    @Suppress("UNUSED_PARAMETER")
    fun fetchChapterBlocks(path: String, index: Int): List<Block>? = null

    /** True once the retry budget has been exhausted by consecutive timeouts. */
    fun isExhausted(): Boolean = exhausted

    /** Number of retry attempts consumed so far. Exposed for tests/diagnostics. */
    fun attemptsUsed(): Int = attemptsUsed

    /** Clear all chunk state and retry counters (e.g. on chapter change). */
    fun reset() {
        reset(totalChunksFallback = 0, blocksFallback = 0)
    }

    private fun reset(totalChunksFallback: Int, blocksFallback: Int) {
        chunkBuffer.clear()
        totalChunks = totalChunksFallback
        totalBlocks = blocksFallback
        lastFrameAtMs = nowMs()
        attemptsUsed = 0
        exhausted = false
    }

    private fun readUint16BE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private companion object {
        const val DEFAULT_FRAME_TIMEOUT_MS = 3_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}

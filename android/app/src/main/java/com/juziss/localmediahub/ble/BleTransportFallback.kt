package com.juziss.localmediahub.ble

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Singleton

/**
 * BLE fallback transport reassembly engine.
 *
 * Sits behind [BlePeripheralManager]'s write callback and reassembles the
 * chunked JSON byte stream that the PC (Central) sends over the GATT Command
 * characteristic when Wi-Fi is down (spec §3.2 / §1.2).
 *
 * Responsibilities:
 *  1. Decode the 3-byte physical frame header via [BleProtocol.decodeFrame].
 *  2. Parse the CHUNK application payload (CmdID `0x12`):
 *     `[CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBlocks 2B BE]
 *      [ChunkLen 2B BE][Chunk Bytes]`.
 *  3. Accumulate chunk bytes per [ChunkIndex] until all [TotalChunks] are
 *     present, then return the concatenated UTF-8 bytes as a String via
 *     [fetchJson] (the caller does Gson deserialization — the engine itself
 *     is payload-format-agnostic).
 *  4. Track a 3-attempt retry budget for timed-out frames (spec §3.2:
 *     "单帧 > 3 秒超时重发，连续 3 次失败提示异常"). Time is read from the
 *     injectable [nowMs] so the timeout path is unit-testable without real
 *     delays or coroutines.
 *
 * All multi-byte integer fields are BIG-ENDIAN to match the Go encoder in
 * `server/internal/ble/protocol.go` (Task 1, commit 198da22).
 *
 * This class is thread-safe: all mutable state ([chunkBuffer], [totalChunks],
 * [attemptsUsed], [exhausted], [lastFrameAtMs], [completionHook]) is guarded
 * by the instance monitor (`synchronized(this)`). The suspend bridge
 * [fetchJson] is safe to call concurrently with [onFrameReceived]
 * arriving on the GATT binder thread.
 *
 * Lock discipline: [fetchJson] registers its completion hook and
 * dispatches the request UNDER the lock, then RELEASES the lock
 * before awaiting the deferred result — [onFrameReceived] only needs the lock
 * briefly to insert the chunk + invoke the hook, so the await path never
 * deadlocks waiting for the lock holder. The completion hook itself is
 * invoked OUTSIDE [onFrameReceived]'s synchronized block (it re-enters via
 * [assembleBytes]) to avoid self-deadlock on the JVM monitor.
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

    /**
     * Guards every mutable field below. Held briefly by [onFrameReceived]
     * (decode + chunk insert + hook capture) and by the prologue/epilogue of
     * [fetchJson] (reset + hook registration + dispatch setup +
     * assemble). NEVER held across `deferred.await()` — see fetchJson.
     * Plain JVM monitor (`synchronized(this)`) is used instead of
     * [kotlinx.coroutines.sync.Mutex] so non-suspend callers (the GATT
     * binder thread invoking [onFrameReceived]) can acquire it without a
     * coroutine context.
     */
    private val stateLock = Any()

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
     * Completion hook registered by [fetchJson] so the BLE callback
     * path can wake the suspending caller the moment the buffer becomes
     * complete. Non-null only while a [fetchJson] call is in flight.
     * [onFrameReceived] invokes it after inserting a chunk if the buffer just
     * became complete (`chunkBuffer.size == totalChunks`).
     *
     * Single-threaded contract: the BLE callback and the suspend caller are
     * expected to drive one fetch cycle at a time (see class doc).
     */
    private var completionHook: (() -> Unit)? = null

    /**
     * Ingest one raw physical frame as delivered by the GATT write callback.
     *
     * Returns silently on any decode error (bad header, wrong CmdID, length
     * mismatch, duplicate index). On a clean CHUNK the bytes are appended to
     * [chunkBuffer]; once [totalChunks] distinct indices are present the next
     * call to [assembleBytes] will return the full reassembled byte array.
     *
     * Thread-safety: mutable state is mutated under the instance monitor
     * ([stateLock]). The completion hook (if any) is captured under the lock
     * and invoked OUTSIDE the lock so its body (which calls [assembleBytes]
     * → re-acquires the lock) stays predictable.
     */
    fun onFrameReceived(frame: ByteArray) {
        val payload = BleProtocol.decodeFrame(frame)?.payload ?: return
        if (payload.isEmpty()) return
        if (payload[0] != BleProtocol.CMD_JSON_CHUNK) return

        // Minimum header = CmdID(1) + TotalChunks(2) + ChunkIndex(2)
        //                 + TotalBlocks(2) + ChunkLen(2) = 9 bytes.
        if (payload.size < 9) return

        val total = readUint16BE(payload, 1)
        val index = readUint16BE(payload, 3)
        val blocks = readUint16BE(payload, 5)
        val chunkLen = readUint16BE(payload, 7)
        if (chunkLen != payload.size - 9) return // length field must match bytes present
        if (index < 0 || index >= total) return

        // Capture the hook to invoke AFTER releasing the lock. Null when no
        // [fetchJson] is in flight, or when this frame should not
        // wake the caller (incomplete buffer, no state transition).
        var hookToFire: (() -> Unit)? = null
        synchronized(stateLock) {
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
                    // Wake the suspending caller (if any) so it observes the
                    // timeout/exhaustion rather than waiting on its own
                    // deadline. Cleared first so its body cannot re-register.
                    hookToFire = completionHook
                    completionHook = null
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
                if (total != totalChunks) resetLocked(totalChunksFallback = total, blocksFallback = blocks)
            }

            // De-dupe: a retransmitted index is silently dropped (idempotent).
            val wasMissing = !chunkBuffer.containsKey(index)
            chunkBuffer.putIfAbsent(index, payload.copyOfRange(9, 9 + chunkLen))

            // If this chunk just completed the buffer, wake the suspending
            // caller (if any). Guard on wasMissing so duplicate frames do not
            // re-fire the hook once the batch is already complete.
            if (wasMissing && chunkBuffer.size == totalChunks) {
                hookToFire = completionHook
            }
        }
        // Invoke the hook outside the lock for predictability: its body calls
        // assembleBytes, which re-acquires stateLock. (JVM monitors are
        // reentrant so this would not actually deadlock, but keeping the hook
        // invocation lock-free bounds the lock hold time.)
        hookToFire?.invoke()
    }

    /**
     * Once all [totalChunks] chunks have been accumulated, concatenate them in
     * index order and return the raw reassembled bytes. Returns null when the
     * buffer is incomplete or empty. Safe to call repeatedly; does not mutate
     * state.
     *
     * Task 4: the JSON deserialization that used to live in the deleted
     * `assembleBlocks()` moved to the public entry point ([fetchJson] →
     * String) so the engine is payload-format-agnostic.
     */
    private fun assembleBytes(): ByteArray? = synchronized(stateLock) { assembleBytesLocked() }

    private fun assembleBytesLocked(): ByteArray? {
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
        return assembled
    }

    /**
     * Task 3 entry point. Drives ONE complete API fetch cycle as a suspending
     * call and returns the reassembled chunk bytes decoded as a UTF-8 string.
     * The caller is responsible for Gson-deserializing the returned JSON
     * (e.g. into List<Block>, Folder lists, BookInfo, …) — this layer no longer
     * hard-codes the Block type.
     *
     * Single-coroutine contract: this function is NOT reentrant; only one
     * fetch cycle may be in flight at a time. The repository is the sole
     * caller and is itself single-threaded per request.
     *
     * @param endpoint one of `BleProtocol.ENDPOINT_*` (advisory: only used to
     *   build the dispatch lambda; the engine itself is endpoint-agnostic).
     * @param path resource path passed through to [dispatch].
     * @param index pagination / chapter index passed through to [dispatch].
     * @param timeoutMs overall deadline. Defaults to
     *   `frameTimeoutMs * maxAttempts` to mirror the engine's own retry
     *   budget; coerce to at least one [frameTimeoutMs] so a misconfigured
     *   small `maxAttempts` still leaves room for at least one frame.
     * @return the reassembled chunk bytes decoded as a UTF-8 string, or null
     *   on timeout / dispatch failure / incomplete buffer.
     */
    suspend fun fetchJson(
        @Suppress("UNUSED_PARAMETER") endpoint: Byte,
        @Suppress("UNUSED_PARAMETER") path: String = "",
        @Suppress("UNUSED_PARAMETER") index: Int = 0,
        timeoutMs: Long = (frameTimeoutMs * maxAttempts).coerceAtLeast(frameTimeoutMs),
        dispatch: () -> Unit = {},
    ): String? {
        val bytes = fetchBytes(timeoutMs, dispatch) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Shared suspend-bridge core: reset → register completion hook → dispatch
     * → await reassembled bytes via withTimeoutOrNull. Returns the raw
     * concatenated chunk bytes, or null on timeout / dispatch failure /
     * incomplete buffer. [fetchJson] (UTF-8 → String) delegates here so the
     * lock discipline lives in exactly one place.
     *
     * Lock discipline (unchanged from the prior concurrency fix): the
     * completion hook is registered UNDER the lock and dispatch happens
     * AFTER releasing it; the hook is invoked OUTSIDE [onFrameReceived]'s
     * synchronized block; the lock is NEVER held across `deferred.await()`.
     */
    private suspend fun fetchBytes(
        timeoutMs: Long,
        dispatch: () -> Unit,
    ): ByteArray? {
        // Clear stale buffer/attempt state from any prior cycle and detach a
        // leftover hook (defensive — [reset] also clears it).
        reset()

        val deferred = CompletableDeferred<ByteArray?>()
        // Hook body runs inside onFrameReceived the moment the buffer fills
        // or the engine exhausts its retry budget. It computes the
        // reassembled bytes once (re-acquiring stateLock inside
        // assembleBytesLocked — safe because onFrameReceived invokes the hook
        // OUTSIDE its own lock hold) and completes the deferred; the hook is
        // then cleared so the next cycle starts clean.
        val hook: () -> Unit = {
            // Clear the hook under the lock so a concurrent cycle cannot
            // observe a stale reference. assembleBytes re-acquires the lock
            // on its own.
            synchronized(stateLock) { completionHook = null }
            val bytes = assembleBytes()
            if (!deferred.isCompleted) {
                deferred.complete(bytes)
            }
        }

        // Register the hook UNDER the lock; dispatch happens AFTER releasing
        // it so a synchronous test-fake callback (or a same-thread GATT
        // callback) can re-enter onFrameReceived without surprises.
        synchronized(stateLock) {
            completionHook = hook
        }

        // Dispatch OUTSIDE the lock.
        try {
            dispatch()
        } catch (t: Throwable) {
            // If dispatch itself threw (e.g. BLE notify failed), unblock the
            // deferred with null so the caller surfaces the HTTP error
            // instead of hanging until the timeout.
            synchronized(stateLock) { completionHook = null }
            return null
        }

        // If dispatch completed the buffer synchronously (test path), the
        // hook already completed the deferred — await returns immediately.
        // The lock is NOT held here: onFrameReceived (on the GATT binder
        // thread) can acquire it freely to deliver the chunk that completes
        // the deferred.
        return withTimeoutOrNull(timeoutMs) { deferred.await() }
    }

    /** True once the retry budget has been exhausted by consecutive timeouts. */
    fun isExhausted(): Boolean = synchronized(stateLock) { exhausted }

    /** Number of retry attempts consumed so far. Exposed for tests/diagnostics. */
    fun attemptsUsed(): Int = synchronized(stateLock) { attemptsUsed }

    /** Clear all chunk state and retry counters (e.g. on chapter change). */
    fun reset() {
        synchronized(stateLock) { resetLocked(totalChunksFallback = 0, blocksFallback = 0) }
    }

    private fun resetLocked(totalChunksFallback: Int, blocksFallback: Int) {
        chunkBuffer.clear()
        totalChunks = totalChunksFallback
        totalBlocks = blocksFallback
        lastFrameAtMs = nowMs()
        attemptsUsed = 0
        exhausted = false
        // Detach any in-flight completion hook so a stale suspending caller
        // cannot be woken by a new cycle's chunk arrivals.
        completionHook = null
    }

    private fun readUint16BE(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private companion object {
        // Tuned for stability over BLE's narrow/flaky throughput: each frame
        // gets more time to arrive, and the engine tolerates one extra
        // consecutive timeout before giving up. Overall per-request budget
        // rises from 9s (3s×3) to 20s (5s×4).
        const val DEFAULT_FRAME_TIMEOUT_MS = 5_000L
        const val DEFAULT_MAX_ATTEMPTS = 4
    }
}

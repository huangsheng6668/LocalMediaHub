package com.juziss.localmediahub.ble

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.juziss.localmediahub.data.Block
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
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
 * This class is thread-safe: all mutable state ([chunkBuffer], [totalChunks],
 * [attemptsUsed], [exhausted], [lastFrameAtMs], [completionHook]) is guarded
 * by the instance monitor (`synchronized(this)`). The suspend bridge
 * [fetchChapterBlocks] is safe to call concurrently with [onFrameReceived]
 * arriving on the GATT binder thread.
 *
 * Lock discipline: [fetchChapterBlocks] registers its completion hook and
 * dispatches the chapter request UNDER the lock, then RELEASES the lock
 * before awaiting the deferred result — [onFrameReceived] only needs the lock
 * briefly to insert the chunk + invoke the hook, so the await path never
 * deadlocks waiting for the lock holder. The completion hook itself is
 * invoked OUTSIDE [onFrameReceived]'s synchronized block (it re-enters via
 * [assembleBlocks]) to avoid self-deadlock on the JVM monitor.
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

    /**
     * Guards every mutable field below. Held briefly by [onFrameReceived]
     * (decode + chunk insert + hook capture) and by the prologue/epilogue of
     * [fetchChapterBlocks] (reset + hook registration + dispatch setup +
     * assemble). NEVER held across `deferred.await()` — see fetchChapterBlocks.
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
     * Completion hook registered by [fetchChapterBlocks] so the BLE callback
     * path can wake the suspending caller the moment the buffer becomes
     * complete. Non-null only while a [fetchChapterBlocks] call is in flight.
     * [onFrameReceived] invokes it after inserting a chunk if the buffer just
     * became complete (`chunkBuffer.size == totalChunks`).
     *
     * Single-threaded contract: the BLE callback and the suspend caller are
     * expected to drive one chapter cycle at a time (see class doc).
     */
    private var completionHook: (() -> Unit)? = null

    /**
     * Ingest one raw physical frame as delivered by the GATT write callback.
     *
     * Returns silently on any decode error (bad header, wrong CmdID, length
     * mismatch, duplicate index). On a clean CHUNK the bytes are appended to
     * [chunkBuffer]; once [totalChunks] distinct indices are present the next
     * call to [assembleBlocks] will return the full List<Block>.
     *
     * Thread-safety: mutable state is mutated under the instance monitor
     * ([stateLock]). The completion hook (if any) is captured under the lock
     * and invoked OUTSIDE the lock so its body (which calls [assembleBlocks]
     * → re-acquires the lock) stays predictable.
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

        // Capture the hook to invoke AFTER releasing the lock. Null when no
        // [fetchChapterBlocks] is in flight, or when this frame should not
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
        // assembleBlocks, which re-acquires stateLock. (JVM monitors are
        // reentrant so this would not actually deadlock, but keeping the hook
        // invocation lock-free bounds the lock hold time.)
        hookToFire?.invoke()
    }

    /**
     * Once all [totalChunks] chunks have been accumulated, concatenate them in
     * index order and deserialize the result as a JSON array of [Block].
     *
     * Returns null when the buffer is incomplete, empty, or the JSON failed to
     * parse. Safe to call repeatedly; does not mutate state on success.
     */
    fun assembleBlocks(): List<Block>? = synchronized(stateLock) { assembleBlocksLocked() }

    private fun assembleBlocksLocked(): List<Block>? {
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
     * Task 3 entry point. Drives ONE complete chapter fetch cycle as a
     * suspending call so the repository can AWAIT asynchronous chunk arrival
     * instead of polling [assembleBlocks] synchronously (which returns null
     * on real hardware because chunks land on the GATT callback thread some
     * time after `requestChapter` returns).
     *
     * Cycle:
     *  1. `reset()` — clear stale buffer / attempt state from any prior cycle.
     *  2. Register an internal completion hook on this engine that fires the
     *     moment [chunkBuffer] fills (or the engine exhausts its retry
     *     budget). The hook completes [deferred].
     *  3. Call [dispatch] — the repository binds this to
     *     `bleController.requestChapter(path, index)`. The fake in unit tests
     *     triggers [onFrameReceived] synchronously inside `dispatch`; the
     *     production GATT callback triggers it asynchronously. Both paths
     *     work because the hook fires from within [onFrameReceived] either way.
     *  4. `withTimeoutOrNull(timeoutMs) { deferred.await() }` — returns null
     *     on timeout (preserves the spec's "surface HTTP error on BLE
     *     exhaustion" behavior). On success returns [assembleBlocks]'s result
     *     (the reassembled `List<Block>`, or null if JSON parsing failed).
     *
     * Single-coroutine contract: this function is NOT reentrant; only one
     * chapter cycle may be in flight at a time. The repository's
     * [com.juziss.localmediahub.data.MediaRepository.tryBleFailover] is the
     * sole caller and is itself single-threaded per chapter request.
     *
     * @param dispatch the side-effecting lambda that asks the Central to
     *   stream the chapter (e.g. `bleController.requestChapter(path, index)`).
     *   Invoked AFTER the completion hook is registered so any chunks the
     *   Central returns — synchronously or asynchronously — are observed.
     *   Placed LAST so callers can use trailing-lambda syntax.
     * @param timeoutMs overall deadline. Defaults to
     *   `frameTimeoutMs * maxAttempts` to mirror the engine's own retry
     *   budget; coerce to at least one [frameTimeoutMs] so a misconfigured
     *   small `maxAttempts` still leaves room for at least one frame.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun fetchChapterBlocks(
        path: String,
        index: Int,
        timeoutMs: Long = (frameTimeoutMs * maxAttempts).coerceAtLeast(frameTimeoutMs),
        dispatch: () -> Unit,
    ): List<Block>? {
        // Clear stale buffer/attempt state from any prior cycle and detach a
        // leftover hook (defensive — [reset] also clears it).
        reset()

        val deferred = CompletableDeferred<List<Block>?>()
        // Hook body runs inside onFrameReceived the moment the buffer fills
        // or the engine exhausts its retry budget. It computes the
        // reassembled blocks once (re-acquiring stateMutex inside
        // assembleBlocks — safe because onFrameReceived invokes the hook
        // OUTSIDE its own lock hold) and completes the deferred; the hook is
        // then cleared so the next cycle starts clean.
        val hook: () -> Unit = {
            // Clear the hook under the lock so a concurrent cycle cannot
            // observe a stale reference. assembleBlocks re-acquires the lock
            // on its own.
            synchronized(stateLock) { completionHook = null }
            val blocks = assembleBlocks()
            if (!deferred.isCompleted) {
                deferred.complete(blocks)
            }
        }

        // Register the hook UNDER the lock; dispatch happens AFTER releasing
        // it so a synchronous test-fake callback (or a same-thread GATT
        // callback) can re-enter onFrameReceived without surprises. The JVM
        // monitor is reentrant so a same-thread re-entry would not deadlock
        // either, but keeping dispatch outside the lock bounds its hold time.
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
        const val DEFAULT_FRAME_TIMEOUT_MS = 3_000L
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}

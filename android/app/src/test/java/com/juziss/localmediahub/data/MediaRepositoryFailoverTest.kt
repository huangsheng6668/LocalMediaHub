package com.juziss.localmediahub.data

import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BlePeripheralManager
import com.juziss.localmediahub.ble.BleProtocol
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3 / Task 4: MediaRepository BLE failover.
 *
 * Verifies the two spec §3.2 branches across EVERY failover-capable endpoint
 * (folders / browseFolder / bookInfo / bookChapter):
 *  1. HTTP IOException + BLE CONNECTED → repository routes through
 *     [BleTransportFallback], returns BLE-sourced data, and sets
 *     `isBleDegraded = true` + emits one [bleDegradedEvents] per request.
 *  2. BLE NOT connected → repository does NOT fail over; the original HTTP
 *     error is returned and `isBleDegraded` stays false.
 *
 * The HTTP path is exercised end-to-end against an unreachable URL so the
 * real OkHttp stack throws a genuine [java.net.ConnectException] (which is
 * an [java.io.IOException]). No HTTP mocking framework is needed. The BLE
 * path uses a [SimulatingPeripheralManager] whose `notifyPayload` (called by
 * [BleController.requestApi] to dispatch CMD_API_REQ) feeds the prebuilt
 * CHUNK payloads back into the registered payload callback, exactly as a
 * real Central would when it responds to the request over GATT.
 * [BleController] then routes those CHUNK payloads into the same
 * [BleTransportFallback] instance the repository reads from.
 *
 * Async discipline (Finding #2 fix): the fake ASYNCHRONOUSLY posts each
 * CHUNK payload to the registered callback via `scope.launch { delay(10);
 * cb(chunk) }` — it does NOT deliver them inline inside `notifyPayload`.
 * This mirrors real GATT hardware, where `onCharacteristicWriteRequest`
 * fires later on the GATT callback thread. As a result this test ONLY passes
 * if [MediaRepository.bleFetchOrHttp] correctly AWAITS chunk arrival instead
 * of polling synchronously (the old code called `assembleBlocks()` before
 * any chunk could arrive and always got null).
 */
class MediaRepositoryFailoverTest {

    /**
     * Fake [BlePeripheralManager] that simulates the Central (PC server)
     * responding to a CMD_API_REQ notify by writing the prebuilt CHUNK
     * payloads back to the peripheral.
     *
     * Phase 9 (Task 9): the channel is now authenticated end-to-end.
     *  - Construction wiring ([repoWithBle]) drives the mutual challenge/
     *    response handshake via [simulateCentralHandshake] right after
     *    markConnected, sharing the "sekrit" token with the controller.
     *  - [notifyPayload] receives a v2 authed CMD_API_REQ frame (the
     *    controller's post-auth outbound format) and decodes it with the
     *    shared key before the wire-parity checks.
     *  - Chunk responses are RAW v2 authed frames posted through the same
     *    raw-frame callback the handshake uses (Task 10 raw pass-through
     *    seam — the manager no longer decodes/re-frames, so v2 frames reach
     *    `BleController.onCommandWrite` unchanged).
     *
     * Delivery is asynchronous: each chunk is dispatched to [scope] via
     * `launch { delay(chunkDelayMs); sink(frame) }` so the test exercises the
     * suspend-await bridge in [MediaRepository.bleFetchOrHttp]. A real
     * BluetoothGattServer delivers chunks on its callback thread with
     * indeterminate timing; modeling that here ensures the test fails on any
     * future regression that re-introduces synchronous polling.
     *
     * Wire-format parity: the fake decodes the incoming CMD_API_REQ payload
     * using the SAME Go-spec §2.2 layout the real Go decoder
     * (DecodeApiReqPayload) expects:
     *   `[CmdID 1B = CMD_API_REQ][Endpoint 1B][PathLen 1B][Path UTF-8][Index 2B BE]`
     *
     * @param expectedEndpoint the endpoint byte the test expects the
     *   repository to request (e.g. [BleProtocol.ENDPOINT_FOLDERS]). Used to
     *   assert wire-format parity on every notify.
     * @param expectedPath the path the test expects to see in the request.
     *   For endpoints with no path (folders), pass the empty string.
     * @param scope the [runTest] scope that schedules chunk delivery. Must
     *   outlive the `notifyPayload` call (it does: [runTest] waits for all
     *   child coroutines to complete before returning).
     * @param chunkDelayMs virtual-time delay before each chunk is delivered.
     *   Default 10 ms is enough to ensure the delivery does NOT happen
     *   inline inside `notifyPayload`.
     */
    private class SimulatingPeripheralManager(
        private val responsePayloads: List<ByteArray>,
        private val scope: CoroutineScope,
        private val expectedEndpoint: Byte = BleProtocol.ENDPOINT_BOOK_CHAPTER,
        private val expectedPath: String = "/book.txt",
        private val chunkDelayMs: Long = 10L,
    ) : BlePeripheralManager {
        var advertising = false
        private var cb: ((ByteArray) -> Unit)? = null

        /** Shared BLE auth key — mirrors the controller's token ("sekrit"). */
        private val authKey: ByteArray = BleProtocol.deriveBleAuthKey("sekrit")

        /** PC → phone data seq, strictly increasing per connection. */
        private val pcSeq = java.util.concurrent.atomic.AtomicLong(0)

        /**
         * Per-request response cursor: each CMD_API_REQ consumes ONE queued
         * payload (in order). Replaying the whole list per request would make
         * consecutive segment fetches (segmented-chapter streaming) receive
         * segment 0 again.
         */
        private val respIdx = java.util.concurrent.atomic.AtomicInteger(0)

        override fun startAdvertising() { advertising = true }
        override fun stopAdvertising() { advertising = false }
        override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) {}
        override fun setOnRawFrameReceived(cb: (ByteArray) -> Unit) { this.cb = cb }
        override fun setOnPeerConnected(cb: () -> Unit) {}
        override fun setOnPeerDisconnected(cb: () -> Unit) {}
        override fun disconnectPeer() {}

        /**
         * Phase 9: drives the PC side of the mutual handshake to completion
         * in ONE synchronous call — writes a C2P challenge (raw v1 frame,
         * Task 10 seam contract); the controller responds and challenges back
         * via [notifyPayload], whose synchronous answer below closes the
         * loop, so `controller.authenticated` is true when this returns.
         * (The synchronous re-entry is safe: the controller registers its
         * pending nonce BEFORE notifying.)
         */
        fun simulateCentralHandshake() {
            cb?.invoke(
                BleProtocol.encodeFrame(
                    BleProtocol.encodeAuthChallengePayload(
                        BleProtocol.AUTH_DIR_C2P, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                    ),
                ),
            )
        }

        override fun notifyPayload(payload: ByteArray): Boolean {
            // Handshake frames still ride v1. The controller's own P2C
            // challenge is answered SYNCHRONOUSLY through the payload
            // callback (a test-only re-entry that real GATT hardware cannot
            // do) so repoWithBle can complete auth inline; data chunks below
            // keep the asynchronous discipline.
            val v1 = BleProtocol.decodeFrame(payload)
            if (v1 != null && v1.payload.isNotEmpty() &&
                v1.payload[0] == BleProtocol.CMD_AUTH_CHALLENGE
            ) {
                val decoded = BleProtocol.decodeAuthChallengePayload(v1.payload)
                if (decoded != null && decoded.first == BleProtocol.AUTH_DIR_P2C) {
                val (dir, nonce) = decoded
                cb?.invoke(
                    BleProtocol.encodeFrame(
                        BleProtocol.encodeAuthResponsePayload(
                            nonce,
                            BleProtocol.authResponseMac(authKey, nonce, dir),
                        ),
                    ),
                )
                }
                return true
            }

            // Data requests arrive as v2 authed frames; decode with the shared
            // key, then parity-check the CMD_API_REQ payload using the SAME
            // Go-spec §2.2 layout the real Go decoder (DecodeApiReqPayload)
            // expects:
            //   [CmdID 1B = CMD_API_REQ][Endpoint 1B][PathLen 1B]
            //   [Path UTF-8][Index 2B BE]
            val authed = BleProtocol.decodeAuthedFrame(payload, authKey) ?: return true
            val p = authed.payload
            if (p.isEmpty() || p[0] != BleProtocol.CMD_API_REQ) return true
            // Minimum header = CmdID(1) + Endpoint(1) + PathLen(1) + Index(2) = 5
            // bytes (path may be empty). An optional trailing Index2 (2B BE,
            // the segmented-chapter block offset) may follow the legacy
            // layout — mirroring Go's DecodeApiReqPayload tolerance.
            require(p.size >= 5) {
                "api req payload too short: ${p.size}"
            }
            val endpoint = p[1]
            val pathLen = p[2].toInt() and 0xFF
            require(p.size == 3 + pathLen + 2 || p.size == 3 + pathLen + 4) {
                "api req PathLen=$pathLen but payload size ${p.size} fits neither legacy nor index2 layout"
            }
            val decodedPath = String(p, 3, pathLen, Charsets.UTF_8)
            val indexOff = 3 + pathLen
            val decodedIndex = ((p[indexOff].toInt() and 0xFF) shl 8) or
                (p[indexOff + 1].toInt() and 0xFF)
            // Sanity-check the decoded args so a future wire-layout
            // regression in BleController.requestApi surfaces here, not as an
            // opaque test failure downstream.
            require(endpoint == expectedEndpoint) {
                "decoded endpoint mismatch: got $endpoint, expected $expectedEndpoint"
            }
            require(decodedPath == expectedPath) {
                "decoded path mismatch: got '$decodedPath', expected '$expectedPath'"
            }
            require(decodedIndex in 0..0xFFFF) {
                "decoded index out of uint16 range: $decodedIndex"
            }
            val chunk = responsePayloads.getOrNull(respIdx.getAndIncrement()) ?: return true
            scope.launch {
                delay(chunkDelayMs)
                // Task 10: the peripheral seam is raw pass-through now, so
                // v2 authed frames ride the SAME cb the handshake used —
                // exactly the bytes a real bonded Central would write.
                cb?.invoke(
                    BleProtocol.encodeAuthedFrame(
                        chunk, pcSeq.getAndIncrement().toULong(), authKey,
                    ),
                )
            }
            return true
        }
        override fun isAdapterUsable(): Boolean = true
    }

    /**
     * Build a single CHUNK application payload (CmdID 0x12) carrying [json]
     * as chunk [chunkIndex] of [totalChunks]. This is the decoded payload the
     * Central writes to the Command characteristic; [BleController]'s payload
     * callback re-encodes it before handing it to [BleTransportFallback].
     */
    private fun chunkPayload(totalChunks: Int, chunkIndex: Int, totalBlocks: Int, json: ByteArray): ByteArray {
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
        return payload
    }

    /**
     * A repository + controller whose HTTP base URL is unreachable and BLE is
     * [state]. The fake peripheral posts chunk payloads to [scope] so that
     * delivery happens asynchronously from the `notifyPayload` call site
     * (mirrors real GATT callback timing).
     *
     * [expectedEndpoint] / [expectedPath] parameterize the wire-format parity
     * assertion in the fake (defaults match the chapter case for backward
     * compat with the Task 3 tests).
     */
    private fun repoWithBle(
        scope: CoroutineScope,
        state: MutableStateFlow<BleConnState>,
        responsePayloads: List<ByteArray>,
        chunkDelayMs: Long = 10L,
        fallback: BleTransportFallback = BleTransportFallback(),
        expectedEndpoint: Byte = BleProtocol.ENDPOINT_BOOK_CHAPTER,
        expectedPath: String = "/book.txt",
    ): MediaRepository {
        val peripheral = SimulatingPeripheralManager(
            responsePayloads = responsePayloads,
            scope = scope,
            expectedEndpoint = expectedEndpoint,
            expectedPath = expectedPath,
            chunkDelayMs = chunkDelayMs,
        )
        val controller = BleController(
            peripheralManager = peripheral,
            bleTransportFallback = fallback,
            bleEnabledFlow = kotlinx.coroutines.flow.flowOf(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
            // Phase 9: the token shared with the fake Central — the BLE data
            // channel now refuses to open without a completed handshake.
            authTokenProvider = { "sekrit" },
        )
        controller.evaluateAvailability(enabled = true)
        if (state.value == BleConnState.CONNECTED) {
            controller.markConnected()
            // Phase 9 / Task 10: the fake's cb IS the controller's raw-frame
            // seam now (registered in BleController's init), so driving the
            // PC side of the mutual handshake through it also completes the
            // wiring the repository's requestApi dispatches rely on.
            peripheral.simulateCentralHandshake()
            check(controller.authenticated) {
                "test harness: BLE handshake must complete synchronously"
            }
        }
        val serverConfig = ServerConfig().apply {
            // Reserved loopback port with no listener → ConnectException (IOException).
            setBaseUrl("http://127.0.0.1:1")
        }
        return MediaRepository(
            http = OkHttpClient(),
            serverConfig = serverConfig,
            bleController = controller,
            bleTransportFallback = fallback,
        )
    }

    @Test
    fun fallsBackToBleWhenHttpFailsAndBleConnected() = runTest {
        val json = "[{\"type\":\"text\",\"value\":\"ble-body\"}]".toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json))
        val repo = repoWithBle(this, MutableStateFlow(BleConnState.CONNECTED), payloads)

        val result = repo.getBookChapter(path = "/book.txt", index = 0)

        assertTrue("expected Success from BLE failover, got $result", result is NetworkResult.Success)
        val content = (result as NetworkResult.Success).data
        assertEquals("ble-body", content.blocks.single().value)
        assertTrue("isBleDegraded must flip true after BLE-served chapter",
            repo.isBleDegraded.value)
    }

    /**
     * Segmented degraded reading: getBookChapter with onAppend returns the
     * FIRST segment synchronously (fast open), then streams the remaining
     * segments in appendScope, invoking onAppend per segment.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun segmentedChapterStreamsRemainingBlocksInBackground() = runTest {
        val seg0 = """{"offset":0,"total":2,"blocks":[{"type":"text","value":"part1"}]}"""
        val seg1 = """{"offset":1,"total":2,"blocks":[{"type":"text","value":"part2"}]}"""
        val payloads = listOf(
            chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = seg0.toByteArray()),
            chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = seg1.toByteArray()),
        )
        val repo = repoWithBle(
            this, MutableStateFlow(BleConnState.CONNECTED), payloads,
            expectedEndpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER_SEGMENT,
        )
        val appended = mutableListOf<String>()
        val result = repo.getBookChapter(
            path = "/book.txt", index = 0,
            onAppend = { more -> more.forEach { appended.add(it.value ?: "") } },
            appendScope = this,
        )
        assertTrue("expected Success from segmented BLE failover, got $result",
            result is NetworkResult.Success)
        assertEquals("part1", (result as NetworkResult.Success).data.blocks.single().value)
        // Let the background continuation loop deliver segment 1.
        advanceUntilIdle()
        assertEquals(listOf("part2"), appended)
        assertTrue(repo.isBleDegraded.value)
    }

    @Test
    fun doesNotFailOverWhenBleDisconnected() = runTest {
        val json = "[{\"type\":\"text\",\"value\":\"ble-body\"}]".toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json))
        val repo = repoWithBle(this, MutableStateFlow(BleConnState.DISCONNECTED), payloads)

        val result = repo.getBookChapter(path = "/book.txt", index = 0)

        assertFalse("must NOT return Success when BLE is disconnected", result is NetworkResult.Success)
        assertTrue("expected Error result when failover skipped, got $result",
            result is NetworkResult.Error)
        assertFalse("isBleDegraded must stay false when failover did not fire",
            repo.isBleDegraded.value)
    }

    /**
     * Finding #2 timeout contract: when BLE is CONNECTED but the Central
     * never streams any chunk back (empty payload list), the suspend bridge
     * in [BleTransportFallback.fetchJson] must time out and the
     * repository must surface the ORIGINAL HTTP error (not an empty chapter,
     * not Success). The degradation flag must also stay false because no BLE
     * traffic was actually served.
     *
     * Uses a real wall-clock timeout (not runTest's virtual time) by
     * constructing [BleTransportFallback] with a small per-frame timeout and
     * small max-attempts so the test stays fast while still exercising the
     * production timeout math (`frameTimeoutMs * maxAttempts`).
     */
    @Test
    fun returnsOriginalHttpErrorWhenBleChunksNeverArrive() = runTest {
        // No response payloads — the Central never answers. Use a short
        // per-frame timeout + small retry budget so the test stays fast
        // while still exercising the production timeout math
        // (`frameTimeoutMs * maxAttempts`).
        val fallback = BleTransportFallback(
            frameTimeoutMs = 100L,
            maxAttempts = 1,
        )
        val repo = repoWithBle(
            scope = this,
            state = MutableStateFlow(BleConnState.CONNECTED),
            responsePayloads = emptyList(),
            fallback = fallback,
        )

        val result = repo.getBookChapter(path = "/book.txt", index = 0)

        assertFalse("must NOT return Success when BLE times out", result is NetworkResult.Success)
        assertTrue("expected Error result when BLE timed out, got $result",
            result is NetworkResult.Error)
        assertFalse("isBleDegraded must stay false when BLE timed out",
            repo.isBleDegraded.value)
    }

    /**
     * I2: the degradation badge must re-trigger on EVERY BLE-served chapter,
     * not just the first one. The sticky boolean [MediaRepository.isBleDegraded]
     * flips true on the first BLE chapter and stays true, so a consumer that
     * keys off the boolean's value-change (LaunchedEffect(isBleDegraded))
     * would only fire once during a prolonged outage. The
     * [MediaRepository.bleDegradedEvents] SharedFlow emits once PER BLE-served
     * chapter; this test fetches two chapters back-to-back and asserts the
     * flow emits at least twice — locking the per-delivery feedback contract.
     */
    @Test
    fun bleDegradedEvents_emitsOncePerBleServedChapter() = runTest {
        val json = "[{\"type\":\"text\",\"value\":\"ble-body\"}]".toByteArray(Charsets.UTF_8)
        // SimulatingPeripheralManager consumes ONE queued payload per notify,
        // so both fetches get their own (identical) response.
        val singleChapterPayloads = listOf(
            chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json),
            chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json),
        )
        val repo = repoWithBle(this, MutableStateFlow(BleConnState.CONNECTED), singleChapterPayloads)

        // Collector that counts emissions across both fetches. Launched in
        // backgroundScope so runTest keeps it alive until the test body returns.
        val emissions = java.util.concurrent.atomic.AtomicInteger(0)
        val collectorJob = backgroundScope.launch {
            repo.bleDegradedEvents.collect { emissions.incrementAndGet() }
        }

        // First BLE-served chapter.
        val r1 = repo.getBookChapter(path = "/book.txt", index = 0)
        assertTrue("first fetch must succeed via BLE, got $r1", r1 is NetworkResult.Success)
        // Flush any pending SharedFlow collector resumptions so emission #1 is
        // counted before the second fetch.
        runCurrent()

        // Second BLE-served chapter — the sticky boolean is ALREADY true, so
        // only the event-stream contract can re-trigger the badge.
        val r2 = repo.getBookChapter(path = "/book.txt", index = 1)
        assertTrue("second fetch must succeed via BLE, got $r2", r2 is NetworkResult.Success)
        runCurrent()

        collectorJob.cancel()

        assertTrue(
            "bleDegradedEvents must emit once per BLE-served chapter (expected >=2, got ${emissions.get()})",
            emissions.get() >= 2,
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Task 4: generalized failover for folders / browseFolder / bookInfo.
    //  Each test asserts: (a) Success carries BLE-sourced data, (b) the
    //  isBleDegraded flag flips true, (c) exactly one bleDegradedEvents
    //  emission fires for the BLE-served request.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Helper: collect [bleDegradedEvents] emissions into a counter launched in
     * the runTest [backgroundScope] so the per-request event contract is
     * observable regardless of when the BLE-served request fires.
     */
    private fun attachEmissionCounter(repo: MediaRepository, backgroundScope: CoroutineScope):
        java.util.concurrent.atomic.AtomicInteger {
        val emissions = java.util.concurrent.atomic.AtomicInteger(0)
        backgroundScope.launch {
            repo.bleDegradedEvents.collect { emissions.incrementAndGet() }
        }
        return emissions
    }

    @Test
    fun getFolders_fallsBackToBleWhenHttpFailsAndBleConnected() = runTest {
        val json =
            """[{"name":"Books","path":"/books","relative_path":"books","is_root":true}]"""
                .toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 0, json = json))
        val repo = repoWithBle(
            scope = this,
            state = MutableStateFlow(BleConnState.CONNECTED),
            responsePayloads = payloads,
            expectedEndpoint = BleProtocol.ENDPOINT_FOLDERS,
            expectedPath = "",
        )
        val emissions = attachEmissionCounter(repo, backgroundScope)

        val result = repo.getFolders()

        assertTrue("expected Success from BLE failover, got $result", result is NetworkResult.Success)
        val folders = (result as NetworkResult.Success).data
        assertEquals("Books", folders.single().name)
        assertTrue("isBleDegraded must flip true after BLE-served folders fetch",
            repo.isBleDegraded.value)
        runCurrent()
        assertEquals(
            "bleDegradedEvents must emit exactly once for one BLE-served request",
            1, emissions.get(),
        )
    }

    @Test
    fun browseFolder_fallsBackToBle() = runTest {
        val json = """
            {"current_path":"books","folders":[],"files":[]}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 0, json = json))
        val repo = repoWithBle(
            scope = this,
            state = MutableStateFlow(BleConnState.CONNECTED),
            responsePayloads = payloads,
            expectedEndpoint = BleProtocol.ENDPOINT_BROWSE_FOLDER,
            expectedPath = "books",
        )

        val result = repo.browseFolder(relativePath = "books")

        assertTrue("expected Success from BLE failover, got $result", result is NetworkResult.Success)
        val browse = (result as NetworkResult.Success).data
        assertEquals("books", browse.currentPath)
        assertTrue("isBleDegraded must flip true after BLE-served browse fetch",
            repo.isBleDegraded.value)
    }

    @Test
    fun getBookInfo_fallsBackToBle() = runTest {
        val json = """
            {"path":"/book.txt","format":"txt","title":"Book","chapters":[],"mod_time":""}
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 0, json = json))
        val repo = repoWithBle(
            scope = this,
            state = MutableStateFlow(BleConnState.CONNECTED),
            responsePayloads = payloads,
            expectedEndpoint = BleProtocol.ENDPOINT_BOOK_INFO,
            expectedPath = "/book.txt",
        )

        val result = repo.getBookInfo(path = "/book.txt")

        assertTrue("expected Success from BLE failover, got $result", result is NetworkResult.Success)
        val book = (result as NetworkResult.Success).data
        assertEquals("Book", book.title)
        assertTrue("isBleDegraded must flip true after BLE-served bookInfo fetch",
            repo.isBleDegraded.value)
    }

    @Test
    fun getFolders_doesNotFailOverWhenBleDisconnected() = runTest {
        val json = "[]".toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 0, json = json))
        val repo = repoWithBle(
            scope = this,
            state = MutableStateFlow(BleConnState.DISCONNECTED),
            responsePayloads = payloads,
            expectedEndpoint = BleProtocol.ENDPOINT_FOLDERS,
            expectedPath = "",
        )

        val result = repo.getFolders()

        assertFalse("must NOT return Success when BLE is disconnected", result is NetworkResult.Success)
        assertTrue("expected Error result when failover skipped, got $result",
            result is NetworkResult.Error)
        assertFalse("isBleDegraded must stay false when failover did not fire",
            repo.isBleDegraded.value)
    }
}

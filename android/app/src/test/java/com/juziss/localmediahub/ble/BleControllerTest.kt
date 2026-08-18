package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleControllerTest {

    private class FakePeripheralManager : BlePeripheralManager {
        var advertising = false
        var received: ByteArray? = null
        val notified = mutableListOf<ByteArray>()

        /** Task 10: counts fatal-path disconnectPeer() calls from the controller. */
        var disconnectPeerCalls = 0

        /** Simulates "CCCD not subscribed yet" — every notify fails while > 0. */
        var notifyFailuresRemaining = 0
        private var cb: ((ByteArray) -> Unit)? = null
        private var onAdvertisingStarted: ((Boolean) -> Unit)? = null
        private var onPeerConnected: (() -> Unit)? = null
        private var onPeerDisconnected: (() -> Unit)? = null

        override fun startAdvertising() { advertising = true }
        override fun stopAdvertising() { advertising = false }
        override fun setOnRawFrameReceived(cb: (ByteArray) -> Unit) { this.cb = cb }
        override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) {
            onAdvertisingStarted = cb
        }
        override fun setOnPeerConnected(cb: () -> Unit) {
            onPeerConnected = cb
        }
        override fun setOnPeerDisconnected(cb: () -> Unit) {
            onPeerDisconnected = cb
        }
        override fun notifyPayload(payload: ByteArray): Boolean {
            if (notifyFailuresRemaining > 0) {
                notifyFailuresRemaining--
                return false
            }
            received = payload.copyOf()
            notified.add(payload.copyOf())
            return true
        }
        override fun disconnectPeer() { disconnectPeerCalls++ }
        override fun isAdapterUsable(): Boolean = true

        // Test hook to simulate a Central write. Matches the Task 10 raw
        // pass-through contract: the EXACT on-air frame bytes (v1
        // `encodeFrame(...)`-wrapped payloads, or raw v2 authed frames) go to
        // the controller unchanged.
        fun simulateWrite(rawFrame: ByteArray) { cb?.invoke(rawFrame) }

        // Phase 9 (C-1) hooks: simulate AndroidBlePeripheralManager's
        // onConnectionStateChange STATE_CONNECTED / STATE_DISCONNECTED
        // callbacks (what a real GATT link does to the controller).
        fun simulatePeerConnected() { onPeerConnected?.invoke() }
        fun simulatePeerDisconnected() { onPeerDisconnected?.invoke() }

        // Test hook simulating the AdvertiseCallback's onStartSuccess(true) /
        // onStartFailure(false). Backs the Task 1 advertising-started signal.
        fun fireAdvertisingStarted(success: Boolean) {
            onAdvertisingStarted?.invoke(success)
        }
    }

    private val authKey: ByteArray by lazy { BleProtocol.deriveBleAuthKey("sekrit") }

    private fun newController(
        mgr: FakePeripheralManager,
        token: String = "sekrit",
        fallback: BleTransportFallback = BleTransportFallback(),
    ): BleController = BleController(
        peripheralManager = mgr,
        bleTransportFallback = fallback,
        bleEnabledFlow = MutableStateFlow(true),
        bleHardwareAvailable = { true },
        saveBleEnabled = {},
        authTokenProvider = { token },
    )

    /**
     * Drive the PC (Central) side of the Phase 9 handshake up to and
     * including the phone's own challenge: writes a C2P challenge (as a RAW
     * v1 frame through the peripheral seam), and returns the nonce of the
     * phone's P2C challenge (notified[1]). [notifiedBefore] is the manager's
     * notified-frame count BEFORE this handshake round (0 for a fresh
     * manager), so the helper also works for a re-handshake on a reused
     * manager (C-1 reconnect test).
     */
    private fun pcChallengeAndExtractOwnNonce(
        mgr: FakePeripheralManager,
        nonce1: ByteArray = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
        notifiedBefore: Int = 0,
    ): ByteArray {
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(BleProtocol.AUTH_DIR_C2P, nonce1),
            ),
        )
        assertEquals(
            "handshake must notify exactly: response, then own challenge",
            notifiedBefore + 2, mgr.notified.size,
        )
        val ownCh = BleProtocol.decodeFrame(mgr.notified[notifiedBefore + 1])!!.payload
        assertEquals(BleProtocol.CMD_AUTH_CHALLENGE, ownCh[0])
        return BleProtocol.decodeAuthChallengePayload(ownCh)!!.second
    }

    /** Full mutual handshake: PC challenge → phone response+own challenge → PC response. */
    private fun performHandshake(mgr: FakePeripheralManager, controller: BleController) {
        val ownNonce = pcChallengeAndExtractOwnNonce(mgr, notifiedBefore = mgr.notified.size)
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthResponsePayload(
                    ownNonce,
                    BleProtocol.authResponseMac(authKey, ownNonce, BleProtocol.AUTH_DIR_P2C),
                ),
            ),
        )
        assertTrue("handshake must complete", controller.authenticated)
    }

    // ------------------------------------------------------------------
    // Pre-existing lifecycle coverage (unchanged semantics).
    // ------------------------------------------------------------------

    @Test
    fun disabledByDefault_doesNotAdvertise() {
        val enabledFlow = MutableStateFlow(false)
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = false)
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
        assertTrue(!mgr.advertising)
    }

    @Test
    fun enabledWithHardware_startsAdvertising() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
        assertTrue(mgr.advertising)
    }

    @Test
    fun markConnected_setsState() {
        val controller = newController(FakePeripheralManager())
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        assertEquals(BleConnState.CONNECTED, controller.connectionState.value)
    }

    @Test
    fun markDisconnected_returnsToAdvertising() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        controller.markDisconnected()
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
    }

    /**
     * Task 1: the advertising-start signal from the peripheral manager
     * (AdvertiseCallback.onStartSuccess) MUST flow out through
     * [BleController.advertisingStarted] so callers can defer any
     * auto-connect trigger until the peripheral is actually discoverable
     * (spec method B; auto-trigger itself is Task 2).
     */
    @Test
    fun advertisingStarted_emitsTrueOnPeripheralStartSuccess() = runTest {
        val fake = FakePeripheralManager()
        val controller = newController(fake)
        val collected = mutableListOf<Boolean>()
        val job = launch { controller.advertisingStarted.collect { collected.add(it) } }
        runCurrent()
        fake.fireAdvertisingStarted(true)
        runCurrent()
        job.cancel()
        assertEquals(listOf(true), collected)
    }

    // ------------------------------------------------------------------
    // Phase 9 (Task 9, H-1b): mutual-challenge handshake.
    // ------------------------------------------------------------------

    /**
     * Handshake success path (brief Step 4): a legal C2P challenge makes the
     * controller notify (1) its v1 response and (2) its own P2C challenge;
     * the PC's valid response completes the handshake
     * (`authenticated == true`); a subsequent PLAINTEXT CMD_JSON_CHUNK write
     * is rejected as a fatal downgrade.
     */
    @Test
    fun handshakeSuccess_completesMutualChallengeAndRejectsPlaintextAfterAuth() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        val nonce1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(BleProtocol.AUTH_DIR_C2P, nonce1),
            ),
        )

        // Response: v1 frame, echoes nonce1 with MAC over nonce1||dir(C2P).
        val resp = BleProtocol.decodeFrame(mgr.notified[0])!!.payload
        assertEquals(BleProtocol.CMD_AUTH_RESPONSE, resp[0])
        val (rn, rm) = BleProtocol.decodeAuthResponsePayload(resp)!!
        assertArrayEquals(nonce1, rn)
        assertArrayEquals(BleProtocol.authResponseMac(authKey, nonce1, BleProtocol.AUTH_DIR_C2P), rm)

        // Own challenge: v1 frame, dir = P2C, fresh 8-byte nonce.
        val ownCh = BleProtocol.decodeFrame(mgr.notified[1])!!.payload
        assertEquals(BleProtocol.CMD_AUTH_CHALLENGE, ownCh[0])
        val (dir2, ownNonce) = BleProtocol.decodeAuthChallengePayload(ownCh)!!
        assertEquals(BleProtocol.AUTH_DIR_P2C, dir2)
        assertEquals(8, ownNonce.size)
        assertFalse("not authenticated until the PC proves itself", controller.authenticated)

        // PC answers our challenge → both directions verified → authenticated.
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthResponsePayload(
                    ownNonce,
                    BleProtocol.authResponseMac(authKey, ownNonce, BleProtocol.AUTH_DIR_P2C),
                ),
            ),
        )
        assertTrue(controller.authenticated)
        assertNull("successful handshake clears any prior error text", controller.authErrorText)
        assertEquals(BleConnState.CONNECTED, controller.connectionState.value)

        // Post-auth plaintext CMD_JSON_CHUNK (what a pre-Phase-9 peer or an
        // attacker would write) must be rejected fatally, never reassembled.
        val plaintextChunk = BleProtocol.encodeJsonChunkPayload(
            totalChunks = 1, chunkIndex = 0, totalBytes = 4, chunk = "abcd".toByteArray(),
        )
        mgr.simulateWrite(plaintextChunk)
        assertFalse("plaintext after auth must kill the session", controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull("a fatal violation must surface an error reason", controller.authErrorText)
    }

    /**
     * Empty-token path (brief Step 4): without a configured authToken the
     * controller refuses to enter the auth flow — no response is sent at
     * all — and the channel drops to DISCONNECTED with an operator-readable
     * reason (open-auth mode has no BLE data channel).
     */
    @Test
    fun emptyTokenRefusesHandshake_sendsNothingAndDropsToDisconnected() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr, token = "")
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(
                    BleProtocol.AUTH_DIR_C2P, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                ),
            ),
        )

        assertEquals("no frame may be sent without a token", 0, mgr.notified.size)
        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull("refusal must expose an error text", controller.authErrorText)
    }

    /** The PC's response MAC must verify against our own challenge's nonce+dir. */
    @Test
    fun handshakeWrongMacFromCentral_dropsToDisconnected() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        val ownNonce = pcChallengeAndExtractOwnNonce(mgr)

        val badMac = ByteArray(16) { 0xAA.toByte() }
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthResponsePayload(ownNonce, badMac),
            ),
        )

        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
    }

    /** A challenge claiming the wrong direction (P2C from the Central) is fatal. */
    @Test
    fun handshakeWrongDirectionChallenge_isFatal() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(
                    BleProtocol.AUTH_DIR_P2C, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                ),
            ),
        )

        assertEquals("no response to a mis-directed challenge", 0, mgr.notified.size)
        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
    }

    /**
     * Go-implementer note (a): the PC's challenge write and its CCCD
     * subscription are back-to-back, so the FIRST notify can hit a
     * microsecond window where notifications are not yet enabled. The
     * controller must retry briefly instead of failing the handshake.
     */
    @Test
    fun handshakeRetriesNotifyWhileCccdNotReady() {
        val mgr = FakePeripheralManager()
        mgr.notifyFailuresRemaining = 1 // first notify fails (CCCD race)
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        performHandshake(mgr, controller)

        assertEquals(
            "response + own challenge must both get out despite the first failure",
            2, mgr.notified.size,
        )
    }

    /** When the CCCD never becomes ready, the handshake fails closed. */
    @Test
    fun handshakeNotifyRetriesExhausted_dropsToDisconnected() {
        val mgr = FakePeripheralManager()
        mgr.notifyFailuresRemaining = 100 // never succeeds
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(
                    BleProtocol.AUTH_DIR_C2P, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                ),
            ),
        )

        assertEquals(0, mgr.notified.size)
        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
    }

    /**
     * Pre-auth policy (Go listener symmetry): any non-handshake command
     * before authentication is a protocol violation — the old plaintext
     * echo behavior is gone.
     */
    @Test
    fun preAuthNonHandshakeCommand_isFatal() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        mgr.simulateWrite(BleProtocol.encodeFrame(byteArrayOf(BleProtocol.CMD_ECHO)))

        assertEquals("no echo before authentication", 0, mgr.notified.size)
        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
    }

    // ------------------------------------------------------------------
    // Phase 9 (Task 9): post-auth v2 data path.
    // ------------------------------------------------------------------

    /**
     * Post-auth data path: a v2 CMD_JSON_CHUNK frame that verifies (MAC +
     * strictly-increasing seq) is routed into the reassembly engine and
     * completes a fetchJson cycle; replaying the exact same frame (seq ≤
     * max-seen) is fatal.
     */
    @Test
    fun authedV2DataPath_routesJsonChunkAndRejectsSeqReplay() = runTest {
        val mgr = FakePeripheralManager()
        val fallback = BleTransportFallback()
        val controller = newController(mgr, fallback = fallback)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)

        val json = "{\"k\":1}".toByteArray(Charsets.UTF_8)
        val chunkPayload = BleProtocol.encodeJsonChunkPayload(
            totalChunks = 1, chunkIndex = 0, totalBytes = json.size, chunk = json,
        )
        val v2 = BleProtocol.encodeAuthedFrame(chunkPayload, 0uL, authKey)

        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
        ) {
            controller.onCommandWrite(v2.copyOf())
        }
        assertEquals(
            "verified v2 chunk frames must reach the reassembly engine",
            "{\"k\":1}", result,
        )
        assertTrue(controller.authenticated)

        // Replay the identical frame: seq 0 <= max-seen 0 → fatal.
        controller.onCommandWrite(v2.copyOf())
        assertFalse("seq replay must kill the session", controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
    }

    /** A v2 frame with a bad MAC (or wrong key) is fatal, never routed. */
    @Test
    fun authedV2BadMac_dropsToDisconnected() = runTest {
        val mgr = FakePeripheralManager()
        val fallback = BleTransportFallback()
        val controller = newController(mgr, fallback = fallback)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)

        val chunkPayload = BleProtocol.encodeJsonChunkPayload(
            totalChunks = 1, chunkIndex = 0, totalBytes = 4, chunk = "abcd".toByteArray(),
        )
        val v2 = BleProtocol.encodeAuthedFrame(chunkPayload, 0uL, authKey)
        v2[v2.size - 1] = (v2.last().toInt() xor 0xFF).toByte()

        val fetched = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
            timeoutMs = 150L,
        ) {
            controller.onCommandWrite(v2)
        }
        assertNull("tampered frame must not be reassembled", fetched)
        assertFalse(controller.authenticated)
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
    }

    /**
     * Post-auth echo (connectivity-loop "发送测试"): the PC's v2 CMD_ECHO
     * write must be echoed back as a v2 authed frame with the first
     * outbound data seq (0 — per-direction, per-connection reset).
     */
    @Test
    fun postAuthEcho_repliesWithV2AuthedFrame() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)

        val echoPayload = byteArrayOf(BleProtocol.CMD_ECHO) + "ping".toByteArray()
        controller.onCommandWrite(BleProtocol.encodeAuthedFrame(echoPayload, 5uL, authKey))

        val sent = mgr.received
        assertNotNull("echo must be notified", sent)
        val af = BleProtocol.decodeAuthedFrame(sent!!, authKey)
        assertNotNull("echo must be a valid v2 authed frame", af)
        assertArrayEquals(echoPayload, af!!.payload)
        assertEquals("first outbound data frame uses seq 0", 0uL, af.seq)
    }

    // ------------------------------------------------------------------
    // Task 3 wire-parity guards for requestApi (now behind the auth gate).
    // ------------------------------------------------------------------

    /**
     * Task 3 cross-side wire-parity guard: hand-build the EXACT byte sequence
     * the Go encoder (`server/internal/ble/protocol.go` EncodeApiReqPayload,
     * Task 1 commit 48e7d4a) produces for a known (endpoint, path, index) and
     * assert the v2-authenticated frame the peripheral manager receives from
     * `requestApi` decodes to those payload bytes.
     *
     * Payload layout (matches Go server EncodeApiReqPayload):
     *   [CmdID 0x11][Endpoint 1B][PathLen 1B][Path UTF-8][Index 2B BE]
     * Physical (Phase 9): [0x02][len 2B BE][payload][seq 8B BE][hmac 16B].
     *
     * This test MUST never regress — it is the cross-side parity contract for
     * the generalized endpoint-routing wire format. It subsumes the deleted
     * chapter-only `requestChapter_emitsGoSpecWireLayout` test by exercising
     * ENDPOINT_BOOK_CHAPTER through the same generalized path.
     */
    @Test
    fun requestApi_emitsGoSpecApiReqLayout() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)

        val path = "/books/n" // 8 bytes UTF-8
        val index = 7
        val pathBytes = path.toByteArray(Charsets.UTF_8)

        // Hand-build the expected payload exactly as the Go encoder does
        // (plus the trailing index2 field: 0 for legacy endpoints).
        val expectedPayload = ByteArray(1 + 1 + 1 + pathBytes.size + 2 + 2)
        var q = 0
        expectedPayload[q++] = BleProtocol.CMD_API_REQ               // 0x11
        expectedPayload[q++] = BleProtocol.ENDPOINT_BROWSE_FOLDER    // 0x03
        expectedPayload[q++] = (pathBytes.size and 0xFF).toByte()    // PathLen (1 byte)
        System.arraycopy(pathBytes, 0, expectedPayload, q, pathBytes.size)
        q += pathBytes.size
        expectedPayload[q++] = ((index shr 8) and 0xFF).toByte()     // Index high byte
        expectedPayload[q++] = (index and 0xFF).toByte()             // Index low byte
        expectedPayload[q++] = 0                                     // Index2 high byte
        expectedPayload[q++] = 0                                     // Index2 low byte

        val notified = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_BROWSE_FOLDER,
            path = path,
            index = index,
        )

        assertTrue("requestApi must report a notified subscriber", notified)
        val sent = mgr.received
        assertNotNull("peripheral manager must have received a frame", sent)
        val af = BleProtocol.decodeAuthedFrame(sent!!, authKey)
        assertNotNull("post-auth requestApi must ride a v2 authed frame", af)
        // Byte-identical assertion on the decapsulated payload: ANY
        // field-width or ordering drift fails here.
        assertEquals(
            "requestApi payload must match Go EncodeApiReqPayload",
            expectedPayload.toList(), af!!.payload.toList(),
        )
        assertEquals("first outbound request uses seq 0", 0uL, af.seq)
    }

    /**
     * Task 4 parity: the chapter endpoint MUST round-trip through the same
     * generalized `requestApi` wire format. This locks the migration path
     * (chapter requests now go through `requestApi(ENDPOINT_BOOK_CHAPTER,
     * path, index)` instead of the deleted `requestChapter`) and guards
     * against a future regression that re-introduces a chapter-specific
     * layout.
     */
    @Test
    fun requestApi_emitsGoSpecLayoutForChapterEndpoint() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)

        val path = "/books/novel.txt"
        val index = 4
        val pathBytes = path.toByteArray(Charsets.UTF_8)

        // Trailing index2 field (0 for legacy endpoints) is part of the wire
        // layout since the segmented-chapter endpoint was introduced.
        val expectedPayload = ByteArray(1 + 1 + 1 + pathBytes.size + 2 + 2)
        var q = 0
        expectedPayload[q++] = BleProtocol.CMD_API_REQ
        expectedPayload[q++] = BleProtocol.ENDPOINT_BOOK_CHAPTER
        expectedPayload[q++] = (pathBytes.size and 0xFF).toByte()
        System.arraycopy(pathBytes, 0, expectedPayload, q, pathBytes.size)
        q += pathBytes.size
        expectedPayload[q++] = ((index shr 8) and 0xFF).toByte()
        expectedPayload[q++] = (index and 0xFF).toByte()
        expectedPayload[q++] = 0
        expectedPayload[q++] = 0

        val notified = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = path,
            index = index,
        )

        assertTrue("requestApi must report a notified subscriber", notified)
        val sent = mgr.received
        assertNotNull("peripheral manager must have received a frame", sent)
        val af = BleProtocol.decodeAuthedFrame(sent!!, authKey)
        assertNotNull(af)
        assertEquals(
            "requestApi(ENDPOINT_BOOK_CHAPTER) payload must match Go EncodeApiReqPayload",
            expectedPayload.toList(), af!!.payload.toList(),
        )
    }

    /**
     * Task 3 boundary: a path whose UTF-8 length exceeds the 1-byte PathLen
     * ceiling (255) must be rejected (return false, no notify) — mirroring
     * the Go encoder's `ErrPathTooLong` rejection.
     */
    @Test
    fun requestApi_rejectsPathLongerThan255() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)
        val notifiedBefore = mgr.notified.size

        val longPath = "a".repeat(256)
        val ok = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_BOOK_INFO,
            path = longPath,
            index = 0,
        )
        assertFalse("over-length path must not be notified", ok)
        assertEquals(
            "no frame should be sent for an over-length path",
            notifiedBefore, mgr.notified.size,
        )
    }

    /**
     * Phase 9: `requestApi` is a data-phase operation — it must refuse
     * before the mutual handshake completes (Go returns ErrNotAuthenticated
     * symmetrically).
     */
    @Test
    fun requestApi_refusedBeforeAuthentication() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        // Connected but NOT authenticated.

        val ok = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_FOLDERS,
            path = "/",
            index = 0,
        )
        assertFalse("pre-auth requestApi must be refused", ok)
        assertEquals("nothing may be notified pre-auth", 0, mgr.notified.size)
    }

    // ------------------------------------------------------------------
    // Task 10: raw pass-through seam + fatal-path GATT cancel.
    // ------------------------------------------------------------------

    /**
     * Task 10 seam close-out (Task 9 leftover #1): pre-Task-10 the manager
     * decoded v1 frames and delivered only the payload, re-framed — so a
     * real PC's v2 authenticated frames NEVER reached the controller. The
     * manager now passes the exact on-air bytes through, and this test locks
     * the real-device end-to-end shape at the fake-manager level: a full
     * mutual handshake over RAW v1 frames, then a RAW v2 data frame flowing
     * through the same seam into the reassembly engine.
     */
    @Test
    fun rawSeam_endToEnd_v1HandshakeAndV2DataReachController() = runTest {
        val mgr = FakePeripheralManager()
        val fallback = BleTransportFallback()
        val controller = newController(mgr, fallback = fallback)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()

        // (1) PC's C2P challenge as a raw v1 frame through the seam.
        val nonce1 = byteArrayOf(9, 8, 7, 6, 5, 4, 3, 2)
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(BleProtocol.AUTH_DIR_C2P, nonce1),
            ),
        )
        assertEquals(2, mgr.notified.size)
        val ownCh = BleProtocol.decodeFrame(mgr.notified[1])!!.payload
        val ownNonce = BleProtocol.decodeAuthChallengePayload(ownCh)!!.second
        assertFalse("not yet authenticated", controller.authenticated)

        // (2) PC's response as a raw v1 frame through the seam.
        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthResponsePayload(
                    ownNonce,
                    BleProtocol.authResponseMac(authKey, ownNonce, BleProtocol.AUTH_DIR_P2C),
                ),
            ),
        )
        assertTrue("raw v1 handshake frames must complete auth", controller.authenticated)

        // (3) A raw v2 authed CMD_JSON_CHUNK frame through the SAME seam —
        // the exact bytes a real bonded Central writes. Pre-Task-10 this
        // frame died in the manager's v1-only decode; it must now reach the
        // controller's v2 gate, verify, and complete a fetchJson cycle.
        val json = "{\"task10\":true}".toByteArray(Charsets.UTF_8)
        val v2 = BleProtocol.encodeAuthedFrame(
            BleProtocol.encodeJsonChunkPayload(
                totalChunks = 1, chunkIndex = 0, totalBytes = json.size, chunk = json,
            ),
            0uL, authKey,
        )
        val result = fallback.fetchJson(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = "/book.txt",
            index = 0,
        ) {
            mgr.simulateWrite(v2.copyOf())
        }
        assertEquals("raw v2 frames must survive the manager seam into the engine",
            "{\"task10\":true}", result)
        assertTrue(controller.authenticated)
        assertTrue("no fatal occurred: manager must NOT have cancelled the link",
            mgr.disconnectPeerCalls == 0)
    }

    /**
     * Task 9 leftover #2 wiring: a fatal auth/protocol violation must now
     * proactively cancel the GATT link via the manager (fail closed on the
     * transport, not just the state machine).
     */
    @Test
    fun fatalAuthViolation_cancelsGattLinkViaManager() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr, token = "") // empty token → fatal refusal
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        assertEquals(0, mgr.disconnectPeerCalls)

        mgr.simulateWrite(
            BleProtocol.encodeFrame(
                BleProtocol.encodeAuthChallengePayload(
                    BleProtocol.AUTH_DIR_C2P, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
                ),
            ),
        )

        assertEquals(
            "fatal violation must cancel the GATT link exactly once",
            1, mgr.disconnectPeerCalls,
        )
        assertEquals(BleConnState.DISCONNECTED, controller.connectionState.value)
        assertNotNull(controller.authErrorText)
    }

    /**
     * Negative control for the fatal wiring: benign lifecycle transitions
     * (successful handshake, HTTP-coordination disconnect, disable) must NOT
     * cancel the GATT link — only [BleController] fatal paths may.
     */
    @Test
    fun nonFatalPaths_doNotCancelGattLink() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        performHandshake(mgr, controller)
        controller.markDisconnected()
        controller.evaluateAvailability(enabled = false)

        assertEquals(
            "no fatal occurred: manager link cancel must not fire",
            0, mgr.disconnectPeerCalls,
        )
    }

    // ------------------------------------------------------------------
    // Phase 9 (C-1): per-connection auth reset driven by GATT link events,
    // not by the HTTP-coordination markConnected() callback.
    // ------------------------------------------------------------------

    /**
     * C-1 regression (production ordering): the Go Central completes the
     * ENTIRE mutual handshake inside the POST /api/v1/ble/connect HTTP
     * handler, so on the phone the true event order is
     *
     *   GATT STATE_CONNECTED → handshake over the air → HTTP response →
     *   markConnected()
     *
     * The old markConnected() unconditionally called resetAuthLocked() and
     * erased the already-completed handshake, permanently killing the BLE
     * data channel (requestApi pre-refused; the PC's v2 frames were dropped
     * on the pre-auth path). markConnected() must now be a pure state-machine
     * transition.
     */
    @Test
    fun markConnected_afterCompletedHandshake_preservesAuthentication() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)

        // (1) GATT link established — per-connection auth reset fires HERE
        // (the PC's challenge has not arrived yet; ordering is race-free).
        mgr.simulatePeerConnected()

        // (2) The PC drives the mutual handshake to completion over the air
        // (this all happens inside the server's HTTP /connect handler).
        performHandshake(mgr, controller)

        // (3) The HTTP response finally reaches the phone — LATE. This used
        // to wipe authenticated=true (C-1 root cause).
        controller.markConnected()

        assertTrue(
            "late HTTP markConnected must NOT erase a completed handshake",
            controller.authenticated,
        )
        assertEquals(BleConnState.CONNECTED, controller.connectionState.value)

        // The data channel must remain usable: requestApi sends a v2 frame.
        val notifiedCountBefore = mgr.notified.size
        val ok = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_FOLDERS,
            path = "/",
            index = 0,
        )
        assertTrue("post-markConnected requestApi must be admitted", ok)
        assertEquals(
            "requestApi must have notified a v2 frame",
            notifiedCountBefore + 1, mgr.notified.size,
        )
    }

    /**
     * C-1 reverse case: when the GATT link drops (peer went away, or the
     * manager cancelled the link after a fatal), the auth state MUST be
     * cleared — the next link has to re-run the mutual handshake before the
     * data phase reopens. Stale v2 seq state must not survive either.
     */
    @Test
    fun peerDisconnectEvent_clearsAuthentication_requiresRehandshake() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        mgr.simulatePeerConnected()
        performHandshake(mgr, controller)
        assertTrue(controller.authenticated)

        mgr.simulatePeerDisconnected()

        assertFalse("GATT link loss must clear the auth state", controller.authenticated)
        assertFalse(
            "requestApi must be refused until the next handshake",
            controller.requestApi(BleProtocol.ENDPOINT_FOLDERS, "/", 0),
        )
    }

    /**
     * C-1 forward case: a NEW GATT link resets any auth state from the
     * previous link (a reconnecting peer must re-handshake — it cannot ride
     * a stale authenticated=true), and a fresh handshake re-authenticates
     * normally on the new link.
     */
    @Test
    fun peerReconnectEvent_resetsAuth_thenRehandshakeSucceeds() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        mgr.simulatePeerConnected()
        performHandshake(mgr, controller)

        // Link dropped and a new one came up.
        mgr.simulatePeerDisconnected()
        mgr.simulatePeerConnected()

        assertFalse("new GATT link must invalidate the previous handshake", controller.authenticated)

        // A stale post-auth v1 frame from the old link is now pre-auth input
        // again — the handshake-command routing accepts the re-handshake.
        performHandshake(mgr, controller)
        assertTrue("fresh handshake on the new link must re-authenticate", controller.authenticated)
    }

    /**
     * C-1 regression: the HTTP-coordination disconnect path keeps its auth
     * reset (markConnected lost its reset, markDisconnected keeps it).
     */
    @Test
    fun markDisconnected_stillResetsAuthentication() {
        val mgr = FakePeripheralManager()
        val controller = newController(mgr)
        controller.evaluateAvailability(enabled = true)
        mgr.simulatePeerConnected()
        performHandshake(mgr, controller)

        controller.markDisconnected()

        assertFalse("markDisconnected must still reset the auth state", controller.authenticated)
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
    }
}

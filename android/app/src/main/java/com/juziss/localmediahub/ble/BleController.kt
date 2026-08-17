package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton aggregating BLE policy for the Peripheral role.
 *
 * When enabled + hardware available: starts advertising (state ADVERTISING).
 * HTTP coordination (Task 9 BleApi via the VM) drives [markConnected] /
 * [markDisconnected] based on the Central's connect/disconnect responses.
 *
 * Phase 9 (Task 9, H-1b) authentication policy — the receive mirror of the Go
 * Central's `handleNotifyFrame` (`server/internal/ble/central.go`):
 *
 *  - PRE-AUTH ([authenticated] == false): only v1 frames carrying
 *    CMD_AUTH_CHALLENGE / CMD_AUTH_RESPONSE are admissible.
 *      - A C2P challenge is answered with (1) a v1 response whose MAC proves
 *        possession of the shared key (`HMAC-SHA256(key, nonce||dir)[:16]`)
 *        and (2) our own P2C challenge with a fresh 8-byte nonce; the PC's
 *        response must verify against that nonce before the data phase opens.
 *      - An empty configured token refuses the handshake entirely (open-auth
 *        mode has no BLE data channel — the derived key would be publicly
 *        computable): nothing is sent, state drops to DISCONNECTED with an
 *        operator-readable [authErrorText].
 *      - Any OTHER command pre-auth is a fatal protocol violation; an
 *        undecodable frame is silently dropped (Go listener parity).
 *  - POST-AUTH: every inbound frame must decode as an authenticated v2 frame
 *    ([BleProtocol.decodeAuthedFrame] — a plaintext v1 frame fails structure/
 *    MAC and is treated as a fatal downgrade) and pass the strictly
 *    increasing per-direction seq gate (`seq <= maxSeen` ⇒ fatal). Verified
 *    CMD_JSON_CHUNK payloads are routed into [bleTransportFallback]; other
 *    verified commands are echoed back as v2 frames (connectivity loop);
 *    handshake commands post-auth are fatal.
 *  - Outbound data frames ([requestApi], echo) are v2 with a strictly
 *    increasing per-direction tx seq starting at 0, reset per connection.
 *  - Any fatal violation: [authenticated] ← false, state → DISCONNECTED
 *    ([BleConnectionStateMachine.onAuthFailure]) + [authErrorText] set + the
 *    GATT link cancelled via the manager's disconnectPeer (Task 10). The link
 *    is dead to the data phase until the PC re-connects and re-handshakes.
 *
 * Go implementer note (a), honored here: the PC's challenge write and its
 * CCCD subscription are back-to-back, so the FIRST handshake notify can hit a
 * microsecond window where notifications are not enabled — handshake notifies
 * get a short bounded retry ([NOTIFY_RETRY_ATTEMPTS] × [NOTIFY_RETRY_DELAY_MS])
 * before the handshake fails closed.
 *
 * Zero-regression: when disabled or hardware unavailable, state is DISABLED
 * and no advertising occurs; Wi-Fi/HTTP behavior is entirely unaffected.
 *
 * @param peripheralManager the hardware seam (real impl: [AndroidBlePeripheralManager]).
 * @param bleTransportFallback chunk reassembly engine; verified v2 CHUNK
 *   payloads are routed here so the repository can reassemble them on demand.
 * @param bleEnabledFlow the persisted user setting (default false). Observed by
 *   the Hilt module, which calls [evaluateAvailability] on each emission.
 * @param bleHardwareAvailable returns true only if the device has a powered,
 *   authorized Bluetooth adapter.
 * @param saveBleEnabled persists the toggle (DataStore in production).
 * @param authTokenProvider synchronous read of the CURRENT server Bearer
 *   token (decrypted). Drives [BleProtocol.deriveBleAuthKey] per handshake;
 *   an empty token refuses the auth flow (see above). The DI module backs
 *   this with a cached latest emission of `ServerConfigStore.authToken`.
 */
@Singleton
class BleController @Inject constructor(
    private val peripheralManager: BlePeripheralManager,
    private val bleTransportFallback: BleTransportFallback,
    @Suppress("unused") private val bleEnabledFlow: Flow<Boolean>,
    private val bleHardwareAvailable: () -> Boolean,
    private val saveBleEnabled: suspend (Boolean) -> Unit,
    private val authTokenProvider: () -> String,
) {
    private val machine = BleConnectionStateMachine()
    val connectionState: StateFlow<BleConnState> = machine.state

    /**
     * Phase 9: true only after BOTH handshake directions verified. @Volatile
     * for lock-free observation (UI / repository guards); every mutation
     * happens under [authLock].
     */
    @Volatile
    var authenticated: Boolean = false
        private set

    /**
     * Phase 9: operator-readable reason for the last fatal auth/protocol
     * violation, or null while the channel is clean. Cleared on a successful
     * handshake. Surfaced for the settings UI (Task 10 wiring).
     */
    @Volatile
    var authErrorText: String? = null
        private set

    /**
     * Guards all handshake + seq state ([pendingOwnNonce], [nextTxSeq],
     * [maxRxSeq], [haveRxSeq]) and serializes the notify-with-sequence-reserve
     * critical sections. Plain JVM monitor: entry points ([onCommandWrite],
     * [requestApi]) are non-suspend so GATT binder threads can enter directly.
     */
    private val authLock = Any()

    /** Nonce of OUR in-flight P2C challenge; non-null only mid-handshake. */
    private var pendingOwnNonce: ByteArray? = null

    /** Next outbound v2 seq (per-direction, strictly increasing, reset per connection). */
    private var nextTxSeq: ULong = 0uL

    /** Max inbound v2 seq seen this connection ([haveRxSeq] gates first-frame-any-seq). */
    private var maxRxSeq: ULong = 0uL
    private var haveRxSeq: Boolean = false

    private val secureRandom = SecureRandom()

    /**
     * Task 1: emits `true` when the peripheral's AdvertiseCallback reports
     * onStartSuccess (the device is now actually discoverable) and `false` on
     * onStartFailure.
     *
     * `extraBufferCapacity = 8` ensures back-pressure never drops an emission
     * under transient slow-collector conditions.
     */
    private val _advertisingStarted = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val advertisingStarted: SharedFlow<Boolean> = _advertisingStarted.asSharedFlow()

    init {
        // Task 10 raw seam: the manager hands over the EXACT on-air frame
        // bytes a bonded Central wrote (v1 handshake frames and v2
        // authenticated frames alike) — no decode/re-frame here; this method
        // is the single v1/v2 dispatch + authentication gate.
        peripheralManager.setOnRawFrameReceived { rawFrame ->
            onCommandWrite(rawFrame)
        }
        // Surface the advertising-started signal so callers can observe
        // when the peripheral is actually discoverable.
        peripheralManager.setOnAdvertisingStarted { success ->
            _advertisingStarted.tryEmit(success)
        }
    }

    /**
     * Re-evaluate whether BLE should be active based on the current setting
     * + hardware. Called by the Hilt module on each [bleEnabledFlow] emission.
     *
     * When enabling, the machine unconditionally transitions to ADVERTISING
     * (start advertising); the hardware guard short-circuits to DISABLED when
     * no usable adapter is present. Disabling also drops any half-open
     * handshake state (fail closed).
     */
    fun evaluateAvailability(enabled: Boolean) {
        if (!enabled || !bleHardwareAvailable()) {
            machine.onBleDisabled()
            peripheralManager.stopAdvertising()
            synchronized(authLock) { resetAuthLocked() }
            return
        }
        machine.onStartAdvertising()
        peripheralManager.startAdvertising()
    }

    /** Called by BleApi (Task 9 VM) when the Central reports a successful /connect. */
    fun markConnected() {
        machine.onConnected()
        // Per-connection auth reset (Go `resetAuthLocked` on Connect): a new
        // GATT link must re-run the mutual handshake; stale v2 seq state
        // from the previous link must not survive.
        synchronized(authLock) { resetAuthLocked() }
    }

    /** Called by BleApi (Task 9 VM) when the Central reports disconnect or connect failure. */
    fun markDisconnected() {
        machine.onDisconnected()
        synchronized(authLock) { resetAuthLocked() }
    }

    // ------------------------------------------------------------------
    // Phase 9 (Task 9, H-1b): authenticated command-write gate.
    // ------------------------------------------------------------------

    /**
     * The authenticated receive gate for one RAW frame written by the Central
     * to the Command characteristic (full physical bytes: `[version][len 2B
     * BE][...]`). Routes by auth state exactly as the Go Central's listener
     * routes notifications — see the class doc for the policy table.
     *
     * Fatal outcomes ([fatalLocked]) drop the channel: [authenticated] ←
     * false, machine → DISCONNECTED, [authErrorText] records the reason.
     */
    fun onCommandWrite(rawFrame: ByteArray) {
        synchronized(authLock) {
            if (authenticated) {
                handleAuthedWriteLocked(rawFrame)
            } else {
                handlePreAuthWriteLocked(rawFrame)
            }
        }
    }

    /** PRE-AUTH policy: only the two handshake commands are admissible. */
    private fun handlePreAuthWriteLocked(rawFrame: ByteArray) {
        // Undecodable-as-v1 pre-auth frame: dropped silently (Go listener
        // parity — a raced v2/garbage write never opens the data phase).
        val frame = BleProtocol.decodeFrame(rawFrame) ?: return
        val payload = frame.payload
        if (payload.isEmpty()) return

        when (payload[0]) {
            BleProtocol.CMD_AUTH_CHALLENGE -> handleChallengeLocked(payload)
            BleProtocol.CMD_AUTH_RESPONSE -> handleCentralResponseLocked(payload)
            else -> fatalLocked(
                "BLE protocol violation: data command 0x%02x before authentication".format(payload[0]),
            )
        }
    }

    /**
     * The PC challenged us (dir MUST be C2P — a mis-directed challenge is an
     * attack or a broken peer, fatal either way). Respond with the truncated
     * HMAC proof over nonce||dir, then immediately fire our own P2C challenge
     * with a fresh nonce; the data phase opens only when the PC answers it
     * correctly ([handleCentralResponseLocked]).
     */
    private fun handleChallengeLocked(payload: ByteArray) {
        val token = authTokenProvider()
        if (token.isEmpty()) {
            fatalLocked("BLE auth refused: no access token configured (open-auth mode has no BLE data channel)")
            return
        }
        val key = BleProtocol.deriveBleAuthKey(token)
        val decoded = BleProtocol.decodeAuthChallengePayload(payload)
        if (decoded == null) {
            fatalLocked("BLE handshake failed: malformed auth challenge payload")
            return
        }
        val (dir, nonce) = decoded
        if (dir != BleProtocol.AUTH_DIR_C2P) {
            fatalLocked("BLE handshake failed: unexpected challenge direction 0x%02x".format(dir))
            return
        }

        // (1) Prove ourselves: v1 response echoing nonce1 + MAC(nonce1||dir).
        val mac = BleProtocol.authResponseMac(key, nonce, dir)
        if (!notifyHandshakeFrameLocked(BleProtocol.encodeAuthResponsePayload(nonce, mac))) {
            fatalLocked("BLE handshake failed: response notify failed (no CCCD subscriber)")
            return
        }

        // (2) Challenge back: v1 frame, dir P2C, fresh 8-byte nonce. Go note
        // (a): both notifies get the short CCCD-race retry. The pending nonce
        // is registered BEFORE the notify so a peer that answers within the
        // notify call (synchronous test fakes; real GATT cannot) never sees
        // "no outstanding challenge".
        val ownNonce = ByteArray(8).also(secureRandom::nextBytes)
        pendingOwnNonce = ownNonce
        if (!notifyHandshakeFrameLocked(
                BleProtocol.encodeAuthChallengePayload(BleProtocol.AUTH_DIR_P2C, ownNonce),
            )
        ) {
            pendingOwnNonce = null
            fatalLocked("BLE handshake failed: reverse challenge notify failed (no CCCD subscriber)")
            return
        }
    }

    /**
     * The PC answered OUR challenge. The echoed nonce must match the pending
     * own nonce and the MAC must verify over `ownNonce||P2C` (constant-time)
     * before [authenticated] flips true. Either side of the proof failing is
     * fatal.
     */
    private fun handleCentralResponseLocked(payload: ByteArray) {
        val pending = pendingOwnNonce
        if (pending == null) {
            // Unsolicited response with no outstanding challenge — the only
            // legitimate responder is our own challenge, so this is a
            // protocol violation.
            fatalLocked("BLE handshake failed: auth response with no outstanding challenge")
            return
        }
        val decoded = BleProtocol.decodeAuthResponsePayload(payload)
        if (decoded == null) {
            fatalLocked("BLE handshake failed: malformed auth response payload")
            return
        }
        val (nonce, mac) = decoded
        if (!nonce.contentEquals(pending)) {
            fatalLocked("BLE handshake failed: auth response nonce mismatch")
            return
        }
        val token = authTokenProvider()
        if (token.isEmpty()) {
            fatalLocked("BLE auth refused: no access token configured (open-auth mode has no BLE data channel)")
            return
        }
        val key = BleProtocol.deriveBleAuthKey(token)
        val expected = BleProtocol.authResponseMac(key, pending, BleProtocol.AUTH_DIR_P2C)
        if (!MessageDigest.isEqual(expected, mac)) {
            fatalLocked("BLE handshake failed: auth response MAC mismatch (wrong key?)")
            return
        }

        // Mutual proof complete: open the data phase with fresh, per-connection
        // seq state (Go `resetAuthLocked` then authenticated=true semantics).
        pendingOwnNonce = null
        authenticated = true
        authErrorText = null
        nextTxSeq = 0uL
        maxRxSeq = 0uL
        haveRxSeq = false
    }

    /** POST-AUTH policy: v2-only, strictly increasing seq, fatal on violation. */
    private fun handleAuthedWriteLocked(rawFrame: ByteArray) {
        val token = authTokenProvider()
        if (token.isEmpty()) {
            // Token vanished mid-session: the shared key can no longer be
            // established symmetrically — fail closed.
            fatalLocked("BLE session dropped: access token no longer configured")
            return
        }
        val key = BleProtocol.deriveBleAuthKey(token)

        // A plaintext v1 frame post-auth (downgrade) fails decodeAuthedFrame
        // on structure or MAC — same fatal path as Go's DecodeAuthedFrame.
        val authed = BleProtocol.decodeAuthedFrame(rawFrame, key)
        if (authed == null) {
            fatalLocked("BLE session dropped: inbound frame failed v2 authentication (tamper/downgrade?)")
            return
        }
        if (haveRxSeq && authed.seq <= maxRxSeq) {
            fatalLocked("BLE session dropped: v2 seq rollback/replay rejected (seq=%d)".format(authed.seq.toLong()))
            return
        }
        maxRxSeq = authed.seq
        haveRxSeq = true

        val payload = authed.payload
        if (payload.isEmpty()) return
        when (payload[0]) {
            BleProtocol.CMD_JSON_CHUNK ->
                // Re-wrap the verified payload as a v1 frame for the engine's
                // internal decoder (the engine's seam is unchanged).
                bleTransportFallback.onFrameReceived(BleProtocol.encodeFrame(payload))
            BleProtocol.CMD_AUTH_CHALLENGE,
            BleProtocol.CMD_AUTH_RESPONSE,
            -> fatalLocked("BLE protocol violation: handshake command 0x%02x after authentication".format(payload[0]))
            else -> notifyAuthedFrameLocked(payload) // echo path (connectivity loop)
        }
    }

    // ------------------------------------------------------------------
    // Outbound paths.
    // ------------------------------------------------------------------

    /**
     * Sends one handshake payload as a v1 frame with a short bounded retry
     * against the CCCD-race window (Go implementer note (a)): the PC writes
     * its challenge back-to-back with subscribing to our State
     * characteristic, so the very first notify can fail while the
     * subscription is not yet visible. The PC side carries a 5s handshake
     * timeout as the outer backstop; our retry budget is far below that.
     */
    private fun notifyHandshakeFrameLocked(payload: ByteArray): Boolean {
        val frame = BleProtocol.encodeFrame(payload)
        var attempt = 0
        while (true) {
            if (peripheralManager.notifyPayload(frame)) return true
            attempt++
            if (attempt >= NOTIFY_RETRY_ATTEMPTS) return false
            try {
                Thread.sleep(NOTIFY_RETRY_DELAY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * Sends one data payload as a v2 authed frame: atomically reserves the
     * next strictly-increasing outbound seq and notifies (the reserve + radio
     * call are one critical section under [authLock] — the Android mirror of
     * Go's `sendAuthedFrame` under sendMu, so two threads can never put
     * frames on the wire out of seq order). A failed notify still consumes
     * its seq, leaving a gap — harmless for a strict-increase receiver.
     */
    private fun notifyAuthedFrameLocked(payload: ByteArray): Boolean {
        val token = authTokenProvider()
        if (token.isEmpty()) return false
        val key = BleProtocol.deriveBleAuthKey(token)
        val seq = nextTxSeq++
        return peripheralManager.notifyPayload(BleProtocol.encodeAuthedFrame(payload, seq, key))
    }

    /**
     * Task 3: Dispatch a CMD_API_REQ to the Central (PC server) over the State
     * (Notify) characteristic so it streams the requested resource back as a
     * sequence of CMD_JSON_CHUNK frames that [BleTransportFallback] reassembles.
     *
     * Phase 9: data-phase operation — refused (false) until the mutual
     * handshake completes; afterwards the request rides a v2 authed frame
     * (Go `ErrNotAuthenticated` symmetry).
     *
     * Returns true when a GATT subscriber was actually notified; false when
     * unauthenticated, there is no subscriber, or [path] exceeds the 255-byte
     * PathLen ceiling (the request is dropped silently — the resource will
     * surface as a BLE timeout upstream, matching the Go encoder's
     * `ErrPathTooLong` rejection).
     *
     * Wire format (spec §2.2; MUST match server `EncodeApiReqPayload` /
     * `DecodeApiReqPayload` byte-for-byte — locked by
     * [BleControllerTest.requestApi_emitsGoSpecApiReqLayout]):
     * `[CmdID 1B = CMD_API_REQ][Endpoint 1B][PathLen 1B][Path UTF-8][Index 2B BE]`
     *
     * @param endpoint one of `BleProtocol.ENDPOINT_*`. Selects which Go
     *   ApiProvider handler the Central dispatches to (BookChapter, Folders,
     *   BrowseFolder, BookInfo).
     * @param path resource path (UTF-8). Length must fit in 1 byte (≤ 255).
     * @param index pagination / chapter index. Serialized uint16 big-endian;
     *   negative values are programmer error and masked to their low 16 bits.
     */
    fun requestApi(endpoint: Byte, path: String, index: Int): Boolean {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        // PathLen is a single byte (max 255); a longer path cannot be encoded
        // without truncation, which would make the server fetch a wrong
        // resource. Match the Go encoder's ErrPathTooLong rejection: drop.
        if (pathBytes.size > 0xFF) return false
        val payload = ByteArray(1 + 1 + 1 + pathBytes.size + 2)
        var p = 0
        payload[p++] = BleProtocol.CMD_API_REQ
        payload[p++] = endpoint
        payload[p++] = (pathBytes.size and 0xFF).toByte()
        System.arraycopy(pathBytes, 0, payload, p, pathBytes.size); p += pathBytes.size
        // Index as uint16 big-endian (high byte first) — matches Go's
        // binary.BigEndian.PutUint16. Negative indices are programmer error
        // and are masked to their low 16 bits.
        payload[p++] = ((index shr 8) and 0xFF).toByte()
        payload[p++] = (index and 0xFF).toByte()
        synchronized(authLock) {
            if (!authenticated) return false
            return notifyAuthedFrameLocked(payload)
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
    }

    // ------------------------------------------------------------------
    // Auth state helpers (authLock held).
    // ------------------------------------------------------------------

    /**
     * Fail closed: kills the session. [authenticated] ← false, seq/handshake
     * state cleared, machine → DISCONNECTED (distinct from the
     * HTTP-coordination [markDisconnected] → ADVERTISING), error text
     * recorded for the UI. The GATT link is also proactively cancelled via
     * [BlePeripheralManager.disconnectPeer] (Task 10 minimal wiring): the
     * dead peer's writes would bounce off the auth gate regardless, but
     * dropping the link frees the phone's connection resources immediately
     * instead of waiting for the PC's handshake/echo timeout to tear it down.
     */
    private fun fatalLocked(reason: String) {
        resetAuthLocked()
        authErrorText = reason
        machine.onAuthFailure()
        peripheralManager.disconnectPeer()
    }

    /** Clears handshake + per-connection seq state. authLock held. */
    private fun resetAuthLocked() {
        authenticated = false
        pendingOwnNonce = null
        nextTxSeq = 0uL
        maxRxSeq = 0uL
        haveRxSeq = false
    }

    private companion object {
        /**
         * CCCD-race retry budget for handshake notifies (Go note (a)):
         * 5 attempts × 25 ms ≈ 100 ms total — enough to bridge the
         * subscribe-vs-write window, far below the PC's 5 s handshake timeout.
         */
        const val NOTIFY_RETRY_ATTEMPTS = 5
        const val NOTIFY_RETRY_DELAY_MS = 25L
    }
}

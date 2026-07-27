package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton aggregating BLE policy for the Peripheral role.
 *
 * When enabled + hardware available: starts advertising (state ADVERTISING).
 * HTTP coordination (Task 9 BleApi via the VM) drives [markConnected] /
 * [markDisconnected] based on the Central's connect/disconnect responses.
 *
 * When the Central writes to the Command characteristic, [BlePeripheralManager]
 * invokes the registered callback with the decoded payload. Task 3 routing:
 *  - CMD_BOOK_CHAPTER_CHUNK frames are handed to [bleTransportFallback] for
 *    reassembly (spec §3.2 / §1.2 step 4) so [com.juziss.localmediahub.data.MediaRepository]
 *    can fail HTTP requests over to BLE when Wi-Fi is down.
 *  - Any other command is echoed back via [BlePeripheralManager.notifyPayload]
 *    (re-encoded frame) so the existing connectivity loop still verifies
 *    end-to-end.
 *
 * Zero-regression: when disabled or hardware unavailable, state is DISABLED
 * and no advertising occurs; Wi-Fi/HTTP behavior is entirely unaffected.
 *
 * @param peripheralManager the hardware seam (real impl: [AndroidBlePeripheralManager]).
 * @param bleTransportFallback Task 2 chunk reassembly engine; CHUNK frames are
 *   routed here so the repository can reassemble them on demand.
 * @param bleEnabledFlow the persisted user setting (default false). Observed by
 *   the Hilt module, which calls [evaluateAvailability] on each emission.
 * @param bleHardwareAvailable returns true only if the device has a powered,
 *   authorized Bluetooth adapter.
 * @param saveBleEnabled persists the toggle (DataStore in production).
 */
@Singleton
class BleController @Inject constructor(
    private val peripheralManager: BlePeripheralManager,
    private val bleTransportFallback: BleTransportFallback,
    @Suppress("unused") private val bleEnabledFlow: Flow<Boolean>,
    private val bleHardwareAvailable: () -> Boolean,
    private val saveBleEnabled: suspend (Boolean) -> Unit,
) {
    private val machine = BleConnectionStateMachine()
    val connectionState: StateFlow<BleConnState> = machine.state

    init {
        // Route CHUNK frames into the fallback engine; echo everything else so
        // the connectivity-loop verification (Central write → Notify back) still
        // works for non-payload commands like CMD_ECHO.
        peripheralManager.setOnPayloadReceived { payload ->
            if (payload.isNotEmpty() && payload[0] == BleProtocol.CMD_BOOK_CHAPTER_CHUNK) {
                bleTransportFallback.onFrameReceived(BleProtocol.encodeFrame(payload))
            } else {
                peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
            }
        }
    }

    /**
     * Re-evaluate whether BLE should be active based on the current setting
     * + hardware. Called by the Hilt module on each [bleEnabledFlow] emission.
     *
     * When enabling, the machine unconditionally transitions to ADVERTISING
     * (start advertising); the hardware guard short-circuits to DISABLED when
     * no usable adapter is present.
     */
    fun evaluateAvailability(enabled: Boolean) {
        if (!enabled || !bleHardwareAvailable()) {
            machine.onBleDisabled()
            peripheralManager.stopAdvertising()
            return
        }
        machine.onStartAdvertising()
        peripheralManager.startAdvertising()
    }

    /** Called by BleApi (Task 9 VM) when the Central reports a successful /connect. */
    fun markConnected() {
        machine.onConnected()
    }

    /** Called by BleApi (Task 9 VM) when the Central reports disconnect or connect failure. */
    fun markDisconnected() {
        machine.onDisconnected()
    }

    /**
     * Task 3: Dispatch a CMD_BOOK_CHAPTER_REQ to the Central (PC server) over
     * the State (Notify) characteristic so it streams the requested chapter
     * back as a sequence of CMD_BOOK_CHAPTER_CHUNK frames that
     * [BleTransportFallback] reassembles.
     *
     * Returns true when a GATT subscriber was actually notified; false when
     * there is no subscriber (the Central has not yet enabled notifications),
     * or when [path] exceeds the 255-byte PathLen ceiling (the request is
     * dropped silently — the chapter will surface as a BLE timeout upstream,
     * matching the Go encoder's `ErrPathTooLong` rejection).
     *
     * Wire format (spec §2.2; MUST match server
     * `EncodeBookChapterReqPayload` / `DecodeBookChapterReqPayload` exactly):
     * `[CmdID 1B = CMD_BOOK_CHAPTER_REQ][ChapterIndex 2B BE][PathLen 1B]
     *  [Path UTF-8 bytes]`
     *
     * Cross-side parity is locked by [BleControllerTest.requestChapter_emitsGoSpecWireLayout]:
     * it hand-builds the exact byte sequence the Go encoder produces for a
     * known (path, index) and asserts the bytes reaching the peripheral
     * manager are byte-identical.
     */
    fun requestChapter(path: String, index: Int): Boolean {
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        // PathLen is a single byte (max 255); a longer path cannot be encoded
        // without truncation, which would make the server fetch a wrong chapter.
        // Match the Go encoder's ErrPathTooLong rejection: drop the request.
        if (pathBytes.size > 0xFF) return false
        val payload = ByteArray(1 + 2 + 1 + pathBytes.size)
        var p = 0
        payload[p++] = BleProtocol.CMD_BOOK_CHAPTER_REQ
        // ChapterIndex as uint16 big-endian (high byte first) — matches Go's
        // binary.BigEndian.PutUint16. Negative indices are programmer error and
        // are masked to their low 16 bits (chapter indices are always >= 0).
        payload[p++] = ((index shr 8) and 0xFF).toByte()
        payload[p++] = (index and 0xFF).toByte()
        payload[p++] = (pathBytes.size and 0xFF).toByte()
        System.arraycopy(pathBytes, 0, payload, p, pathBytes.size)
        return peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
    }

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
    }
}

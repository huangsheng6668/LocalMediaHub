package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
 *  - CMD_JSON_CHUNK frames are handed to [bleTransportFallback] for
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

    /**
     * Task 1: emits `true` when the peripheral's AdvertiseCallback reports
     * onStartSuccess (the device is now actually discoverable) and `false` on
     * onStartFailure. Task 2's auto-connect trigger MUST wait on this signal
     * (spec method B) so it never fires before the advertiser is ready.
     *
     * `extraBufferCapacity = 8` ensures back-pressure never drops an emission
     * under transient slow-collector conditions.
     */
    private val _advertisingStarted = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)
    val advertisingStarted: SharedFlow<Boolean> = _advertisingStarted.asSharedFlow()

    init {
        // Route CHUNK frames into the fallback engine; echo everything else so
        // the connectivity-loop verification (Central write → Notify back) still
        // works for non-payload commands like CMD_ECHO.
        peripheralManager.setOnPayloadReceived { payload ->
            if (payload.isNotEmpty() && payload[0] == BleProtocol.CMD_JSON_CHUNK) {
                bleTransportFallback.onFrameReceived(BleProtocol.encodeFrame(payload))
            } else {
                peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
            }
        }
        // Surface the advertising-started signal so callers (Task 2 auto-connect)
        // can defer triggering until the peripheral is actually discoverable.
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
     * Task 3: Dispatch a CMD_API_REQ to the Central (PC server) over the State
     * (Notify) characteristic so it streams the requested resource back as a
     * sequence of CMD_JSON_CHUNK frames that [BleTransportFallback] reassembles.
     *
     * Returns true when a GATT subscriber was actually notified; false when
     * there is no subscriber (the Central has not yet enabled notifications),
     * or when [path] exceeds the 255-byte PathLen ceiling (the request is
     * dropped silently — the resource will surface as a BLE timeout upstream,
     * matching the Go encoder's `ErrPathTooLong` rejection).
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
        return peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
    }

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
    }
}

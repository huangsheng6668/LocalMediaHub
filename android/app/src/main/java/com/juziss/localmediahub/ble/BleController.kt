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
 * invokes the registered callback with the decoded payload; this controller
 * echoes it back via [BlePeripheralManager.notifyPayload] (re-encoded frame)
 * so the connectivity loop can be verified end-to-end.
 *
 * Zero-regression: when disabled or hardware unavailable, state is DISABLED
 * and no advertising occurs; Wi-Fi/HTTP behavior is entirely unaffected.
 *
 * @param peripheralManager the hardware seam (real impl: [AndroidBlePeripheralManager]).
 * @param bleEnabledFlow the persisted user setting (default false). Observed by
 *   the Hilt module, which calls [evaluateAvailability] on each emission.
 * @param bleHardwareAvailable returns true only if the device has a powered,
 *   authorized Bluetooth adapter.
 * @param saveBleEnabled persists the toggle (DataStore in production).
 */
@Singleton
class BleController @Inject constructor(
    private val peripheralManager: BlePeripheralManager,
    @Suppress("unused") private val bleEnabledFlow: Flow<Boolean>,
    private val bleHardwareAvailable: () -> Boolean,
    private val saveBleEnabled: suspend (Boolean) -> Unit,
) {
    private val machine = BleConnectionStateMachine()
    val connectionState: StateFlow<BleConnState> = machine.state

    init {
        // Echo: re-encode and notify back. (Minimal connectivity verification.)
        peripheralManager.setOnPayloadReceived { payload ->
            peripheralManager.notifyPayload(BleProtocol.encodeFrame(payload))
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

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
        evaluateAvailability(enabled = enabled)
    }
}

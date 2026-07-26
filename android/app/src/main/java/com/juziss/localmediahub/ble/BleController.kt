package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt singleton aggregating BLE policy: setting flag + hardware availability
 * drive the connection state machine. When BLE is off or unavailable, the
 * controller stays [BleConnState.DISABLED] and existing Wi-Fi/HTTP behavior
 * is entirely unaffected (zero-regression principle).
 *
 * @param bleEnabledFlow the persisted user setting (default false). Observed
 *   by the Task 8 Hilt module, which calls [evaluateAvailability] on each
 *   emission with the latest value.
 * @param bleHardwareAvailable returns true only if the device has a powered,
 *   authorized Bluetooth adapter. Production wires this to BluetoothAdapter;
 *   tests inject a lambda.
 * @param saveBleEnabled persists the toggle (DataStore in production).
 */
@Singleton
class BleController @Inject constructor(
    private val centralManager: BleCentralManager,
    private val bleEnabledFlow: Flow<Boolean>,
    private val bleHardwareAvailable: () -> Boolean,
    private val saveBleEnabled: suspend (Boolean) -> Unit,
) {
    private val machine = BleConnectionStateMachine()
    val connectionState: StateFlow<BleConnState> = machine.state

    init {
        // Bridge central-manager callbacks into the state machine.
        centralManager.onStateChanged = { incoming ->
            when (incoming) {
                BleConnState.CONNECTING -> machine.onConnecting()
                BleConnState.CONNECTED -> machine.onConnected()
                BleConnState.DISCONNECTED -> machine.onDisconnected()
                BleConnState.IDLE -> machine.onError()
                BleConnState.DISABLED -> machine.onBleDisabled()
                BleConnState.SCANNING -> machine.onStartScan()
            }
        }
    }

    /**
     * Re-evaluate whether BLE should be active based on the current setting
     * + hardware. Called when the setting changes or bluetooth state changes.
     *
     * The current enabled value is passed explicitly by the caller (the Task
     * 8 Hilt module collects [bleEnabledFlow] and forwards each emission here)
     * rather than read off the flow internally, so the contract works against
     * any `Flow<Boolean>` — not just `MutableStateFlow`.
     */
    fun evaluateAvailability(enabled: Boolean) {
        if (!enabled || !bleHardwareAvailable()) {
            machine.onBleDisabled()
            centralManager.stopScan()
            return
        }
        if (connectionState.value == BleConnState.DISABLED ||
            connectionState.value == BleConnState.IDLE
        ) {
            machine.onStartScan()
            centralManager.startScan()
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        saveBleEnabled(enabled)
        evaluateAvailability(enabled = enabled)
    }

    /**
     * Send a raw payload over the Command characteristic. Returns false if
     * not currently connected (caller falls back to Wi-Fi).
     */
    fun send(payload: ByteArray): Boolean {
        if (connectionState.value != BleConnState.CONNECTED) return false
        return centralManager.send(BleProtocol.encodeFrame(payload))
    }
}

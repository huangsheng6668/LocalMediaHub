package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Test-only fixtures shared across JVM unit tests that need to construct a
 * [MediaRepository] (or any other BLE-aware dependency) without exercising
 * the BLE path. [disabledBleController] drives the state machine to
 * [BleConnState.DISABLED] so the failover gate in `MediaRepository.getBookChapter`
 * never fires; [noopPeripheralManager] is the seam that lets the controller
 * be built without a real Bluetooth stack.
 */
object TestBleFixtures {

    /** A [BlePeripheralManager] that records nothing and reports no adapter. */
    class NoopPeripheralManager : BlePeripheralManager {
        override fun startAdvertising() = Unit
        override fun stopAdvertising() = Unit
        override fun setOnAdvertisingStarted(cb: (Boolean) -> Unit) = Unit
        override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) = Unit
        override fun notifyPayload(payload: ByteArray): Boolean = false
        override fun isAdapterUsable(): Boolean = false
    }

    /**
     * Returns a [BleController] whose connection state is [BleConnState.DISABLED],
     * so any code path that gates on `connectionState.value == CONNECTED`
     * (e.g. [com.juziss.localmediahub.data.MediaRepository] BLE failover)
     * behaves as if BLE is off — preserving the pre-BLE behavior of tests
     * that do not target the failover path.
     */
    fun disabledBleController(
        enabledFlow: Flow<Boolean> = flowOf(false),
    ): BleController {
        val controller = BleController(
            peripheralManager = NoopPeripheralManager(),
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {},
            // Phase 9: disabled controller never authenticates — empty token
            // keeps the (unused) auth path fail-closed in these fixtures.
            authTokenProvider = { "" },
        )
        controller.evaluateAvailability(enabled = false)
        return controller
    }
}

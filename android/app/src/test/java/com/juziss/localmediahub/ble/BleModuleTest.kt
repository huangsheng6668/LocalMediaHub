package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Smoke test that the production wiring shape (constructor + DI-style assembly)
 * produces a non-null controller. The real Hilt provider lives in
 * [com.juziss.localmediahub.di.BleModule]; this test mirrors its construction
 * using a fake [BlePeripheralManager] so no Android framework BLE stack is
 * required.
 */
class BleModuleTest {

    private class NoopPeripheralManager : BlePeripheralManager {
        override fun startAdvertising() {}
        override fun stopAdvertising() {}
        override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) {}
        override fun notifyPayload(payload: ByteArray): Boolean = false
        override fun isAdapterUsable(): Boolean = false
    }

    @Test
    fun bleController_constructsWithPeripheralManagerDeps() {
        val controller = BleController(
            peripheralManager = NoopPeripheralManager(),
            bleEnabledFlow = MutableStateFlow(false),
            bleHardwareAvailable = { false },
            saveBleEnabled = {},
        )
        assertNotNull(controller)
    }
}

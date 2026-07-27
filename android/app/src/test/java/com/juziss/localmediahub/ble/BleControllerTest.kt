package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleControllerTest {

    private class FakePeripheralManager : BlePeripheralManager {
        var advertising = false
        var received: ByteArray? = null
        var notifyResult = true
        private var cb: ((ByteArray) -> Unit)? = null

        override fun startAdvertising() { advertising = true }
        override fun stopAdvertising() { advertising = false }
        override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) { this.cb = cb }
        override fun notifyPayload(payload: ByteArray): Boolean {
            received = payload
            return notifyResult
        }
        override fun isAdapterUsable(): Boolean = true

        // Test hook to simulate a Central write.
        fun simulateWrite(payload: ByteArray) { cb?.invoke(payload) }
    }

    @Test
    fun disabledByDefault_doesNotAdvertise() {
        val enabledFlow = MutableStateFlow(false)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = false)
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
        assertTrue(!mgr.advertising)
    }

    @Test
    fun enabledWithHardware_startsAdvertising() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
        assertTrue(mgr.advertising)
    }

    @Test
    fun markConnected_setsState() {
        val enabledFlow = MutableStateFlow(true)
        val controller = BleController(
            peripheralManager = FakePeripheralManager(),
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        assertEquals(BleConnState.CONNECTED, controller.connectionState.value)
    }

    @Test
    fun markDisconnected_returnsToAdvertising() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        controller.markDisconnected()
        assertEquals(BleConnState.ADVERTISING, controller.connectionState.value)
    }

    @Test
    fun receivedPayload_notifiesBackViaPeripheralManager() {
        val enabledFlow = MutableStateFlow(true)
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        controller.markConnected()
        // Simulate the Central writing "ping"; controller should echo back via notify.
        mgr.simulateWrite("ping".toByteArray())
        // Verify notify was called (echo logic lives in controller).
        val sent = mgr.received
        assertNotNull(sent)
    }
}

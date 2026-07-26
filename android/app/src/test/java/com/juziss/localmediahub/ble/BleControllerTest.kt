package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleControllerTest {

    private class FakeCentralManager : BleCentralManager {
        var scanning = false
        var sent: ByteArray? = null
        override var onStateChanged: ((BleConnState) -> Unit)? = null
        override var onPayloadReceived: ((ByteArray) -> Unit)? = null

        override fun startScan() { scanning = true }
        override fun stopScan() { scanning = false }
        override fun send(payload: ByteArray): Boolean {
            sent = payload
            return true
        }
    }

    @Test
    fun disabledByDefault_doesNotScan() {
        val enabledFlow = MutableStateFlow(false)
        val controller = BleController(
            centralManager = FakeCentralManager(),
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability(enabled = enabledFlow.value)
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
    }

    @Test
    fun enabledButNoHardware_staysDisabled() {
        val enabledFlow = MutableStateFlow(true)
        val fake = FakeCentralManager()
        val controller = BleController(
            centralManager = fake,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { false },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability(enabled = enabledFlow.value)
        assertEquals(BleConnState.DISABLED, controller.connectionState.value)
        assertFalse(fake.scanning)
    }

    @Test
    fun enabledWithHardware_startsScanning() {
        val enabledFlow = MutableStateFlow(true)
        val fake = FakeCentralManager()
        val controller = BleController(
            centralManager = fake,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability(enabled = enabledFlow.value)
        assertEquals(BleConnState.SCANNING, controller.connectionState.value)
        assertTrue(fake.scanning)
    }

    @Test
    fun send_whenNotConnected_returnsFalse() {
        val enabledFlow = MutableStateFlow(true)
        val fake = FakeCentralManager()
        val controller = BleController(
            centralManager = fake,
            bleEnabledFlow = enabledFlow,
            bleHardwareAvailable = { true },
            saveBleEnabled = {}
        )
        controller.evaluateAvailability(enabled = enabledFlow.value) // -> SCANNING, not connected
        assertFalse(controller.send("hi".toByteArray()))
    }
}

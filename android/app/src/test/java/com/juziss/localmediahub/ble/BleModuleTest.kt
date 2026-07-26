package com.juziss.localmediahub.ble

import com.juziss.localmediahub.di.BleModule
import org.junit.Assert.assertNotNull
import org.junit.Test

class BleModuleTest {

    @Test
    fun provideBleController_returnsNonNull() {
        val central = object : BleCentralManager {
            override fun startScan() {}
            override fun stopScan() {}
            override fun send(payload: ByteArray) = false
            override var onStateChanged: ((BleConnState) -> Unit)? = null
            override var onPayloadReceived: ((ByteArray) -> Unit)? = null
        }
        val controller = BleModule.provideBleControllerForTest(
            centralManager = central,
            bleHardwareAvailable = { false }
        )
        assertNotNull(controller)
    }
}

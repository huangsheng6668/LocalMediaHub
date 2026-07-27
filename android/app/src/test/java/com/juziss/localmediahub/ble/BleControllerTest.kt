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
            bleTransportFallback = BleTransportFallback(),
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
            bleTransportFallback = BleTransportFallback(),
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
            bleTransportFallback = BleTransportFallback(),
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
            bleTransportFallback = BleTransportFallback(),
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
            bleTransportFallback = BleTransportFallback(),
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

    /**
     * C1 cross-side wire-parity guard: hand-build the EXACT byte sequence the
     * Go encoder (`server/internal/ble/protocol.go` EncodeBookChapterReqPayload)
     * produces for a known (path, index) and assert the bytes the peripheral
     * manager receives from `requestChapter` are byte-identical.
     *
     * Spec §2.2 layout (matches Go server):
     *   Physical frame: [0x01 version][uint16 BE length][payload]
     *   Payload:        [CmdID 0x11][ChapterIndex 2B BE][PathLen 1B][Path UTF-8]
     *
     * This test MUST never regress — it is the cross-side parity contract the
     * final review demanded. If `requestChapter` ever drifts back to the old
     * `[CmdID][PathLen 2B][Path][Index 4B]` layout, this assertion fails.
     */
    @Test
    fun requestChapter_emitsGoSpecWireLayout() {
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = MutableStateFlow(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )

        val path = "/books/novel.txt"
        val index = 4 // arbitrary; exercises both bytes of the uint16 BE field
        val pathBytes = path.toByteArray(Charsets.UTF_8)

        // Hand-build the expected payload exactly as the Go encoder does.
        val expectedPayload = ByteArray(1 + 2 + 1 + pathBytes.size)
        var q = 0
        expectedPayload[q++] = BleProtocol.CMD_BOOK_CHAPTER_REQ // 0x11
        expectedPayload[q++] = ((index shr 8) and 0xFF).toByte() // ChapterIndex high byte
        expectedPayload[q++] = (index and 0xFF).toByte()        // ChapterIndex low byte
        expectedPayload[q++] = (pathBytes.size and 0xFF).toByte() // PathLen (1 byte)
        System.arraycopy(pathBytes, 0, expectedPayload, q, pathBytes.size)
        val expectedFrame = BleProtocol.encodeFrame(expectedPayload)

        val notified = controller.requestChapter(path, index)

        assertTrue("requestChapter must report a notified subscriber", notified)
        val sent = mgr.received
        assertNotNull("peripheral manager must have received a frame", sent)
        // Byte-identical assertion: ANY field-width or ordering drift fails here.
        assertEquals(
            "requestChapter wire layout must match Go EncodeBookChapterReqPayload",
            expectedFrame.toList(),
            sent!!.toList(),
        )
    }

    /**
     * C1 boundary: a path whose UTF-8 length exceeds the 1-byte PathLen
     * ceiling (255) must be rejected (return false, no notify) instead of
     * silently truncating and requesting a wrong chapter — mirroring the Go
     * encoder's `ErrPathTooLong` rejection.
     */
    @Test
    fun requestChapter_rejectsPathLongerThan255Bytes() {
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = MutableStateFlow(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        val tooLong = "a".repeat(256)
        val notified = controller.requestChapter(tooLong, index = 0)
        assertTrue("over-length path must not be notified", !notified)
        assertEquals("no frame should be sent for an over-length path",
            null, mgr.received)
    }
}

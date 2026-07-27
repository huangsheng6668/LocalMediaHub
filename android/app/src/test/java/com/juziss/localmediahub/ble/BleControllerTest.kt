package com.juziss.localmediahub.ble

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
     * Task 3 cross-side wire-parity guard: hand-build the EXACT byte sequence
     * the Go encoder (`server/internal/ble/protocol.go` EncodeApiReqPayload,
     * Task 1 commit 48e7d4a) produces for a known (endpoint, path, index) and
     * assert the bytes the peripheral manager receives from `requestApi` are
     * byte-identical.
     *
     * Task 1 wire layout (matches Go server EncodeApiReqPayload):
     *   Physical frame: [0x01 version][uint16 BE length][payload]
     *   Payload:        [CmdID 0x11][Endpoint 1B][PathLen 1B][Path UTF-8]
     *                   [Index 2B BE]
     *
     * This test MUST never regress — it is the cross-side parity contract for
     * the generalized endpoint-routing wire format. It subsumes the deleted
     * chapter-only `requestChapter_emitsGoSpecWireLayout` test by exercising
     * ENDPOINT_BOOK_CHAPTER through the same generalized path.
     */
    @Test
    fun requestApi_emitsGoSpecApiReqLayout() {
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = MutableStateFlow(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )

        val path = "/books/n" // 8 bytes UTF-8
        val index = 7
        val pathBytes = path.toByteArray(Charsets.UTF_8)

        // Hand-build the expected payload exactly as the Go encoder does.
        val expectedPayload = ByteArray(1 + 1 + 1 + pathBytes.size + 2)
        var q = 0
        expectedPayload[q++] = BleProtocol.CMD_API_REQ               // 0x11
        expectedPayload[q++] = BleProtocol.ENDPOINT_BROWSE_FOLDER    // 0x03
        expectedPayload[q++] = (pathBytes.size and 0xFF).toByte()    // PathLen (1 byte)
        System.arraycopy(pathBytes, 0, expectedPayload, q, pathBytes.size)
        q += pathBytes.size
        expectedPayload[q++] = ((index shr 8) and 0xFF).toByte()     // Index high byte
        expectedPayload[q++] = (index and 0xFF).toByte()             // Index low byte
        val expectedFrame = BleProtocol.encodeFrame(expectedPayload)

        val notified = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_BROWSE_FOLDER,
            path = path,
            index = index,
        )

        assertTrue("requestApi must report a notified subscriber", notified)
        val sent = mgr.received
        assertNotNull("peripheral manager must have received a frame", sent)
        // Byte-identical assertion: ANY field-width or ordering drift fails here.
        assertEquals(
            "requestApi wire layout must match Go EncodeApiReqPayload",
            expectedFrame.toList(),
            sent!!.toList(),
        )

        // Field-level spot checks (mirror the brief's byte-index assertions on
        // the raw sent frame: [ver][len2][0x11][endpoint][pathLen][path][idx2 BE]).
        assertEquals(0x11.toByte(), sent!![3])
        assertEquals(BleProtocol.ENDPOINT_BROWSE_FOLDER, sent[4])
        assertEquals(8, sent[5].toInt() and 0xFF)
        assertEquals(0, sent[6 + 8].toInt() and 0xFF)       // index high
        assertEquals(7, sent[6 + 8 + 1].toInt() and 0xFF)   // index low
    }

    /**
     * Task 4 parity: the chapter endpoint MUST round-trip through the same
     * generalized `requestApi` wire format. This locks the migration path
     * (chapter requests now go through `requestApi(ENDPOINT_BOOK_CHAPTER,
     * path, index)` instead of the deleted `requestChapter`) and guards
     * against a future regression that re-introduces a chapter-specific
     * layout.
     */
    @Test
    fun requestApi_emitsGoSpecLayoutForChapterEndpoint() {
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = MutableStateFlow(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )

        val path = "/books/novel.txt"
        val index = 4
        val pathBytes = path.toByteArray(Charsets.UTF_8)

        val expectedPayload = ByteArray(1 + 1 + 1 + pathBytes.size + 2)
        var q = 0
        expectedPayload[q++] = BleProtocol.CMD_API_REQ
        expectedPayload[q++] = BleProtocol.ENDPOINT_BOOK_CHAPTER
        expectedPayload[q++] = (pathBytes.size and 0xFF).toByte()
        System.arraycopy(pathBytes, 0, expectedPayload, q, pathBytes.size)
        q += pathBytes.size
        expectedPayload[q++] = ((index shr 8) and 0xFF).toByte()
        expectedPayload[q++] = (index and 0xFF).toByte()
        val expectedFrame = BleProtocol.encodeFrame(expectedPayload)

        val notified = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_BOOK_CHAPTER,
            path = path,
            index = index,
        )

        assertTrue("requestApi must report a notified subscriber", notified)
        val sent = mgr.received
        assertNotNull("peripheral manager must have received a frame", sent)
        assertEquals(
            "requestApi(ENDPOINT_BOOK_CHAPTER) wire layout must match Go EncodeApiReqPayload",
            expectedFrame.toList(),
            sent!!.toList(),
        )
    }

    /**
     * Task 3 boundary: a path whose UTF-8 length exceeds the 1-byte PathLen
     * ceiling (255) must be rejected (return false, no notify) — mirroring the
     * Go encoder's `ErrPathTooLong` rejection.
     */
    @Test
    fun requestApi_rejectsPathLongerThan255() {
        val mgr = FakePeripheralManager()
        val controller = BleController(
            peripheralManager = mgr,
            bleTransportFallback = BleTransportFallback(),
            bleEnabledFlow = MutableStateFlow(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        val longPath = "a".repeat(256)
        val ok = controller.requestApi(
            endpoint = BleProtocol.ENDPOINT_BOOK_INFO,
            path = longPath,
            index = 0,
        )
        assertFalse("over-length path must not be notified", ok)
        assertEquals(
            "no frame should be sent for an over-length path",
            null, mgr.received,
        )
    }
}

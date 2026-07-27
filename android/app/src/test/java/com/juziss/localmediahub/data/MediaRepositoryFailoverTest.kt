package com.juziss.localmediahub.data

import com.juziss.localmediahub.ble.BleConnState
import com.juziss.localmediahub.ble.BleController
import com.juziss.localmediahub.ble.BlePeripheralManager
import com.juziss.localmediahub.ble.BleProtocol
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 3: MediaRepository BLE failover.
 *
 * Verifies the two spec §3.2 branches:
 *  1. HTTP IOException + BLE CONNECTED → repository routes through
 *     [BleTransportFallback], returns BLE-sourced [BookChapterContent],
 *     and sets `isBleDegraded = true`.
 *  2. BLE NOT connected → repository does NOT fail over; the original HTTP
 *     error is returned and `isBleDegraded` stays false.
 *
 * The HTTP path is exercised end-to-end against an unreachable URL so the
 * real OkHttp stack throws a genuine [java.net.ConnectException] (which is
 * an [java.io.IOException]). No HTTP mocking framework is needed. The BLE
 * path uses a [SimulatingPeripheralManager] whose `notifyPayload` (called by
 * [BleController.requestChapter] to dispatch CMD_BOOK_CHAPTER_REQ) feeds the
 * prebuilt CHUNK payloads straight back into the registered payload
 * callback, exactly as a real Central would when it responds to the request
 * over GATT. [BleController] then routes those CHUNK payloads into the same
 * [BleTransportFallback] instance the repository reads from.
 */
class MediaRepositoryFailoverTest {

    /**
     * Fake [BlePeripheralManager] that simulates the Central (PC server)
     * responding to a CMD_BOOK_CHAPTER_REQ notify by writing the prebuilt
     * CHUNK payloads back through the registered payload callback.
     */
    private class SimulatingPeripheralManager(
        private val responsePayloads: List<ByteArray>,
    ) : BlePeripheralManager {
        var advertising = false
        private var cb: ((ByteArray) -> Unit)? = null

        override fun startAdvertising() { advertising = true }
        override fun stopAdvertising() { advertising = false }
        override fun setOnPayloadReceived(cb: (ByteArray) -> Unit) { this.cb = cb }
        override fun notifyPayload(payload: ByteArray): Boolean {
            // The controller just dispatched a CMD_BOOK_CHAPTER_REQ frame over
            // GATT (notifyPayload takes an already frame-encoded payload, per
            // the existing echo path in BleController.init). Decode it to read
            // the CmdID; on a chapter request, simulate the Central streaming
            // back each CHUNK payload via the registered callback.
            val decoded = BleProtocol.decodeFrame(payload)
            if (decoded != null && decoded.payload.isNotEmpty() &&
                decoded.payload[0] == BleProtocol.CMD_BOOK_CHAPTER_REQ) {
                responsePayloads.forEach { cb?.invoke(it) }
            }
            return true
        }
        override fun isAdapterUsable(): Boolean = true
    }

    /**
     * Build a single CHUNK application payload (CmdID 0x12) carrying [json]
     * as chunk [chunkIndex] of [totalChunks]. This is the decoded payload the
     * Central writes to the Command characteristic; [BleController]'s payload
     * callback re-encodes it before handing it to [BleTransportFallback].
     */
    private fun chunkPayload(totalChunks: Int, chunkIndex: Int, totalBlocks: Int, json: ByteArray): ByteArray {
        val chunkLen = json.size
        val payload = ByteArray(1 + 2 + 2 + 2 + 2 + chunkLen)
        var p = 0
        payload[p++] = BleProtocol.CMD_BOOK_CHAPTER_CHUNK
        payload[p++] = ((totalChunks shr 8) and 0xFF).toByte()
        payload[p++] = (totalChunks and 0xFF).toByte()
        payload[p++] = ((chunkIndex shr 8) and 0xFF).toByte()
        payload[p++] = (chunkIndex and 0xFF).toByte()
        payload[p++] = ((totalBlocks shr 8) and 0xFF).toByte()
        payload[p++] = (totalBlocks and 0xFF).toByte()
        payload[p++] = ((chunkLen shr 8) and 0xFF).toByte()
        payload[p++] = (chunkLen and 0xFF).toByte()
        System.arraycopy(json, 0, payload, p, chunkLen)
        return payload
    }

    /** A repository + controller whose HTTP base URL is unreachable and BLE is [state]. */
    private fun repoWithBle(
        state: MutableStateFlow<BleConnState>,
        responsePayloads: List<ByteArray>,
    ): MediaRepository {
        val fallback = BleTransportFallback()
        val peripheral = SimulatingPeripheralManager(responsePayloads)
        val controller = BleController(
            peripheralManager = peripheral,
            bleTransportFallback = fallback,
            bleEnabledFlow = kotlinx.coroutines.flow.flowOf(true),
            bleHardwareAvailable = { true },
            saveBleEnabled = {},
        )
        controller.evaluateAvailability(enabled = true)
        if (state.value == BleConnState.CONNECTED) {
            controller.markConnected()
        }
        val serverConfig = ServerConfig().apply {
            // Reserved loopback port with no listener → ConnectException (IOException).
            setBaseUrl("http://127.0.0.1:1")
        }
        return MediaRepository(
            http = OkHttpClient(),
            serverConfig = serverConfig,
            bleController = controller,
            bleTransportFallback = fallback,
        )
    }

    @Test
    fun fallsBackToBleWhenHttpFailsAndBleConnected() = runBlocking {
        val json = "[{\"type\":\"text\",\"value\":\"ble-body\"}]".toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json))
        val repo = repoWithBle(MutableStateFlow(BleConnState.CONNECTED), payloads)

        val result = repo.getBookChapter(path = "/book.txt", index = 0)

        assertTrue("expected Success from BLE failover, got $result", result is NetworkResult.Success)
        val content = (result as NetworkResult.Success).data
        assertEquals("ble-body", content.blocks.single().value)
        assertTrue("isBleDegraded must flip true after BLE-served chapter",
            repo.isBleDegraded.value)
    }

    @Test
    fun doesNotFailOverWhenBleDisconnected() = runBlocking {
        val json = "[{\"type\":\"text\",\"value\":\"ble-body\"}]".toByteArray(Charsets.UTF_8)
        val payloads = listOf(chunkPayload(totalChunks = 1, chunkIndex = 0, totalBlocks = 1, json = json))
        val repo = repoWithBle(MutableStateFlow(BleConnState.DISCONNECTED), payloads)

        val result = repo.getBookChapter(path = "/book.txt", index = 0)

        assertFalse("must NOT return Success when BLE is disconnected", result is NetworkResult.Success)
        assertTrue("expected Error result when failover skipped, got $result",
            result is NetworkResult.Error)
        assertFalse("isBleDegraded must stay false when failover did not fire",
            repo.isBleDegraded.value)
    }
}

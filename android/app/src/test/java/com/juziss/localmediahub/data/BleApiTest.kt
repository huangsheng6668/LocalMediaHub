package com.juziss.localmediahub.data

import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Task 8: BleApi MockWebServer tests.
 *
 * Adapted from the plan: the real [NetworkResult] sealed type (see
 * `network/NetworkResult.kt`) uses `Success(data)` and `Error(message, code?)`,
 * so tests read `.data` (not `.value` as the plan draft assumed).
 */
class BleApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: BleApi

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val serverConfig = ServerConfig().apply {
            setBaseUrl(server.url("/").toString().trimEnd('/'))
            setToken("test-token")
        }
        api = BleApi(OkHttpClient(), serverConfig)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun scan_parsesDevices() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"devices":[{"id":"AA:BB","name":"Pixel","rssi":-45}]}""",
            ),
        )
        val result = api.scan()
        assertTrue("expected Success, got $result", result is NetworkResult.Success)
        val devices = (result as NetworkResult.Success).data
        assertEquals(1, devices.size)
        assertEquals("AA:BB", devices[0].id)
        assertEquals("Pixel", devices[0].name)
        assertEquals(-45, devices[0].rssi)
    }

    @Test
    fun scan_errorOnUnavailable() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"devices":[],"error":"ble unavailable"}"""),
        )
        val result = api.scan()
        assertTrue("expected Error, got $result", result is NetworkResult.Error)
        assertEquals("ble unavailable", (result as NetworkResult.Error).message)
    }

    @Test
    fun connect_returnsTrue() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"connected":true}"""))
        val result = api.connect("AA:BB")
        assertTrue("expected Success, got $result", result is NetworkResult.Success)
        assertTrue((result as NetworkResult.Success).data)
    }

    @Test
    fun send_returnsEcho() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"echo":"pong"}"""))
        val result = api.send("ping")
        assertTrue("expected Success, got $result", result is NetworkResult.Success)
        assertEquals("pong", (result as NetworkResult.Success).data)
    }

    @Test
    fun send_nullEchoOnError() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"echo":null,"error":"timeout"}"""))
        val result = api.send("ping")
        assertTrue("expected Error, got $result", result is NetworkResult.Error)
        assertEquals("timeout", (result as NetworkResult.Error).message)
    }
}

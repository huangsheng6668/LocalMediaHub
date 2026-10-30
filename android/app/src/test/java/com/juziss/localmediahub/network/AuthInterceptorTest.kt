package com.juziss.localmediahub.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `empty token does not add Authorization header`() {
        val interceptor = AuthInterceptor { "" } // tokenProvider returns empty
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/test").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `non-empty token adds Bearer Authorization header`() {
        val interceptor = AuthInterceptor { "my-secret" }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/test").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret", recorded.getHeader("Authorization"))
    }

    @Test
    fun `matching configured origin receives Bearer Authorization header`() {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val interceptor = AuthInterceptor(
            tokenProvider = { "my-secret" },
            baseUrlProvider = { baseUrl },
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/api/v1/health").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret", recorded.getHeader("Authorization"))
    }

    @Test
    fun `cross-origin LAN scan host does NOT receive Bearer Authorization header`() {
        val interceptor = AuthInterceptor(
            tokenProvider = { "my-secret" },
            baseUrlProvider = { "http://192.168.1.250:8000" },
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        // Request goes to MockWebServer (127.0.0.1:<ephemeral>), which differs from 192.168.1.250:8000
        client.newCall(Request.Builder().url(server.url("/").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertNull(
            "Bearer token must NEVER be sent to non-matching origins during LAN scan",
            recorded.getHeader("Authorization"),
        )
    }

    @Test
    fun `blank configured baseUrl does NOT leak Bearer token`() {
        val interceptor = AuthInterceptor(
            tokenProvider = { "my-secret" },
            baseUrlProvider = { "" },
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `existing Authorization header is preserved and not duplicated`() {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val interceptor = AuthInterceptor(
            tokenProvider = { "my-secret" },
            baseUrlProvider = { baseUrl },
        )
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(
            Request.Builder()
                .url(server.url("/test").toString())
                .header("Authorization", "Bearer custom-override")
                .build()
        ).execute()

        val recorded = server.takeRequest()
        assertEquals(listOf("Bearer custom-override"), recorded.headers.values("Authorization"))
    }
}

package com.juziss.localmediahub.network

import okhttp3.Interceptor
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
}

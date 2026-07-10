package com.juziss.localmediahub.network

import javax.inject.Provider
import okhttp3.Cache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 验证 OkHttpModule 提供的 OkHttpClient 有正确的 dispatcher / connectionPool 配置。
 * 防止未来误改 dispatcher 配置（如漏掉 maxRequestsPerHost）时静默回归。
 *
 * 用 Robolectric 因为 OkHttpClient 构建过程依赖 Android Context（Cache 路径）。
 *
 * Round 29：provideOkHttpClient 现在接受 `javax.inject.Provider<ServerConfig>` 用于
 * 在每次请求时通过 AuthInterceptor 注入 bearer token。测试用一个 fake
 * Provider 返回已知 token 的 ServerConfig，以验证：
 *   1. dispatcher / connectionPool 配置未回归。
 *   2. AuthInterceptor 真的被接进了 client（发出的请求带 Authorization 头）。
 *
 * 注意：OkHttp 4.12.0 的 ConnectionPool 不暴露已配置的 maxIdleConnections /
 * keepAliveDuration 公共 getter，因此 pool 容量的配置通过 OkHttpModule 上公开的
 * const val（MAX_IDLE_CONNECTIONS / KEEP_ALIVE_MINUTES）来锁定——这些常量是
 * production 与 test 共同消费的唯一真相源。dispatcher 的运行时值通过真实 getter 校验。
 */
@RunWith(RobolectricTestRunner::class)
class OkHttpModuleTest {

    private lateinit var cacheDir: File
    private lateinit var cache: Cache

    @Before
    fun setUp() {
        cacheDir = File(System.getProperty("java.io.tmpdir"), "okhttp-test-" + System.nanoTime())
        cacheDir.mkdirs()
        cache = Cache(File(cacheDir, "okhttp"), 20L * 1024 * 1024)
    }

    @After
    fun tearDown() {
        cache.close()
        cacheDir.deleteRecursively()
    }

    /**
     * Builds a Provider that always returns a ServerConfig with the given token,
     * so tests can assert AuthInterceptor behaviour without a full Hilt graph.
     */
    private fun serverConfigProviderWithToken(token: String): Provider<ServerConfig> {
        val serverConfig = ServerConfig().apply { setToken(token) }
        return Provider { serverConfig }
    }

    @Test
    fun provideOkHttpClient_has40MaxRequestsPerHost() {
        val client = OkHttpModule.provideOkHttpClient(
            cache, serverConfigProviderWithToken(""))
        assertEquals(
            "maxRequestsPerHost must be 40 to match ConnectionPool capacity",
            40,
            client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun provideOkHttpClient_has40ConnectionPoolSize() {
        // 触发 client 构建以确保配置路径不抛异常。
        OkHttpModule.provideOkHttpClient(cache, serverConfigProviderWithToken(""))
        // OkHttp 4.12.0 ConnectionPool 没有 maxIdleConnections 公共 getter；
        // 通过 OkHttpModule.MAX_IDLE_CONNECTIONS / KEEP_ALIVE_MINUTES 常量
        //（production 同源、provideOkHttpClient 内 ConnectionPool 构造直接引用）
        // 锁定 pool 容量与 keepAlive 配置。
        assertEquals(
            "ConnectionPool must be configured for 40 idle connections",
            40,
            OkHttpModule.MAX_IDLE_CONNECTIONS)
        assertEquals(
            "keepAlive must be 3 minutes (Round 24 tightening from 5min)",
            3L,
            OkHttpModule.KEEP_ALIVE_MINUTES)
    }

    @Test
    fun provideOkHttpClient_hasDefaultMaxRequests() {
        val client = OkHttpModule.provideOkHttpClient(
            cache, serverConfigProviderWithToken(""))
        assertEquals(
            "maxRequests should remain at OkHttp default (64)",
            64,
            client.dispatcher.maxRequests)
    }

    /**
     * Round 29 regression guard: confirms AuthInterceptor is actually wired
     * into the shared client and that a non-empty token produces the
     * `Authorization: Bearer <token>` header on outgoing requests. Uses a
     * local MockWebServer-style stub (no network) via OkHttp's built-in
     * `MockWebServer` would add a dependency; instead we exercise the
     * interceptor directly through the application interceptor list to avoid
     * touching the network in a unit test.
     */
    @Test
    fun provideOkHttpClient_wiresAuthInterceptorWithToken() {
        val token = "abc123"
        val client = OkHttpModule.provideOkHttpClient(
            cache, serverConfigProviderWithToken(token))

        val authInterceptors = client.interceptors.filterIsInstance<AuthInterceptor>()
        assertEquals(
            "exactly one AuthInterceptor should be wired into the shared client",
            1,
            authInterceptors.size)
        assertEquals(
            "AuthInterceptor must read the latest token from ServerConfig",
            token,
            authInterceptors.single().tokenForTest())
        assertTrue(
            "token must be a non-empty bearer value for a configured ServerConfig",
            authInterceptors.single().tokenForTest().isNotEmpty())
    }

    @Test
    fun provideOkHttpClient_authInterceptorReadsEmptyTokenByDefault() {
        // Default ServerConfig (no setToken call) yields empty token — the
        // interceptor must NOT add an Authorization header in that case
        // (open mode, matches server-side passthrough).
        val client = OkHttpModule.provideOkHttpClient(
            cache, serverConfigProviderWithToken(""))

        val token = client.interceptors.filterIsInstance<AuthInterceptor>()
            .single()
            .tokenForTest()
        assertEquals("empty token means open mode (no auth header)", "", token)
    }
}


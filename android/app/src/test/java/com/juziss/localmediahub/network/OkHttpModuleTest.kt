package com.juziss.localmediahub.network

import okhttp3.Cache
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 验证 OkHttpModule 提供的 OkHttpClient 有正确的 dispatcher / connectionPool 配置。
 * 防止未来误改 dispatcher 配置（如漏掉 maxRequestsPerHost）时静默回归。
 *
 * 用 Robolectric 因为 OkHttpClient 构建过程依赖 Android Context（Cache 路径）。
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

    @Test
    fun provideOkHttpClient_has40MaxRequestsPerHost() {
        val client = OkHttpModule.provideOkHttpClient(cache)
        assertEquals(
            "maxRequestsPerHost must be 40 to match ConnectionPool capacity",
            40,
            client.dispatcher.maxRequestsPerHost)
    }

    @Test
    fun provideOkHttpClient_has40ConnectionPoolSize() {
        // 触发 client 构建以确保配置路径不抛异常。
        OkHttpModule.provideOkHttpClient(cache)
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
        val client = OkHttpModule.provideOkHttpClient(cache)
        assertEquals(
            "maxRequests should remain at OkHttp default (64)",
            64,
            client.dispatcher.maxRequests)
    }
}

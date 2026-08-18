package com.juziss.localmediahub.network

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.juziss.localmediahub.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * OkHttp Interceptor that injects `Authorization: Bearer <token>` header
 * when the tokenProvider returns a non-empty value. Empty token = no header
 * (open mode, matches server-side passthrough).
 *
 * The tokenProvider is a lambda returning String so the interceptor always
 * reads the latest value (tokens can change at runtime via Settings).
 */
class AuthInterceptor(private val tokenProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenProvider()
        val request = if (token.isEmpty()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }

    /**
     * Test-only accessor for the current token value. Lets unit tests verify
     * the interceptor was wired with the right tokenProvider without making
     * a network call.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    fun tokenForTest(): String = tokenProvider()
}

/**
 * Hilt module providing a singleton [OkHttpClient] + [Cache] shared across
 * MediaRepository, ServerConfig, VideoPlayerScreen, and ConnectionViewModel.
 *
 * Round 17 collapses 4 historical OkHttpClient instances into one. Cache
 * lives under `cacheDir/okhttp/` (sibling to Coil's `cacheDir/coil/`) and is
 * capped at 20MB. TTL is controlled by server-side `Cache-Control` headers
 * added in Round 17 C1.
 *
 * Round 29 (Phase 1 Bearer Token auth) wires [AuthInterceptor] into the
 * shared client. The interceptor reads the current token from
 * [ServerConfig.getTokenSnapshot] on every request. Because ServerConfig
 * previously depended on OkHttpClient in its constructor, injecting
 * ServerConfig directly here would form a Hilt cycle
 * (provideOkHttpClient -> ServerConfig -> OkHttpClient). We break the cycle
 * with `javax.inject.Provider<ServerConfig>` (JSR-330 lazy lookup) and
 * removed `httpClient` from ServerConfig's constructor.
 *
 * Call sites needing custom timeouts (e.g. ConnectionViewModel LAN scan
 * with 250ms connect timeout) use `client.newBuilder()` to derive a child
 * client that shares the connection pool.
 */
@Module
@InstallIn(SingletonComponent::class)
object OkHttpModule {

    private const val CACHE_DIR = "okhttp"
    private const val CACHE_SIZE_BYTES = 20L * 1024 * 1024 // 20MB

    // Round 24: OkHttp dispatcher / connection-pool tuning. Exposed as public
    // const so OkHttpModuleTest can lock them against regression — OkHttp 4.x
    // ConnectionPool exposes no public getter for the configured max idle /
    // keep-alive, so the configured values are only observable via these
    // constants that both production and test consume.
    const val MAX_REQUESTS_PER_HOST = 40
    const val MAX_IDLE_CONNECTIONS = 40
    const val KEEP_ALIVE_MINUTES = 3L

    @Provides
    @Singleton
    fun provideOkHttpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cache: Cache,
        // javax.inject.Provider (JSR-330) gives lazy lookup and breaks the
        // Hilt cycle that would otherwise form (provideOkHttpClient ->
        // ServerConfig -> OkHttpClient). The lambda captures the provider,
        // not an instance, so the interceptor reads the latest token on
        // each request.
        serverConfigProvider: javax.inject.Provider<ServerConfig>,
    ): OkHttpClient {
        // Round 24: 解除 OkHttp 默认 maxRequestsPerHost=5 的隐藏瓶颈。
        // 40 与 ConnectionPool 容量对齐；C3 场景 2-3 台 × 12-15 并发 ≈ 30-45，
        // 40 给余量。
        val dispatcher = Dispatcher().apply {
            maxRequests = 64                  // OkHttp 默认，不动
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST // 默认 5 → 40，与 ConnectionPool 对齐
        }

        val builder = OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            // Real-device degraded-reading finding: with Wi-Fi off (mobile
            // data routing a 192.168.x.x SYN into a black hole), every
            // request burned the FULL connect timeout before the BLE
            // failover could start — opening a book meant two dead 30s
            // waits. A healthy LAN establishes TCP in <100ms; 2s is generous
            // headroom while capping the failover entry latency.
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            // Round 24: 扩到 40 与 dispatcher 对齐；keepAlive 5min → 3min
            // （缩略图访问是密集短脉冲，长时间闲置占着服务端 FD 意义不大）。
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            // Round 29: bearer token injection. AuthInterceptor reads the
            // latest token via the lazy provider on every request.
            .addInterceptor(AuthInterceptor { serverConfigProvider.get().getTokenSnapshot() })

        // Verbose HTTP logging only in debug; release builds skip the
        // interceptor to save memory and avoid leaking paths in logcat.
        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}

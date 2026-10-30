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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * OkHttp Interceptor that injects `Authorization: Bearer <token>` header
 * when the tokenProvider returns a non-empty value AND (when [baseUrlProvider]
 * is supplied) the outgoing request targets the same origin (`scheme`, `host`,
 * `port`) as the configured server.
 *
 * Scoping by origin prevents leaking the user's Bearer token to arbitrary
 * LAN hosts when derived clients (such as subnet discovery scanners) share
 * the singleton OkHttpClient.
 */
class AuthInterceptor(
    private val tokenProvider: () -> String,
    private val baseUrlProvider: (() -> String)? = null,
) : Interceptor {
    constructor(tokenProvider: () -> String) : this(tokenProvider, null)
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val original = chain.request()
        val token = tokenProvider()
        if (token.isEmpty() || original.header("Authorization") != null) {
            return chain.proceed(original)
        }
        if (baseUrlProvider != null) {
            val baseStr = baseUrlProvider()
            val baseUrl = baseStr.takeIf { it.isNotBlank() }?.toHttpUrlOrNull()
                ?: return chain.proceed(original)
            val reqUrl = original.url
            val sameOrigin = reqUrl.scheme.equals(baseUrl.scheme, ignoreCase = true) &&
                reqUrl.host.equals(baseUrl.host, ignoreCase = true) &&
                reqUrl.port == baseUrl.port
            if (!sameOrigin) {
                return chain.proceed(original)
            }
        }
        val request = original.newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
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
 */
@Module
@InstallIn(SingletonComponent::class)
object OkHttpModule {

    private const val CACHE_DIR = "okhttp"
    private const val CACHE_SIZE_BYTES = 20L * 1024 * 1024 // 20MB

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
        serverConfigProvider: javax.inject.Provider<ServerConfig>,
    ): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = MAX_REQUESTS_PER_HOST
        }

        val builder = OkHttpClient.Builder()
            .cache(cache)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
            .addInterceptor(
                AuthInterceptor(
                    tokenProvider = { serverConfigProvider.get().getTokenSnapshot() },
                    baseUrlProvider = { serverConfigProvider.get().getBaseUrl() },
                )
            )

        if (BuildConfig.DEBUG) {
            builder.addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
        }

        return builder.build()
    }
}

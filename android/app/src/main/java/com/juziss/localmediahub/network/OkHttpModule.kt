package com.juziss.localmediahub.network

import android.content.Context
import com.juziss.localmediahub.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing a singleton [OkHttpClient] + [Cache] shared across
 * MediaRepository, ServerConfig, VideoPlayerScreen, and ConnectionViewModel.
 *
 * Round 17 collapses 4 historical OkHttpClient instances into one. Cache
 * lives under `cacheDir/okhttp/` (sibling to Coil's `cacheDir/coil/`) and is
 * capped at 20MB. TTL is controlled by server-side `Cache-Control` headers
 * added in Round 17 C1.
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

    @Provides
    @Singleton
    fun provideOkHttpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, CACHE_DIR), CACHE_SIZE_BYTES)

    @Provides
    @Singleton
    fun provideOkHttpClient(cache: Cache): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .cache(cache)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(15, 5, TimeUnit.MINUTES))

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

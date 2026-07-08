package com.juziss.localmediahub

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.juziss.localmediahub.native.NativeDecoderFactory
import com.juziss.localmediahub.util.cleanupOldEntries
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class LocalMediaHubApplication : Application(), SingletonImageLoader.Factory {

    companion object {
        /** Coil diskCache directory name; kept in sync with newImageLoader() below. */
        const val DISK_CACHE_DIR = "coil"

        /** Cache entries older than this (by mtime) are deleted on app startup.
         *  See CacheCleanup.kt doc for the mtime-vs-access-time limitation. */
        private const val DISK_CACHE_MAX_AGE_DAYS = 30

        /**
         * Round 24 Task 8: cap on concurrent image fetch + decode work.
         *
         * Coil 3.x exposes per-stage coroutine contexts. Wiring
         * `Dispatchers.Default.limitedParallelism(12)` into both
         * `fetcherCoroutineContext` and `decoderCoroutineContext` caps the
         * number of simultaneously in-flight network reads and bitmap decodes
         * at 12 — matching the server-side thumbnail worker pool size (Task 1).
         * Combined with LazyGrid's automatic AsyncImage onDispose →
         * request.dispose(), scrolling releases slots so visible items load
         * without queueing behind off-screen ones.
         */
        private const val MAX_CONCURRENT_IMAGE_REQUESTS = 12
    }

    /**
     * Hilt-injected singleton OkHttpClient — shared with MediaRepository,
     * VideoPlayerScreen, etc. (see OkHttpModule). Wiring it into Coil's
     * `OkHttpNetworkFetcherFactory` lets Coil's network fetcher reuse the
     * same connection pool.
     *
     * NOTE on Cache-Control: Coil 3.x's NetworkFetcher bypasses OkHttp's
     * Cache and does not respect `Cache-Control` headers by default (this
     * changed from Coil 2.x which honored them via `respectCacheHeaders`).
     * The server-side `setMediaCacheHeaders` calls (Round 3) are therefore
     * ineffective for thumbnail responses and will be removed in a follow-up
     * commit. Coil's own DiskCache (100MB, configured below) still provides
     * LRU-based cache eviction.
     */
    @Inject
    lateinit var okHttpClient: OkHttpClient

    /** Holds SupervisorJob so cleanup coroutine isn't cancelled prematurely and
     *  the scope can be cancelled structurally if needed later. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Startup cleanup: delete Coil diskCache entries unmodified for >30 days.
        // applicationScope is bound to Application; Dispatchers.IO ensures no main-thread block.
        applicationScope.launch {
            cleanupOldEntries(
                cacheDir = cacheDir.resolve(DISK_CACHE_DIR),
                maxAgeDays = DISK_CACHE_MAX_AGE_DAYS,
            )
        }
    }

    override fun newImageLoader(context: Context): ImageLoader {
        // Round 24 Task 8: cap concurrent fetch + decode at 12. The same
        // limited dispatcher is shared by both stages so the total in-flight
        // image work is bounded; limitedParallelism returns a reusable
        // CoroutineDispatcher with an internal semaphore.
        val limiter = Dispatchers.Default.limitedParallelism(MAX_CONCURRENT_IMAGE_REQUESTS)
        return ImageLoader.Builder(context)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
                // coil-network-okhttp: register the OkHttp-backed network
                // fetcher, wired to the Hilt-managed OkHttpClient so Coil
                // shares the same connection pool as the rest of the app.
                // Coil 3.x's NetworkFetcher bypasses OkHttp's Cache and does
                // not respect Cache-Control by default — see the note on
                // `okHttpClient` above.
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
            }
            .fetcherCoroutineContext(limiter)
            .decoderCoroutineContext(limiter)
            .crossfade(200) // Smooth fade animation of 200ms
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25) // 15% → 25%：全屏图片位图更多余量，减少滚动淘汰/重解码
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(DISK_CACHE_DIR))
                    .maxSizeBytes(100L * 1024 * 1024) // Disk cache capped at 100MB
                    .build()
            }
            .build()
    }
}

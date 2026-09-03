package com.juziss.localmediahub

import android.app.Application
import android.content.Context
import androidx.emoji2.bundled.BundledEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import com.juziss.localmediahub.ble.BleDegradedImageInterceptor
import com.juziss.localmediahub.data.BatchThumbnailFetcherFactory
import com.juziss.localmediahub.native.NativeDecoderFactory
import com.juziss.localmediahub.network.ServerConfig
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

    /** Current server base URL, consumed by the batch thumbnail fetcher. */
    @Inject
    lateinit var serverConfig: ServerConfig

    /** Holds SupervisorJob so cleanup coroutine isn't cancelled prematurely and
     *  the scope can be cancelled structurally if needed later. */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        initEmojiCompat()
        // Startup cleanup: delete Coil diskCache entries unmodified for >30 days.
        // applicationScope is bound to Application; Dispatchers.IO ensures no main-thread block.
        applicationScope.launch {
            cleanupOldEntries(
                cacheDir = cacheDir.resolve(DISK_CACHE_DIR),
                maxAgeDays = DISK_CACHE_MAX_AGE_DAYS,
            )
        }
        registerWifiTransportMonitor()
    }

    /**
     * Initialize EmojiCompat with bundled font (offline support).
     * setReplaceAll(true) ensures all modern emojis are rendered consistently using the bundled
     * color emoji font across all Android versions, devices without GMS, and all reader font families
     * (System, Serif, Kaiti, Monospace).
     */
    private fun initEmojiCompat() {
        try {
            val emojiConfig = BundledEmojiCompatConfig(this)
                .setReplaceAll(true)
            EmojiCompat.init(emojiConfig)
        } catch (e: Exception) {
            android.util.Log.w("LocalMediaHubApp", "Failed to initialize EmojiCompat", e)
        }
    }

    /**
     * Global Wi-Fi transport monitor (degraded-reading latency fix): keeps
     * [com.juziss.localmediahub.ble.BleDegradedState.wifiOnline] live via a
     * ConnectivityManager network callback, so transport selection can be
     * decided from process-wide state instead of burning the HTTP connect
     * timeout on every request while Wi-Fi is off. Registration failures
     * (exotic device restrictions) leave the default `wifiOnline = true`,
     * i.e. the previous try-HTTP-first behavior — fail-open to the old path.
     */
    private fun registerWifiTransportMonitor() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return
        // TRANSPORT_WIFI only (association), NOT NET_CAPABILITY_VALIDATED:
        // a home LAN without internet never validates, and actual server
        // reachability is already covered by the HTTP failure breaker.
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                com.juziss.localmediahub.ble.BleDegradedState.wifiOnline = true
            }

            override fun onLost(network: android.net.Network) {
                com.juziss.localmediahub.ble.BleDegradedState.wifiOnline = false
            }
        }
        try {
            cm.registerNetworkCallback(request, callback)
            // Seed the initial value: a Wi-Fi network may already be up.
            com.juziss.localmediahub.ble.BleDegradedState.wifiOnline =
                cm.activeNetwork?.let { net ->
                    cm.getNetworkCapabilities(net)?.hasTransport(
                        android.net.NetworkCapabilities.TRANSPORT_WIFI,
                    ) == true
                } == true
        } catch (e: Exception) {
            android.util.Log.w("LocalMediaHubApp", "wifi monitor registration failed: ${e.message}")
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
                // Batch thumbnail fetcher: collapses the grid's N+1 thumbnail
                // requests into one POST /api/v1/media/thumbnails per scroll
                // frame. Registered BEFORE the OkHttp network fetcher so our
                // thumbnail URLs never fall through to a per-image request.
                add(BatchThumbnailFetcherFactory(okHttpClient) { serverConfig.getBaseUrl() })
                // coil-network-okhttp: register the OkHttp-backed network
                // fetcher, wired to the Hilt-managed OkHttpClient so Coil
                // shares the same connection pool as the rest of the app.
                // Coil 3.x's NetworkFetcher bypasses OkHttp's Cache and does
                // not respect Cache-Control by default — see the note on
                // `okHttpClient` above.
                add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient }))
                // Task 5 §1.3: short-circuit image requests to a local
                // placeholder while the media list is being served over the
                // BLE fallback transport. The interceptor reads
                // BleDegradedState (mirrored from MediaRepository.isBleDegraded)
                // on every request and rewrites HTTP(S) URLs to the placeholder
                // drawable so the OkHttp fetcher is bypassed entirely.
                // Registered LAST so it runs FIRST in the chain (Coil runs
                // interceptors in registration order for outbound requests).
                add(BleDegradedImageInterceptor())
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

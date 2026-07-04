package com.juziss.localmediahub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.juziss.localmediahub.native.NativeDecoderFactory
import com.juziss.localmediahub.util.cleanupOldEntries
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class LocalMediaHubApplication : Application(), ImageLoaderFactory {

    companion object {
        /** Coil diskCache directory name; kept in sync with newImageLoader() below. */
        const val DISK_CACHE_DIR = "coil"

        /** Cache entries older than this (by mtime) are deleted on app startup.
         *  See CacheCleanup.kt doc for the mtime-vs-access-time limitation. */
        private const val DISK_CACHE_MAX_AGE_DAYS = 30
    }

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

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(200) // Smooth fade animation of 200ms
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25) // 15% → 25%：全屏图片位图更多余量，减少滚动淘汰/重解码
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(DISK_CACHE_DIR))
                    .maxSizeBytes(100L * 1024 * 1024) // Disk cache capped at 100MB
                    .build()
            }
            .respectCacheHeaders(true) // Round 12: honor server Cache-Control from round 3
            .build()
    }
}

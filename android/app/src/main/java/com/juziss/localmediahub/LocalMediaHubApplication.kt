package com.juziss.localmediahub

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.juziss.localmediahub.native.NativeDecoderFactory

class LocalMediaHubApplication : Application(), ImageLoaderFactory {

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
                    .maxSizePercent(0.15) // Memory cache capped at 15%
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil"))
                    .maxSizeBytes(100L * 1024 * 1024) // Disk cache capped at 100MB
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}

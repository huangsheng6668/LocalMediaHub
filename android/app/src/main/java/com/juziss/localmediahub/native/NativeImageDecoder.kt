package com.juziss.localmediahub.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.juziss.localmediahub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Native image decoder backed by Rust (`liblocalmedia_native.so`).
 *
 * Round 11 Task 3 rewrite: the legacy C++ decoder (`native-image-decoder`
 * library with `nativeDecodeJpeg` / `nativeDecodeWebp` / `nativeGetImageInfo`)
 * is gone. The new entry points are `nativeDecodeByteArray` and
 * `nativeDecodeDirect` — both do format detection inside Rust so the Kotlin
 * side no longer needs the pre-routing `getImageInfo` call (one JNI hop per
 * decode instead of two).
 *
 * Host-JVM fallback pattern (Tasks 1 + 2): `init` wraps
 * `System.loadLibrary` in a `try/catch UnsatisfiedLinkError`. On host JVM
 * unit tests (Robolectric) the native library is absent, `nativeAvailable`
 * stays `false`, and `decode()` transparently falls back to
 * `BitmapFactory`. The flag is also used by callers (e.g.
 * `NativeDecoderFactory`) to short-circuit native routing.
 *
 * The `getImageInfo()` method and the `ImageInfo` data class that the old
 * C++ backend exposed have been deleted — Rust's `nativeDecodeByteArray`
 * sniffs the magic bytes itself and routes to `jpeg::decode_scaled` /
 * `webp::decode_scaled` internally.
 */
object NativeImageDecoder {

    private const val TAG = "NativeImageDecoder"

    @Volatile
    var nativeAvailable: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("localmedia_native")
            nativeAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            if (BuildConfig.DEBUG) {
                // Debug build (incl. Robolectric unit tests on host JVM):
                // native lib absent is expected; fall back gracefully.
                Log.w(TAG, "liblocalmedia_native.so unavailable, using BitmapFactory fallback", e)
            } else {
                // Release build: missing .so means the build pipeline broke or
                // R8 stripped the symbol. Silent fallback would hide a critical
                // regression. Crash loudly so it surfaces in crash reports /
                // user feedback.
                throw IllegalStateException(
                    "liblocalmedia_native.so failed to load — production builds must include the native library",
                    e,
                )
            }
        }
    }

    const val FORMAT_UNKNOWN = 0
    const val FORMAT_JPEG = 1
    const val FORMAT_WEBP = 2
    const val FORMAT_PNG = 3
    const val FORMAT_HEIC = 4

    // JNI: byte-array path — single copy from JVM heap into the Rust slice.
    private external fun nativeDecodeByteArray(
        data: ByteArray,
        length: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap?

    // JNI: DirectByteBuffer path — zero copy for off-heap buffers (e.g.
    // Coil's SourceResult when backed by a direct ByteBuffer).
    private external fun nativeDecodeDirect(
        data: ByteBuffer,
        length: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Bitmap?

    /**
     * Decode image bytes to a Bitmap, optionally aspect-fit downscaled to
     * `(targetWidth, targetHeight)`. Falls back to `BitmapFactory` when the
     * native library is unavailable (host JVM tests) or when Rust returns
     * null (corrupt input, unsupported format).
     *
     * Routes on `Dispatchers.Default` because both the JNI call and the
     * `BitmapFactory` fallback are blocking CPU work.
     */
    suspend fun decode(
        data: ByteArray,
        targetWidth: Int = 0,
        targetHeight: Int = 0,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (!nativeAvailable) {
            return@withContext fallbackDecode(data, targetWidth, targetHeight)
        }
        nativeDecodeByteArray(data, data.size, targetWidth, targetHeight)
            ?: fallbackDecode(data, targetWidth, targetHeight)
    }

    /**
     * Pure-`BitmapFactory` decode path used when the native library is
     * missing or rejects the input. Public so unit tests can drive it
     * directly without going through the JNI dispatch.
     */
    fun fallbackDecode(data: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap {
        Log.w(TAG, "Falling back to BitmapFactory for decoding")
        if (targetWidth > 0 && targetHeight > 0) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            options.inSampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeByteArray(data, 0, data.size, options)
                ?: throw IllegalArgumentException("Failed to decode image")
        }
        return BitmapFactory.decodeByteArray(data, 0, data.size)
            ?: throw IllegalArgumentException("Failed to decode image")
    }

    private fun calculateInSampleSize(
        options: BitmapFactory.Options,
        reqWidth: Int,
        reqHeight: Int,
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

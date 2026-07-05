package com.juziss.localmediahub.native

import com.juziss.localmediahub.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * EXIF metadata parser backed by a Rust implementation living in
 * `liblocalmedia_native.so`.
 *
 * Production: every call goes through JNI into `exif_reader::parse` in
 * `android/app/src/main/rust/src/exif_reader.rs`, which delegates to the
 * pure-Rust `kamadak-exif` crate (no C dependencies — cross-compiles
 * cleanly to `aarch64-linux-android`).
 *
 * Test host (Robolectric / plain JVM): the `.so` is only cross-compiled for
 * `arm64-v8a` and is not loadable on the host JVM, so the loader catches
 * `UnsatisfiedLinkError` once and `nativeParseExif` is never invoked. In
 * that mode [parse] returns `null` for every input, which downstream
 * callers treat as "no EXIF available → orientation defaults to 1". This
 * is the correct behaviour for non-JPEG inputs and for JPEGs without EXIF,
 * and it matches how the production Rust path surfaces missing-EXIF.
 *
 * Unlike [NaturalSorter], no Kotlin re-implementation of the EXIF parser is
 * provided: re-implementing EXIF in Kotlin would be a substantial maintenance
 * burden and the production-only path is what callers care about. Tests
 * that need a non-null `ExifInfo` should run as instrumentation tests on a
 * real device.
 */
object NativeExif {

    @Volatile
    private var nativeAvailable: Boolean = false

    init {
        try {
            System.loadLibrary("localmedia_native")
            nativeAvailable = true
        } catch (e: UnsatisfiedLinkError) {
            if (BuildConfig.DEBUG) {
                // Debug build (incl. Robolectric unit tests on host JVM):
                // [parse] will return null for every input, which downstream
                // code already handles as the "no EXIF" case.
                nativeAvailable = false
            } else {
                // Release build: missing .so means the build pipeline broke or
                // R8 stripped the symbol. Silent fallback would hide a critical
                // regression. Crash loudly so it surfaces in crash reports.
                throw IllegalStateException(
                    "liblocalmedia_native.so failed to load — production builds must include the native library",
                    e,
                )
            }
        }
    }

    /**
     * Parsed EXIF metadata for an image byte stream.
     *
     * Mirrors the Rust `exif_reader::ExifInfo` struct. Field types are
     * nullable where the Rust side uses `Option<String>`; `orientation`
     * is always non-null because EXIF defines `1` as the "no rotation"
     * sentinel.
     */
    data class ExifInfo(
        val orientation: Int,
        val dateTimeOriginal: String?,
        val make: String?,
        val model: String?,
    )

    private external fun nativeParseExif(
        data: ByteArray,
        length: Int,
    ): ExifInfo?

    /**
     * Parse EXIF metadata from [data] on a background dispatcher.
     *
     * Returns `null` when:
     *  * the byte stream is not an EXIF-bearing container (PNG, WebP,
     *    random bytes), or
     *  * the container has no APP1 / EXIF segment (e.g. a JPEG stripped of
     *    metadata), or
     *  * the native library could not be loaded (host JVM tests).
     *
     * Callers should treat `null` as "no EXIF → orientation defaults to 1".
     */
    suspend fun parse(data: ByteArray): ExifInfo? =
        withContext(Dispatchers.Default) {
            if (!nativeAvailable) return@withContext null
            nativeParseExif(data, data.size)
        }
}

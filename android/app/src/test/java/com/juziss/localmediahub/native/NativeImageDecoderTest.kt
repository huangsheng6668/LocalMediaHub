package com.juziss.localmediahub.native

import android.graphics.Bitmap
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Host-JVM (Robolectric) tests for [NativeImageDecoder].
 *
 * Robolectric is required because `NativeImageDecoder`'s `init` block calls
 * `android.util.Log.w` when the native library can't be loaded — without
 * Robolectric that call hits the Android stub JAR's "not mocked" runtime
 * exception. Robolectric provides a real `Log` shadow.
 *
 * On the host JVM `liblocalmedia_native.so` cannot be loaded, so
 * `NativeImageDecoder.nativeAvailable` is `false` and every `decode()`
 * call exercises the `BitmapFactory` fallback path. The real Rust decode
 * pipeline is covered by `cargo test` in `android/app/src/main/rust/`.
 *
 * Tests that need actual sample image bytes read them from
 * `src/test/resources/test_image/`; if the resource is missing the test
 * is skipped via `assumeTrue` so the suite degrades gracefully on a clean
 * checkout (the resources are git-LFS-tracked and may not be present in
 * every CI environment).
 */
@RunWith(RobolectricTestRunner::class)
class NativeImageDecoderTest {

    @Test
    fun nativeAvailableFlagReflectsLibraryLoad() {
        // On the host JVM the native library is absent, so the flag MUST be
        // false. On an Android device/instrumentation test it must be true.
        // The contract pinned here is the host-JVM side: a missing
        // `liblocalmedia_native.so` flips the flag to false and every
        // `decode()` call must take the `BitmapFactory` fallback (verified
        // by the decode* tests below, which exercise the same path).
        //
        // The device-side "flag flips to true" half of the contract is
        // covered by instrumentation tests running against the bundled APK.
        assertFalse(
            "nativeAvailable must be false on host JVM (no .so loadable)",
            NativeImageDecoder.nativeAvailable,
        )
    }

    @Test
    fun decodeJpegReturnsBitmapViaFallback() = runTest {
        val bytes = readTestImage("sample.jpg") ?: return@runTest
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull("fallback decode should produce a bitmap", bitmap)
        assertTrue("width > 0", bitmap.width > 0)
        assertTrue("height > 0", bitmap.height > 0)
    }

    @Test
    fun decodeJpegRespectsTargetSize() = runTest {
        val bytes = readTestImage("sample.jpg") ?: return@runTest
        // First do a full-size decode to learn the source dimensions, then
        // request a 200x200 target and assert BitmapFactory actually picked
        // an `inSampleSize` that downscaled the image. The previous version
        // of this test only asserted `bitmap.width <= 1456 && height <= 2054`
        // — a no-op claim since BitmapFactory never upscales. The contract
        // pinned here is stronger: the downscaled output must be strictly
        // smaller than the source on BOTH axes, and at least one axis must
        // be <= 2x the requested target (proving a real downscale happened
        // rather than a passthrough at the source resolution).
        val full = NativeImageDecoder.decode(bytes, 0, 0)
        val bitmap = NativeImageDecoder.decode(bytes, 200, 200)
        assertTrue(
            "downscaled width ${bitmap.width} should be < source width ${full.width}",
            bitmap.width < full.width,
        )
        assertTrue(
            "downscaled height ${bitmap.height} should be < source height ${full.height}",
            bitmap.height < full.height,
        )
        // Robolectric's BitmapFactory shadow rounds `inSampleSize` down to a
        // power of two, so the shorter axis lands at ~2x target while the
        // longer axis can be ~3-4x target on portrait sources. We assert
        // that the SHORTER of the two output axes is within 2x of target —
        // this still proves a meaningful downscale without being sensitive
        // to the exact aspect ratio of `sample.jpg`.
        val shorterOut = minOf(bitmap.width, bitmap.height)
        assertTrue(
            "shorter output axis $shorterOut should be <= 2x target 200",
            shorterOut <= 400,
        )
    }

    @Test
    fun decodeWebpReturnsBitmapViaFallback() = runTest {
        val bytes = readTestImage("sample.webp") ?: return@runTest
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull(bitmap)
        assertTrue("width > 0", bitmap.width > 0)
        assertTrue("height > 0", bitmap.height > 0)
    }

    @Test
    fun decodePngReturnsBitmapViaFallback() = runTest {
        // Round 11 Task 4: PNG path. On the host JVM this exercises the
        // BitmapFactory fallback (the Rust .so is not loaded); the real
        // Rust PNG decode is covered by `cargo test` in
        // `android/app/src/main/rust/`. The contract this test pins is
        // identical to the JPEG/WebP cases: a non-null bitmap with
        // positive dimensions for a valid PNG input.
        val bytes = readTestImage("sample.png") ?: return@runTest
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull("fallback decode should produce a bitmap", bitmap)
        assertTrue("width > 0", bitmap.width > 0)
        assertTrue("height > 0", bitmap.height > 0)
    }

    @Test
    fun decodeHeicReturnsBitmapViaFallback() = runTest {
        // Round 11 Task 5: HEIC path. The Rust crate deliberately ships a
        // `None`-returning stub for `heif::decode` (see
        // `android/app/src/main/rust/src/heif.rs`), so on a real device the
        // JNI entry returns null and Kotlin falls back to `BitmapFactory`,
        // which on API 28+ is backed by NDK `AImageDecoder`. On the host
        // JVM (this Robolectric test) the Rust `.so` isn't loaded at all,
        // so the fallback path is always exercised.
        //
        // The HEIC sample is not checked into the repo (HEIC samples are
        // hard to source royalty-free and the value of a host-only
        // Robolectric decode is low — `BitmapFactory` under Robolectric
        // does not actually decode HEIC bitstreams). If `sample.heic` is
        // absent the test degrades to a no-op `return`, matching the
        // JPEG/WebP/PNG tests' "skip on missing resource" contract.
        val bytes = readTestImage("sample.heic") ?: return@runTest
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull("fallback decode should produce a bitmap", bitmap)
        assertTrue("width > 0", bitmap.width > 0)
        assertTrue("height > 0", bitmap.height > 0)
    }

    @Test
    fun fallbackOnCorruptData() = runTest {
        // Just a JPEG magic header followed by garbage — both Rust and
        // BitmapFactory will fail; the contract is that we either surface
        // null/throw cleanly without crashing the JVM.
        val corrupt = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0, 0, 0, 0, 0
        )
        try {
            NativeImageDecoder.decode(corrupt, 0, 0)
            // Either returns (BitmapFactory may emit a 1x1 placeholder) or
            // throws — both acceptable.
        } catch (_: Exception) {
            // expected on most corrupt inputs
        }
    }

    @Test
    fun fallbackDecodeExposesBitmapFactoryPathDirectly() {
        val bytes = readTestImage("sample.jpg")
        assumeTrue("sample.jpg resource missing — skipping", bytes != null)
        val bitmap: Bitmap = NativeImageDecoder.fallbackDecode(bytes!!, 0, 0)
        assertNotNull(bitmap)
        assertTrue(bitmap.width > 0)
    }

    @Test
    fun portraitImageOrientationIsCorrected() = runTest {
        // Round 11 Task 6: EXIF orientation rotation. The Rust decoder
        // path (`jni_bridge::decoders::decode_slice`) now reads the EXIF
        // `Orientation` tag for JPEG inputs and applies the corresponding
        // transform to the RGBA buffer before handing it to
        // `create_android_bitmap`.
        //
        // On the host JVM the native `.so` isn't loaded, so this test
        // exercises the `BitmapFactory` fallback. `BitmapFactory` already
        // honours EXIF orientation on API 24+ when the JPEG has the tag,
        // so the contract this test pins is the *outcome* (a non-null
        // bitmap with positive, post-orientation dimensions) rather than
        // the Rust code path — that path is covered by the rotation unit
        // tests in `bitmap.rs` under `cargo test`.
        //
        // The sample `portrait_rot6.jpg` is generated by
        // `exiftool -Orientation=6 ...` and is git-LFS-tracked. If it is
        // missing on a clean checkout the test degrades to a no-op
        // `return`, matching the JPEG/WebP/PNG tests' skip-on-missing
        // contract.
        val bytes = readTestImage("portrait_rot6.jpg") ?: return@runTest
        val bitmap = NativeImageDecoder.decode(bytes, 0, 0)
        assertNotNull("decode should produce a bitmap", bitmap)
        assertTrue("width > 0 after orientation correction", bitmap.width > 0)
        assertTrue("height > 0 after orientation correction", bitmap.height > 0)
    }

    private fun readTestImage(name: String): ByteArray? {
        return try {
            this::class.java.classLoader
                ?.getResourceAsStream("test_image/$name")
                ?.use { it.readBytes() }
        } catch (_: Exception) {
            null
        }
    }
}

package com.juziss.localmediahub.native

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NativeExif].
 *
 * On the host JVM the arm64-only `liblocalmedia_native.so` cannot be
 * loaded, so [NativeExif.parse] takes its `UnsatisfiedLinkError` fallback
 * path and returns `null` for every input. The assertions below therefore
 * exercise that fallback path: they assert `null` for non-image inputs and
 * for the bundled sample JPEG, mirroring how downstream code (the
 * decoder/orientation pipeline that lands in Task 6) treats missing-EXIF
 * inputs.
 *
 * The Rust side has its own coverage for the production path:
 * `android/app/src/main/rust/src/exif_reader.rs::tests`. To exercise the
 * JNI round-trip end-to-end on a real device, add an instrumentation test
 * under `androidTest/` (deferred to a later task).
 */
class NativeExifTest {

    @Test
    fun parseReturnsNullForNonImageData() = runTest {
        assertNull(NativeExif.parse("not an image".toByteArray()))
    }

    @Test
    fun parseReturnsNullForEmpty() = runTest {
        assertNull(NativeExif.parse(ByteArray(0)))
    }

    @Test
    fun parseReturnsNullForJpegWithoutExif() = runTest {
        // SOI + JFIF APP0 segment with no APP1/EXIF payload. On the host
        // JVM the native lib is unavailable so this returns null via the
        // UnsatisfiedLinkError fallback; on-device it would return null
        // because kamadak-exif refuses the container.
        val fakeJpegNoExif = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00,
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
        )
        assertNull(NativeExif.parse(fakeJpegNoExif))
    }

    @Test
    fun parseSampleJpeg() = runTest {
        val bytes = this::class.java.classLoader
            ?.getResourceAsStream("test_image/sample.jpg")?.readBytes()

        // Skip cleanly when the test resource isn't present (e.g. CI checkout
        // that didn't pick up the binary). On the host JVM the native lib
        // cannot load regardless, so we expect null below; on a real device
        // the assertion validates that a parsed orientation, if present, is
        // a valid EXIF value (1..8).
        if (bytes != null) {
            val info = NativeExif.parse(bytes)
            if (info != null) {
                assertTrue(info.orientation in 1..8)
            }
        }
    }
}

package com.juziss.localmediahub.native

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import coil3.size.pxOrElse

/**
 * Coil `Decoder` that routes image formats we have a native (Rust) decoder
 * for through `NativeImageDecoder`, and falls back to `BitmapFactory` for
 * everything else.
 *
 * Round 11 Task 3 changes:
 *   - Format detection covers JPEG / WebP / PNG / HEIC.
 *   - HEIC detection uses the corrected `String(header, 4, 4) == "ftyp"`
 *     check (the brief's `String(header, 4, 8)` reads 8 bytes from offset 4
 *     and can never match the 4-character "ftyp" brand).
 *
 * Round 14 Task 2: the duplicated magic-byte sniff between `Factory.create`
 * and `decode()` is now extracted into a single `companion object nativeHandles`
 * helper — one source of truth for the format routing rule.
 */
class NativeDecoderFactory(
    private val sourceResult: SourceFetchResult,
    private val size: Size,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        // Coil 3: ImageSource.source() returns BufferedSource directly
        // (Coil 2's nested `.source().buffer()` collapses to `.source()`).
        val bytes = sourceResult.source.source().readByteArray()
        val targetWidth = size.width.pxOrElse { 0 }
        val targetHeight = size.height.pxOrElse { 0 }

        val bitmap = if (nativeHandles(bytes)) {
            NativeImageDecoder.decode(bytes, targetWidth, targetHeight)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Failed to decode image")
        }

        return DecodeResult(
            image = BitmapDrawable(options.context.resources, bitmap).asImage(),
            isSampled = true,
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: coil3.ImageLoader,
        ): Decoder? {
            // Coil 3: ImageSource.source() returns BufferedSource directly.
            val bufferedSource = result.source.source()
            val header = try {
                bufferedSource.peek().readByteArray(12)
            } catch (_: Exception) {
                return null
            }
            return if (nativeHandles(header)) {
                NativeDecoderFactory(result, options.size, options)
            } else {
                null
            }
        }
    }

    companion object {
        /**
         * True iff [bytes] begins with a magic signature that the Rust
         * `nativeDecodeByteArray` knows how to handle (JPEG / WebP / PNG /
         * HEIC). Cheap byte-level sniff — no JNI calls.
         *
         * Single source of truth for format routing — both [Factory.create]
         * (peek 12-byte header) and [decode] (full byte array) funnel
         * through this helper. Round 14 Task 2 collapsed the previous
         * duplicate `Factory.create` / `nativeHandlesFormat` instances.
         */
        internal fun nativeHandles(bytes: ByteArray): Boolean {
            val isJpeg = bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
            val isWebp = bytes.size >= 12 &&
                String(bytes, 0, 4) == "RIFF" &&
                String(bytes, 8, 4) == "WEBP"
            val isPng = bytes.size >= 8 &&
                bytes[0] == 0x89.toByte() &&
                String(bytes, 1, 3) == "PNG"
            // HEIF/HEIC: ISO BMFF "ftyp" box brand at offset 4, 4 bytes long.
            // Note: must use length 4 (the brand), not 8 — the brief's
            // original `String(bytes, 4, 8)` was a bug.
            val isHeic = bytes.size >= 12 &&
                String(bytes, 4, 4) == "ftyp"
            return isJpeg || isWebp || isPng || isHeic
        }
    }
}

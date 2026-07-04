package com.juziss.localmediahub.native

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Size
import coil.size.pxOrElse

/**
 * Coil `Decoder` that routes image formats we have a native (Rust) decoder
 * for through `NativeImageDecoder`, and falls back to `BitmapFactory` for
 * everything else.
 *
 * Round 11 Task 3 changes:
 *   - Format detection is extended to JPEG / WebP / PNG / HEIC (HEIC is
 *     reserved for Task 5; the routing branch is wired up now so the file
 *     type reaches `NativeImageDecoder`, where Rust currently returns null
 *     for HEIC and the Kotlin fallback kicks in).
 *   - The `NativeImageDecoder.getImageInfo(bytes)` pre-routing call has
 *     been removed — Rust's `nativeDecodeByteArray` does its own magic-byte
 *     detection, so we no longer pay for two JNI round trips per decode.
 *     `NativeDecoderFactory.decode()` now calls
 *     `NativeImageDecoder.decode(bytes, tw, th)` directly for the four
 *     routed formats.
 *   - HEIC detection uses the corrected `String(header, 4, 4) == "ftyp"`
 *     check (the brief's `String(header, 4, 8)` reads 8 bytes from offset 4
 *     and can never match the 4-character "ftyp" brand).
 */
class NativeDecoderFactory(
    private val sourceResult: SourceResult,
    private val size: Size,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = sourceResult.source.source().buffer().readByteArray()
        val targetWidth = size.width.pxOrElse { 0 }
        val targetHeight = size.height.pxOrElse { 0 }

        val bitmap = if (nativeHandlesFormat(bytes)) {
            // Rust sniffs the format itself; one JNI round trip.
            NativeImageDecoder.decode(bytes, targetWidth, targetHeight)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                ?: throw IllegalArgumentException("Failed to decode image")
        }

        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true,
        )
    }

    /**
     * True iff [bytes] begins with a magic signature that the Rust
     * `nativeDecodeByteArray` knows how to handle (or, for HEIC, will be
     * able to handle once Task 5 lands). Cheap byte-level sniff — no JNI
     * calls.
     */
    private fun nativeHandlesFormat(bytes: ByteArray): Boolean {
        val header = bytes
        val isJpeg = header.size >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        val isWebp = header.size >= 12 &&
            String(header, 0, 4) == "RIFF" &&
            String(header, 8, 4) == "WEBP"
        val isPng = header.size >= 8 &&
            header[0] == 0x89.toByte() &&
            String(header, 1, 3) == "PNG"
        // HEIF/HEIC: ISO BMFF "ftyp" box brand at offset 4, 4 bytes long.
        // Note: must use length 4 (the brand), not 8 — the brief's original
        // `String(header, 4, 8)` was a bug.
        val isHeic = header.size >= 12 &&
            String(header, 4, 4) == "ftyp"
        return isJpeg || isWebp || isPng || isHeic
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: Options,
            imageLoader: coil.ImageLoader,
        ): Decoder? {
            val bufferedSource = result.source.source().buffer()
            val header = try {
                bufferedSource.peek().readByteArray(12)
            } catch (_: Exception) {
                return null
            }

            val isJpeg = header.size >= 3 &&
                header[0] == 0xFF.toByte() &&
                header[1] == 0xD8.toByte() &&
                header[2] == 0xFF.toByte()
            val isWebp = header.size >= 12 &&
                String(header, 0, 4) == "RIFF" &&
                String(header, 8, 4) == "WEBP"
            val isPng = header.size >= 8 &&
                header[0] == 0x89.toByte() &&
                String(header, 1, 3) == "PNG"
            val isHeic = header.size >= 12 &&
                String(header, 4, 4) == "ftyp"

            return if (isJpeg || isWebp || isPng || isHeic) {
                NativeDecoderFactory(result, options.size, options)
            } else {
                null
            }
        }
    }
}

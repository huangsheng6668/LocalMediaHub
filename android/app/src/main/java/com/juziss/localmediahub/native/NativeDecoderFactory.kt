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
 * Coil Decoder that routes JPEG/WebP images to native decoding
 * and falls back to BitmapFactory for other formats.
 *
 * Coil 2.x: Decoder.Factory receives the SourceResult and creates a Decoder.
 * The Decoder.decode() reads from the source passed during Factory.create().
 */
class NativeDecoderFactory(
    private val sourceResult: SourceResult,
    private val size: Size,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = sourceResult.source.source().buffer().readByteArray()
        val info = NativeImageDecoder.getImageInfo(bytes)

        val bitmap = when (info?.format) {
            NativeImageDecoder.FORMAT_JPEG,
            NativeImageDecoder.FORMAT_WEBP -> {
                val targetWidth = size.width.pxOrElse { 0 }
                val targetHeight = size.height.pxOrElse { 0 }
                NativeImageDecoder.decode(bytes, targetWidth, targetHeight)
            }
            else -> {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("Failed to decode image")
            }
        }

        return DecodeResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            isSampled = true
        )
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

            return if (isJpeg || isWebp) {
                NativeDecoderFactory(result, Size.ORIGINAL, options)
            } else {
                null
            }
        }
    }
}

package com.juziss.localmediahub.native

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import coil.size.Size
import coil.size.pxOrElse

class NativeDecoderFactory : Decoder {

    override fun key(): String = "NativeDecoder"

    override suspend fun decode(
        source: SourceResult,
        size: Size,
        options: Options
    ): DecodeResult {
        val bytes = source.source.source().buffer().readByteArray()
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
            imageLoader: coil.ImageLoader
        ): Decoder? {
            val bufferedSource = result.source.source().buffer()
            // Peek header bytes without consuming them
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
                NativeDecoderFactory()
            } else {
                null // Let Coil use default BitmapFactoryDecoder
            }
        }
    }
}

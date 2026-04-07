package com.juziss.localmediahub.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ImageInfo(
    val width: Int,
    val height: Int,
    val format: Int
)

object NativeImageDecoder {

    private const val TAG = "NativeImageDecoder"

    init {
        System.loadLibrary("native-image-decoder")
    }

    const val FORMAT_UNKNOWN = 0
    const val FORMAT_JPEG = 1
    const val FORMAT_WEBP = 2

    private external fun nativeDecodeJpeg(
        data: ByteArray,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap?

    private external fun nativeDecodeWebp(
        data: ByteArray,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap?

    private external fun nativeGetImageInfo(data: ByteArray): IntArray?

    fun getImageInfo(data: ByteArray): ImageInfo? {
        val info = nativeGetImageInfo(data) ?: return null
        if (info.size != 3) return null
        return ImageInfo(width = info[0], height = info[1], format = info[2])
    }

    suspend fun decode(
        data: ByteArray,
        targetWidth: Int = 0,
        targetHeight: Int = 0
    ): Bitmap = withContext(Dispatchers.Default) {
        val info = getImageInfo(data)
        when (info?.format) {
            FORMAT_JPEG -> {
                nativeDecodeJpeg(data, targetWidth, targetHeight)
                    ?: fallbackDecode(data, targetWidth, targetHeight)
            }
            FORMAT_WEBP -> {
                nativeDecodeWebp(data, targetWidth, targetHeight)
                    ?: fallbackDecode(data, targetWidth, targetHeight)
            }
            else -> fallbackDecode(data, targetWidth, targetHeight)
        }
    }

    private fun fallbackDecode(data: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap {
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

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
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

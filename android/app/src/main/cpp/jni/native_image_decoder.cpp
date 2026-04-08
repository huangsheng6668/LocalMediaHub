#include "native_image_decoder.h"

#include <cstring>
#include <cstdlib>
#include <algorithm>

#include <android/log.h>
#include <android/bitmap.h>

#include <jpeglib.h>
#include <jerror.h>
#include <setjmp.h>
#include <webp/decode.h>

#define LOG_TAG "NativeImageDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// Image format constants returned by nativeGetImageInfo.
constexpr int FORMAT_JPEG = 1;
constexpr int FORMAT_WEBP = 2;
constexpr int FORMAT_UNKNOWN = 0;

// ---------------------------------------------------------------------------
// Format detection helpers
// ---------------------------------------------------------------------------

bool isJpeg(const uint8_t *data, size_t length) {
    return length >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF;
}

bool isWebp(const uint8_t *data, size_t length) {
    return length >= 12
        && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
        && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
}

int detectFormat(const uint8_t *data, size_t length) {
    if (isJpeg(data, length)) return FORMAT_JPEG;
    if (isWebp(data, length)) return FORMAT_WEBP;
    return FORMAT_UNKNOWN;
}

// ---------------------------------------------------------------------------
// Dimension helpers
// ---------------------------------------------------------------------------

/**
 * Calculate target dimensions that fit within (targetWidth x targetHeight)
 * while preserving aspect ratio.  If targetWidth or targetHeight is <= 0 the
 * original dimension is returned unchanged (no scaling requested).
 */
void calculateTargetDimensions(
        int srcWidth, int srcHeight,
        int targetWidth, int targetHeight,
        int *outWidth, int *outHeight) {
    if (targetWidth <= 0 || targetHeight <= 0) {
        *outWidth  = srcWidth;
        *outHeight = srcHeight;
        return;
    }

    if (srcWidth <= targetWidth && srcHeight <= targetHeight) {
        *outWidth  = srcWidth;
        *outHeight = srcHeight;
        return;
    }

    float widthRatio  = static_cast<float>(targetWidth)  / static_cast<float>(srcWidth);
    float heightRatio = static_cast<float>(targetHeight) / static_cast<float>(srcHeight);
    float scale = std::min(widthRatio, heightRatio);

    *outWidth  = std::max(1, static_cast<int>(srcWidth  * scale));
    *outHeight = std::max(1, static_cast<int>(srcHeight * scale));
}

// ---------------------------------------------------------------------------
// ScopedByteArray — RAII wrapper for JNI GetByteArrayElements / ReleaseByteArrayElements
// ---------------------------------------------------------------------------

class ScopedByteArray {
public:
    ScopedByteArray(JNIEnv *env, jbyteArray array)
        : env_(env), array_(array),
          ptr_(reinterpret_cast<const uint8_t *>(
                  env->GetByteArrayElements(array, nullptr))) {}

    ~ScopedByteArray() {
        if (ptr_) {
            env_->ReleaseByteArrayElements(
                array_,
                reinterpret_cast<jbyte *>(const_cast<uint8_t *>(ptr_)),
                JNI_ABORT);
        }
    }

    const uint8_t *data() const { return ptr_; }
    size_t size() const {
        return static_cast<size_t>(env_->GetArrayLength(array_));
    }

    bool valid() const { return ptr_ != nullptr; }

private:
    JNIEnv *env_;
    jbyteArray array_;
    const uint8_t *ptr_;
};

// ---------------------------------------------------------------------------
// Android Bitmap creation
// ---------------------------------------------------------------------------

/**
 * Create an Android Bitmap of the given dimensions and copy RGBA pixel data
 * into it.  Android Bitmap uses ARGB_8888 ordering (A,R,G,B in memory on
 * little-endian), so we swap from RGBA to ARGB.
 *
 * Returns a local reference to the Bitmap, or null on failure.
 */
jobject createBitmapFromRGBA(
        JNIEnv *env,
        const uint8_t *rgbaData,
        int width, int height) {

    // Look up android.graphics.Bitmap.Config class and ARGB_8888 field.
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!configClass) {
        LOGE("Failed to find Bitmap.Config class");
        return nullptr;
    }

    jfieldID argb8888Field = env->GetStaticFieldID(
        configClass, "ARGB_8888", "Landroid/graphics/Bitmap$Config;");
    if (!argb8888Field) {
        LOGE("Failed to find ARGB_8888 field");
        return nullptr;
    }

    jobject configObj = env->GetStaticObjectField(configClass, argb8888Field);
    if (!configObj) {
        LOGE("Failed to get ARGB_8888 config object");
        return nullptr;
    }

    // Call Bitmap.createBitmap(width, height, config).
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!bitmapClass) {
        LOGE("Failed to find Bitmap class");
        return nullptr;
    }

    jmethodID createBitmapMethod = env->GetStaticMethodID(
        bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!createBitmapMethod) {
        LOGE("Failed to find Bitmap.createBitmap method");
        return nullptr;
    }

    jobject bitmap = env->CallStaticObjectMethod(
        bitmapClass, createBitmapMethod, width, height, configObj);
    if (!bitmap) {
        LOGE("Bitmap.createBitmap returned null");
        return nullptr;
    }

    // Lock pixels and copy data.
    AndroidBitmapInfo info;
    int rc = AndroidBitmap_getInfo(env, bitmap, &info);
    if (rc != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_getInfo failed: %d", rc);
        return nullptr;
    }

    uint32_t *pixels = nullptr;
    rc = AndroidBitmap_lockPixels(env, bitmap, reinterpret_cast<void **>(&pixels));
    if (rc != ANDROID_BITMAP_RESULT_SUCCESS) {
        LOGE("AndroidBitmap_lockPixels failed: %d", rc);
        return nullptr;
    }

    int stride = static_cast<int>(info.stride);
    for (int y = 0; y < height; ++y) {
        const uint8_t *srcRow = rgbaData + y * width * 4;
        uint32_t *dstRow = reinterpret_cast<uint32_t *>(
            reinterpret_cast<uint8_t *>(pixels) + y * stride);

        for (int x = 0; x < width; ++x) {
            uint8_t r = srcRow[x * 4 + 0];
            uint8_t g = srcRow[x * 4 + 1];
            uint8_t b = srcRow[x * 4 + 2];
            uint8_t a = srcRow[x * 4 + 3];
            // Pack as ARGB for Android Bitmap (little-endian: B,G,R,A in memory)
            dstRow[x] = (static_cast<uint32_t>(a) << 24)
                      | (static_cast<uint32_t>(r) << 16)
                      | (static_cast<uint32_t>(g) << 8)
                      |  static_cast<uint32_t>(b);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

// ---------------------------------------------------------------------------
// JPEG decoding (libjpeg-turbo) with setjmp/longjmp error recovery
// ---------------------------------------------------------------------------

struct JpegErrorHandler {
    struct jpeg_error_mgr base;
    jmp_buf setjmpBuffer;
    char lastMessage[JMSG_LENGTH_MAX];
};

static void jpegErrorCallback(j_common_ptr cinfo) {
    auto *handler = reinterpret_cast<JpegErrorHandler *>(cinfo->err);
    (*cinfo->err->format_message)(cinfo, handler->lastMessage);
    LOGE("JPEG error: %s", handler->lastMessage);
    longjmp(handler->setjmpBuffer, 1);
}

static void jpegOutputMessageCallback(j_common_ptr cinfo) {
    auto *handler = reinterpret_cast<JpegErrorHandler *>(cinfo->err);
    char buffer[JMSG_LENGTH_MAX];
    (*cinfo->err->format_message)(cinfo, buffer);
    LOGW("JPEG warning: %s", buffer);
}

/**
 * Decode a JPEG byte buffer to RGBA pixel data.
 * Caller is responsible for freeing the returned buffer with free().
 * Returns null on failure.
 */
uint8_t *decodeJpegToRGBA(
        const uint8_t *data, size_t length,
        int *outWidth, int *outHeight) {

    jpeg_decompress_struct cinfo;
    memset(&cinfo, 0, sizeof(cinfo));

    JpegErrorHandler jerr;
    cinfo.err = jpeg_std_error(&jerr.base);
    jerr.base.error_exit = jpegErrorCallback;
    jerr.base.output_message = jpegOutputMessageCallback;

    uint8_t *rgba = nullptr;

    // setjmp returns 0 on first call, non-zero when longjmp'd from error handler.
    if (setjmp(jerr.setjmpBuffer)) {
        LOGE("JPEG decoding aborted due to error: %s", jerr.lastMessage);
        if (rgba) { free(rgba); rgba = nullptr; }
        jpeg_destroy_decompress(&cinfo);
        return nullptr;
    }

    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, data, length);

    if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
        LOGE("jpeg_read_header failed");
        jpeg_destroy_decompress(&cinfo);
        return nullptr;
    }

    cinfo.out_color_space = JCS_EXT_RGBA;

    if (!jpeg_start_decompress(&cinfo)) {
        LOGE("jpeg_start_decompress failed");
        jpeg_destroy_decompress(&cinfo);
        return nullptr;
    }

    int width  = static_cast<int>(cinfo.output_width);
    int height = static_cast<int>(cinfo.output_height);
    int rowStride = width * 4;

    rgba = static_cast<uint8_t *>(malloc(width * height * 4));
    if (!rgba) {
        LOGE("Failed to allocate RGBA buffer for JPEG decode");
        jpeg_destroy_decompress(&cinfo);
        return nullptr;
    }

    while (cinfo.output_scanline < cinfo.output_height) {
        uint8_t *rowPtr = rgba + cinfo.output_scanline * rowStride;
        jpeg_read_scanlines(&cinfo, &rowPtr, 1);
    }

    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);

    *outWidth  = width;
    *outHeight = height;
    return rgba;
}

// ---------------------------------------------------------------------------
// WebP decoding (libwebp)
// ---------------------------------------------------------------------------

/**
 * Decode a WebP byte buffer to RGBA pixel data at the requested dimensions.
 * Caller is responsible for freeing the returned buffer with WebPFree().
 * Returns null on failure.
 */
uint8_t *decodeWebpToRGBA(
        const uint8_t *data, size_t length,
        int targetWidth, int targetHeight,
        int *outWidth, int *outHeight) {

    WebPDecoderConfig config;
    if (!WebPInitDecoderConfig(&config)) {
        LOGE("WebPInitDecoderConfig failed");
        return nullptr;
    }

    // Retrieve bitstream features.
    VP8StatusCode status = WebPGetFeatures(data, length, &config.input);
    if (status != VP8_STATUS_OK) {
        LOGE("WebPGetFeatures failed: %d", status);
        return nullptr;
    }

    int srcWidth  = config.input.width;
    int srcHeight = config.input.height;

    int decodedWidth, decodedHeight;
    calculateTargetDimensions(
        srcWidth, srcHeight, targetWidth, targetHeight,
        &decodedWidth, &decodedHeight);

    // Request RGBA output.
    config.output.colorspace = MODE_RGBA;
    config.output.is_external_memory = 0;

    bool needsScaling = (decodedWidth != srcWidth || decodedHeight != srcHeight);
    if (needsScaling) {
        config.options.use_scaling = 1;
        config.options.scaled_width  = decodedWidth;
        config.options.scaled_height = decodedHeight;
    }

    status = WebPDecode(data, length, &config);
    if (status != VP8_STATUS_OK) {
        LOGE("WebPDecode failed: %d", status);
        WebPFreeDecBuffer(&config.output);
        return nullptr;
    }

    *outWidth  = config.output.width;
    *outHeight = config.output.height;

    // Transfer ownership of the decoded buffer to the caller.
    // config.output.u.RGBA.rgba is freed via WebPFree().
    uint8_t *result = config.output.u.RGBA.rgba;

    // Prevent WebPFreeDecBuffer from freeing the pixel data we are returning.
    config.output.u.RGBA.rgba = nullptr;
    config.output.u.RGBA.size = 0;
    WebPFreeDecBuffer(&config.output);

    return result;
}

} // anonymous namespace

// ===========================================================================
// JNI exported functions
// ===========================================================================

JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeJpeg(
        JNIEnv *env, jclass clazz, jbyteArray data,
        jint targetWidth, jint targetHeight) {

    if (!data) {
        LOGE("nativeDecodeJpeg: null data");
        return nullptr;
    }

    ScopedByteArray bytes(env, data);
    if (!bytes.valid()) {
        LOGE("nativeDecodeJpeg: failed to get byte array");
        return nullptr;
    }

    int width = 0, height = 0;
    uint8_t *rgba = decodeJpegToRGBA(bytes.data(), bytes.size(), &width, &height);
    if (!rgba) {
        LOGE("nativeDecodeJpeg: decodeJpegToRGBA failed");
        return nullptr;
    }

    // If downscaling is requested, do a simple box-filter downscale on the
    // RGBA data before creating the bitmap. libjpeg-turbo does not natively
    // support scaled decoding through the standard API, so we handle it here.
    int dstWidth, dstHeight;
    calculateTargetDimensions(width, height, targetWidth, targetHeight,
                              &dstWidth, &dstHeight);

    jobject bitmap = nullptr;
    if (dstWidth == width && dstHeight == height) {
        // No scaling needed — use the decoded data directly.
        bitmap = createBitmapFromRGBA(env, rgba, width, height);
    } else {
        // Perform a simple area-average downscale.
        int scaledStride = dstWidth * 4;
        uint8_t *scaled = static_cast<uint8_t *>(malloc(dstWidth * dstHeight * 4));
        if (scaled) {
            float xRatio = static_cast<float>(width)  / dstWidth;
            float yRatio = static_cast<float>(height) / dstHeight;

            for (int y = 0; y < dstHeight; ++y) {
                int srcY0 = static_cast<int>(y * yRatio);
                int srcY1 = std::min(static_cast<int>((y + 1) * yRatio), height);
                int rowCount = srcY1 - srcY0;
                if (rowCount <= 0) rowCount = 1;

                for (int x = 0; x < dstWidth; ++x) {
                    int srcX0 = static_cast<int>(x * xRatio);
                    int srcX1 = std::min(static_cast<int>((x + 1) * xRatio), width);
                    int colCount = srcX1 - srcX0;
                    if (colCount <= 0) colCount = 1;

                    int totalR = 0, totalG = 0, totalB = 0, totalA = 0;
                    int count = rowCount * colCount;

                    for (int sy = srcY0; sy < srcY1; ++sy) {
                        const uint8_t *row = rgba + sy * width * 4;
                        for (int sx = srcX0; sx < srcX1; ++sx) {
                            totalR += row[sx * 4 + 0];
                            totalG += row[sx * 4 + 1];
                            totalB += row[sx * 4 + 2];
                            totalA += row[sx * 4 + 3];
                        }
                    }

                    uint8_t *dst = scaled + y * scaledStride + x * 4;
                    dst[0] = static_cast<uint8_t>((totalR + count / 2) / count);
                    dst[1] = static_cast<uint8_t>((totalG + count / 2) / count);
                    dst[2] = static_cast<uint8_t>((totalB + count / 2) / count);
                    dst[3] = static_cast<uint8_t>((totalA + count / 2) / count);
                }
            }

            bitmap = createBitmapFromRGBA(env, scaled, dstWidth, dstHeight);
            free(scaled);
        } else {
            LOGW("Failed to allocate scaled buffer, falling back to full-size decode");
            bitmap = createBitmapFromRGBA(env, rgba, width, height);
        }
    }

    free(rgba);
    return bitmap;
}

JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeWebp(
        JNIEnv *env, jclass clazz, jbyteArray data,
        jint targetWidth, jint targetHeight) {

    if (!data) {
        LOGE("nativeDecodeWebp: null data");
        return nullptr;
    }

    ScopedByteArray bytes(env, data);
    if (!bytes.valid()) {
        LOGE("nativeDecodeWebp: failed to get byte array");
        return nullptr;
    }

    int width = 0, height = 0;
    uint8_t *rgba = decodeWebpToRGBA(
        bytes.data(), bytes.size(), targetWidth, targetHeight, &width, &height);
    if (!rgba) {
        LOGE("nativeDecodeWebp: decodeWebpToRGBA failed");
        return nullptr;
    }

    jobject bitmap = createBitmapFromRGBA(env, rgba, width, height);
    WebPFree(rgba);
    return bitmap;
}

JNIEXPORT jintArray JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeGetImageInfo(
        JNIEnv *env, jclass clazz, jbyteArray data) {

    if (!data) {
        LOGE("nativeGetImageInfo: null data");
        return nullptr;
    }

    ScopedByteArray bytes(env, data);
    if (!bytes.valid()) {
        LOGE("nativeGetImageInfo: failed to get byte array");
        return nullptr;
    }

    const uint8_t *rawData = bytes.data();
    size_t length = bytes.size();

    int format = detectFormat(rawData, length);

    int width = 0, height = 0;

    if (format == FORMAT_JPEG) {
        jpeg_decompress_struct cinfo;
        memset(&cinfo, 0, sizeof(cinfo));
        JpegErrorHandler jerr;
        cinfo.err = jpeg_std_error(&jerr.base);
        jerr.base.error_exit = jpegErrorCallback;
        jerr.base.output_message = jpegOutputMessageCallback;

        if (setjmp(jerr.setjmpBuffer)) {
            LOGW("nativeGetImageInfo: JPEG error: %s", jerr.lastMessage);
            jpeg_destroy_decompress(&cinfo);
        } else {
            jpeg_create_decompress(&cinfo);
            jpeg_mem_src(&cinfo, rawData, length);

            if (jpeg_read_header(&cinfo, TRUE) == JPEG_HEADER_OK) {
                width  = static_cast<int>(cinfo.image_width);
                height = static_cast<int>(cinfo.image_height);
            } else {
                LOGW("nativeGetImageInfo: jpeg_read_header failed");
            }

            jpeg_destroy_decompress(&cinfo);
        }
    } else if (format == FORMAT_WEBP) {
        if (!WebPGetInfo(rawData, length, &width, &height)) {
            LOGW("nativeGetImageInfo: WebPGetInfo failed");
            width = 0;
            height = 0;
        }
    } else {
        LOGW("nativeGetImageInfo: unknown image format");
    }

    // Return [width, height, format] or null if we could not determine dimensions.
    if (width <= 0 || height <= 0) {
        return nullptr;
    }

    jint info[3];
    info[0] = width;
    info[1] = height;
    info[2] = format;

    jintArray result = env->NewIntArray(3);
    if (!result) {
        LOGE("nativeGetImageInfo: NewIntArray failed");
        return nullptr;
    }

    env->SetIntArrayRegion(result, 0, 3, info);
    return result;
}

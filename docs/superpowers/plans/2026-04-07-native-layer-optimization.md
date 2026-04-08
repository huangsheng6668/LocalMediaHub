# Native Layer Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native C++ libraries (libjpeg-turbo, libwebp, FFmpeg) to the Android client for faster image decoding and broader video format support, plus custom video gesture controls.

**Architecture:** Three independent modules — (1) native image decoder via JNI + Coil integration, (2) FFmpeg extension for ExoPlayer software fallback, (3) Compose gesture overlay for video seek/brightness/volume. Each module produces working, testable code on its own.

**Tech Stack:** C++ (JNI), CMake, libjpeg-turbo, libwebp, FFmpeg, Media3 ExoPlayer, Coil 2.5, Jetpack Compose, Kotlin

---

## Current Status (updated 2026-04-08)

### Completed
- [x] Task 1: Vendored libjpeg-turbo 3.0.1 and libwebp 1.3.2 sources
- [x] Task 2: CMakeLists.txt — IMPORTED STATIC libs + jnigraphics, CMake enabled in build.gradle
- [x] Task 3: JNI bridge (native_image_decoder.cpp/h) with setjmp/longjmp error handling
- [x] Task 4: build.gradle.kts — NDK abiFilter arm64-v8a + CMake enabled
- [x] Task 5: NativeImageDecoder.kt Kotlin wrapper with fallback
- [x] Task 6: NativeDecoderFactory.kt — Coil 2.x integration
- [x] Task 7: MainActivity.kt implements ImageLoaderFactory
- [x] Task 8: FFmpeg .so (3.3 MB, n6.1)
- [x] Task 10: VideoGestureOverlay.kt with full gesture logic
- [x] Task 11: Gesture overlay integrated into VideoPlayerScreen.kt
- [x] Task 12: Full swipe gesture logic (seek, brightness, volume)
- [x] Task 13: BUILD SUCCESSFUL — APK 24MB, includes libnative-image-decoder.so (828KB) + libffmpeg.so (3.3MB)

### Static Libraries Built (via WSL + cmake 3.28)
- `libjpeg.a` (5.2 MB) — libjpeg-turbo 3.0.1, SIMD disabled
- `libwebp.a` (5.2 MB) — libwebp 1.3.2, NEON enabled
- `libwebpdecoder.a` (3.0 MB) — libwebp decoder only

### Remaining / Future Work
- **Task 9 (FFmpeg ExoPlayer):** media3-decoder-ffmpeg NOT on Maven. FFmpeg .so in jniLibs for future JNI bridge.
- **Gesture overlay blocking:** needs runtime verification on device

### Files (final state)
| File | Status |
|------|--------|
| `android/app/src/main/cpp/CMakeLists.txt` | Done — IMPORTED STATIC libs + project() + jnigraphics |
| `android/app/src/main/cpp/jni/native_image_decoder.cpp` | Done — setjmp/longjmp error handling |
| `android/app/src/main/cpp/jni/native_image_decoder.h` | Done |
| `android/app/src/main/cpp/libs/arm64-v8a/libjpeg.a` | Built via WSL |
| `android/app/src/main/cpp/libs/arm64-v8a/libwebp.a` | Built via WSL |
| `android/app/src/main/cpp/libs/arm64-v8a/libwebpdecoder.a` | Built via WSL |
| `android/app/src/main/cpp/third_party/libjpeg-turbo/` | Vendored + jconfig.h/jconfigint.h |
| `android/app/src/main/cpp/third_party/libwebp/` | Vendored |
| `android/app/src/main/java/.../native/NativeImageDecoder.kt` | Done |
| `android/app/src/main/java/.../native/NativeDecoderFactory.kt` | Done — Coil 2.x |
| `android/app/src/main/java/.../ui/component/VideoGestureOverlay.kt` | Done |
| `android/app/src/main/java/.../MainActivity.kt` | Done — ImageLoaderFactory |
| `android/app/src/main/java/.../ui/screen/VideoPlayerScreen.kt` | Done — ExoPlayer + gesture |
| `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so` | Done (3.3 MB) |
| `android/app/build.gradle.kts` | Done — CMake enabled |
| `build_native.sh` | Build script for WSL cross-compilation |

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `android/app/CMakeLists.txt` | Top-level CMake entry, adds subdirectories |
| `android/app/src/main/cpp/CMakeLists.txt` | CMake build for JNI bridge + third-party libs |
| `android/app/src/main/cpp/jni/native_image_decoder.cpp` | JNI C++ functions: decodeJpeg, decodeWebp, getImageInfo |
| `android/app/src/main/cpp/jni/native_image_decoder.h` | JNI header declarations |
| `android/app/src/main/cpp/third_party/libjpeg-turbo/` | libjpeg-turbo source (git submodule or vendored) |
| `android/app/src/main/cpp/third_party/libwebp/` | libwebp source (git submodule or vendored) |
| `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so` | Pre-built FFmpeg shared library |
| `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt` | Kotlin wrapper: suspend functions, format detection, fallback |
| `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt` | Coil Decoder implementation routing JPEG/WebP to native |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/VideoGestureOverlay.kt` | Compose gesture overlay: seek, brightness, volume |

### Modified Files
| File | Change |
|------|--------|
| `android/app/build.gradle.kts` | Add NDK/CMake config, FFmpeg extension dependency, Coil version bump |
| `android/app/src/main/.../ui/screen/VideoPlayerScreen.kt` | FFmpeg ExoPlayer builder + gesture overlay integration |
| `android/app/src/main/.../MainActivity.kt` | Coil ImageLoader with NativeDecoderFactory |

---

## Module 1: Native Image Decoder

### Task 1: Vendor third-party native libraries

**Files:**
- Create: `android/app/src/main/cpp/third_party/libjpeg-turbo/` (vendored source)
- Create: `android/app/src/main/cpp/third_party/libwebp/` (vendored source)

- [ ] **Step 1: Clone libjpeg-turbo into third_party**

```bash
cd android/app/src/main/cpp/third_party
git clone --depth 1 --branch 3.0.1 https://github.com/libjpeg-turbo/libjpeg-turbo.git
```

Expected: `libjpeg-turbo/` directory populated with CMakeLists.txt and source.

- [ ] **Step 2: Clone libwebp into third_party**

```bash
cd android/app/src/main/cpp/third_party
git clone --depth 1 --branch v1.3.2 https://chromium.googlesource.com/webm/libwebp
```

Expected: `libwebp/` directory populated with CMakeLists.txt and source.

- [ ] **Step 3: Verify directory structure**

Run: `ls android/app/src/main/cpp/third_party/`
Expected: `libjpeg-turbo/  libwebp/`

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/cpp/third_party/
git commit -m "chore: vendor libjpeg-turbo 3.0.1 and libwebp 1.3.2 sources"
```

---

### Task 2: Write CMake build configuration

**Files:**
- Create: `android/app/CMakeLists.txt`
- Create: `android/app/src/main/cpp/CMakeLists.txt`

- [ ] **Step 1: Create top-level CMakeLists.txt**

Create `android/app/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project("localmediahub-native")

add_subdirectory(src/main/cpp)
```

- [ ] **Step 2: Create cpp/CMakeLists.txt**

Create `android/app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)

# --- libjpeg-turbo ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_TESTING OFF CACHE BOOL "" FORCE)
set(WITH_SIMD ON CACHE BOOL "" FORCE)
set(WITH_TURBOJPEG OFF CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo libjpeg-turbo-build)

# --- libwebp ---
set(WEBP_BUILD_CWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_DWEBP OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_EXTRAS OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPINFO OFF CACHE BOOL "" FORCE)
set(WEBP_BUILD_WEBPMUX OFF CACHE BOOL "" FORCE)
set(WEBP_ENABLE_SIMD ON CACHE BOOL "" FORCE)
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/libwebp libwebp-build)

# --- JNI bridge library ---
add_library(native-image-decoder SHARED jni/native_image_decoder.cpp)

target_include_directories(native-image-decoder PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/jni
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libjpeg-turbo
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libwebp/src
)

target_link_libraries(native-image-decoder
    jpeg
    webp
    webpdecoder
    android
    log
)
```

- [ ] **Step 3: Commit**

```bash
git add android/app/CMakeLists.txt android/app/src/main/cpp/CMakeLists.txt
git commit -m "build: add CMake configuration for native image decoder"
```

---

### Task 3: Write JNI bridge (C++ header and source)

**Files:**
- Create: `android/app/src/main/cpp/jni/native_image_decoder.h`
- Create: `android/app/src/main/cpp/jni/native_image_decoder.cpp`

- [ ] **Step 1: Create JNI header**

Create `android/app/src/main/cpp/jni/native_image_decoder.h`:

```cpp
#ifndef NATIVE_IMAGE_DECODER_H
#define NATIVE_IMAGE_DECODER_H

#include <jni.h>
#include <android/bitmap.h>

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Decode JPEG data into an Android Bitmap at target size.
 * Returns null on failure.
 */
JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeJpeg(
    JNIEnv *env, jclass clazz, jbyteArray data, jint targetWidth, jint targetHeight);

/**
 * Decode WebP data into an Android Bitmap at target size.
 * Returns null on failure.
 */
JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeWebp(
    JNIEnv *env, jclass clazz, jbyteArray data, jint targetWidth, jint targetHeight);

/**
 * Get image info (width, height, format) from header bytes.
 * Returns int[3]: {width, height, format} where format is:
 *   0 = unknown, 1 = JPEG, 2 = WebP
 */
JNIEXPORT jintArray JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeGetImageInfo(
    JNIEnv *env, jclass clazz, jbyteArray data);

#ifdef __cplusplus
}
#endif

#endif // NATIVE_IMAGE_DECODER_H
```

- [ ] **Step 2: Create JNI implementation**

Create `android/app/src/main/cpp/jni/native_image_decoder.cpp`:

```cpp
#include "native_image_decoder.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <cstring>
#include <cstdlib>

// libjpeg-turbo
#include <jpeglib.h>
#include <jerror.h>

// libwebp
#include <webp/decode.h>
#include <webp/demux.h>

#define LOG_TAG "NativeImageDecoder"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// --- Format detection from header bytes ---
static int detectFormat(const uint8_t *data, size_t length) {
    if (length < 12) return 0; // unknown
    // JPEG: FF D8 FF
    if (data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF) return 1;
    // WebP: RIFF....WEBP
    if (memcmp(data, "RIFF", 4) == 0 && memcmp(data + 8, "WEBP", 4) == 0) return 2;
    return 0;
}

// --- Create Android Bitmap from RGBA pixels ---
static jobject createBitmap(JNIEnv *env, int width, int height, const uint8_t *rgbaData) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");

    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
                                                     "Landroid/graphics/Bitmap$Config;");
    jobject argb8888 = env->GetStaticObjectField(configClass, argb8888Field);

    jmethodID createBitmapMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
                                                  width, height, argb8888);

    // Copy pixel data into bitmap
    AndroidBitmapInfo info;
    AndroidBitmap_getInfo(env, bitmap, &info);

    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) == ANDROID_BITMAP_RESUT_SUCCESS) {
        // Bitmap is ARGB_8888, our decoded data is RGBA — swap channels
        uint32_t *dst = static_cast<uint32_t *>(pixels);
        const uint8_t *src = rgbaData;
        for (int i = 0; i < width * height; i++) {
            uint8_t r = src[i * 4 + 0];
            uint8_t g = src[i * 4 + 1];
            uint8_t b = src[i * 4 + 2];
            uint8_t a = src[i * 4 + 3];
            dst[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        AndroidBitmap_unlockPixels(env, bitmap);
    }

    return bitmap;
}

// --- Scale dimensions to fit target ---
static void calcTargetSize(int srcW, int srcH, int targetW, int targetH, int &outW, int &outH) {
    if (targetW <= 0 && targetH <= 0) {
        outW = srcW;
        outH = srcH;
        return;
    }
    float scaleW = (targetW > 0) ? (float)targetW / srcW : 999.0f;
    float scaleH = (targetH > 0) ? (float)targetH / srcH : 999.0f;
    float scale = (scaleW < scaleH) ? scaleW : scaleH;
    if (scale > 1.0f) scale = 1.0f;
    outW = (int)(srcW * scale);
    outH = (int)(srcH * scale);
    if (outW < 1) outW = 1;
    if (outH < 1) outH = 1;
}

// ============================================================
// JPEG decoding via libjpeg-turbo
// ============================================================

JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeJpeg(
    JNIEnv *env, jclass clazz, jbyteArray data, jint targetWidth, jint targetHeight) {

    jsize dataLen = env->GetArrayLength(data);
    jbyte *dataBytes = env->GetByteArrayElements(data, nullptr);
    if (!dataBytes) return nullptr;

    uint8_t *jpegData = reinterpret_cast<uint8_t *>(dataBytes);
    jobject resultBitmap = nullptr;

    struct jpeg_decompress_struct cinfo;
    struct jpeg_error_mgr jerr;

    cinfo.err = jpeg_std_error(&jerr);
    jpeg_create_decompress(&cinfo);
    jpeg_mem_src(&cinfo, jpegData, (unsigned long)dataLen);

    if (jpeg_read_header(&cinfo, TRUE) != JPEG_HEADER_OK) {
        LOGE("JPEG header read failed");
        jpeg_destroy_decompress(&cinfo);
        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
        return nullptr;
    }

    int srcW = cinfo.image_width;
    int srcH = cinfo.image_height;
    int outW, outH;
    calcTargetSize(srcW, srcH, targetWidth, targetHeight, outW, outH);

    cinfo.out_color_space = JCS_EXT_RGBA;
    cinfo.scale_num = outW;
    cinfo.scale_denom = srcW;
    // Use libjpeg-turbo's built-in scaling (only supports 1/1, 1/2, 1/4, 1/8)
    // We'll do full decode then scale if needed
    cinfo.scale_num = 1;
    cinfo.scale_denom = 1;

    if (!jpeg_start_decompress(&cinfo)) {
        LOGE("JPEG start_decompress failed");
        jpeg_destroy_decompress(&cinfo);
        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
        return nullptr;
    }

    int rowStride = cinfo.output_width * cinfo.output_components;
    std::vector<uint8_t> imageData(cinfo.output_width * cinfo.output_height * 4);
    uint8_t *rowPtr = imageData.data();

    while (cinfo.output_scanline < cinfo.output_height) {
        jpeg_read_scanlines(&cinfo, &rowPtr, 1);
        rowPtr += rowStride;
    }

    jpeg_finish_decompress(&cinfo);
    jpeg_destroy_decompress(&cinfo);

    // If scaling needed, use Android Bitmap.createScaledBitmap
    // For now, create bitmap at decoded size
    resultBitmap = createBitmap(env, cinfo.output_width, cinfo.output_height, imageData.data());

    env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
    return resultBitmap;
}

// ============================================================
// WebP decoding via libwebp
// ============================================================

JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeWebp(
    JNIEnv *env, jclass clazz, jbyteArray data, jint targetWidth, jint targetHeight) {

    jsize dataLen = env->GetArrayLength(data);
    jbyte *dataBytes = env->GetByteArrayElements(data, nullptr);
    if (!dataBytes) return nullptr;

    const uint8_t *webpData = reinterpret_cast<const uint8_t *>(dataBytes);
    jobject resultBitmap = nullptr;

    int srcW = 0, srcH = 0;
    if (!WebPGetInfo(webpData, (size_t)dataLen, &srcW, &srcH)) {
        LOGE("WebPGetInfo failed");
        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
        return nullptr;
    }

    int outW, outH;
    calcTargetSize(srcW, srcH, targetWidth, targetHeight, outW, outH);

    WebPDecoderConfig config;
    if (!WebPInitDecoderConfig(&config)) {
        LOGE("WebPInitDecoderConfig failed");
        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
        return nullptr;
    }

    config.options.use_scaling = 1;
    config.options.scaled_width = outW;
    config.options.scaled_height = outH;
    config.output.colorspace = MODE_RGBA;

    VP8StatusCode status = WebPDecode(webpData, (size_t)dataLen, &config);
    if (status != VP8_STATUS_OK) {
        LOGE("WebPDecode failed with status %d", status);
        WebPFreeDecBuffer(&config.output);
        env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
        return nullptr;
    }

    resultBitmap = createBitmap(env, config.output.width, config.output.height,
                                config.output.u.RGBA.rgba);
    WebPFreeDecBuffer(&config.output);
    env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);
    return resultBitmap;
}

// ============================================================
// Image info from header
// ============================================================

JNIEXPORT jintArray JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeGetImageInfo(
    JNIEnv *env, jclass clazz, jbyteArray data) {

    jsize dataLen = env->GetArrayLength(data);
    jbyte *dataBytes = env->GetByteArrayElements(data, nullptr);
    if (!dataBytes) return nullptr;

    const uint8_t *rawData = reinterpret_cast<const uint8_t *>(dataBytes);
    int format = detectFormat(rawData, dataLen);
    int width = 0, height = 0;

    if (format == 1) { // JPEG
        // Quick JPEG dimension read from SOF marker
        for (int i = 0; i < dataLen - 9; i++) {
            if ((rawData[i] & 0xFF) == 0xFF) {
                uint8_t marker = rawData[i + 1];
                if (marker == 0xC0 || marker == 0xC2) { // SOF0 or SOF2
                    height = ((rawData[i + 5] & 0xFF) << 8) | (rawData[i + 6] & 0xFF);
                    width = ((rawData[i + 7] & 0xFF) << 8) | (rawData[i + 8] & 0xFF);
                    break;
                }
            }
        }
    } else if (format == 2) { // WebP
        // Simple WebP: width/height at offset 26-29 (RIFF header + VP8 chunk header)
        if (dataLen >= 30) {
            if (memcmp(rawData + 12, "VP8 ", 4) == 0) {
                // Lossy WebP
                width = ((rawData[26] & 0xFF) | ((rawData[27] & 0xFF) << 8)) & 0x3FFF;
                height = ((rawData[28] & 0xFF) | ((rawData[29] & 0xFF) << 8)) & 0x3FFF;
            } else if (memcmp(rawData + 12, "VP8L", 4) == 0) {
                // Lossless WebP
                uint32_t bits = rawData[21] | (rawData[22] << 8) | (rawData[23] << 16) | (rawData[24] << 24);
                width = (bits & 0x3FFF) + 1;
                height = ((bits >> 14) & 0x3FFF) + 1;
            }
        }
    }

    env->ReleaseByteArrayElements(data, dataBytes, JNI_ABORT);

    jint info[3] = {width, height, format};
    jintArray result = env->NewIntArray(3);
    env->SetIntArrayRegion(result, 0, 3, info);
    return result;
}
```

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/cpp/jni/
git commit -m "feat: add JNI bridge for native JPEG/WebP image decoding"
```

---

### Task 4: Configure build.gradle.kts for NDK/CMake

**Files:**
- Modify: `android/app/build.gradle.kts`

- [ ] **Step 1: Add NDK and CMake configuration**

In `android/app/build.gradle.kts`, add inside the `android { }` block, after the `defaultConfig { }` block:

```kotlin
    defaultConfig {
        // ... existing config ...
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
```

Add after `buildFeatures { }` block:

```kotlin
    buildFeatures {
        compose = true
    }
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

- [ ] **Step 2: Verify build.gradle.kts compiles**

Run: `cd android && ./gradlew :app:assembleDebug --dry-run`
Expected: BUILD SUCCESSFUL (dry run)

- [ ] **Step 3: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "build: add NDK/CMake config for arm64-v8a native image decoder"
```

---

### Task 5: Write NativeImageDecoder Kotlin wrapper

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt`

- [ ] **Step 1: Create NativeImageDecoder.kt**

```kotlin
package com.juziss.localmediahub.native

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Kotlin wrapper for native image decoding via JNI.
 * Provides suspend functions for JPEG/WebP decoding with automatic format detection
 * and fallback to BitmapFactory on JNI failure.
 */
object NativeImageDecoder {

    private const val TAG = "NativeImageDecoder"

    init {
        System.loadLibrary("native-image-decoder")
    }

    // Format constants matching C++ side
    const val FORMAT_UNKNOWN = 0
    const val FORMAT_JPEG = 1
    const val FORMAT_WEBP = 2

    // --- JNI native methods ---
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

    /**
     * Detect image format from header bytes.
     * @return IntArray [width, height, format] or null on error
     */
    fun getImageInfo(data: ByteArray): ImageInfo? {
        val info = nativeGetImageInfo(data) ?: return null
        if (info.size != 3) return null
        return ImageInfo(
            width = info[0],
            height = info[1],
            format = info[2]
        )
    }

    /**
     * Decode image data to Bitmap using native libraries.
     * Automatically detects format and selects the correct decoder.
     * Falls back to BitmapFactory on JNI failure.
     *
     * @param data Raw image bytes
     * @param targetWidth Max width (0 = no limit)
     * @param targetHeight Max height (0 = no limit)
     * @return Decoded Bitmap
     */
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

    private fun fallbackDecode(
        data: ByteArray,
        targetWidth: Int,
        targetHeight: Int
    ): Bitmap {
        Log.w(TAG, "Falling back to BitmapFactory for decoding")
        if (targetWidth > 0 && targetHeight > 0) {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
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
        reqHeight: Int
    ): Int {
        val (height, width) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight &&
                   halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }
}

data class ImageInfo(
    val width: Int,
    val height: Int,
    val format: Int
)
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/native/NativeImageDecoder.kt
git commit -m "feat: add Kotlin wrapper for native image decoding with fallback"
```

---

### Task 6: Write NativeDecoderFactory for Coil integration

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt`

- [ ] **Step 1: Create NativeDecoderFactory.kt**

```kotlin
package com.juziss.localmediahub.native

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.decode.ImageSource
import coil.fetch.SourceResult
import coil.size.Size
import coil.size.pxOrElse
import okio.buffer
import okio.sink
import java.io.ByteArrayOutputStream

/**
 * Coil Decoder that routes JPEG/WebP images to native decoding
 * and falls back to BitmapFactory for PNG/BMP/GIF.
 */
class NativeDecoderFactory : Decoder {

    override fun key(): String = "NativeDecoder"

    override suspend fun decode(
        source: SourceResult,
        size: Size,
        options: coil.request.Options
    ): DecodeResult {
        val bufferedSource = source.source.source().buffer()
        val bytes = bufferedSource.readByteArray()

        val info = NativeImageDecoder.getImageInfo(bytes)

        val bitmap: Bitmap = when (info?.format) {
            NativeImageDecoder.FORMAT_JPEG,
            NativeImageDecoder.FORMAT_WEBP -> {
                val targetWidth = size.width.pxOrElse { 0 }
                val targetHeight = size.height.pxOrElse { 0 }
                NativeImageDecoder.decode(bytes, targetWidth, targetHeight)
            }
            else -> {
                // PNG, BMP, GIF — use BitmapFactory
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalArgumentException("Failed to decode image")
            }
        }

        return DecodeResult(
            drawable = android.graphics.drawable.BitmapDrawable(
                options.context.resources,
                bitmap
            ),
            isSampled = true
        )
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceResult,
            options: coil.request.Options,
            imageLoader: coil.ImageLoader
        ): Decoder? {
            // Peek at first 12 bytes for format detection
            val peekSource = result.source.source().buffer()
            peekSource.require(12) {
                // If we can read 12 bytes, check the format
            }
            val header = peekSource.readByteArray(12)

            val isJpeg = header.size >= 3 &&
                         header[0] == 0xFF.toByte() &&
                         header[1] == 0xD8.toByte() &&
                         header[2] == 0xFF.toByte()

            val isWebp = header.size >= 12 &&
                         String(header, 0, 4) == "RIFF" &&
                         String(header, 8, 4) == "WEBP"

            // Only handle JPEG and WebP natively, let others pass through
            return if (isJpeg || isWebp) {
                NativeDecoderFactory()
            } else {
                null // Let Coil use default BitmapFactoryDecoder
            }
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/native/NativeDecoderFactory.kt
git commit -m "feat: add Coil NativeDecoderFactory for JPEG/WebP native decoding"
```

---

### Task 7: Register NativeDecoder in MainActivity

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt`

- [ ] **Step 1: Add Coil ImageLoader with native decoder**

Add imports at top of `MainActivity.kt`:

```kotlin
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.BitmapFactoryDecoder
import com.juziss.localmediahub.native.NativeDecoderFactory
```

Make `MainActivity` implement `ImageLoaderFactory`:

```kotlin
class MainActivity : ComponentActivity(), ImageLoaderFactory {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalMediaHubTheme {
                LocalMediaHubApp()
            }
        }
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(NativeDecoderFactory.Factory())
                add(BitmapFactoryDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt
git commit -m "feat: register native image decoder in Coil ImageLoader"
```

---

## Module 2: FFmpeg Extension for Video Playback

### Task 8: Add pre-built FFmpeg .so to jniLibs

**Files:**
- Create: `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`

- [ ] **Step 1: Download or build FFmpeg .so for arm64-v8a**

This step requires a pre-built FFmpeg shared library. The recommended approach is to build from source on a Linux/macOS host:

```bash
# Build FFmpeg for Android arm64-v8a (run on Linux/macOS host)
git clone --depth 1 --branch n6.1 https://github.com/FFmpeg/FFmpeg.git
cd FFmpeg

export NDK=/path/to/android-ndk
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export API=26
export TARGET=aarch64-linux-android

./configure \
    --prefix=./build \
    --enable-shared \
    --disable-static \
    --disable-programs \
    --disable-doc \
    --disable-debug \
    --enable-small \
    --enable-decoder=h264,hevc,vp8,vp9,av1,mpeg4,vc1,h263 \
    --enable-demuxer=mkv,avi,flv,asf,ts,mov \
    --enable-parser=h264,hevc,vp8,vp9,av1,mpeg4video,vc1,h263 \
    --disable-everything \
    --enable-decoder=h264,hevc,vp8,vp9,av1,mpeg4,vc1,h263 \
    --enable-demuxer=mkv,avi,flv,asf,ts,mov \
    --enable-parser=h264,hevc,vp8,vp9,av1,mpeg4video,vc1,h263 \
    --cross-prefix=$TOOLCHAIN/bin/$TARGET$API- \
    --target-os=android \
    --arch=aarch64 \
    --enable-cross-compile \
    --sysroot=$TOOLCHAIN/sysroot \
    --extra-cflags="-Os -fPIC" \
    --extra-ldflags="" \
    --enable-jni \
    --enable-mediacodec \
    --disable-network \
    --disable-autodetect

make -j$(nproc)
make install
```

Then copy `build/lib/libffmpeg.so` to `android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`.

Expected: `libffmpeg.so` ~8-12 MB, placed in jniLibs.

- [ ] **Step 2: Verify .so is in place**

Run: `ls -lh android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`
Expected: File exists, ~8-12 MB

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/jniLibs/
git commit -m "feat: add pre-built FFmpeg shared library for arm64-v8a"
```

**Note:** If the pre-built .so is too large for git, consider using Git LFS or providing a download script. Add to `.gitattributes` if using LFS:

```
*.so filter=lfs diff=lfs merge=lfs -text
```

---

### Task 9: Add FFmpeg Extension dependency and configure ExoPlayer

**Files:**
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`

- [ ] **Step 1: Add media3-decoder-ffmpeg dependency**

In `android/app/build.gradle.kts`, replace the existing media3 dependencies:

```kotlin
    // Video player
    implementation("androidx.media3:media3-exoplayer:1.2.0")
    implementation("androidx.media3:media3-ui:1.2.0")
    implementation("androidx.media3:media3-decoder-ffmpeg:1.2.0")
```

- [ ] **Step 2: Update ExoPlayer builder in VideoPlayerScreen.kt**

Replace the ExoPlayer builder section in `VideoPlayerScreen.kt` (lines 37-44):

```kotlin
    val exoPlayer = remember {
        val renderersFactory = DefaultRenderersFactory(context).apply {
            // Enable FFmpeg extension as software decoder fallback
            // ExoPlayer will try hardware MediaCodec first, then FFmpeg
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
        ExoPlayer.Builder(context, renderersFactory).build().apply {
            val mediaItem = MediaItem.fromUri(streamUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }
```

Add the import:

```kotlin
import androidx.media3.exoplayer.DefaultRenderersFactory
```

- [ ] **Step 3: Verify build compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "feat: add FFmpeg extension for broader video format support"
```

---

## Module 3: Video Gesture Controls

### Task 10: Write VideoGestureOverlay Composable

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/VideoGestureOverlay.kt`

- [ ] **Step 1: Create VideoGestureOverlay.kt**

```kotlin
package com.juziss.localmediahub.ui.component

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class SeekState(
    val isSeeking: Boolean = false,
    val offsetMs: Long = 0L,
)

private data class GestureIndicatorState(
    val visible: Boolean = false,
    val icon: ImageVector? = null,
    val text: String = "",
)

/**
 * Gesture overlay for video player.
 *
 * Supports:
 * - Horizontal swipe: seek forward/backward (proportional, 5s-120s)
 * - Double tap: play/pause toggle
 * - Left half vertical swipe: brightness
 * - Right half vertical swipe: volume
 */
@Composable
fun VideoGestureOverlay(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var seekState by remember { mutableStateOf(SeekState()) }
    var playPauseIndicator by remember { mutableStateOf(GestureIndicatorState()) }
    var brightnessIndicator by remember { mutableStateOf(GestureIndicatorState()) }
    var volumeIndicator by remember { mutableStateOf(GestureIndicatorState()) }

    // Auto-hide indicators after 800ms
    LaunchedEffect(playPauseIndicator.visible) {
        if (playPauseIndicator.visible) {
            delay(800)
            playPauseIndicator = playPauseIndicator.copy(visible = false)
        }
    }
    LaunchedEffect(brightnessIndicator.visible) {
        if (brightnessIndicator.visible) {
            delay(1000)
            brightnessIndicator = brightnessIndicator.copy(visible = false)
        }
    }
    LaunchedEffect(volumeIndicator.visible) {
        if (volumeIndicator.visible) {
            delay(1000)
            volumeIndicator = volumeIndicator.copy(visible = false)
        }
    }

    // Apply seek on gesture end
    LaunchedEffect(seekState.isSeeking) {
        if (!seekState.isSeeking && seekState.offsetMs != 0L) {
            val currentPos = player.currentPosition
            val newPos = (currentPos + seekState.offsetMs).coerceIn(0L, player.duration)
            player.seekTo(newPos)
            seekState = SeekState()
        }
    }

    Box(modifier = modifier) {
        // Gesture detector fills entire area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragStartX = 0f
                    var dragStartY = 0f
                    var totalDragX = 0f
                    var totalDragY = 0f
                    var isHorizontalDrag: Boolean? = null
                    var lastTapTime = 0L

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    val change = event.changes.first()
                                    dragStartX = change.position.x
                                    dragStartY = change.position.y
                                    totalDragX = 0f
                                    totalDragY = 0f
                                    isHorizontalDrag = null
                                }
                                PointerEventType.Release -> {
                                    val change = event.changes.first()
                                    val dx = change.position.x - dragStartX
                                    val dy = change.position.y - dragStartY
                                    val distanceThreshold = 30.dp.toPx()

                                    if (kotlin.math.abs(dx) < distanceThreshold &&
                                        kotlin.math.abs(dy) < distanceThreshold) {
                                        // It's a tap, not a swipe — check double tap
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 300) {
                                            // Double tap: toggle play/pause
                                            if (player.isPlaying) {
                                                player.pause()
                                                playPauseIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.Pause,
                                                    text = "已暂停"
                                                )
                                            } else {
                                                player.play()
                                                playPauseIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.PlayArrow,
                                                    text = "播放中"
                                                )
                                            }
                                            lastTapTime = 0L
                                        } else {
                                            lastTapTime = now
                                        }

                                        // Finalize seek if was seeking
                                        if (seekState.isSeeking) {
                                            seekState = seekState.copy(isSeeking = false)
                                        }
                                    } else {
                                        // Finalize seek after horizontal swipe
                                        if (seekState.isSeeking) {
                                            seekState = seekState.copy(isSeeking = false)
                                        }
                                    }
                                }
                                PointerEventType.Move -> {
                                    // Not used in basic implementation — 
                                    // horizontal seek is calculated on Release
                                }
                            }
                        }
                    }
                }
        )

        // --- Seek indicator ---
        AnimatedVisibility(
            visible = seekState.isSeeking,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    if (seekState.offsetMs >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = formatSeekOffset(seekState.offsetMs),
                    color = Color.White,
                    fontSize = 16.sp
                )
            }
        }

        // --- Play/Pause indicator ---
        AnimatedVisibility(
            visible = playPauseIndicator.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
            ) {
                Icon(
                    playPauseIndicator.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // --- Brightness indicator ---
        AnimatedVisibility(
            visible = brightnessIndicator.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.Brightness6,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(brightnessIndicator.text, color = Color.White, fontSize = 14.sp)
            }
        }

        // --- Volume indicator ---
        AnimatedVisibility(
            visible = volumeIndicator.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(volumeIndicator.text, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

private fun formatSeekOffset(offsetMs: Long): String {
    val seconds = kotlin.math.abs(offsetMs) / 1000
    val sign = if (offsetMs >= 0) "+" else "-"
    return if (seconds >= 60) {
        val min = seconds / 60
        val sec = seconds % 60
        "$sign${min}分${sec}秒"
    } else {
        "$sign${seconds}秒"
    }
}
```

**Note:** The initial version provides double-tap play/pause and basic seek indicator UI. The full swipe-based seek, brightness, and volume gesture logic requires more advanced `pointerInput` handling with `detectDragGestures`. The implementation above establishes the composable structure and indicator UI. The next task adds the complete drag gesture logic.

- [ ] **Step 2: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/VideoGestureOverlay.kt
git commit -m "feat: add video gesture overlay composable with seek/brightness/volume UI"
```

---

### Task 11: Integrate gesture overlay into VideoPlayerScreen

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`

- [ ] **Step 1: Add gesture overlay to VideoPlayerScreen**

Add import:

```kotlin
import com.juziss.localmediahub.ui.component.VideoGestureOverlay
```

In the `Box` composable, after the `AndroidView` for `PlayerView` and before the back button `IconButton`, add:

```kotlin
        // ---- Gesture overlay on top of video ----
        VideoGestureOverlay(
            player = exoPlayer,
            modifier = Modifier.fillMaxSize()
        )
```

- [ ] **Step 2: Verify build compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "feat: integrate gesture overlay into video player screen"
```

---

### Task 12: Implement full swipe gesture logic (seek, brightness, volume)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/VideoGestureOverlay.kt`

- [ ] **Step 1: Replace the basic pointerInput with full gesture detection**

Replace the `pointerInput(Unit)` block inside `VideoGestureOverlay` with the complete gesture logic that handles:

1. **Horizontal swipe** → proportional seek (5s-120s based on distance)
2. **Left half vertical swipe** → brightness adjustment
3. **Right half vertical swipe** → volume adjustment
4. **Double tap** → play/pause (already implemented)

The replacement `pointerInput` block:

```kotlin
                .pointerInput(Unit) {
                    var dragStartX = 0f
                    var dragStartY = 0f
                    var lastTapTime = 0L
                    var isDragging = false
                    var isHorizontalDrag: Boolean? = null

                    // Track accumulated values
                    var seekAccumulatorMs = 0L
                    var brightnessStart = 0f
                    var volumeStart = 0

                    fun getBrightness(): Float {
                        val activity = context as? Activity ?: return 0.5f
                        val params = activity.window.attributes
                        return if (params.screenBrightness < 0) 0.5f else params.screenBrightness
                    }

                    fun setBrightness(value: Float) {
                        val activity = context as? Activity ?: return
                        val clamped = value.coerceIn(0f, 1f)
                        val params = activity.window.attributes
                        params.screenBrightness = clamped
                        activity.window.attributes = params
                    }

                    fun getVolume(): Int {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }

                    fun getMaxVolume(): Int {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    }

                    fun setVolume(volume: Int) {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, volume.coerceIn(0, max), 0)
                    }

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    val change = event.changes.first()
                                    dragStartX = change.position.x
                                    dragStartY = change.position.y
                                    isDragging = false
                                    isHorizontalDrag = null
                                    seekAccumulatorMs = 0L
                                    brightnessStart = getBrightness()
                                    volumeStart = getVolume()
                                }
                                PointerEventType.Move -> {
                                    val change = event.changes.first()
                                    val dx = change.position.x - dragStartX
                                    val dy = change.position.y - dragStartY
                                    val threshold = 30.dp.toPx()

                                    if (!isDragging) {
                                        if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) {
                                            isDragging = true
                                            isHorizontalDrag = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                        }
                                    }

                                    if (isDragging) {
                                        if (isHorizontalDrag == true) {
                                            // Horizontal: seek proportional
                                            // 1px = 1s, clamped 5s-120s
                                            val seekSec = (dx / density).toInt().coerceIn(-120, 120)
                                            seekAccumulatorMs = (seekSec.toLong() * 1000).coerceIn(-120_000L, 120_000L)
                                            seekState = SeekState(
                                                isSeeking = true,
                                                offsetMs = seekAccumulatorMs
                                            )
                                        } else {
                                            // Vertical: brightness (left) or volume (right)
                                            val isLeftHalf = dragStartX < size.width / 2
                                            val progress = -dy / size.height // swipe up = positive

                                            if (isLeftHalf) {
                                                val newBrightness = (brightnessStart + progress).coerceIn(0f, 1f)
                                                setBrightness(newBrightness)
                                                brightnessIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.Brightness6,
                                                    text = "${(newBrightness * 100).toInt()}%"
                                                )
                                            } else {
                                                val maxVolume = getMaxVolume()
                                                val delta = (progress * maxVolume).toInt()
                                                val newVolume = (volumeStart + delta).coerceIn(0, maxVolume)
                                                setVolume(newVolume)
                                                volumeIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.VolumeUp,
                                                    text = "$newVolume/$maxVolume"
                                                )
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (isDragging) {
                                        // Finalize seek
                                        if (seekState.isSeeking) {
                                            seekState = seekState.copy(isSeeking = false)
                                        }
                                    } else {
                                        // It's a tap
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 300) {
                                            // Double tap: toggle play/pause
                                            if (player.isPlaying) {
                                                player.pause()
                                                playPauseIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.Pause,
                                                    text = "已暂停"
                                                )
                                            } else {
                                                player.play()
                                                playPauseIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.PlayArrow,
                                                    text = "播放中"
                                                )
                                            }
                                            lastTapTime = 0L
                                        } else {
                                            lastTapTime = now
                                        }
                                    }
                                    isDragging = false
                                }
                            }
                        }
                    }
                }
```

- [ ] **Step 2: Verify build compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/VideoGestureOverlay.kt
git commit -m "feat: add full swipe gesture logic for seek, brightness, volume"
```

---

## Final Verification

### Task 13: Full build verification

- [ ] **Step 1: Clean build**

Run: `cd android && ./gradlew clean :app:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Check APK size**

Run: `ls -lh android/app/build/outputs/apk/debug/app-debug.apk`
Expected: APK size increased by ~10-14 MB compared to baseline

- [ ] **Step 3: Verify native libraries in APK**

Run: `unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep -E "\.so$"`
Expected: `lib/arm64-v8a/libnative-image-decoder.so`, `lib/arm64-v8a/libffmpeg.so`

- [ ] **Step 4: Commit final state**

```bash
git add -A
git commit -m "feat: complete native layer optimization — image decoder, FFmpeg, gesture controls"
```

---

## Dependency Graph

```
Task 1 (vendor libs) ──┐
Task 2 (CMake)        ──┼── Task 3 (JNI bridge) ── Task 4 (build.gradle) ── Task 5 (Kotlin wrapper) ── Task 6 (Coil factory) ── Task 7 (MainActivity)
                       │
                       └── (independent of Module 2 and 3)

Task 8 (FFmpeg .so)  ──── Task 9 (ExoPlayer config) ── (independent of Module 1)

Task 10 (Gesture UI) ──── Task 11 (Integrate) ──── Task 12 (Full gestures) ── (independent of Module 1 and 2)

Task 13 (Verification) ── depends on ALL above
```

**Module 1, 2, and 3 can be developed in parallel.** Task 13 is the final integration gate.

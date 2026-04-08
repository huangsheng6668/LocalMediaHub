# Native Layer Optimization Design Spec

## Goal

Add native C++ libraries (libjpeg-turbo, libwebp, FFmpeg) to the Android client for faster image decoding and broader video format support, plus custom video gesture controls.

## Architecture

**Approach B: Specialized libraries** — each domain gets the best-in-class library:
- Image decoding: libjpeg-turbo (JPEG) + libwebp (WebP), integrated via Coil custom Decoder
- Video playback: FFmpeg Extension for Media3 ExoPlayer (hardware-first, software fallback)
- Video UX: Custom Compose gesture overlay for seek/brightness/volume

**Target ABI:** arm64-v8a only (~10-14 MB APK increase)

## Module 1: Native Image Decoder

### Libraries
- **libjpeg-turbo**: SIMD-accelerated JPEG decoding (2-4x faster than BitmapFactory on arm64)
- **libwebp**: Google's WebP decoder with NEON optimizations
- **BitmapFactory fallback**: PNG, BMP, GIF remain on Android's built-in decoder

### JNI Interface

C++ functions exposed via JNI (`native_image_decoder.cpp`):
- `nativeDecodeJpeg(byte[] data, int targetWidth, int targetHeight)` → Bitmap
- `nativeDecodeWebp(byte[] data, int targetWidth, int targetHeight)` → Bitmap
- `nativeGetImageInfo(byte[] data)` → int[] {width, height, format}

Format detection by file header bytes (FF D8 for JPEG, RIFF...WEBP for WebP).

### Kotlin Wrapper

`NativeImageDecoder.kt` — wraps JNI calls with:
- Suspend functions running on `Dispatchers.Default`
- Automatic format detection and decoder selection
- Target size calculation (thumbnails: 300px, preview: screen dimensions)
- Error handling with fallback to BitmapFactory on JNI failure

### Coil Integration

`NativeDecoderFactory.kt` — implements Coil's `Decoder` interface:
- Checks source data for JPEG/WebP magic bytes
- Routes to `NativeImageDecoder` for supported formats
- Falls back to default `BitmapFactoryDecoder` for PNG/BMP/GIF
- Registered globally in `ImageLoader` via `MainActivity`

## Module 2: Video Enhancement (FFmpeg Extension)

### Strategy
- Keep Media3 ExoPlayer as the player framework
- Add `media3-decoder-ffmpeg` extension for software decoding fallback
- ExoPlayer's `DefaultRenderersFactory` automatically picks: MediaCodec (hardware) → FFmpeg (software)

### FFmpeg Build
- Custom build with decoders: H.264, H.265/HEVC, VP8, VP9, AV1, MPEG-4, VC-1, H.263
- Demuxers: MKV, AVI, FLV, WMV/ASF, TS, MOV/MP4
- arm64-v8a only, estimated 8-12 MB
- Pre-built .so placed in `jniLibs/arm64-v8a/`

### Integration
- Add `media3-decoder-ffmpeg` dependency to build.gradle.kts
- Configure `ExoPlayer.Builder` with `DefaultRenderersFactory` that includes FFmpeg renderer
- `VideoPlayerScreen.kt`: minimal change — just ExoPlayer builder config

## Module 3: Video Gesture Controls

### Gesture Map
| Gesture | Action |
|---------|--------|
| Horizontal swipe left | Fast forward (5s-120s proportional to distance) |
| Horizontal swipe right | Rewind (5s-120s proportional to distance) |
| Double tap | Play/Pause toggle |
| Left half vertical swipe | Brightness adjustment |
| Right half vertical swipe | Volume adjustment |

### Implementation
- `VideoGestureOverlay.kt` — standalone Composable with `pointerInput` gesture detection
- Overlay UI: seek indicator (time + offset), brightness/volume progress bar
- Stacked on top of `PlayerView` via Compose `Box` layout
- Does not modify `PlayerView` internals
- Brightness via `WindowManager.LayoutParams.screenBrightness`
- Volume via `AudioManager.setStreamVolume()`
- Swipe threshold: 30dp minimum to distinguish tap from swipe

## Build System

### CMake Configuration
- Top-level `CMakeLists.txt` in `android/app/`
- Builds libjpeg-turbo from source (requires NASM)
- Builds libwebp from source
- Compiles JNI bridge code (`native_image_decoder.cpp`)
- FFmpeg: pre-built `.so` in `jniLibs/`, not built by CMake

### build.gradle.kts Changes
- Add `externalNativeBuild { cmake { ... } }` block
- Add `ndk { abiFilters += "arm64-v8a" }` to defaultConfig
- Add `media3-decoder-ffmpeg` dependency
- Add CMake version `"3.22.1"`

### File Structure (New Files)
```
android/app/
├── CMakeLists.txt
├── src/main/
│   ├── cpp/
│   │   ├── CMakeLists.txt
│   │   ├── jni/
│   │   │   ├── native_image_decoder.cpp
│   │   │   └── native_image_decoder.h
│   │   └── third_party/
│   │       ├── libjpeg-turbo/    (source)
│   │       └── libwebp/          (source)
│   ├── jniLibs/
│   │   └── arm64-v8a/
│   │       └── libffmpeg.so      (pre-built)
│   └── java/com/juziss/localmediahub/
│       └── native/
│           ├── NativeImageDecoder.kt
│           └── NativeDecoderFactory.kt
├── src/main/java/com/juziss/localmediahub/
│   └── ui/
│       └── component/
│           └── VideoGestureOverlay.kt
```

### Modified Files
- `android/app/build.gradle.kts` — NDK/CMake + FFmpeg Extension dependency
- `android/app/src/main/.../ui/screen/VideoPlayerScreen.kt` — FFmpeg ExoPlayer + gesture overlay
- `android/app/src/main/.../MainActivity.kt` — Coil ImageLoader with NativeDecoder

### APK Size Estimate
| Component | arm64-v8a |
|-----------|-----------|
| libjpeg-turbo | ~1.5 MB |
| libwebp | ~0.8 MB |
| libffmpeg (stripped) | ~8-12 MB |
| JNI bridge | ~0.1 MB |
| **Total** | **~10-14 MB** |

## Constraints
- arm64-v8a only — no x86, no armeabi-v7a
- Android minSdk 26 — NDK APIs from API 26 available
- Server-side code unchanged
- No breaking changes to existing navigation, ViewModel, or network layers

## Not In Scope
- Audio playback support
- Native network layer (OkHttp stays)
- Native sort/search computation
- Server-side changes

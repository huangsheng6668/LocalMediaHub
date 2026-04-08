# Building libffmpeg.so for Android arm64-v8a

This directory should contain a pre-built FFmpeg shared library (`libffmpeg.so`)
used by the Media3 ExoPlayer FFmpeg extension for software decoding fallback.

The app will compile without this file -- hardware MediaCodec decoding still works.
However, formats not supported by the device's hardware decoder will fail without
this library.

## Prerequisites

- Linux or macOS build host (not possible on Windows)
- Android NDK r25+ installed
- Make, NASM/YASM installed

## Build Steps

```bash
# 1. Clone FFmpeg source
git clone --depth 1 --branch n6.1 https://github.com/FFmpeg/FFmpeg.git
cd FFmpeg

# 2. Set up NDK paths
export NDK=/path/to/android-ndk
export TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
export API=26
export TARGET=aarch64-linux-android

# 3. Configure for minimal video decoder build
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

# 4. Build
make -j$(nproc)
make install

# 5. Copy output to this directory
cp build/lib/libffmpeg.so /path/to/project/android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so
```

## Expected Result

- `libffmpeg.so` approximately 8-12 MB in size
- Contains decoders: H.264, HEVC, VP8, VP9, AV1, MPEG-4, VC-1, H.263
- Contains demuxers: MKV, AVI, FLV, ASF, TS, MOV

## Git LFS

If the .so file is too large for git (over 100 MB), use Git LFS:

```bash
# In project root
git lfs install
echo "*.so filter=lfs diff=lfs merge=lfs -text" >> .gitattributes
git add .gitattributes
```

## Alternative: Pre-built Binaries

Instead of building from source, you can also download pre-built FFmpeg Android
libraries from:

- https://github.com/Artalk25/FFmpeg-Android (unofficial builds)
- https://github.com/nicehash/FFmpeg-Android (unofficial builds)

Ensure the build includes the decoders and demuxers listed above and is
configured with `--enable-jni` and `--enable-mediacodec`.

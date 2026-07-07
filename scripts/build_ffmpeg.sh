#!/usr/bin/env bash
# scripts/build_ffmpeg.sh
# Cross-compiles FFmpeg for Android arm64-v8a, producing a minimal libffmpeg.so
# suitable for LocalMediaHub.
set -euo pipefail

FFMPEG_VERSION="6.1.1"
WORKDIR="$(pwd)/build/ffmpeg-src"
OUTPUT_DIR="$(pwd)/android/app/src/main/jniLibs/arm64-v8a"

NDK="/home/juziss/android-ndk-r26b"
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"

# Define compilers and tools
CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
STRIP="$TOOLCHAIN/bin/llvm-strip"

mkdir -p "$WORKDIR" "$OUTPUT_DIR"
cd "$WORKDIR"

if [[ ! -d "ffmpeg-${FFMPEG_VERSION}" ]]; then
  echo "Downloading FFmpeg ${FFMPEG_VERSION}..."
  curl -fsSL "https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz" -o "ffmpeg.tar.xz"
  tar xf ffmpeg.tar.xz
fi
cd "ffmpeg-${FFMPEG_VERSION}"

echo "Configuring FFmpeg..."
./configure \
  --target-os=android \
  --arch=aarch64 \
  --cpu=cortex-a57 \
  --enable-cross-compile \
  --cc="$CC" \
  --ar="$AR" \
  --sysroot="$SYSROOT" \
  --extra-cflags="-Os -fPIC" \
  --extra-ldflags="-Wl,--gc-sections" \
  --enable-static \
  --disable-shared \
  --disable-everything \
  --disable-doc \
  --disable-programs \
  --disable-debug \
  --disable-network \
  --disable-autodetect \
  --enable-small \
  --enable-pic \
  --disable-avformat \
  --disable-avfilter \
  --disable-swscale \
  --enable-swresample \
  --enable-decoder=h264,hevc \
  --disable-asm

echo "Building FFmpeg..."
make -j"$(nproc)"

echo "Linking static libraries into libffmpeg.so..."
$CC -shared -Wl,-Bsymbolic -Wl,--whole-archive \
  libavcodec/libavcodec.a \
  libavutil/libavutil.a \
  libswresample/libswresample.a \
  -Wl,--no-whole-archive \
  -lz -lm -llog \
  -o libffmpeg.so

echo "Stripping debug symbols..."
$STRIP --strip-unneeded libffmpeg.so

echo "Copying to target directory..."
cp libffmpeg.so "$OUTPUT_DIR/libffmpeg.so"

echo "Done!"
stat -c '%s bytes' "$OUTPUT_DIR/libffmpeg.so"

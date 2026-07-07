# FFmpeg Build Configuration (Round 21 D5)

This document records the build flags used to produce
`android/app/src/main/jniLibs/arm64-v8a/libffmpeg.so`.

## Source

FFmpeg upstream: <https://ffmpeg.org>
Version: 6.1.1

## Toolchain

- Android NDK r27 (set `/mnt/e/androidSDK/ndk/27.3.13750724`)
- Host: WSL (Ubuntu)
- make, autoconf, automake, libtool

## Configure flags

```bash
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
  --enable-decoder=h264,hevc,vp8,vp9,mpeg4,mpeg2video,theora,vc1,wmv3 \
  --enable-demuxer=mov,matroska,avi,flv,webm,mpegts,asf,ogg \
  --enable-protocol=file,pipe \
  --enable-filter=scale,format \
  --enable-muxer=mp4 \
  --disable-asm
```

Static libraries are compiled and then merged into `libffmpeg.so` via:
```bash
$CC -shared -Wl,-Bsymbolic -Wl,--whole-archive \
  libavcodec/libavcodec.a \
  libavformat/libavformat.a \
  libavutil/libavutil.a \
  libswresample/libswresample.a \
  libswscale/libswscale.a \
  libavfilter/libavfilter.a \
  -Wl,--no-whole-archive \
  -lz -lm -llog \
  -o libffmpeg.so
```
Then stripped with `llvm-strip`.

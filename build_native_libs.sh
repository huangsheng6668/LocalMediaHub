#!/bin/bash
set -e

NDK=/mnt/c/Users/juziss/AppData/Local/Android/Sdk/ndk/android-ndk-r27
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/linux-x86_64
API=26
TARGET=aarch64-linux-android

export PATH="$TOOLCHAIN/bin:/usr/bin:/bin"

CC=$TOOLCHAIN/bin/$TARGET$API-clang
AR=$TOOLCHAIN/bin/llvm-ar
RANLIB=$TOOLCHAIN/bin/llvm-ranlib

CFLAGS="-Os -fPIC -DPIC -DANDROID"
PROJECT=/mnt/f/github_project/localResourcesToPhone/android/app/src/main/cpp
LIBS_DIR=$PROJECT/libs/arm64-v8a
mkdir -p $LIBS_DIR

# ============================
# Build libjpeg-turbo manually
# ============================
echo "=== Building libjpeg-turbo ==="
JPEG_SRC=$PROJECT/third_party/libjpeg-turbo
JPEG_BUILD=/tmp/jpeg-manual
rm -rf $JPEG_BUILD && mkdir -p $JPEG_BUILD && cd $JPEG_BUILD

# Compile all .c files (excluding SIMD for now to avoid PIC issues)
JPEG_C_FILES=$(find $JPEG_SRC -maxdepth 1 -name 'jaricom.c' -o -name 'jcapimin.c' -o -name 'jcapistd.c' -o -name 'jcarith.c' -o -name 'jccoefct.c' -o -name 'jccolor.c' -o -name 'jcdctmgr.c' -o -name 'jchuff.c' -o -name 'jcinit.c' -o -name 'jcmainct.c' -o -name 'jcmarker.c' -o -name 'jcmaster.c' -o -name 'jcomapi.c' -o -name 'jcparam.c' -o -name 'jcprepct.c' -o -name 'jcsample.c' -o -name 'jctrans.c' -o -name 'jdapimin.c' -o -name 'jdapistd.c' -o -name 'jdarith.c' -o -name 'jdatadst.c' -o -name 'jdatasrc.c' -o -name 'jdcoefct.c' -o -name 'jdcolor.c' -o -name 'jddctmgr.c' -o -name 'jdhuff.c' -o -name 'jdinput.c' -o -name 'jdmainct.c' -o -name 'jdmarker.c' -o -name 'jdmaster.c' -o -name 'jdmerge.c' -o -name 'jdpostct.c' -o -name 'jdsample.c' -o -name 'jdtrans.c' -o -name 'jerror.c' -o -name 'jfdctflt.c' -o -name 'jfdctfst.c' -o -name 'jfdctint.c' -o -name 'jidctflt.c' -o -name 'jidctfst.c' -o -name 'jidctint.c' -o -name 'jmemmgr.c' -o -name 'jmemnobs.c' -o -name 'jpegtran.c' -o -name 'jquant1.c' -o -name 'jquant2.c' -o -name 'jutils.c' -o -name 'rdbmp.c' -o -name 'rdcolmap.c' -o -name 'rdgif.c' -o -name 'rdppm.c' -o -name 'rdrle.c' -o -name 'rdswitch.c' -o -name 'rdtarga.c' -o -name 'transupp.c' -o -name 'wrbmp.c' -o -name 'wrgif.c' -o -name 'wrppm.c' -o -name 'wrrle.c' -o -name 'wrtarga.c')

for f in $JPEG_C_FILES; do
    echo "  CC $(basename $f)"
    $CC $CFLAGS -I$JPEG_SRC -c $f -o $JPEG_BUILD/$(basename $f .c).o
done

$AR rcs $LIBS_DIR/libjpeg.a $JPEG_BUILD/*.o
$RANLIB $LIBS_DIR/libjpeg.a
echo "libjpeg.a: $(ls -lh $LIBS_DIR/libjpeg.a)"

# ============================
# Build libwebp manually
# ============================
echo "=== Building libwebp ==="
WEBP_SRC=$PROJECT/third_party/libwebp
WEBP_BUILD=/tmp/webp-manual
rm -rf $WEBP_BUILD && mkdir -p $WEBP_BUILD && cd $WEBP_BUILD

# Compile libwebp decoder + encoder core files
WEBP_C_FILES="
$WEBP_SRC/src/dec/alpha_dec.c
$WEBP_SRC/src/dec/buffer_dec.c
$WEBP_SRC/src/dec/frame_dec.c
$WEBP_SRC/src/dec/idec_dec.c
$WEBP_SRC/src/dec/io_dec.c
$WEBP_SRC/src/dec/layer_dec.c
$WEBP_SRC/src/dec/quant_dec.c
$WEBP_SRC/src/dec/tree_dec.c
$WEBP_SRC/src/dec/vp8_dec.c
$WEBP_SRC/src/dec/vp8l_dec.c
$WEBP_SRC/src/dec/webp_dec.c
$WEBP_SRC/src/demux/anim_decode.c
$WEBP_SRC/src/demux/demux.c
$WEBP_SRC/src/dsp/alpha_processing.c
$WEBP_SRC/src/dsp/cpu.c
$WEBP_SRC/src/dsp/dec.c
$WEBP_SRC/src/dsp/dec_clip_tables.c
$WEBP_SRC/src/dsp/filters.c
$WEBP_SRC/src/dsp/rescaler.c
$WEBP_SRC/src/dsp/upsampling.c
$WEBP_SRC/src/dsp/yuv.c
$WEBP_SRC/src/enc/alpha_enc.c
$WEBP_SRC/src/enc/analysis_enc.c
$WEBP_SRC/src/enc/backward_references_cost_enc.c
$WEBP_SRC/src/enc/backward_references_enc.c
$WEBP_SRC/src/enc/config_enc.c
$WEBP_SRC/src/enc/cost_enc.c
$WEBP_SRC/src/enc/filter_enc.c
$WEBP_SRC/src/enc/frame_enc.c
$WEBP_SRC/src/enc/histogram_enc.c
$WEBP_SRC/src/enc/iterator_enc.c
$WEBP_SRC/src/enc/near_lossless_enc.c
$WEBP_SRC/src/enc/picture_enc.c
$WEBP_SRC/src/enc/picture_csp_enc.c
$WEBP_SRC/src/enc/picture_psnr_enc.c
$WEBP_SRC/src/enc/picture_rescale_enc.c
$WEBP_SRC/src/enc/picture_tools_enc.c
$WEBP_SRC/src/enc/predictor_enc.c
$WEBP_SRC/src/enc/quant_enc.c
$WEBP_SRC/src/enc/syntax_enc.c
$WEBP_SRC/src/enc/token_enc.c
$WEBP_SRC/src/enc/tree_enc.c
$WEBP_SRC/src/enc/webp_enc.c
$WEBP_SRC/src/utils/bit_reader_utils.c
$WEBP_SRC/src/utils/bit_writer_utils.c
$WEBP_SRC/src/utils/color_cache_utils.c
$WEBP_SRC/src/utils/filters_utils.c
$WEBP_SRC/src/utils/huffman_utils.c
$WEBP_SRC/src/utils/quant_levels_dec_utils.c
$WEBP_SRC/src/utils/quant_levels_utils.c
$WEBP_SRC/src/utils/random_utils.c
$WEBP_SRC/src/utils/rescaler_utils.c
$WEBP_SRC/src/utils/thread_utils.c
$WEBP_SRC/src/utils/utils.c
"

for f in $WEBP_C_FILES; do
    echo "  CC $(basename $f)"
    $CC $CFLAGS -I$WEBP_SRC -c $f -o $WEBP_BUILD/$(basename $f .c).o 2>/dev/null || true
done

# Build decoder-only library
DECODER_OBJS=$(ls $WEBP_BUILD/alpha_dec.o $WEBP_BUILD/buffer_dec.o $WEBP_BUILD/frame_dec.o $WEBP_BUILD/idec_dec.o $WEBP_BUILD/io_dec.o $WEBP_BUILD/layer_dec.o $WEBP_BUILD/quant_dec.o $WEBP_BUILD/tree_dec.o $WEBP_BUILD/vp8_dec.o $WEBP_BUILD/vp8l_dec.o $WEBP_BUILD/webp_dec.o $WEBP_BUILD/anim_decode.o $WEBP_BUILD/demux.o $WEBP_BUILD/alpha_processing.o $WEBP_BUILD/cpu.o $WEBP_BUILD/dec.o $WEBP_BUILD/dec_clip_tables.o $WEBP_BUILD/filters.o $WEBP_BUILD/rescaler.o $WEBP_BUILD/upsampling.o $WEBP_BUILD/yuv.o $WEBP_BUILD/bit_reader_utils.o $WEBP_BUILD/bit_writer_utils.o $WEBP_BUILD/color_cache_utils.o $WEBP_BUILD/filters_utils.o $WEBP_BUILD/huffman_utils.o $WEBP_BUILD/quant_levels_dec_utils.o $WEBP_BUILD/quant_levels_utils.o $WEBP_BUILD/random_utils.o $WEBP_BUILD/rescaler_utils.o $WEBP_BUILD/thread_utils.o $WEBP_BUILD/utils.o 2>/dev/null)

# Full webp lib (encoder + decoder)
ALL_OBJS=$(ls $WEBP_BUILD/*.o 2>/dev/null)

echo "  Creating libwebpdecoder.a"
$AR rcs $LIBS_DIR/libwebpdecoder.a $DECODER_OBJS 2>/dev/null
$RANLIB $LIBS_DIR/libwebpdecoder.a

echo "  Creating libwebp.a"
$AR rcs $LIBS_DIR/libwebp.a $ALL_OBJS 2>/dev/null
$RANLIB $LIBS_DIR/libwebp.a

echo "=== ALL DONE ==="
ls -lh $LIBS_DIR/

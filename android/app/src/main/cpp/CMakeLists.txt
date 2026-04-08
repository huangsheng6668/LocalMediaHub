cmake_minimum_required(VERSION 3.22.1)
project(native-image-decoder)

# Pre-built static libraries from WSL cross-compilation
# These are built separately and placed in libs/arm64-v8a/

set(LIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libs/${ANDROID_ABI})

# Import pre-built static libraries
add_library(jpeg STATIC IMPORTED)
set_target_properties(jpeg PROPERTIES
    IMPORTED_LOCATION ${LIBS_DIR}/libjpeg.a
)

add_library(webp STATIC IMPORTED)
set_target_properties(webp PROPERTIES
    IMPORTED_LOCATION ${LIBS_DIR}/libwebp.a
)

add_library(webpdecoder STATIC IMPORTED)
set_target_properties(webpdecoder PROPERTIES
    IMPORTED_LOCATION ${LIBS_DIR}/libwebpdecoder.a
)

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
    jnigraphics
)

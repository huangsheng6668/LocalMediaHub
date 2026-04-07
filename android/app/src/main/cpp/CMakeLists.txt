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

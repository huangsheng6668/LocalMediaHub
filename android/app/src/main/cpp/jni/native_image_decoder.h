#ifndef NATIVE_IMAGE_DECODER_H
#define NATIVE_IMAGE_DECODER_H

#include <jni.h>
#include <android/bitmap.h>

#ifdef __cplusplus
extern "C" {
#endif

JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeJpeg(
    JNIEnv *env, jclass clazz, jbyteArray data, jint targetWidth, jint targetHeight);

JNIEXPORT jobject JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeWebp(
    JNIEnv *env, jclass clazz, jbyteArray data, jint targetWidth, jint targetHeight);

JNIEXPORT jintArray JNICALL
Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeGetImageInfo(
    JNIEnv *env, jclass clazz, jbyteArray data);

#ifdef __cplusplus
}
#endif

#endif

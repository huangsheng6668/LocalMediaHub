//! Task 0 skeleton for `localmedia_native`.
//!
//! Every JNI entry point currently returns null / 0. Real implementations
//! land in Tasks 1-6 (natural sort, EXIF, JPEG, WebP, PNG, HEIC). The crate
//! compiles for both the host target and `aarch64-linux-android` via
//! cargo-ndk, and is wired into Gradle's `preBuild` task.

use jni::JNIEnv;
use jni::objects::JClass;

pub mod natural_sort;
pub mod exif_reader;
pub mod jpeg;
pub mod webp;
pub mod png;
pub mod bitmap;
mod jni_bridge;

// heif module will be added in Task 5 behind the `heif-native` feature.

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeByteArray(
    _env: JNIEnv,
    _class: JClass,
    _data: jni::objects::JByteArray,
    _length: jni::sys::jint,
    _target_width: jni::sys::jint,
    _target_height: jni::sys::jint,
) -> jni::sys::jobject {
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeDirect(
    _env: JNIEnv,
    _class: JClass,
    _data: jni::objects::JByteBuffer,
    _length: jni::sys::jint,
    _target_width: jni::sys::jint,
    _target_height: jni::sys::jint,
) -> jni::sys::jobject {
    std::ptr::null_mut()
}

#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeExif_nativeParseExif(
    _env: JNIEnv,
    _class: JClass,
    _data: jni::objects::JByteArray,
    _length: jni::sys::jint,
) -> jni::sys::jobject {
    std::ptr::null_mut()
}

// The `Java_com_juziss_localmediahub_native_NaturalSorter_compare` entry point
// was a null-returning stub in the Task 0 skeleton. As of Task 1 the real
// implementation lives in `jni_bridge::natural_sort_jni`, exported from there
// via `#[no_mangle] extern "system"`. The three decoder/EXIF stubs above
// remain until their owning tasks (2-6) land.

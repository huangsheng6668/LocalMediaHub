//! Android Bitmap allocation + RGBA→ARGB_8888 swizzle.
//!
//! Links against `libjnigraphics.so` (provided by the NDK; linked
//! automatically when the `jnigraphics` cargo feature is set on the `jni`
//! crate — see `Cargo.toml`). On non-Android targets these symbols don't
//! exist; the whole module is therefore gated by `cfg(target_os = "android")`
//! and the host build (`cargo test` for pure-Rust unit tests) does not
//! touch it.
//!
//! Memory layout reminder: Android Bitmap `ARGB_8888` on a little-endian
//! ARM device stores each pixel as a `u32` whose byte order in memory is
//! `B, G, R, A`. Our decoders emit tightly-packed RGBA in byte order
//! `R, G, B, A`, so we swizzle R<->B and pack alpha into the high byte on
//! write.

#[cfg(target_os = "android")]
use jni::objects::{JObject, JValue};
use jni::sys::jobject;
use jni::JNIEnv;

#[cfg(target_os = "android")]
#[repr(C)]
struct AndroidBitmapInfo {
    width: u32,
    height: u32,
    stride: u32,
    format: i32,
    flags: u32,
}

#[cfg(target_os = "android")]
#[link(name = "jnigraphics")]
extern "C" {
    fn AndroidBitmap_getInfo(
        env: *mut std::ffi::c_void,
        bitmap: jni::sys::jobject,
        info: *mut AndroidBitmapInfo,
    ) -> i32;
    fn AndroidBitmap_lockPixels(
        env: *mut std::ffi::c_void,
        bitmap: jni::sys::jobject,
        addr_ptr: *mut *mut std::ffi::c_void,
    ) -> i32;
    fn AndroidBitmap_unlockPixels(
        env: *mut std::ffi::c_void,
        bitmap: jni::sys::jobject,
    ) -> i32;
}

/// Create an Android `Bitmap` of size `width × height` with config
/// `ARGB_8888` and fill it with the supplied RGBA pixel data.
///
/// On any failure (class lookup, OOM, jnigraphics error) the function
/// returns a null `jobject` so the caller (a JNI bridge) surfaces it as a
/// `null` Bitmap to Kotlin, which then falls back to `BitmapFactory`.
#[cfg(target_os = "android")]
pub fn create_android_bitmap(
    env: &mut JNIEnv,
    width: i32,
    height: i32,
    rgba: &[u8],
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_android_bitmap_inner(env, width, height, rgba)
    }));
    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

#[cfg(target_os = "android")]
fn create_android_bitmap_inner(
    env: &mut JNIEnv,
    width: i32,
    height: i32,
    rgba: &[u8],
) -> Option<jobject> {
    use jni::objects::JClass;

    if width <= 0 || height <= 0 {
        return None;
    }

    // --- Look up Bitmap.Config.ARGB_8888 -------------------------------------
    let config_cls: JClass = env
        .find_class("android/graphics/Bitmap$Config")
        .ok()?;
    // jni-rs 0.21: `get_static_field` takes the class and (name, signature)
    // — the field-id lookup is performed internally. The returned `JValue`
    // is unwrapped to a `JObject` via `.l()`.
    let config_val = env
        .get_static_field(
            &config_cls,
            "ARGB_8888",
            "Landroid/graphics/Bitmap$Config;",
        )
        .ok()?;
    let config_obj = config_val.l().ok()?;

    // --- Call Bitmap.createBitmap(int, int, Bitmap.Config) -------------------
    let bitmap_cls: JClass = env.find_class("android/graphics/Bitmap").ok()?;
    // jni-rs 0.21: `call_static_method` takes the class, method name, method
    // signature, and args — the method-id lookup happens internally.
    let bitmap_val = env
        .call_static_method(
            &bitmap_cls,
            "createBitmap",
            "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;",
            &[
                JValue::Int(width),
                JValue::Int(height),
                JValue::Object(&config_obj),
            ],
        )
        .ok()?;
    let bitmap_obj: JObject = bitmap_val.l().ok()?;

    // --- Lock pixels, write RGBA→ARGB_8888, unlock --------------------------
    let raw_env = env.get_native_interface() as *mut std::ffi::c_void;
    let raw_bitmap = bitmap_obj.as_raw();
    let mut info = AndroidBitmapInfo {
        width: 0,
        height: 0,
        stride: 0,
        format: 0,
        flags: 0,
    };
    let rc = unsafe { AndroidBitmap_getInfo(raw_env, raw_bitmap, &mut info) };
    if rc != 0 {
        return None;
    }

    let mut pixels: *mut std::ffi::c_void = std::ptr::null_mut();
    let rc = unsafe { AndroidBitmap_lockPixels(raw_env, raw_bitmap, &mut pixels) };
    if rc != 0 {
        return None;
    }

    let stride_bytes = info.stride as usize;
    let row_bytes = (width as usize) * 4;
    let expected = (width as usize) * (height as usize) * 4;
    if rgba.len() < expected {
        unsafe { AndroidBitmap_unlockPixels(raw_env, raw_bitmap) };
        return None;
    }

    unsafe {
        let base = pixels as *mut u8;
        for y in 0..(height as usize) {
            let src_row_start = y * row_bytes;
            let dst_row = std::slice::from_raw_parts_mut(
                base.add(y * stride_bytes),
                row_bytes,
            );
            for x in 0..(width as usize) {
                let r = rgba[src_row_start + x * 4];
                let g = rgba[src_row_start + x * 4 + 1];
                let b = rgba[src_row_start + x * 4 + 2];
                let a = rgba[src_row_start + x * 4 + 3];
                // ARGB_8888 little-endian memory byte order is B, G, R, A.
                dst_row[x * 4] = b;
                dst_row[x * 4 + 1] = g;
                dst_row[x * 4 + 2] = r;
                dst_row[x * 4 + 3] = a;
            }
        }
        AndroidBitmap_unlockPixels(raw_env, raw_bitmap);
    }

    // Hand ownership of the local reference to the JVM caller.
    Some(bitmap_obj.into_raw())
}

// On non-Android targets (host unit-test builds) `create_android_bitmap` is
// unavailable — the JNI decoder entry points are also gated, so this stub
// only needs to exist to satisfy any non-JNI references (there are none).
#[cfg(not(target_os = "android"))]
#[allow(dead_code)]
pub fn create_android_bitmap(
    _env: &mut JNIEnv,
    _width: i32,
    _height: i32,
    _rgba: &[u8],
) -> jobject {
    std::ptr::null_mut()
}

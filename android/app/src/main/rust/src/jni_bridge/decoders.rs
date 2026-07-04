//! JNI entry points for `NativeImageDecoder.nativeDecodeByteArray` and
//! `NativeImageDecoder.nativeDecodeDirect`.
//!
//! Both functions are gated by `cfg(target_os = "android")` because:
//!   1. They link against `JNIEnv` and `Bitmap` symbols that only exist on
//!      Android.
//!   2. The `crate::bitmap::create_android_bitmap` helper is itself gated
//!      the same way.
//!
//! On the host (`cargo test`) the JNI bridge is absent — the pure-Rust
//! decoders in `jpeg.rs` / `webp.rs` are still exercised by unit tests.

#[cfg(target_os = "android")]
use jni::objects::{JByteArray, JByteBuffer, JClass};
#[cfg(target_os = "android")]
use jni::sys::{jint, jobject};
#[cfg(target_os = "android")]
use jni::JNIEnv;

/// Magic-byte format detection. Mirrors `NativeDecoderFactory` on the Kotlin
/// side. Returns:
///   - `1` for JPEG (SOI = FF D8 FF)
///   - `2` for WebP (RIFF....WEBP)
///   - `3` for PNG (89 50 4E 47 0D 0A 1A 0A)
///   - `0` for unknown / unsupported
#[cfg(target_os = "android")]
fn detect_format(data: &[u8]) -> u32 {
    if data.len() >= 3 && data[0] == 0xFF && data[1] == 0xD8 && data[2] == 0xFF {
        return 1;
    }
    if data.len() >= 12 && &data[0..4] == b"RIFF" && &data[8..12] == b"WEBP" {
        return 2;
    }
    if data.len() >= 8 && data[0..8] == [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A] {
        return 3;
    }
    0
}

#[cfg(target_os = "android")]
fn decode_slice(data: &[u8], tw: jint, th: jint) -> Option<(Vec<u8>, i32, i32)> {
    match detect_format(data) {
        1 => crate::jpeg::decode_scaled(data, tw, th),
        2 => crate::webp::decode_scaled(data, tw, th),
        // Task 4: PNG decode. PNG path uses fixed-size decode (no IDCT-scale
        // shortcut like JPEG), so we ignore (tw, th) here; the optional
        // aspect-fit downscale lives inside `png::decode_scaled`.
        3 => crate::png::decode_scaled(data, tw, th),
        // HEIC lands in Task 5; for now it falls through to the Kotlin
        // BitmapFactory fallback (the JNI entry returns null).
        _ => None,
    }
}

/// `NativeImageDecoder.nativeDecodeByteArray(data, length, tw, th): Bitmap?`.
///
/// Wraps the body in `catch_unwind(AssertUnwindSafe(...))` so a Rust panic
/// is converted to a `null` return rather than aborting the JVM (uncaught
/// unwinds across the FFI boundary are UB in jni-rs 0.21).
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeByteArray(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
    length: jint,
    target_width: jint,
    target_height: jint,
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // `convert_byte_array` performs a single-copy into a Rust `Vec<u8>`.
        // The `length` argument from Kotlin is the array length and is
        // redundant with the JByteArray's own size — we trust the JVM
        // allocation, not the caller-supplied `length`, to avoid any chance
        // of out-of-bounds reads.
        let _ = length;
        let bytes = env.convert_byte_array(&data).ok()?;
        let decoded = decode_slice(&bytes, target_width, target_height)?;
        let bitmap = crate::bitmap::create_android_bitmap(
            &mut env,
            decoded.1,
            decoded.2,
            &decoded.0,
        );
        // `create_android_bitmap` returns null on any failure; surface that
        // as a `None` here so the outer match hands null back to Kotlin.
        if bitmap.is_null() {
            None
        } else {
            Some(bitmap)
        }
    }));
    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

/// `NativeImageDecoder.nativeDecodeDirect(buf, length, tw, th): Bitmap?`.
///
/// `buf` is a `java.nio.ByteBuffer` allocated direct (off-heap) on the
/// Kotlin side. We read its address via `get_direct_buffer_address` and
/// treat the first `length` bytes as the image byte stream.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_juziss_localmediahub_native_NativeImageDecoder_nativeDecodeDirect(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteBuffer,
    length: jint,
    target_width: jint,
    target_height: jint,
) -> jobject {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        // jni-rs 0.21 `get_direct_buffer_address` returns
        // `Result<*mut u8, Error>`. A null pointer (no backing memory) is
        // surfaced as `Ok(null)` by the crate, so we explicit-null-check
        // it after the `?`.
        let ptr = env.get_direct_buffer_address(&data).ok()?;
        if ptr.is_null() {
            return None;
        }
        if length <= 0 {
            return None;
        }
        let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, length as usize) };
        let decoded = decode_slice(slice, target_width, target_height)?;
        let bitmap = crate::bitmap::create_android_bitmap(
            &mut env,
            decoded.1,
            decoded.2,
            &decoded.0,
        );
        if bitmap.is_null() {
            None
        } else {
            Some(bitmap)
        }
    }));
    match result {
        Ok(Some(ptr)) => ptr,
        _ => std::ptr::null_mut(),
    }
}

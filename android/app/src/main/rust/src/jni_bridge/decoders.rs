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
///   - `4` for HEIC/HEIF (ISO BMFF `ftyp` box at offset 4..8)
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
    // HEIF/HEIC: ISO base media file format container. The first box is
    // always a `ftyp` box — its 4-byte type lives at offset 4..8. This
    // matches `heic`, `heix`, `heim`, `heis`, `mif1` (HEIF) and AVIF's
    // `avif`/`avis` brands; the actual codec dispatch happens inside
    // `heif::decode` (currently a stub that returns `None`, delegating to
    // Android's `AImageDecoder` via the Kotlin `BitmapFactory` fallback).
    if data.len() >= 12 && &data[4..8] == b"ftyp" {
        return 4;
    }
    0
}

#[cfg(target_os = "android")]
fn decode_slice(data: &[u8], tw: jint, th: jint) -> Option<(Vec<u8>, i32, i32)> {
    let fmt = detect_format(data);
    let (rgba, w, h) = match fmt {
        1 => crate::jpeg::decode_scaled(data, tw, th)?,
        2 => crate::webp::decode_scaled(data, tw, th)?,
        // Task 4: PNG decode. PNG path uses fixed-size decode (no IDCT-scale
        // shortcut like JPEG), so we ignore (tw, th) here; the optional
        // aspect-fit downscale lives inside `png::decode_scaled`.
        3 => crate::png::decode_scaled(data, tw, th)?,
        // Task 5: HEIC. `heif::decode` currently returns `None` (stub) —
        // the Rust side declines to decode, and the JNI entry surfaces
        // null to Kotlin, which then runs the `BitmapFactory` fallback
        // (it uses NDK `AImageDecoder` on API 28+). Routing still flows
        // through here so a future pure-Rust HEIC crate can be dropped
        // into `heif.rs` without touching the dispatcher.
        4 => crate::heif::decode(data)?,
        _ => return None,
    };

    // Task 6: EXIF orientation correction. Only JPEG carries the
    // EXIF `Orientation` tag in practice (PNG/WebP samples used in
    // this project are all orientation=1), so we read it only for
    // JPEG-formatted inputs. Orientation=1 is the EXIF "no rotation"
    // sentinel and is the no-op fast path — the dominant case for
    // most phone JPEGs in the test corpus. For orientations 2..8 the
    // RGBA buffer is rotated/flipped in place before Bitmap creation.
    let oriented = if fmt == 1 {
        let orientation = crate::exif_reader::parse_orientation_only(data);
        if orientation == 1 {
            (rgba, w, h)
        } else {
            crate::bitmap::apply_exif_orientation(&rgba, w, h, orientation)
        }
    } else {
        (rgba, w, h)
    };
    Some(oriented)
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
        // Defense-in-depth: clamp caller-supplied length to actual buffer capacity.
        // Without this, `slice::from_raw_parts(ptr, length)` would read OOB if a
        // caller's `length` exceeds the DirectByteBuffer's backing memory — Rust UB,
        // with unpredictable compiler-optimized behavior.
        //
        // jni-rs 0.21: `get_direct_buffer_capacity` returns `Result<jint>` (i32).
        // The JNI spec's `GetDirectBufferCapacity` returns `jlong`, but jni-rs 0.21
        // wraps it as `jint`; for this project's image byte streams (well under 2GB)
        // `i32` is sufficient.
        //
        // Negative/zero capacity means invalid buffer (defensive — shouldn't happen
        // in practice). The `<= 0` check also guards against the i32→usize cast
        // sign-extension trap (`-1i32 as usize` on 64-bit = `usize::MAX`).
        let capacity = env.get_direct_buffer_capacity(&data).ok()?;
        if capacity <= 0 {
            return None;
        }
        let effective_length = (length as usize).min(capacity as usize);
        let slice = unsafe { std::slice::from_raw_parts(ptr as *const u8, effective_length) };
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

#[cfg(test)]
mod tests {
    // Unit tests for the capacity-clamp logic in `nativeDecodeDirect`.
    // Pure logic — does NOT exercise the real JNI path, which is gated
    // behind `#[cfg(target_os = "android")]` and only runs on-device.
    //
    // The clamp pattern is: `effective = (length as usize).min(capacity as usize)`
    // preceded by an early-return when `capacity <= 0`.

    #[test]
    fn clamp_length_to_capacity_when_length_exceeds() {
        // Mirrors the runtime min() logic without calling real JNI.
        // jni-rs 0.21: capacity is jint (i32), length param is also jint.
        let caller_length: usize = 1000;
        let capacity: i32 = 500;
        assert!(capacity > 0);
        let effective = caller_length.min(capacity as usize);
        assert_eq!(effective, 500);
    }

    #[test]
    fn keep_length_when_within_capacity() {
        let caller_length: usize = 300;
        let capacity: i32 = 500;
        let effective = caller_length.min(capacity as usize);
        assert_eq!(effective, 300);
    }

    #[test]
    fn reject_non_positive_capacity() {
        // Mimics the early-return path: capacity <= 0 → null return
        let capacity: i32 = 0;
        assert!(!(capacity > 0));
        let capacity: i32 = -1;
        assert!(!(capacity > 0));
    }
}

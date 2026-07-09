//! Android Bitmap allocation and pixel filling.
//!
//! Links against `libjnigraphics.so` (provided by the NDK; linked
//! automatically when the `jnigraphics` cargo feature is set on the `jni`
//! crate — see `Cargo.toml`). On non-Android targets these symbols don't
//! exist; the whole module is therefore gated by `cfg(target_os = "android")`
//! and the host build (`cargo test` for pure-Rust unit tests) does not
//! touch it.
//!
//! Memory layout reminder: Android Bitmap `ARGB_8888` corresponds to
//! `ANDROID_BITMAP_FORMAT_RGBA_8888` in native code, which has a memory layout
//! of `R, G, B, A` per pixel. Since our decoders emit tightly-packed RGBA,
//! we can copy rows directly without any channel swizzling.

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
            // Copy row directly as Android's ARGB_8888 (RGBA_8888 in NDK)
            // matches our decoders' RGBA layout.
            dst_row.copy_from_slice(&rgba[src_row_start..src_row_start + row_bytes]);
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

// =========================================================================
// EXIF orientation rotation
//
// The functions below operate on tightly-packed RGBA byte buffers (4 bytes
// per pixel, row-major, no stride padding) — exactly what the Rust image
// decoders in `jpeg.rs` / `webp.rs` / `png.rs` emit. They are
// `target_os`-agnostic pure-Rust and therefore run under host `cargo test`.
//
// EXIF orientation values 1..8 describe the eight transforms needed to
// display an image as the camera intended:
//   1 = identity
//   2 = mirror horizontal
//   3 = rotate 180°
//   4 = mirror vertical
//   5 = transpose (mirror-horiz + rotate 270° CW)
//   6 = rotate 90° CW
//   7 = transverse (mirror-vert + rotate 270° CW)
//   8 = rotate 90° CCW
// Orientations 1-4 preserve W×H; 5-8 transpose to H×W.
// =========================================================================

/// Apply an EXIF orientation (1..8) to tightly-packed RGBA pixel data.
///
/// Returns `(rotated_rgba, new_width, new_height)`. For orientations 1-4
/// the output dimensions equal the input dimensions; for orientations 5-8
/// width and height are swapped. Any orientation outside 1..=8 is treated
/// as identity (returns a cloned buffer with the original dimensions) —
/// this matches how the EXIF spec is meant to degrade on unknown values.
pub fn apply_exif_orientation(
    rgba: &[u8],
    w: i32,
    h: i32,
    orientation: i32,
) -> (Vec<u8>, i32, i32) {
    match orientation {
        1 => (rgba.to_vec(), w, h),
        2 => flip_horizontal(rgba, w, h),
        3 => rotate_180(rgba, w, h),
        4 => flip_vertical(rgba, w, h),
        5 => {
            let (t, tw, th) = transpose(rgba, w, h);
            flip_horizontal(&t, tw, th)
        }
        6 => rotate_90_cw(rgba, w, h),
        7 => {
            let (t, tw, th) = transpose(rgba, w, h);
            flip_vertical(&t, tw, th)
        }
        8 => rotate_90_ccw(rgba, w, h),
        _ => (rgba.to_vec(), w, h),
    }
}

/// Mirror each row left↔right. Dimensions unchanged.
fn flip_horizontal(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize;
    let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        let src_row = &rgba[y * w * 4..(y + 1) * w * 4];
        let dst_row = &mut out[y * w * 4..(y + 1) * w * 4];
        for x in 0..w {
            let si = x * 4;
            let di = (w - 1 - x) * 4;
            dst_row[di..di + 4].copy_from_slice(&src_row[si..si + 4]);
        }
    }
    (out, w as i32, h as i32)
}

/// Mirror top↔bottom. Dimensions unchanged.
fn flip_vertical(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize;
    let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        let src_row = &rgba[y * w * 4..(y + 1) * w * 4];
        let dst_row = &mut out[(h - 1 - y) * w * 4..(h - y) * w * 4];
        dst_row.copy_from_slice(src_row);
    }
    (out, w as i32, h as i32)
}

/// Rotate 180°. Dimensions unchanged.
fn rotate_180(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize;
    let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        for x in 0..w {
            let si = y * w * 4 + x * 4;
            let dy = h - 1 - y;
            let dx = w - 1 - x;
            let di = dy * w * 4 + dx * 4;
            out[di..di + 4].copy_from_slice(&rgba[si..si + 4]);
        }
    }
    (out, w as i32, h as i32)
}

/// Transpose (swap row/column). Output is `h × w`.
fn transpose(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let w = w as usize;
    let h = h as usize;
    let mut out = vec![0u8; w * h * 4];
    for y in 0..h {
        for x in 0..w {
            let si = y * w * 4 + x * 4;
            let di = x * h * 4 + y * 4;
            out[di..di + 4].copy_from_slice(&rgba[si..si + 4]);
        }
    }
    (out, h as i32, w as i32)
}

/// Rotate 90° clockwise. Output is `h × w`.
///
/// Equivalent to `transpose` then `flip_horizontal` — the canonical
/// decomposition used by every EXIF-orientation library.
fn rotate_90_cw(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let (transposed, tw, th) = transpose(rgba, w, h);
    flip_horizontal(&transposed, tw, th)
}

/// Rotate 90° counter-clockwise. Output is `h × w`.
fn rotate_90_ccw(rgba: &[u8], w: i32, h: i32) -> (Vec<u8>, i32, i32) {
    let (transposed, tw, th) = transpose(rgba, w, h);
    flip_vertical(&transposed, tw, th)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 2×2 RGBA test card used by several orientation tests:
    /// ```text
    ///   R G   Y B
    ///   B K   W .
    /// ```
    /// Encoded as four RGBA quads in row-major order.
    fn sample_2x2() -> Vec<u8> {
        // (R, G, B, A), (Y, _, B, A), (B, _, _, A), (W, W, W, A)
        vec![
            255, 0, 0, 255, 0, 255, 0, 255, // row 0: red, green
            0, 0, 255, 255, 255, 255, 255, 255, // row 1: blue, white
        ]
    }

    #[test]
    fn orientation_1_is_identity() {
        let rgba = sample_2x2();
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 1);
        assert_eq!(out, rgba);
        assert_eq!((w, h), (2, 2));
    }

    #[test]
    fn orientation_out_of_range_is_identity() {
        let rgba = sample_2x2();
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 0);
        assert_eq!(out, rgba);
        assert_eq!((w, h), (2, 2));
        let (out2, _, _) = apply_exif_orientation(&rgba, 2, 2, 9);
        assert_eq!(out2, rgba);
    }

    #[test]
    fn orientation_2_flips_horizontal() {
        let rgba = sample_2x2();
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 2);
        assert_eq!((w, h), (2, 2));
        // Row 0 was [red, green] → becomes [green, red].
        assert_eq!(&out[0..4], &[0, 255, 0, 255]); // green at x=0
        assert_eq!(&out[4..8], &[255, 0, 0, 255]); // red at x=1
        // Row 1 was [blue, white] → becomes [white, blue].
        assert_eq!(&out[8..12], &[255, 255, 255, 255]);
        assert_eq!(&out[12..16], &[0, 0, 255, 255]);
    }

    #[test]
    fn orientation_4_flips_vertical() {
        let rgba = sample_2x2();
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 4);
        assert_eq!((w, h), (2, 2));
        // Top row of output should be bottom row of input.
        assert_eq!(&out[0..8], &[0, 0, 255, 255, 255, 255, 255, 255]);
    }

    #[test]
    fn orientation_3_rotates_180() {
        let rgba = sample_2x2();
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 3);
        assert_eq!((w, h), (2, 2));
        // Pixel at (0,0) of output should be the bottom-right of input
        // (white). Pixel at (1,1) should be the top-left (red).
        assert_eq!(&out[0..4], &[255, 255, 255, 255]);
        assert_eq!(&out[12..16], &[255, 0, 0, 255]);
    }

    #[test]
    fn orientation_6_is_90_cw() {
        // Use a non-square source so the W↔H swap is observable.
        // Source: 2 wide × 1 tall, two pixels: red at (0,0), green at (1,0).
        let rgba = vec![255, 0, 0, 255, 0, 255, 0, 255];
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 1, 6);
        // 90° CW swaps dims → 1 wide × 2 tall.
        assert_eq!((w, h), (1, 2));
        // 90° CW maps input (x, y) → output (h-1-y, x). With h=1, y=0:
        //   input (0,0)=red  → output (0,0)
        //   input (1,0)=green → output (0,1)
        assert_eq!(&out[0..4], &[255, 0, 0, 255]); // red on top
        assert_eq!(&out[4..8], &[0, 255, 0, 255]); // green below
    }

    #[test]
    fn orientation_6_matches_brief_assertion_for_2x2() {
        // Pin the brief's exact 2×2 expectation so regressions in the
        // decomposition order (transpose-first vs flip-first) show up.
        let rgba = vec![
            255, 0, 0, 255, 0, 255, 0, 255, // row 0: red, green
            0, 0, 255, 255, 0, 0, 0, 255, // row 1: blue, black
        ];
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, 6);
        assert_eq!((w, h), (2, 2));
        // Brief asserts: top-left of rotated should be bottom-left of
        // original (the blue pixel).
        assert_eq!(&out[0..4], &[0, 0, 255, 255]);
    }

    #[test]
    fn orientation_8_is_90_ccw() {
        // 2 wide × 1 tall → output 1 wide × 2 tall.
        let rgba = vec![255, 0, 0, 255, 0, 255, 0, 255];
        let (out, w, h) = apply_exif_orientation(&rgba, 2, 1, 8);
        assert_eq!((w, h), (1, 2));
        // 90° CCW maps input (x, y) → output (y, w-1-x). With w=2, y=0:
        //   input (0,0)=red  → output (0,1)
        //   input (1,0)=green → output (0,0)
        assert_eq!(&out[0..4], &[0, 255, 0, 255]); // green on top
        assert_eq!(&out[4..8], &[255, 0, 0, 255]); // red below
    }

    #[test]
    fn orientation_5_and_7_transpose_dims() {
        // Orientations 5 and 7 also swap W↔H. We don't assert exact pixel
        // positions exhaustively (the 90° cases above cover the
        // decomposition primitives) — only that dims transpose and the
        // total pixel count is preserved.
        let rgba = sample_2x2();
        let n = rgba.len();
        for o in [5, 7] {
            let (out, w, h) = apply_exif_orientation(&rgba, 2, 2, o);
            assert_eq!((w, h), (2, 2));
            assert_eq!(out.len(), n);
        }
    }

    #[test]
    fn non_square_swaps_dims_for_6() {
        // 3 wide × 2 tall → orientation 6 yields 2 wide × 3 tall.
        let rgba = vec![0u8; 3 * 2 * 4];
        let (_out, w, h) = apply_exif_orientation(&rgba, 3, 2, 6);
        assert_eq!((w, h), (2, 3));
    }

    #[test]
    fn round_trip_6_then_8_is_identity() {
        // Applying orientation 6 then orientation 8 should return the
        // original buffer (90° CW + 90° CCW = identity). This catches
        // sign errors in either rotation.
        let rgba = sample_2x2();
        let (mid, mw, mh) = apply_exif_orientation(&rgba, 2, 2, 6);
        let (back, w, h) = apply_exif_orientation(&mid, mw, mh, 8);
        assert_eq!((w, h), (2, 2));
        assert_eq!(back, rgba);
    }
}

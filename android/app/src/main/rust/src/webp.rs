//! WebP decoder (lossy VP8 + lossless + alpha).
//!
//! Deviation from the plan: uses pure-Rust [`image_webp`] instead of
//! `libwebp` (which wraps Google's C reference decoder). Same rationale as
//! `jpeg.rs` — see `Cargo.toml`. Public surface (`dimensions`,
//! `decode_scaled`) is identical to the plan.

use std::io::Cursor;

/// Decode a WebP to RGBA pixels, optionally downscaling to fit `(tw, th)`
/// while preserving aspect ratio. Returns `(rgba_bytes, width, height)`.
pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut decoder = image_webp::WebPDecoder::new(Cursor::new(data)).ok()?;
    let (w, h) = decoder.dimensions();
    let (iw, ih, total_pixels) = crate::bitmap::validate_image_dimensions(w, h)?;

    // `image_webp`'s default memory limit is conservative; on a 1456x2054
    // WebP without alpha it returns `ImageTooLarge`. The decoded buffer
    // size is already validated above (`total_pixels <= 64 MP`), so we lift
    // the decoder limit to 256 MiB.
    decoder.set_memory_limit(256 * 1024 * 1024);

    let has_alpha = decoder.has_alpha();
    let bytes_per_pixel = if has_alpha { 4 } else { 3 };
    let mut raw = vec![0u8; total_pixels.checked_mul(bytes_per_pixel)?];
    decoder.read_image(&mut raw).ok()?;

    // Normalise to RGBA so the bitmap allocator can blindly copy it
    // to the Android Bitmap's ARGB_8888 (RGBA_8888) layout, same as the JPEG path.
    let rgba = if has_alpha {
        raw
    } else {
        let mut out = Vec::with_capacity(total_pixels.checked_mul(4)?);
        for chunk in raw.chunks_exact(3) {
            out.push(chunk[0]);
            out.push(chunk[1]);
            out.push(chunk[2]);
            out.push(0xFF);
        }
        out
    };

    if tw > 0 && th > 0 && (iw > tw || ih > th) {
        return crate::jpeg::fast_downscale_rgba(&rgba, iw, ih, tw, th);
    }
    Some((rgba, iw, ih))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_scaled_real_webp_full() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.webp"
        ))
        .expect("testdata/sample.webp missing");
        let (rgba, w, h) = decode_scaled(&data, 0, 0).expect("decode should succeed");
        assert_eq!(w, 1456);
        assert_eq!(h, 2054);
        assert_eq!(rgba.len(), (w as usize) * (h as usize) * 4);
    }

    #[test]
    fn decode_scaled_real_webp_downscaled() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.webp"
        ))
        .expect("testdata/sample.webp missing");
        let (rgba, w, h) = decode_scaled(&data, 200, 200).expect("decode should succeed");
        assert!(w <= 200 && h <= 200, "got {}x{}", w, h);
        assert!(w > 0 && h > 0);
        assert_eq!(rgba.len(), (w as usize) * (h as usize) * 4);
    }
}

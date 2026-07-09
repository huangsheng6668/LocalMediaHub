//! WebP decoder (lossy VP8 + lossless + alpha).
//!
//! Deviation from the plan: uses pure-Rust [`image_webp`] instead of
//! `libwebp` (which wraps Google's C reference decoder). Same rationale as
//! `jpeg.rs` — see `Cargo.toml`. Public surface (`dimensions`,
//! `decode_scaled`) is identical to the plan.

use std::io::Cursor;

/// Return `(width, height)` of a WebP byte buffer without a full decode.
/// `image_webp::WebPDecoder::dimensions` parses the RIFF/VP8X chunk headers
/// and is cheap.
pub fn dimensions(data: &[u8]) -> Option<(i32, i32)> {
    let decoder = image_webp::WebPDecoder::new(Cursor::new(data)).ok()?;
    let (w, h) = decoder.dimensions();
    Some((w as i32, h as i32))
}

/// Decode a WebP to RGBA pixels, optionally downscaling to fit `(tw, th)`
/// while preserving aspect ratio. Returns `(rgba_bytes, width, height)`.
pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut decoder = image_webp::WebPDecoder::new(Cursor::new(data)).ok()?;
    let (w, h) = decoder.dimensions();
    let (iw, ih) = (w as i32, h as i32);

    // `image_webp`'s default memory limit is conservative; on a 1456x2054
    // WebP without alpha it returns `ImageTooLarge`. The decoded buffer
    // size is bounded by `width * height * (3 or 4)` which is already
    // small for thumbnail-grid use, so we lift the limit to 256 MiB (a
    // deliberate ceiling that still rejects absurd inputs but accepts the
    // test fixtures and any reasonable photo).
    decoder.set_memory_limit(256 * 1024 * 1024);

    let has_alpha = decoder.has_alpha();
    let bytes_per_pixel = if has_alpha { 4 } else { 3 };
    let mut raw = vec![0u8; (w as usize) * (h as usize) * bytes_per_pixel];
    // `read_image` decodes the (single) frame of a non-animated WebP into
    // the supplied buffer. For animated WebPs the API is `read_frame`
    // (per-frame); we use `read_image` here because the thumbnail grid only
    // needs the first frame and animated thumbnails are out of scope for
    // the Round 11 rewrite.
    decoder.read_image(&mut raw).ok()?;

    // Normalise to RGBA so the bitmap allocator can blindly copy it
    // to the Android Bitmap's ARGB_8888 (RGBA_8888) layout, same as the JPEG path.
    let rgba = if has_alpha {
        raw
    } else {
        let mut out = Vec::with_capacity(raw.len() / 3 * 4);
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
    fn dimensions_invalid_data() {
        assert!(dimensions(b"not webp").is_none());
        assert!(dimensions(&[]).is_none());
    }

    #[test]
    fn dimensions_real_webp() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.webp"
        ))
        .expect("testdata/sample.webp missing");
        let (w, h) = dimensions(&data).expect("dimensions should parse");
        assert_eq!(w, 1456);
        assert_eq!(h, 2054);
    }

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

//! JPEG decoder + aspect-fit downscaler.
//!
//! Deviation from the task-3 plan (see `Cargo.toml` for the full rationale):
//! we use the pure-Rust [`jpeg_decoder`] crate instead of `turbojpeg` (which
//! wraps libjpeg-turbo via a C build). The public surface is the same as the
//! plan — `dimensions`, `pick_jpeg_scale`, `decode_scaled`, and the shared
//! `fast_downscale_rgba` helper that WebP/PNG also use.
//!
//! `jpeg_decoder` does not expose turbojpeg's IDCT `scale_num/scale_denom`
//! knob, so Phase-1 DCT downscale is folded into a single
//! full-decode-then-`fast_image_resize` pass (Phase-2 of the plan). Output
//! dimensions and pixel layout (RGBA, row-major, tightly packed) are
//! unchanged.

use jpeg_decoder::{Decoder, PixelFormat as JpegPixelFormat};

/// Decode a JPEG to RGBA pixels, optionally downscaling to fit `(tw, th)`
/// while preserving aspect ratio. Returns `(rgba_bytes, width, height)`.
///
/// When `tw <= 0 || th <= 0` or the source already fits, the decoded
/// dimensions are returned verbatim. Otherwise `fast_downscale_rgba`
/// performs a NEON-accelerated bilinear resize via `fast_image_resize`.
pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut decoder = Decoder::new(data);
    let pixels = decoder.decode().ok()?;
    let info = decoder.info()?;
    let (iw, ih) = (info.width as i32, info.height as i32);

    // `jpeg_decoder` emits RGB (3 bytes) or L8 (1 byte) for baseline JPEGs;
    // we normalise everything to RGBA so the bitmap allocator can blindly
    // copy it to the Android Bitmap's ARGB_8888 (RGBA_8888) layout.
    let rgba = match info.pixel_format {
        JpegPixelFormat::RGB24 => rgb_to_rgba(&pixels),
        JpegPixelFormat::L8 => l8_to_rgba(&pixels),
        JpegPixelFormat::L16 => l16_to_rgba(&pixels),
        JpegPixelFormat::CMYK32 => cmyk_to_rgba(&pixels),
    };

    if tw > 0 && th > 0 && (iw > tw || ih > th) {
        return fast_downscale_rgba(&rgba, iw, ih, tw, th);
    }
    Some((rgba, iw, ih))
}

/// Shared aspect-fit downscaler. Used by `jpeg::decode_scaled`,
/// `webp::decode_scaled`, and `png::decode_scaled`. Returns the resized
/// RGBA buffer plus the post-resize `(width, height)`.
pub fn fast_downscale_rgba(
    rgba: &[u8],
    w: i32,
    h: i32,
    tw: i32,
    th: i32,
) -> Option<(Vec<u8>, i32, i32)> {
    use fast_image_resize::images::Image;
    use fast_image_resize::{FilterType, PixelType, ResizeAlg, ResizeOptions, Resizer};

    if w <= 0 || h <= 0 || tw <= 0 || th <= 0 {
        return None;
    }

    // Aspect-fit: scale uniformly so both dimensions fit inside (tw, th).
    let ratio = (tw as f64 / w as f64).min(th as f64 / h as f64);
    let dst_w = ((w as f64) * ratio).round().max(1.0) as u32;
    let dst_h = ((h as f64) * ratio).round().max(1.0) as u32;

    let src = Image::from_vec_u8(w as u32, h as u32, rgba.to_vec(), PixelType::U8x4).ok()?;
    let mut dst = Image::new(dst_w, dst_h, PixelType::U8x4);
    let mut resizer = Resizer::new();
    let opts = ResizeOptions::new().resize_alg(ResizeAlg::Convolution(FilterType::Bilinear));
    resizer.resize(&src, &mut dst, Some(&opts)).ok()?;
    let out = dst.buffer().to_vec();
    Some((out, dst_w as i32, dst_h as i32))
}

// ---------------------------------------------------------------------------
// Pixel-format normalisers. Each produces tightly packed RGBA (4 bytes/px)
// matching what `bitmap::create_android_bitmap` expects.
// ---------------------------------------------------------------------------

fn rgb_to_rgba(rgb: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(rgb.len() / 3 * 4);
    for chunk in rgb.chunks_exact(3) {
        out.push(chunk[0]); // R
        out.push(chunk[1]); // G
        out.push(chunk[2]); // B
        out.push(0xFF);     // A
    }
    out
}

fn l8_to_rgba(l: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(l.len() * 4);
    for &v in l {
        out.push(v);
        out.push(v);
        out.push(v);
        out.push(0xFF);
    }
    out
}

fn l16_to_rgba(l: &[u8]) -> Vec<u8> {
    // L16 emits 2 bytes per pixel (u16, native endian). Downscale to 8-bit.
    let mut out = Vec::with_capacity(l.len() / 2 * 4);
    for chunk in l.chunks_exact(2) {
        let v = u16::from_ne_bytes([chunk[0], chunk[1]]);
        // Scale 0..65535 to 0..255 — shift right by 8 bits (≈ /256).
        let v8 = (v >> 8) as u8;
        out.push(v8);
        out.push(v8);
        out.push(v8);
        out.push(0xFF);
    }
    out
}

fn cmyk_to_rgba(cmyk: &[u8]) -> Vec<u8> {
    // Adobe-style inverted CMYK: all four channels (C, M, Y, K) are inverted
    // (255 = full ink, 0 = no ink). The previous code only inverted C/M/Y
    // and used K directly, which produced black (RGB=0) when K=0 (white paper)
    // — a real bug masked because CMYK JPEGs are rare in mobile test data.
    //
    // Standard Adobe formula: rgb = (255 - channel) * (255 - k) / 255
    //   - C=255 (full cyan) → R = 0 (no red)
    //   - K=255 (full black) → R = G = B = 0
    //   - C=M=Y=K=0 (white) → R = G = B = 255
    //
    // Verified against Adobe CMYK sample conventions; no project test fixture
    // for CMYK JPEGs (see spec §8 limitation #1).
    let mut out = Vec::with_capacity(cmyk.len() / 4 * 4);
    for chunk in cmyk.chunks_exact(4) {
        let c = chunk[0] as u32;
        let m = chunk[1] as u32;
        let y = chunk[2] as u32;
        let k = chunk[3] as u32;
        let r = ((255 - c) * (255 - k) / 255) as u8;
        let g = ((255 - m) * (255 - k) / 255) as u8;
        let b = ((255 - y) * (255 - k) / 255) as u8;
        out.push(r);
        out.push(g);
        out.push(b);
        out.push(0xFF);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decode_scaled_real_jpeg_full() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.jpg"
        ))
        .expect("testdata/sample.jpg missing");
        let (rgba, w, h) = decode_scaled(&data, 0, 0).expect("decode should succeed");
        assert_eq!(w, 1456);
        assert_eq!(h, 2054);
        assert_eq!(rgba.len(), (w as usize) * (h as usize) * 4);
    }

    #[test]
    fn decode_scaled_real_jpeg_downscaled() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.jpg"
        ))
        .expect("testdata/sample.jpg missing");
        let (rgba, w, h) = decode_scaled(&data, 200, 200).expect("decode should succeed");
        // Aspect fit on 1456x2054 against 200x200 → ratio ≈ 0.0974 →
        // 141x200 (height-bound).
        assert!(w <= 200 && h <= 200, "got {}x{}", w, h);
        assert!(w > 0 && h > 0);
        assert_eq!(rgba.len(), (w as usize) * (h as usize) * 4);
    }

    #[test]
    fn rgb_to_rgba_packs_alpha() {
        let out = rgb_to_rgba(&[0x10, 0x20, 0x30]);
        assert_eq!(out, vec![0x10, 0x20, 0x30, 0xFF]);
    }
}

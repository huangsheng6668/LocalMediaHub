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

/// Return `(width, height)` of a JPEG byte buffer without a full decode.
///
/// `jpeg_decoder::Decoder::new` does not require an `io::Read`; the byte
/// slice is consumed directly and only the SOI / SOF markers are scanned.
pub fn dimensions(data: &[u8]) -> Option<(i32, i32)> {
    // `jpeg_decoder::Decoder::new` followed by `decode()` is wasteful here
    // (it runs a full IDCT) just to read the SOF dimensions. The crate does
    // not expose a "header only" mode in 0.3.x, so we parse the SOI/SOFn
    // markers ourselves — see `parse_jpeg_sof_dimensions`.
    parse_jpeg_sof_dimensions(data)
}

/// Pick the largest turbojpeg scale factor (num/den) whose resulting
/// dimensions still fit inside `(tw, th)`. The candidates list is the same
/// 1/8, 1/4, 3/8, 1/2, 5/8, 3/4, 7/8, 1/1 set turbojpeg exposes. Returns
/// `(1, 1)` (no downscale) when the source already fits or when either
/// target is non-positive.
///
/// Although the current backend (`jpeg_decoder`) ignores this scale on
/// decode (it does a full IDCT then resizes via `fast_image_resize`), the
/// function is retained for API parity with the plan, for unit-testing the
/// scale-selection logic, and for a future swap back to a DCT-scaling
/// backend.
pub fn pick_jpeg_scale(w: i32, h: i32, tw: i32, th: i32) -> (i32, i32) {
    if tw <= 0 || th <= 0 {
        return (1, 1);
    }
    let candidates: [(i32, i32); 8] = [
        (1, 8), (1, 4), (3, 8), (1, 2), (5, 8), (3, 4), (7, 8), (1, 1),
    ];
    for &(num, den) in candidates.iter().rev() {
        let sw = w * num / den;
        let sh = h * num / den;
        if sw <= tw && sh <= th {
            return (num, den);
        }
    }
    (1, 1) // fallback — full scale
}

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

/// Minimal SOF0/SOF1/SOF2 marker parser for cheap `dimensions()` — avoids
/// driving a full IDCT just to learn the image size. Scans marker segments
/// for SOFn (0xFFC0..=0xFFCF, excluding 0xFFC4 DHT and 0xFFC8 JIT) and reads
/// the 16-bit height/width fields that immediately follow the segment-length
/// and precision bytes.
fn parse_jpeg_sof_dimensions(data: &[u8]) -> Option<(i32, i32)> {
    if data.len() < 4 || data[0] != 0xFF || data[1] != 0xD8 {
        return None;
    }
    let mut i = 2usize;
    while i + 1 < data.len() {
        if data[i] != 0xFF {
            // Skip padding 0xFF bytes between markers.
            i += 1;
            continue;
        }
        // Skip fill bytes.
        let mut j = i + 1;
        while j < data.len() && data[j] == 0xFF {
            j += 1;
        }
        if j >= data.len() {
            return None;
        }
        let marker = data[j];
        i = j + 1;
        // SOFn markers (baseline + extended + progressive): 0xFFC0..0xFFCF,
        // excluding 0xFFC4 (DHT), 0xFFC8 (JIT), 0xFFCC (DAC).
        let is_sof = matches!(marker, 0xC0..=0xCF if marker != 0xC4 && marker != 0xC8 && marker != 0xCC);
        // Standalone markers with no length payload.
        let standalone = matches!(marker, 0xD0..=0xD9 | 0x01);
        if standalone {
            continue;
        }
        if i + 2 > data.len() {
            return None;
        }
        let seg_len = u16::from_be_bytes([data[i], data[i + 1]]) as usize;
        let seg_start = i + 2;
        if is_sof {
            // SOF layout after the 2-byte length: precision (1), height (2 BE),
            // width (2 BE), ...
            if seg_start + 5 > data.len() {
                return None;
            }
            let h = u16::from_be_bytes([data[seg_start + 1], data[seg_start + 2]]) as i32;
            let w = u16::from_be_bytes([data[seg_start + 3], data[seg_start + 4]]) as i32;
            return Some((w, h));
        }
        // Advance past the segment (seg_len includes the 2 length bytes).
        i = seg_start + seg_len.saturating_sub(2);
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pick_jpeg_scale_exact_match() {
        // 4000x3000 → target 500x500: 1/8 = 500x375 fits both dimensions.
        let (n, d) = pick_jpeg_scale(4000, 3000, 500, 500);
        assert_eq!((n, d), (1, 8));
    }

    #[test]
    fn pick_jpeg_scale_no_scale_needed() {
        // 400x300 → target 800x800 — already fits.
        let (n, d) = pick_jpeg_scale(400, 300, 800, 800);
        assert_eq!((n, d), (1, 1));
    }

    #[test]
    fn pick_jpeg_scale_target_zero() {
        let (n, d) = pick_jpeg_scale(4000, 3000, 0, 0);
        assert_eq!((n, d), (1, 1));
    }

    #[test]
    fn pick_jpeg_scale_picks_largest_fit() {
        // 4000x3000 → 1000x1000: 1/4 = 1000x750 fits, 1/2 = 2000x1500 doesn't.
        let (n, d) = pick_jpeg_scale(4000, 3000, 1000, 1000);
        assert_eq!((n, d), (1, 4));
    }

    #[test]
    fn dimensions_invalid_data() {
        assert!(dimensions(b"not a jpeg").is_none());
        assert!(dimensions(&[]).is_none());
    }

    #[test]
    fn dimensions_real_jpeg() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.jpg"
        ))
        .expect("testdata/sample.jpg missing");
        let (w, h) = dimensions(&data).expect("dimensions should parse");
        assert_eq!(w, 1456);
        assert_eq!(h, 2054);
    }

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

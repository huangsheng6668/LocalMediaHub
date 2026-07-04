//! PNG decoder (RGB / RGBA / Grayscale / GrayscaleAlpha, 8-bit).
//!
//! Uses the pure-Rust [`png`] crate (image-rs/png). No C dependencies, so
//! it cross-compiles cleanly to `aarch64-linux-android` with the same
//! toolchain as the rest of the crate.
//!
//! All colour types are expanded to RGBA so the downstream Android Bitmap
//! allocator can swizzle R<->B for `ARGB_8888` exactly like the JPEG/WebP
//! paths. Indexed (palette) PNGs are normalised to RGBA by the `png` crate
//! itself — by the time we see `next_frame`'s output it is one of
//! `Rgb`/`Rgba`/`Grayscale`/`GrayscaleAlpha` (8 or 16 bit). 16-bit samples
//! are truncated to 8 bit via the `set_format` call below (the thumbnail
//! grid use case does not benefit from the extra precision and truncating
//! keeps the pixel-expansion loop branch-free).

use png::{ColorType, Decoder};

/// Decode a PNG byte buffer to RGBA pixels with no resizing. Returns
/// `(rgba_bytes, width, height)` or `None` on any decode error / unsupported
/// colour type.
pub fn decode(data: &[u8]) -> Option<(Vec<u8>, i32, i32)> {
    decode_scaled(data, 0, 0)
}

/// Decode a PNG to RGBA pixels, optionally downscaling to fit `(tw, th)`
/// while preserving aspect ratio. Returns `(rgba_bytes, width, height)`.
///
/// Mirrors the public surface of `jpeg::decode_scaled` / `webp::decode_scaled`
/// so the JNI dispatcher in `jni_bridge::decoders` can treat all three
/// formats identically.
pub fn decode_scaled(data: &[u8], tw: i32, th: i32) -> Option<(Vec<u8>, i32, i32)> {
    let mut decoder = Decoder::new(data);
    // Force 8-bit output even for 16-bit-per-channel PNGs; the thumbnail
    // grid does not need the extra precision and this keeps the expansion
    // loop below trivially branch-free.
    decoder.set_transformations(png::Transformations::STRIP_16);

    let mut reader = decoder.read_info().ok()?;
    let (iw, ih) = {
        let info = reader.info();
        (info.width as i32, info.height as i32)
    };

    // Allocate the exact buffer the decoder wants to write into. For 8-bit
    // sources this is `width * height * channels`; with STRIP_16 it is the
    // same even for 16-bit sources.
    let mut buf = vec![0u8; reader.output_buffer_size()];
    let frame_info = reader.next_frame(&mut buf).ok()?;

    let n = (iw as usize) * (ih as usize);
    let rgba = match frame_info.color_type {
        ColorType::Rgba => buf,
        ColorType::Rgb => {
            let mut out = Vec::with_capacity(n * 4);
            for i in 0..n {
                let s = i * 3;
                out.push(buf[s]);
                out.push(buf[s + 1]);
                out.push(buf[s + 2]);
                out.push(255);
            }
            out
        }
        ColorType::Grayscale => {
            let mut out = Vec::with_capacity(n * 4);
            for i in 0..n {
                let g = buf[i];
                out.push(g);
                out.push(g);
                out.push(g);
                out.push(255);
            }
            out
        }
        ColorType::GrayscaleAlpha => {
            let mut out = Vec::with_capacity(n * 4);
            for i in 0..n {
                let g = buf[i * 2];
                let a = buf[i * 2 + 1];
                out.push(g);
                out.push(g);
                out.push(g);
                out.push(a);
            }
            out
        }
        // Indexed PNGs are auto-expanded to Rgb/Rgba by the `png` crate
        // before reaching us, so we should never see ColorType::Indexed
        // here. Any other colour type is treated as unsupported.
        _ => return None,
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
    fn decode_invalid_data() {
        assert!(decode(b"not png").is_none());
        assert!(decode(&[]).is_none());
    }

    #[test]
    fn decode_real_png_rgb() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.png"
        ))
        .expect("testdata/sample.png missing");
        let (rgba, w, h) = decode(&data).expect("decode should succeed");
        assert_eq!(w, 1456);
        assert_eq!(h, 2054);
        assert_eq!(rgba.len(), (w as usize) * (h as usize) * 4);
    }

    #[test]
    fn decode_scaled_real_png_downscaled() {
        let data = std::fs::read(concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/testdata/sample.png"
        ))
        .expect("testdata/sample.png missing");
        let (rgba, w, h) =
            decode_scaled(&data, 200, 200).expect("decode_scaled should succeed");
        assert!(w <= 200 && h <= 200, "got {}x{}", w, h);
        assert!(w > 0 && h > 0);
        assert_eq!(rgba.len(), (w as usize) * (h as usize) * 4);
    }
}

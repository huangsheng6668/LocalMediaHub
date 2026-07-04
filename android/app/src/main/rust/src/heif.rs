//! HEIC/HEIF decoding.
//!
//! Native Rust HEIC decoding would require `libheif-rs` (C-binding) plus a
//! `libde265` HEVC backend, adding roughly 2.8 MB to the `.so` and requiring
//! cmake + NDK clang on the build host — the same toolchain complexity Task 3
//! deliberately avoided when it picked `jpeg-decoder` / `image-webp` over the
//! C-binding `turbojpeg` / `libwebp` crates. It would also push the `.so`
//! well past the 5 MB budget (current size ~1.1 MB; +2.8 MB ≈ 3.9 MB before
//! counting transitive deps).
//!
//! Instead, HEIC decoding is delegated to Android's native `AImageDecoder`
//! (available API 28+, which covers the overwhelming majority of devices on
//! our `minSdk = 26` floor — API 26/27 devices fall back to the JVM
//! `BitmapFactory` path which itself only decodes HEIC on encoder-equipped
//! devices, and otherwise surfaces `null`, which is the documented contract).
//! The fallback is wired in `NativeImageDecoder.kt::decode()`: when Rust
//! returns `None`, the Kotlin caller invokes `BitmapFactory`.
//!
//! This function intentionally returns `None` to trigger the Kotlin fallback
//! path. Routing still goes through `decoders.rs::decode_slice` (format=4 →
//! `crate::heif::decode(data)`) so a future pure-Rust HEIC crate — or a
//! `heif-native` feature gated on `libheif-rs` — can be dropped in here
//! without touching the dispatcher.
//!
//! See `task-5-report.md` in `.superpowers/sdd/` for the full rationale and
//! size/toolchain analysis.
pub fn decode(_data: &[u8]) -> Option<(Vec<u8>, i32, i32)> {
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The stub must return `None` for any input (including a valid HEIC
    /// ftyp box header) so the Kotlin fallback path is exercised. This pins
    /// the contract that `decoders.rs::decode_slice` relies on when it
    /// dispatches format=4 here.
    #[test]
    fn heic_stub_returns_none_for_empty_slice() {
        assert!(decode(&[]).is_none());
    }

    #[test]
    fn heic_stub_returns_none_for_ftyp_box() {
        // ISO BMFF `ftyp` box for HEIC: brand "heic" at offset 8.
        // Box size at offset 0..4 (here 0x00 0x00 0x00 0x18 = 24 bytes),
        // "ftyp" at offset 4..8, major brand "heic" at 8..12.
        let heic_header = [
            0x00, 0x00, 0x00, 0x18, b'f', b't', b'y', b'p', b'h', b'e', b'i', b'c',
        ];
        assert!(decode(&heic_header).is_none());
    }
}

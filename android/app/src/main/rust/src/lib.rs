//! `localmedia_native` — Round 11 native Rust rewrite of the C++ JPEG/WebP
//! decoder, EXIF parser, and natural-sort comparator.
//!
//! As of Task 3 the real implementations of
//! `NativeImageDecoder.nativeDecodeByteArray` /
//! `nativeDecodeDirect` live in [`jni_bridge::decoders`]; the null-return
//! stubs that lived here during Tasks 0–2 have been removed.
//!
//! Every JNI entry point in this crate wraps its body in
//! `catch_unwind(AssertUnwindSafe(...))` so a Rust panic is converted to a
//! `null`/`0` return rather than aborting the JVM (uncaught unwinds across
//! the FFI boundary are UB in jni-rs 0.21).

pub mod natural_sort;
pub mod exif_reader;
pub mod jpeg;
pub mod webp;
pub mod png;
pub mod bitmap;
mod jni_bridge;

// `heif` module lands in Task 5 behind the `heif-native` feature.

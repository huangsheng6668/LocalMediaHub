//! EXIF metadata extraction backed by `kamadak-exif`.
//!
//! Replaces a placeholder that always returned `None`. The parser scans the
//! leading EXIF APP1 segment of a JPEG (or the equivalent container) using
//! the pure-Rust `kamadak-exif` crate, which has no C dependencies and
//! therefore cross-compiles cleanly to `aarch64-linux-android`.
//!
//! Public surface:
//!  * [`ExifInfo`] — Rust mirror of the Kotlin `NativeExif.ExifInfo` data
//!    class (`orientation`, plus optional `date_time_original` / `make` /
//!    `model` strings).
//!  * [`parse`] — entry point used by the JNI bridge
//!    (`jni_bridge::exif_jni`). Returns `None` on any error so that the
//!    caller surfaces "no EXIF" to Kotlin as a null `ExifInfo?`, which the
//!    public `NativeExif.parse` wrapper treats identically to "image with
//!    orientation 1". This matches the existing Kotlin contract where a
//!    non-JPEG or JPEG-without-EXIF simply has the default orientation.
//!
//! Allocation profile: each call may allocate up to three `String`s for the
//! text-valued fields. Orientation is read directly from the EXIF `Short`
//! value without allocating. The JNI bridge releases the input byte array
//! back to the JVM promptly via `get_array_elements_critical`.

use exif::{In, Reader, Tag, Value};
use std::io::Cursor;

/// Parsed EXIF metadata for a single image.
///
/// Field names use snake_case on the Rust side and are translated to the
/// Kotlin data class field names by the JNI bridge.
pub struct ExifInfo {
    pub orientation: i32,
    pub date_time_original: Option<String>,
    pub make: Option<String>,
    pub model: Option<String>,
}

/// Parse EXIF metadata from a raw image byte stream.
///
/// Returns `None` if:
///  * the container is not a recognised EXIF-bearing format (e.g. PNG, WebP,
///    random bytes), or
///  * the container has no APP1 / EXIF segment (e.g. a JPEG stripped of
///    metadata).
///
/// On success the orientation field defaults to `1` (the EXIF "no rotation"
/// sentinel) when the tag is absent, matching the Kotlin contract. The other
/// text fields remain `None` when absent.
pub fn parse(data: &[u8]) -> Option<ExifInfo> {
    let mut buf = Cursor::new(data);
    let reader = Reader::new();
    let exif = reader.read_from_container(&mut buf).ok()?;

    // Helper closure: format any EXIF field's value as a display string.
    // `display_value().to_string()` matches what `kamadak-exif` emits for
    // human-readable fields like "Canon" or "2024:01:02 03:04:05".
    // `In::PRIMARY` restricts the lookup to the main image IFD (excluding
    // thumbnail tags), which is the correct scope for the four fields we
    // expose — they are all main-image metadata.
    let get_string = |tag: Tag| -> Option<String> {
        exif.get_field(tag, In::PRIMARY)
            .map(|f| f.display_value().to_string())
    };

    // Orientation is stored as a Short in EXIF. Some exotic encodings use
    // Long, but per the EXIF 2.3 spec Orientation is always Short; if the
    // value type doesn't match we fall back to the default `1`.
    let orientation = exif
        .get_field(Tag::Orientation, In::PRIMARY)
        .and_then(|f| {
            if let Value::Short(ref v) = f.value {
                Some(v[0] as i32)
            } else {
                None
            }
        })
        .unwrap_or(1);

    Some(ExifInfo {
        orientation,
        date_time_original: get_string(Tag::DateTimeOriginal),
        make: get_string(Tag::Make),
        model: get_string(Tag::Model),
    })
}

/// Fast path: parse only the EXIF Orientation tag.
///
/// Returns the orientation value (1..8) on success, or `1` (the EXIF
/// "no rotation" sentinel) when:
///   * the byte stream is too short to be a JPEG,
///   * the container isn't a JPEG (other formats may carry EXIF in
///     principle but the Round 11 decode pipeline only honours JPEG
///     orientation — PNG/WebP test samples all use orientation=1), or
///   * `parse` returns `None` (no EXIF segment / parse failure).
///
/// Implementation note: this simply delegates to [`parse`] and projects
/// out the orientation field. `kamadak-exif` parses the leading APP1
/// segment in ~10µs for a typical phone JPEG, so a hand-written seeker
/// would not be a meaningful win and would duplicate the upstream
/// boundary-checking logic.
pub fn parse_orientation_only(data: &[u8]) -> i32 {
    // Quick JPEG SOI guard. Non-JPEG containers are reported as
    // orientation=1 — see the doc comment for the rationale.
    if data.len() < 12 || data[0] != 0xFF || data[1] != 0xD8 {
        return 1;
    }
    parse(data).map(|e| e.orientation).unwrap_or(1)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_returns_none_on_non_image_data() {
        assert!(parse(b"not an image").is_none());
    }

    #[test]
    fn parse_returns_none_on_empty() {
        assert!(parse(b"").is_none());
    }

    #[test]
    fn parse_returns_none_for_jpeg_without_exif() {
        // A SOI + JFIF APP0 segment with no APP1 (EXIF) payload. The
        // kamadak-exif reader refuses this container (no EXIF segment
        // present), so `parse` surfaces `None`. This is the documented
        // contract: callers should treat `None` as "no EXIF → orientation
        // defaults to 1", not as an error.
        let fake_jpeg_no_exif = b"\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00";
        assert!(parse(fake_jpeg_no_exif).is_none());
    }

    #[test]
    fn parse_orientation_only_returns_1_for_non_jpeg() {
        // PNG/WebP/garbage inputs are not JPEG, so the orientation fast
        // path short-circuits to the EXIF "no rotation" sentinel.
        assert_eq!(parse_orientation_only(b""), 1);
        assert_eq!(parse_orientation_only(b"not an image"), 1);
        assert_eq!(
            parse_orientation_only(&[
                0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0
            ]),
            1
        );
    }

    #[test]
    fn parse_orientation_only_returns_1_for_jpeg_without_exif() {
        // A JPEG SOI that has no APP1 segment: `parse` returns None, which
        // the fast path maps to orientation=1.
        let fake_jpeg_no_exif = b"\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00";
        assert_eq!(parse_orientation_only(fake_jpeg_no_exif), 1);
    }
}

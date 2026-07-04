//! Zero-allocation natural-order string comparison.
//!
//! Replaces the original Kotlin `compareNatural` in `BrowseSorter.kt` (which
//! used `Regex("\\d+|\\D+")` and allocated two `List<String>` per call). This
//! implementation performs a single byte-stream scan per input and produces
//! the same ordering as the Kotlin Regex-based version for all ASCII inputs.
//!
//! Semantics preserved (see `BrowseSorterTest.kt`):
//!  1. Case-insensitive — both inputs are lowercased before comparison.
//!  2. Maximal runs of ASCII digits on both sides are parsed as integers and
//!     compared by numeric value (so "2" < "10", "007" == "7").
//!  3. Non-digit bytes are compared byte-wise (lowercased ASCII), so the
//!     relative order matches Kotlin's `String.compareTo` on lowercased tokens.
//!  4. When one input is a prefix of the other and all comparable bytes are
//!     equal, the shorter input sorts first.
//!  5. Numeric values use saturating arithmetic so very long digit runs do
//!     not overflow or panic.
//!
//! Allocation profile: `to_lowercase` allocates one `String` per input. The
//! scan itself is heap-free. This is a deliberate trade-off — full zero-alloc
//! lowercase handling would require per-code-point lowercasing logic. Future
//! tasks can revisit if profiling shows this is hot.

/// Compare two strings with natural ordering (e.g. "file2" < "file10").
///
/// Returns a `std::cmp::Ordering`. The JNI bridge casts this to `jint` and
/// relies on the `Ordering as i32` representation (-1 / 0 / 1), which matches
/// the contract expected by Kotlin `Comparator`-style callers.
pub fn compare(a: &str, b: &str) -> std::cmp::Ordering {
    let a = a.to_lowercase();
    let b = b.to_lowercase();
    let mut ai = a.as_bytes().iter().peekable();
    let mut bi = b.as_bytes().iter().peekable();

    loop {
        match (ai.peek(), bi.peek()) {
            (Some(ac), Some(bc)) if ac.is_ascii_digit() && bc.is_ascii_digit() => {
                // Both sides start a digit run — parse maximal runs as integers
                // using saturating arithmetic so very long runs (20+ digits)
                // clamp to u64::MAX instead of panicking on overflow.
                let mut na: u64 = 0;
                let mut nb: u64 = 0;
                while let Some(c) = ai.next_if(|c| c.is_ascii_digit()) {
                    na = na.saturating_mul(10).saturating_add((c - b'0') as u64);
                }
                while let Some(c) = bi.next_if(|c| c.is_ascii_digit()) {
                    nb = nb.saturating_mul(10).saturating_add((c - b'0') as u64);
                }
                let ncmp = na.cmp(&nb);
                if ncmp != std::cmp::Ordering::Equal {
                    return ncmp;
                }
            }
            (Some(ac), Some(bc)) => {
                // At least one side is a non-digit. Compare byte-wise — this
                // matches Kotlin's `String.compareTo` for ASCII lowercased
                // tokens (digits sort before letters because '0' < 'a').
                if ac != bc {
                    return ac.cmp(bc);
                }
                ai.next();
                bi.next();
            }
            (Some(_), None) => return std::cmp::Ordering::Greater,
            (None, Some(_)) => return std::cmp::Ordering::Less,
            (None, None) => return std::cmp::Ordering::Equal,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::compare;
    use std::cmp::Ordering;

    #[test]
    fn numeric_ordering() {
        assert_eq!(compare("file2", "file10"), Ordering::Less);
        assert_eq!(compare("file10", "file2"), Ordering::Greater);
    }

    #[test]
    fn equal_numbers() {
        assert_eq!(compare("file007", "file7"), Ordering::Equal);
    }

    #[test]
    fn case_insensitive() {
        assert_eq!(compare("IMG.JPG", "img.jpg"), Ordering::Equal);
    }

    #[test]
    fn mixed_digit_alpha() {
        // '0' < 'a' in ASCII, so a digit-starting run sorts before a letter.
        // This matches the original Kotlin `ta.compareTo(tb)` behaviour where
        // "007" (digit token) is compared against "abc" (non-digit token).
        assert_eq!(compare("007_gjco", "abc"), Ordering::Less);
        assert_eq!(compare("abc", "007_gjco"), Ordering::Greater);
    }

    #[test]
    fn pure_numbers() {
        assert_eq!(compare("100", "20"), Ordering::Greater);
        assert_eq!(compare("20", "100"), Ordering::Less);
    }

    #[test]
    fn very_long_numbers_no_overflow() {
        // 20-digit numbers — saturating arithmetic, no panic.
        assert_eq!(compare("99999999999999999999", "1"), Ordering::Greater);
    }

    #[test]
    fn empty_strings() {
        assert_eq!(compare("", ""), Ordering::Equal);
        assert_eq!(compare("", "a"), Ordering::Less);
        assert_eq!(compare("a", ""), Ordering::Greater);
    }

    /// Regression: original `BrowseSorterTest.compareNatural orders numerically`.
    /// All five assertions must hold with the Rust implementation.
    #[test]
    fn regression_browse_sorter_test_orders_numerically() {
        assert!(compare("2", "10") == Ordering::Less);
        assert!(compare("img2", "img10") == Ordering::Less);
        assert!(compare("a", "b") == Ordering::Less);
        assert!(compare("x", "x") == Ordering::Equal);
        assert!(compare("10", "2") == Ordering::Greater);
    }

    /// Regression: NAME_ASC / NAME_DESC sortFiles expectations.
    #[test]
    fn regression_name_asc_sort_order() {
        let mut names = vec!["img10.jpg", "img2.jpg", "img1.jpg"];
        names.sort_by(|a, b| compare(a, b));
        assert_eq!(names, vec!["img1.jpg", "img2.jpg", "img10.jpg"]);
    }
}

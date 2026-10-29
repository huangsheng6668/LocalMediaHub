package service

import (
	"math"
	"sort"
	"strings"

	"github.com/localmediahub/server/internal/models"
)

// naturalCompare compares two strings with natural ordering. Ported from the
// Android client's Rust implementation (android/app/src/main/rust/src/natural_sort.rs)
// so server-side pagination produces the same NAME_ASC/NAME_DESC order the
// client used to compute locally:
//   - case-insensitive (Unicode lowercase, like Rust str::to_lowercase)
//   - maximal ASCII digit runs are parsed as integers with saturating u64
//     arithmetic ("2" < "10"; "007" == "7")
//   - non-digit bytes compare byte-wise
//   - a prefix sorts first; empty < non-empty
//
// This wrapper exists for callers that hold raw (mixed-case) strings — notably
// tests. Hot paths (SortMediaFiles) must NOT use it: lowering inside the
// comparator makes every one of the O(N log N) comparisons allocate. They call
// naturalCompareLowered on pre-lowered keys instead.
func naturalCompare(a, b string) int {
	return naturalCompareLowered(strings.ToLower(a), strings.ToLower(b))
}

// naturalCompareLowered is naturalCompare with the case folding already done:
// both arguments MUST be lowercase, and the function allocates nothing. It
// indexes the strings directly — the `[]byte(a)` conversions in the original
// implementation were pure overhead, since the results were only ever indexed.
func naturalCompareLowered(a, b string) int {
	i, j := 0, 0
	for i < len(a) && j < len(b) {
		ac, bc := a[i], b[j]
		if ac >= '0' && ac <= '9' && bc >= '0' && bc <= '9' {
			na := saturatingDigits(a, &i)
			nb := saturatingDigits(b, &j)
			if na != nb {
				if na < nb {
					return -1
				}
				return 1
			}
			continue
		}
		if ac != bc {
			if ac < bc {
				return -1
			}
			return 1
		}
		i++
		j++
	}
	switch {
	case i == len(a) && j == len(b):
		return 0
	case i == len(a):
		return -1
	default:
		return 1
	}
}

// saturatingDigits consumes the maximal ASCII digit run starting at *idx and
// returns its numeric value with saturating u64 arithmetic (Rust
// saturating_mul/saturating_add semantics: 20+ digit runs clamp to MaxUint64).
func saturatingDigits(s string, idx *int) uint64 {
	var n uint64
	for *idx < len(s) && s[*idx] >= '0' && s[*idx] <= '9' {
		d := uint64(s[*idx] - '0')
		if n > (math.MaxUint64-d)/10 {
			n = math.MaxUint64
		} else {
			n = n*10 + d
		}
		*idx++
	}
	return n
}

// leadingNumber returns the leading digit run of s as a float64 (the Android
// NUMERIC_ASC/NUMERIC_DESC key: "007_gjco" → 7). ok=false when s does not
// start with a digit — such entries sort last in both directions on the
// client, mirrored by SortMediaFiles.
//
// Scans bytes directly instead of ranging over runes into a strings.Builder:
// the builder version allocated twice per call, and SortMediaFiles invokes it
// once per element. A non-ASCII leading byte is >= 0x80, so the byte-level
// digit test rejects it exactly like the rune version did.
func leadingNumber(s string) (float64, bool) {
	var f float64
	i := 0
	for ; i < len(s) && s[i] >= '0' && s[i] <= '9'; i++ {
		f = f*10 + float64(s[i]-'0')
	}
	if i == 0 {
		return 0, false
	}
	return f, true
}

// sortEntry is one decorate-sort-undecorate row: the pre-lowered comparison key
// plus the file it belongs to. Carrying the struct by value (rather than an
// index into `files`) avoids allocating a second full-length slice just to
// materialise the sorted order — the copies land in the scratch slice that is
// already being allocated, so the extra pass costs no additional allocation.
type sortEntry struct {
	key  string // strings.ToLower(Name) — computed once, not per comparison
	num  float64
	hasN bool
	f    models.MediaFile
}

// SortMediaFiles sorts files for the folder-browse endpoint. Semantics mirror
// the Android client's BrowseSorter so paged results are stable and match the
// order clients previously produced locally:
//   - sort == "name": natural compare (NAME_ASC/NAME_DESC)
//   - sort == "numeric": leading-number key; entries WITHOUT a leading number
//     sort last in BOTH directions (Kotlin used MAX_VALUE for asc and
//     MIN_VALUE for desc)
//   - sort == "size" / "time": by size / modified time
//   - ties break by natural name — equivalent to Kotlin's stable sort over
//     the name-sorted readdir input, and required for deterministic paging
//
// order == "desc" reverses the primary key only; the name tie-break and the
// numeric-vs-non-numeric rule stay direction-independent (matching Kotlin's
// stable-sort-over-name-order behavior).
//
// Implementation: decorate-sort-undecorate. Computing the lowercase key inside
// the comparator made every comparison allocate two strings; with
// sort.SliceStable that is O(N log N) allocations per request (measured: 1269
// allocs / 114 KB for 500 files, ~1.1 MB extrapolated to 5000). Lowering once
// per element drops it to O(N).
func SortMediaFiles(files []models.MediaFile, sortField, order string) {
	if len(files) < 2 {
		return
	}
	asc := order != "desc"
	needNum := sortField == "numeric"

	entries := make([]sortEntry, len(files))
	for i := range files {
		entries[i].key = strings.ToLower(files[i].Name)
		entries[i].f = files[i]
		if needNum {
			entries[i].num, entries[i].hasN = leadingNumber(files[i].Name)
		}
	}

	sort.SliceStable(entries, func(i, j int) bool {
		a, b := &entries[i], &entries[j]
		switch sortField {
		case "numeric":
			switch {
			case a.hasN && b.hasN:
				if a.num == b.num {
					return naturalCompareLowered(a.key, b.key) < 0
				}
				if asc {
					return a.num < b.num
				}
				return a.num > b.num
			case a.hasN != b.hasN:
				return a.hasN // numeric entries before non-numeric in BOTH directions
			default:
				return naturalCompareLowered(a.key, b.key) < 0
			}
		case "size":
			if a.f.Size == b.f.Size {
				return naturalCompareLowered(a.key, b.key) < 0
			}
			if asc {
				return a.f.Size < b.f.Size
			}
			return a.f.Size > b.f.Size
		case "time":
			switch {
			case a.f.ModifiedTime.Equal(b.f.ModifiedTime):
				return naturalCompareLowered(a.key, b.key) < 0
			case asc:
				return a.f.ModifiedTime.Before(b.f.ModifiedTime)
			default:
				return a.f.ModifiedTime.After(b.f.ModifiedTime)
			}
		default: // "name" (and unknown values fall back to natural name)
			c := naturalCompareLowered(a.key, b.key)
			if asc {
				return c < 0
			}
			return c > 0
		}
	})

	// Materialise the sorted order back into the caller's slice.
	for i := range entries {
		files[i] = entries[i].f
	}
}

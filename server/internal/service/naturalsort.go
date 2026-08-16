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
func naturalCompare(a, b string) int {
	a = strings.ToLower(a)
	b = strings.ToLower(b)
	ab, bb := []byte(a), []byte(b)
	i, j := 0, 0
	for i < len(ab) && j < len(bb) {
		ac, bc := ab[i], bb[j]
		if ac >= '0' && ac <= '9' && bc >= '0' && bc <= '9' {
			na := saturatingDigits(ab, &i)
			nb := saturatingDigits(bb, &j)
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
	case i == len(ab) && j == len(bb):
		return 0
	case i == len(ab):
		return -1
	default:
		return 1
	}
}

// saturatingDigits consumes the maximal ASCII digit run starting at *idx and
// returns its numeric value with saturating u64 arithmetic (Rust
// saturating_mul/saturating_add semantics: 20+ digit runs clamp to MaxUint64).
func saturatingDigits(bs []byte, idx *int) uint64 {
	var n uint64
	for *idx < len(bs) && bs[*idx] >= '0' && bs[*idx] <= '9' {
		d := uint64(bs[*idx] - '0')
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
// client, mirrored by sortMediaFiles.
func leadingNumber(s string) (float64, bool) {
	var sb strings.Builder
	for _, r := range s {
		if r >= '0' && r <= '9' {
			sb.WriteRune(r)
			continue
		}
		break
	}
	if sb.Len() == 0 {
		return 0, false
	}
	var f float64
	for _, r := range sb.String() {
		f = f*10 + float64(r-'0')
	}
	return f, true
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
func SortMediaFiles(files []models.MediaFile, sortField, order string) {
	asc := order != "desc"
	sort.SliceStable(files, func(i, j int) bool {
		a, b := files[i], files[j]
		switch sortField {
		case "numeric":
			na, oka := leadingNumber(a.Name)
			nb, okb := leadingNumber(b.Name)
			switch {
			case oka && okb:
				if na == nb {
					return naturalCompare(a.Name, b.Name) < 0
				}
				if asc {
					return na < nb
				}
				return na > nb
			case oka != okb:
				return oka // numeric entries before non-numeric in BOTH directions
			default:
				return naturalCompare(a.Name, b.Name) < 0
			}
		case "size":
			if a.Size == b.Size {
				return naturalCompare(a.Name, b.Name) < 0
			}
			if asc {
				return a.Size < b.Size
			}
			return a.Size > b.Size
		case "time":
			switch {
			case a.ModifiedTime.Equal(b.ModifiedTime):
				return naturalCompare(a.Name, b.Name) < 0
			case asc:
				return a.ModifiedTime.Before(b.ModifiedTime)
			default:
				return a.ModifiedTime.After(b.ModifiedTime)
			}
		default: // "name" (and unknown values fall back to natural name)
			c := naturalCompare(a.Name, b.Name)
			if asc {
				return c < 0
			}
			return c > 0
		}
	})
}

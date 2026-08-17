package ble

import "strings"

// UUID matching for BLE device selection (Task 11 / H-1d).
//
// This file deliberately carries NO build tag: the matcher is a pure string
// function shared by both builds (default stub build and -tags bluetooth), so
// the exact-match security contract is defined — and unit tested
// (uuid_match_test.go) — exactly once.

// normalizeUUIDString strips dashes and lower-cases a UUID string so callers
// can compare representations regardless of canonical formatting
// ("FA6A3001-8B2C-..." vs "fa6a3001-8b2c-..." vs undashed 32-hex).
func normalizeUUIDString(s string) string {
	return strings.ToLower(strings.ReplaceAll(s, "-", ""))
}

// hasServiceUUIDMatch reports whether uuids contains the FULL 128-bit
// ServiceUUID, compared after normalization (dashes stripped, lower-cased).
// Anything shorter than 32 hex chars (16-bit short UUIDs like "ffff",
// truncated prefixes like "fa6a3001-8b2c") is rejected outright: the former
// matcher's 8-char-prefix and short-UUID fallbacks meant ANY nearby device
// sharing our UUID prefix was treated as a LocalMediaHub peripheral and fed
// to the Android auto-connect flow (H-1d). Exact full equality only.
func hasServiceUUIDMatch(uuids []string) bool {
	want := normalizeUUIDString(ServiceUUID)
	for _, u := range uuids {
		if len(u) >= 32 && normalizeUUIDString(u) == want { // 32 hex chars = 128-bit
			return true
		}
	}
	return false
}

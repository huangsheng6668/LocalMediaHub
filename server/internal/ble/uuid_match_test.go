package ble

import (
	"strings"
	"testing"
)

// TestUUIDMatchRequiresFullExactServiceUUID (Task 11 / H-1d) pins the exact-
// match contract of the BLE scan filter: a device only counts as a
// LocalMediaHub peripheral when its advertised service UUID list contains the
// FULL 128-bit ServiceUUID after normalization (dashes stripped, lower-cased).
//
// The former matcher accepted 8-char hex prefixes and 16-bit short forms, so
// any nearby device whose UUID merely shared the "fa6a3001" prefix — or a
// spoofed short UUID — was fed to the Android auto-connect flow. All of those
// loose forms must be rejected now. This file has no build tag: the matcher
// lives in uuid_match.go (tag-independent) so every platform build
// shares and tests the same logic.
//
// NOTE (brief reconciliation): the brief's example used the upper-cased EXACT
// UUID ("FA6A3001-...-123456789ABC") as a must-NOT-match input, but its
// Interfaces section and implementation snippet both mandate lower-case
// normalization ("归一化：去 `-`、lowercase 后比较"), under which that string
// matches by design. The must-not-match list below therefore uses a genuinely
// different full UUID (…789ABD), and the case-insensitivity of exact equality
// is pinned by its own positive assertion.
func TestUUIDMatchRequiresFullExactServiceUUID(t *testing.T) {
	if !hasServiceUUIDMatch([]string{ServiceUUID, "0000ffff-0000-1000-8000-00805f9b34fb"}) {
		t.Fatal("exact UUID must match")
	}
	// Normalization lower-cases: an upper-cased EXACT UUID still matches.
	if !hasServiceUUIDMatch([]string{strings.ToUpper(ServiceUUID)}) {
		t.Fatal("upper-cased exact UUID must match after normalization")
	}
	if hasServiceUUIDMatch([]string{"FA6A3001-8B2C-4E6F-9988-123456789ABD", "fa6a3001-8b2c"}) {
		t.Fatal("prefix/full-string-mismatch must NOT match") // 前缀 8 字符旧逻辑会误命中
	}
	if hasServiceUUIDMatch([]string{"ffff"}) {
		t.Fatal("16-bit short UUID must not match")
	}
}

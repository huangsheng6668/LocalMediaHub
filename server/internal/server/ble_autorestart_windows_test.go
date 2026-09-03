//go:build windows

package server

import (
	"strconv"
	"testing"
	"time"
)

// TestParseRestartCooldown covers the LMH_BLE_RESTART_TS parsing contract
// required by spec docs/superpowers/specs/2026-07-28-ble-auto-restart-design.md
// §6.3: absent env -> no cooldown; malformed -> no cooldown (and no panic);
// recent (<60s) -> cooldown; stale (>60s) -> no cooldown.
func TestParseRestartCooldown(t *testing.T) {
	t.Run("absent env returns false", func(t *testing.T) {
		// t.Setenv ensures the var is restored and also fails the test if the
		// var cannot be set (it never returns an error in practice).
		t.Setenv(restartTsEnv, "")
		// Explicitly unset to simulate the truly-absent case.
		// (t.Setenv with "" sets it to empty, which parseRestartCooldown
		// treats identically to absent via the v == "" guard.)
		if got := parseRestartCooldown(); got {
			t.Fatalf("absent/empty env: want false, got true")
		}
	})

	t.Run("malformed env returns false and does not panic", func(t *testing.T) {
		t.Setenv(restartTsEnv, "not-a-number")
		defer func() {
			if r := recover(); r != nil {
				t.Fatalf("parseRestartCooldown panicked on malformed input: %v", r)
			}
		}()
		if got := parseRestartCooldown(); got {
			t.Fatalf("malformed env: want false, got true")
		}
	})

	t.Run("recent timestamp returns true", func(t *testing.T) {
		// 10 seconds ago is well within the 60s cooldown window.
		recent := time.Now().Add(-10 * time.Second).Unix()
		t.Setenv(restartTsEnv, strconv.FormatInt(recent, 10))
		if got := parseRestartCooldown(); !got {
			t.Fatalf("recent env (now-10s): want true (cooling down), got false")
		}
	})

	t.Run("stale timestamp returns false", func(t *testing.T) {
		// 120 seconds ago is outside the 60s cooldown window.
		stale := time.Now().Add(-120 * time.Second).Unix()
		t.Setenv(restartTsEnv, strconv.FormatInt(stale, 10))
		if got := parseRestartCooldown(); got {
			t.Fatalf("stale env (now-120s): want false, got true")
		}
	})
}

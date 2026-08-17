//go:build windows && bluetooth

package ble

import (
	"sync"
	"testing"
	"time"
)

func newCountingRestart() (*int, func()) {
	var mu sync.Mutex
	count := 0
	return &count, func() {
		mu.Lock()
		defer mu.Unlock()
		count++
	}
}

func TestRecordConnect_RestartsAfterTwoConsecutiveFailures(t *testing.T) {
	count, restart := newCountingRestart()
	m := NewBleHealthMonitor(false, restart)
	m.RecordConnect(false)
	if *count != 0 {
		t.Fatalf("after 1 failure, restart should NOT fire, got %d", *count)
	}
	m.RecordConnect(false)
	if *count != 1 {
		t.Fatalf("after 2 consecutive failures, restart should fire once, got %d", *count)
	}
}

func TestRecordConnect_ClearsOnSuccess(t *testing.T) {
	count, restart := newCountingRestart()
	m := NewBleHealthMonitor(false, restart)
	m.RecordConnect(false)
	m.RecordConnect(true) // clears
	m.RecordConnect(false)
	if *count != 0 {
		t.Fatalf("success resets counter; single subsequent failure must not restart, got %d", *count)
	}
	m.RecordConnect(false)
	if *count != 1 {
		t.Fatalf("second consecutive failure after reset should restart, got %d", *count)
	}
}

func TestRecordConnect_DoesNotRestartWhenCoolingDown(t *testing.T) {
	count, restart := newCountingRestart()
	m := NewBleHealthMonitor(true, restart) // coolDown=true
	m.RecordConnect(false)
	m.RecordConnect(false)
	m.RecordConnect(false)
	if *count != 0 {
		t.Fatalf("cooldown must suppress restart, got %d", *count)
	}
}

func TestRecordConnect_RestartsOnlyOncePerStuckEpisode(t *testing.T) {
	count, restart := newCountingRestart()
	m := NewBleHealthMonitor(false, restart)
	m.RecordConnect(false)
	m.RecordConnect(false) // restart fires
	m.RecordConnect(false) // further failures must NOT re-trigger (already restarting)
	m.RecordConnect(false)
	if *count != 1 {
		t.Fatalf("restart should fire exactly once per stuck episode, got %d", *count)
	}
}

func newHealthMonitorForTest() *BleHealthMonitor {
	return NewBleHealthMonitor(false, func() {})
}

// TestRestartCooldownBacksOffExponentially is the Phase 9 (L-5) gate: the
// auto-restart cooldown must grow exponentially with the number of
// consecutive restarts — n=0 keeps today's base 60s, n=3 → 8min, capped at 2h
// (no duration overflow / unbounded wait for a persistently broken adapter).
func TestRestartCooldownBacksOffExponentially(t *testing.T) {
	h := newHealthMonitorForTest()
	if got := h.cooldownFor(0); got != time.Minute {
		t.Fatalf("n=0: %v", got)
	}
	if got := h.cooldownFor(3); got != 8*time.Minute {
		t.Fatalf("n=3: %v", got)
	}
	if got := h.cooldownFor(10); got != 2*time.Hour {
		t.Fatalf("n=10 cap: %v", got)
	}
}

// TestRecordConnect_BackoffDelaysRefireUntilCooldownElapses verifies the
// in-session backoff (Phase 9 L-5): after a fired restart, a follow-up stuck
// episode must wait cooldownFor(n-1) before the restart may fire again, and a
// stable run of >= stableRunInterval after the last restart resets the backoff
// chain to the base cooldown.
func TestRecordConnect_BackoffDelaysRefireUntilCooldownElapses(t *testing.T) {
	count, restart := newCountingRestart()
	m := NewBleHealthMonitor(false, restart)
	fakeNow := time.Unix(1_700_000_000, 0)
	m.now = func() time.Time { return fakeNow }

	// t0: two consecutive failures → restart #1 fires immediately.
	m.RecordConnect(false)
	m.RecordConnect(false)
	if *count != 1 {
		t.Fatalf("after 2 consecutive failures, restart should fire once, got %d", *count)
	}

	// Within the base cooldown (60s after restart #1) further failures are
	// suppressed — including past the 2-failure threshold.
	fakeNow = fakeNow.Add(30 * time.Second)
	m.RecordConnect(false)
	m.RecordConnect(false)
	if *count != 1 {
		t.Fatalf("failures inside the cooldown must not refire, got %d restarts", *count)
	}

	// Still inside the cooldown (59s < 60s).
	fakeNow = fakeNow.Add(29 * time.Second)
	m.RecordConnect(false)
	if *count != 1 {
		t.Fatalf("failure at 59s must still be suppressed, got %d restarts", *count)
	}

	// Cooldown elapsed (60s since restart #1): the next threshold failure
	// fires restart #2 (n=2 → next cooldown doubles to 2min).
	fakeNow = fakeNow.Add(1 * time.Second)
	m.RecordConnect(false)
	if *count != 2 {
		t.Fatalf("failure after the base cooldown elapsed should refire, got %d restarts", *count)
	}

	// Stable run: a success observed >= stableRunInterval (10min) after the
	// last restart resets the backoff chain.
	fakeNow = fakeNow.Add(stableRunInterval + time.Second)
	m.RecordConnect(true)
	if m.consecutiveRestarts != 0 {
		t.Fatalf("stable run must reset consecutiveRestarts, got %d", m.consecutiveRestarts)
	}

	// A fresh stuck episode after the reset fires at the base cooldown again.
	fakeNow = fakeNow.Add(time.Minute)
	m.RecordConnect(false)
	m.RecordConnect(false)
	if *count != 3 {
		t.Fatalf("fresh episode after stable reset should fire again, got %d restarts", *count)
	}
}

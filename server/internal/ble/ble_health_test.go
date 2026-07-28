//go:build windows && bluetooth

package ble

import (
	"sync"
	"testing"
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

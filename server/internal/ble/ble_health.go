//go:build windows && bluetooth

package ble

import (
	"log/slog"
	"sync"
)

// ConnectFailThreshold is the number of consecutive failed Connect rounds
// (each round = one connectLocked invocation, internally up to 3 retries)
// after which the BLE subsystem is considered stuck and the server
// self-restarts to clear residual WinRT GATT state. See spec §1.1.
const ConnectFailThreshold = 2

// BleHealthMonitor tracks consecutive Connect-round failures and, when the
// threshold is reached, fires the injected restart function exactly once per
// stuck episode. It is safe for concurrent use (connectLocked runs under the
// scanner's opMu, but the monitor guards itself regardless with its own
// mutex — belt-and-suspenders).
//
// Why a process restart: tinygo-org/bluetooth v0.15.0 on Windows cannot
// release a GATT session in-process after the peer drops (COM pointers go
// dangling; any cleanup method faults the process). A fresh process is the
// only way to reset WinRT. See spec §0/§1.
type BleHealthMonitor struct {
	mu                   sync.Mutex
	coolDown             bool
	restart              func()
	consecutiveFailures  int
	restartAlreadyFired  bool
}

// NewBleHealthMonitor returns a monitor. When coolDown is true (this process
// was started within 60s of a prior restart, see main.go) the restart never
// fires — Connect failures are tracked but only logged.
func NewBleHealthMonitor(coolDown bool, restart func()) *BleHealthMonitor {
	return &BleHealthMonitor{coolDown: coolDown, restart: restart}
}

// RecordConnect reports the outcome of one Connect round. ok=true clears the
// failure counter. ok=false increments it; reaching ConnectFailThreshold
// (while not cooling down and not already fired) triggers restart once.
func (m *BleHealthMonitor) RecordConnect(ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if ok {
		m.consecutiveFailures = 0
		return
	}
	m.consecutiveFailures++
	if m.consecutiveFailures < ConnectFailThreshold {
		return
	}
	if m.coolDown {
		slog.Error("BLE stuck but auto-restart is cooling down; manual server restart required",
			"consecutiveFailures", m.consecutiveFailures)
		return
	}
	if m.restartAlreadyFired {
		return
	}
	m.restartAlreadyFired = true
	slog.Error("BLE stuck after consecutive Connect failures; triggering self-restart",
		"consecutiveFailures", m.consecutiveFailures)
	if m.restart != nil {
		m.restart()
	}
}

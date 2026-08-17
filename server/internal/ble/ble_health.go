//go:build windows && bluetooth

package ble

import (
	"log/slog"
	"sync"
	"time"
)

// ConnectFailThreshold is the number of consecutive failed Connect rounds
// (each round = one connectLocked invocation, internally up to 3 retries)
// after which the BLE subsystem is considered stuck and the server
// self-restarts to clear residual WinRT GATT state. See spec §1.1.
const ConnectFailThreshold = 2

// stableRunInterval is how long the monitor must observe successful Connect
// rounds after the last restart before the backoff chain resets (Phase 9
// L-5): a restart that held for 10 minutes proved the issue was transient,
// so the next stuck episode starts from the base cooldown again.
const stableRunInterval = 10 * time.Minute

// BleHealthMonitor tracks consecutive Connect-round failures and, when the
// threshold is reached, fires the injected restart function. It is safe for
// concurrent use (connectLocked runs under the scanner's opMu, but the
// monitor guards itself regardless with its own mutex — belt-and-suspenders).
//
// Why a process restart: tinygo-org/bluetooth v0.15.0 on Windows cannot
// release a GATT session in-process after the peer drops (COM pointers go
// dangling; any cleanup method faults the process). A fresh process is the
// only way to reset WinRT. See spec §0/§1.
//
// Phase 9 (L-5) exponential backoff: the restart fires at most once per stuck
// episode; if the restarter returned without exiting the process (e.g. the
// detached child failed to spawn), a follow-up episode must wait
// cooldownFor(consecutiveRestarts-1) — 1min at the base, doubling per
// consecutive restart, capped at 2h — before the restart may fire again. A
// stable run of >= stableRunInterval after the last restart resets the chain.
type BleHealthMonitor struct {
	mu                  sync.Mutex
	coolDown            bool
	restart             func()
	consecutiveFailures int
	restartAlreadyFired bool
	// consecutiveRestarts counts restarts fired in the current chain (reset
	// by a stable run). lastRestartAt / lastSuccessAt timestamp the most
	// recent restart / success so the backoff can be evaluated from events
	// alone. now is a test seam (defaults to time.Now).
	consecutiveRestarts int
	lastRestartAt       time.Time
	lastSuccessAt       time.Time
	now                 func() time.Time
}

// NewBleHealthMonitor returns a monitor. When coolDown is true (this process
// was started within 60s of a prior restart, see main.go) the restart never
// fires — Connect failures are tracked but only logged.
func NewBleHealthMonitor(coolDown bool, restart func()) *BleHealthMonitor {
	return &BleHealthMonitor{coolDown: coolDown, restart: restart, now: time.Now}
}

// cooldownFor returns the auto-restart cooldown after n consecutive restarts
// (Phase 9 L-5): 1 minute at n=0 (the previous fixed cooldown, kept as the
// base), doubling per additional consecutive restart and capped at 2 hours.
// The bounded loop doubles only while below the cap so no duration overflow
// is possible for large n; the final clamp enforces the exact cap (a doubling
// step may otherwise land just above it).
func (m *BleHealthMonitor) cooldownFor(n int) time.Duration {
	const maxCooldown = 2 * time.Hour
	d := time.Minute
	for n > 0 && d < maxCooldown {
		d *= 2
		n--
	}
	if d > maxCooldown {
		d = maxCooldown
	}
	return d
}

// RecordConnect reports the outcome of one Connect round. ok=true clears the
// failure counter (and, after a stable run of >= stableRunInterval since the
// last restart, resets the backoff chain). ok=false increments it; reaching
// ConnectFailThreshold (while not cooling down and not already fired) triggers
// restart once.
func (m *BleHealthMonitor) RecordConnect(ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if ok {
		m.consecutiveFailures = 0
		m.lastSuccessAt = m.now()
		// Stable run: enough time has elapsed since the last restart with a
		// working Connect — the restart resolved the issue, so the next stuck
		// episode starts from the base cooldown again.
		if !m.lastRestartAt.IsZero() && m.now().Sub(m.lastRestartAt) >= stableRunInterval {
			if m.consecutiveRestarts != 0 || m.restartAlreadyFired {
				m.consecutiveRestarts = 0
				m.restartAlreadyFired = false
				slog.Info("BLE stable after restart; auto-restart backoff reset")
			}
		}
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
		// Phase 9 (L-5): exponential backoff between in-session restart
		// attempts. Only reachable when the restarter returned without
		// exiting the process (a successful restart exits it, so a monitor
		// observing failures past this point means the previous attempt
		// failed to spawn): wait cooldownFor(consecutiveRestarts-1) —
		// counted from the previous restart — before re-arming.
		if m.lastRestartAt.IsZero() ||
			m.now().Sub(m.lastRestartAt) < m.cooldownFor(m.consecutiveRestarts-1) {
			return
		}
	}
	m.restartAlreadyFired = true
	m.consecutiveRestarts++
	m.lastRestartAt = m.now()
	slog.Error("BLE stuck after consecutive Connect failures; triggering self-restart",
		"consecutiveFailures", m.consecutiveFailures,
		"consecutiveRestarts", m.consecutiveRestarts)
	if m.restart != nil {
		m.restart()
	}
}

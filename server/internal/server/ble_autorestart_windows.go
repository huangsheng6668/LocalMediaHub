//go:build windows && bluetooth

package server

import (
	"log/slog"
	"os"
	"strconv"
	"time"

	"github.com/localmediahub/server/internal/ble"
)

// restartTsEnv mirrors the constant in internal/ble/restart_windows.go.
// Duplicated as a string literal here to avoid exporting it from package ble
// (it is an env-var contract, not a public API).
const restartTsEnv = "LMH_BLE_RESTART_TS"

// restartCooldown mirrors internal/ble/restart_windows.go.
const restartCooldown = 60 * time.Second

// wireBleAutoRestart builds the BLE stuck-detection + self-restart machinery
// and connects it to the server's BLE scanner. Called from New once the server
// (and its BLE Central) is fully constructed.
//
// Flow:
//  1. Parse LMH_BLE_RESTART_TS (set by the dying parent just before spawning
//     this child). If present and within restartCooldown, the new process is
//     still cooling down — auto-restart stays disabled this session
//     (BleHealthMonitor still tracks failures but never fires the restart).
//  2. Build ble.NewSelfRestarter(s) — a closure that spawns the detached
//     child, drains s.Stop() for up to restartGraceLimit, then os.Exit(0).
//     *Server satisfies ble's unexported stopper interface structurally.
//  3. Build ble.NewBleHealthMonitor(coolDown, restarter) — tracks consecutive
//     Connect-round failures; at ConnectFailThreshold it calls the restarter
//     exactly once (or just logs if cooling down).
//  4. Inject the monitor as the scanner's ConnectRecorder so every
//     connectLocked round reports its outcome.
//
// No-op when the server has no BLE scanner (NewCentralScanner failed inside
// New — adapter missing or permission denied). The whole file is
// windows && bluetooth guarded; a no-op stub is compiled for other builds so
// the call site in New compiles everywhere.
func (s *Server) wireBleAutoRestart() {
	if s.bleScanner == nil {
		// BLE unavailable at startup. Nothing to monitor.
		return
	}
	coolDown := parseRestartCooldown()
	restarter := ble.NewSelfRestarter(s)
	monitor := ble.NewBleHealthMonitor(coolDown, restarter)
	s.bleScanner.SetConnectRecorder(monitor)
	slog.Info("BLE auto-restart wired",
		"coolDown", coolDown,
		"threshold", ble.ConnectFailThreshold)
}

// parseRestartCooldown reads LMH_BLE_RESTART_TS and returns true if this
// process was started within restartCooldown of a prior restart. Absent /
// unparseable / stale values all return false (no cooldown).
func parseRestartCooldown() bool {
	v := os.Getenv(restartTsEnv)
	if v == "" {
		return false
	}
	ts, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		slog.Warn("BLE auto-restart: unparseable "+restartTsEnv+" env; ignoring (no cooldown)",
			"value", v, "error", err)
		return false
	}
	age := time.Since(time.Unix(ts, 0))
	if age < restartCooldown {
		slog.Info("BLE auto-restart cooling down (restarted recently); auto-restart disabled this session",
			"restartAge", age.String(), "cooldown", restartCooldown.String())
		return true
	}
	return false
}

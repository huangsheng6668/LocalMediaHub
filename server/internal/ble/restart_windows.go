//go:build windows && bluetooth

package ble

import (
	"log/slog"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"time"
)

const (
	// restartTsEnv carries the unix timestamp of the most recent BLE-triggered
	// self-restart. The freshly-spawned child reads it to decide whether to
	// enter a 60s cooldown (spec §3). Absent on a normal (user-launched) start.
	restartTsEnv = "LMH_BLE_RESTART_TS"

	// restartCooldown is how long after a restart the new process refuses to
	// auto-restart again, to prevent loops if the peer keeps thrashing.
	restartCooldown = 60 * time.Second

	// restartGraceLimit caps how long the outgoing process waits for Server.Stop
	// to drain in-flight requests before exiting anyway.
	restartGraceLimit = 3 * time.Second

	// detachedProcess is the Windows process creation flag for a detached child
	// (CREATE_DETACHED_PROCESS) so the new server survives the parent's exit.
	detachedProcess = 0x00000008
)

// stopper is the Server seam the restarter needs (just Stop). Interface so
// tests can inject a fake.
type stopper interface {
	Stop() error
}

// buildRestartChildCommand constructs (but does NOT start) the detached child
// exec.Cmd that re-launches this server with the same args plus the
// LMH_BLE_RESTART_TS env. Pure helper — testable without exec/exit.
func buildRestartChildCommand(exePath string, args []string, ts int64) *exec.Cmd {
	cmd := exec.Command(exePath, args[1:]...)
	// Inherit current environment, then set/override the restart timestamp so
	// the child knows it was spawned by a BLE-triggered restart.
	cmd.Env = append(os.Environ(), restartTsEnv+"="+strconv.FormatInt(ts, 10))
	// Detach so the child outlives the parent.
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: detachedProcess}
	return cmd
}

// NewSelfRestarter returns a restart function suitable for injection into
// BleHealthMonitor. When called it: spawns the detached child, waits up to
// restartGraceLimit for the current server to drain, then exits the process.
//
// The stopper reference is captured by closure; the monitor calls the returned
// func at most once per stuck episode (Task 1 guards re-entry). Task 3 passes
// *server.Server, which satisfies stopper structurally (Stop() error).
func NewSelfRestarter(s stopper) func() {
	return func() {
		exe, err := os.Executable()
		if err != nil {
			slog.Error("BLE self-restart: cannot resolve executable; aborting restart", "error", err)
			return
		}
		ts := time.Now().Unix()
		cmd := buildRestartChildCommand(exe, os.Args, ts)
		if err := cmd.Start(); err != nil {
			slog.Error("BLE self-restart: failed to spawn child; aborting restart", "error", err)
			return
		}
		slog.Info("BLE self-restart: child spawned; draining current server", "child_pid", cmd.Process.Pid)
		// Best-effort graceful drain. Stop() closes the HTTP listener and
		// waits for in-flight requests; we bound it so a stuck request can't
		// hold the restart hostage.
		done := make(chan struct{})
		go func() {
			_ = s.Stop()
			close(done)
		}()
		select {
		case <-done:
		case <-time.After(restartGraceLimit):
			slog.Warn("BLE self-restart: Stop() grace limit reached; forcing exit")
		}
		os.Exit(0)
	}
}

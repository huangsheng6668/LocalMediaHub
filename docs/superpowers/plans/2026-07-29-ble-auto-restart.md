# BLE 卡死自动重启 server Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** server 检测到 BLE GATT 连接卡死（tinygo/bluetooth v0.15.0 无法在进程内释放残留 session）时，自动自我重启进程，恢复降级通道，无需用户手动重启。

**Architecture:** 在 `ble` 包新增 `bleHealthMonitor`（windows+bluetooth 构建守卫），`tinyGoCentralScanner.connectLocked` 每轮上报结果，连续 2 轮全失败且未冷却 → 调注入的 `restartFn`：DETACHED 子进程继承 `LMH_BLE_RESTART_TS` → 当前进程 graceful `Stop()`（3 秒上限）→ `os.Exit(0)`。main.go 启动时读 `LMH_BLE_RESTART_TS`，距 now < 60s 则冷却。

**Tech Stack:** Go (os/exec, syscall.SysProcAttr DETACHED_PROCESS, os.Executable), build tags `windows && bluetooth`.

## Global Constraints

- **Spec source of truth:** `docs/superpowers/specs/2026-07-28-ble-auto-restart-design.md`
- **卡死判定：** `connectLocked` 连续 2 轮 Connect 调用全部失败（每轮含内部最多 3 次重试，累计 ≤6 次 DiscoverServices/Connect 错误）。任何一轮成功清零。失败 = `connectLocked` 返回非 nil error。
- **自我重启：** `exec.Command(os.Executable(), os.Args[1:]...)`，Windows `SysProcAttr.CreationFlags = DETACHED_PROCESS (0x00000008)`；子进程 env 设 `LMH_BLE_RESTART_TS=<unix秒>`；当前进程 `Server.Stop()` 最多排空 3 秒后 `os.Exit(0)`。
- **冷却：** 60 秒。新进程启动读 `LMH_BLE_RESTART_TS`，距 now < 60s → 本会话禁用自动重启（只记日志 + Connect 返回错误）。
- **范围：** 仅 `//go:build windows && bluetooth` 生效。stub 构建不受影响。
- **零 Wi-Fi 回归：** 重启仅由 BLE 卡死触发；Wi-Fi 正常时 BLE 不会卡死。
- **不触碰 third_party patch：** 依赖 patch（自动 Disconnect 删除 + MaintainConnection 不设）已生效。

---

### Task 1: bleHealthMonitor 纯逻辑（失败计数 + 冷却 + 触发 restart）

**Files:**
- Create: `server/internal/ble/ble_health.go`
- Create: `server/internal/ble/ble_health_test.go`

**Interfaces:**
- Consumes: 无（纯逻辑，restart 通过注入的 `func()`）
- Produces: `type BleHealthMonitor struct{...}`; `func NewBleHealthMonitor(coolDown bool, restart func()) *BleHealthMonitor`; `func (m *BleHealthMonitor) RecordConnect(ok bool)`; `const ConnectFailThreshold = 2`

- [ ] **Step 1: Write failing test for threshold + cooldown + clear-on-success**

```go
// server/internal/ble/ble_health_test.go
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test -tags 'bluetooth' ./internal/ble -run TestRecordConnect`
Expected: FAIL (undefined `NewBleHealthMonitor`).

- [ ] **Step 3: Implement ble_health.go**

```go
// server/internal/ble/ble_health.go

//go:build windows && bluetooth

package ble

import "log/slog"

// ConnectFailThreshold is the number of consecutive failed Connect rounds
// (each round = one connectLocked invocation, internally up to 3 retries)
// after which the BLE subsystem is considered stuck and the server
// self-restarts to clear residual WinRT GATT state. See spec §1.1.
const ConnectFailThreshold = 2

// BleHealthMonitor tracks consecutive Connect-round failures and, when the
// threshold is reached, fires the injected restart function exactly once per
// stuck episode. It is safe for concurrent use (connectLocked runs under the
// scanner's opMu, but the monitor guards itself regardless).
//
// Why a process restart: tinygo-org/bluetooth v0.15.0 on Windows cannot
// release a GATT session in-process after the peer drops (COM pointers go
// dangling; any cleanup method faults the process). A fresh process is the
// only way to reset WinRT. See spec §0/§1.
type BleHealthMonitor struct {
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test -tags 'bluetooth' ./internal/ble -run TestRecordConnect`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit Task 1**

```bash
git add server/internal/ble/ble_health.go server/internal/ble/ble_health_test.go
git commit -m "feat(ble): add BleHealthMonitor failure-tracking logic with cooldown"
```

---

### Task 2: 自我重启执行器（DETACHED 子进程 + graceful Stop）

**Files:**
- Create: `server/internal/ble/restart_windows.go`
- Create: `server/internal/ble/restart_windows_test.go`

**Interfaces:**
- Consumes: `*github.com/localmediahub/server/internal/server.Server` (`Stop() error`); `os.Executable`, `os.Args`, `os/exec`, `syscall.SysProcAttr`
- Produces: `func NewSelfRestarter(s stopper) func()` where `type stopper interface { Stop() error }`. The returned func, when called, launches a DETACHED child with `LMH_BLE_RESTART_TS` env, calls `s.Stop()`, then `os.Exit(0)`.

- [ ] **Step 1: Write failing test that the restarter builds + invokes child + stops**

The real restarter calls `os.Exit`, so it cannot be unit-tested directly. Test the **composition** via a seam: extract the "prepare child command" step into a pure helper that does NOT exec/exit, and test that.

```go
// server/internal/ble/restart_windows_test.go
package ble

import (
	"os"
	"testing"
	"time"
)

func TestBuildRestartChildCommand_SetsEnvAndArgs(t *testing.T) {
	// Fake os.Args via the helper's signature.
	args := []string{"LocalMediaHub.exe", "--headless"}
	ts := time.Now().Unix()
	cmd := buildRestartChildCommand("/path/to/exe", args, ts)

	if cmd.Path != "/path/to/exe" {
		t.Fatalf("cmd.Path=%s want /path/to/exe", cmd.Path)
	}
	if len(cmd.Args) != len(args) {
		t.Fatalf("cmd.Args len=%d want %d (%v)", len(cmd.Args), len(args), cmd.Args)
	}
	// Env must include LMH_BLE_RESTART_TS=<ts>.
	found := false
	for _, e := range cmd.Env {
		if e == "LMH_BLE_RESTART_TS="+itoa(ts) {
			found = true
		}
	}
	if !found {
		t.Fatalf("LMH_BLE_RESTART_TS env not set on child; env=%v", cmd.Env)
	}
}

// itoa avoids importing strconv just for the test assertion readability.
func itoa(n int64) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}

var _ = os.Getenv // keep import if needed elsewhere
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test -tags 'bluetooth' ./internal/ble -run TestBuildRestartChildCommand`
Expected: FAIL (undefined `buildRestartChildCommand`).

- [ ] **Step 3: Implement restart_windows.go**

```go
// server/internal/ble/restart_windows.go

//go:build windows && bluetooth

package ble

import (
	"log/slog"
	"os"
	"os/exec"
	"strconv"
	"syscall"
	"time"

	"github.com/localmediahub/server/internal/server"
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
// The Server reference is captured by closure; the monitor calls the returned
// func at most once per stuck episode (Task 1 guards re-entry).
func NewSelfRestarter(s *server.Server) func() {
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test -tags 'bluetooth' ./internal/ble -run TestBuildRestartChildCommand`
Expected: PASS.

- [ ] **Step 5: Commit Task 2**

```bash
git add server/internal/ble/restart_windows.go server/internal/ble/restart_windows_test.go
git commit -m "feat(ble): add Windows self-restart executor (DETACHED child + graceful Stop)"
```

---

### Task 3: 冷却判定 + 接线（main.go 读 env，scanner 注入 monitor）

**Files:**
- Modify: `server/internal/ble/central_adapter.go` (inject monitor, call RecordConnect in connectLocked)
- Modify: `server/cmd/server/main.go` (parse env, build restarter, wire into scanner)
- Modify: `server/internal/ble/central.go` or wherever `NewCentralScanner`/scanner is constructed (thread the monitor through)
- Test: `server/internal/ble/central_adapter_test.go` (extend)

**Interfaces:**
- Consumes: Task 1 `NewBleHealthMonitor` + `RecordConnect`; Task 2 `NewSelfRestarter`; `server.Server`
- Produces: scanner records each Connect round outcome; main wires cooldown flag from `LMH_BLE_RESTART_TS`.

- [ ] **Step 1: Write failing test that connectLocked records outcome on the monitor**

```go
// server/internal/ble/central_adapter_test.go (append)
func TestConnectLocked_RecordsOutcomeOnMonitor(t *testing.T) {
	// Build a scanner whose adapter.Connect fails fast (no real device) so
	// connectLocked returns an error. Inject a monitor that records calls.
	stub := &stubScanner{}                      // existing helper in this test file
	m := newRecordingMonitor()                  // helper: records (ok) pairs
	s := newCentralWithMonitorForAdapterTest(stub, m)

	_ = s.connectLocked(t.Context(), "AA:BB:CC:DD:EE:FF") // fails

	if len(m.records) != 1 || m.records[0] != false {
		t.Fatalf("expected one failure recorded, got %v", m.records)
	}
}
```

The exact helpers (`stubScanner`, `newCentralWith...`) follow whatever pattern `central_adapter_test.go` already uses for its existing connectLocked tests — read that file first and match it. If a recording-monitor test seam doesn't exist, add a tiny `type recordingMonitor struct{ records []bool }` implementing `RecordConnect(bool)`.

NOTE: the monitor field on the scanner MUST be behind an interface so this test (and the stub build) can inject a fake without dragging the real `*BleHealthMonitor` + restart side effects. Define `type connectRecorder interface { RecordConnect(ok bool) }` in `central_adapter.go`; the scanner stores `recorder connectRecorder` (may be nil → no-op).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test -tags 'bluetooth' ./internal/ble -run TestConnectLocked_RecordsOutcomeOnMonitor`
Expected: FAIL (no recorder field / not called).

- [ ] **Step 3: Add recorder seam to scanner and call it in connectLocked**

In `central_adapter.go`:
- Add field to `tinyGoCentralScanner`: `recorder connectRecorder` (interface defined in this file, `windows && bluetooth` guarded).
- At the END of `connectLocked`, before each `return`, report the outcome. Cleanest: change `connectLocked` to compute `ok := err == nil` once at the end via a deferred capture:
```go
func (t *tinyGoCentralScanner) connectLocked(ctx context.Context, id string) (err error) {
    defer func() {
        if t.recorder != nil {
            t.recorder.RecordConnect(err == nil)
        }
    }()
    t.disconnectLocked()
    // ... existing body unchanged ...
}
```
- Add setter `func (t *tinyGoCentralScanner) setRecorder(r connectRecorder)` (or thread through `NewCentralScanner`).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test -tags 'bluetooth' ./internal/ble -run TestConnectLocked`
Expected: PASS.

- [ ] **Step 5: Wire cooldown + restarter in main.go**

In `cmd/server/main.go` `runHeadless`, after `server.New`:
1. Parse cooldown:
```go
coolDown := false
if v := os.Getenv("LMH_BLE_RESTART_TS"); v != "" {
    if ts, err := strconv.ParseInt(v, 10, 64); err == nil {
        if time.Since(time.Unix(ts, 0)) < 60*time.Second {
            coolDown = true
            slog.Info("BLE auto-restart cooling down (restarted recently); auto-restart disabled this session")
        }
    }
}
```
2. Build the restarter + monitor and wire into the BLE central. The exact wiring point depends on where `*ble.Central` / the scanner is constructed (look in `server.New` / `central.go`). Add a constructor param or setter on the scanner/Central to receive the `connectRecorder`. If `ble.NewSelfRestarter(s)` needs the `*Server`, it's `s` here.
3. Use the `restartTsEnv` constant name `"LMH_BLE_RESTART_TS"` (string literal is fine in main if importing the const would create a cycle; match the value exactly).

If threading the recorder through `server.New` ripples too far (it touches server construction), add a `(*Server).SetBleConnectRecorder(connectRecorder)` method (behind `windows && bluetooth` build tag, with a no-op stub for other builds) and call it from main after `New`. Choose the minimal path; note it in the report.

- [ ] **Step 6: Build full server with bluetooth tag + vet**

Run: `cd server && go build -tags bluetooth ./... && go vet -tags bluetooth ./...`
Expected: clean.

Also verify the non-bluetooth build still compiles: `cd server && go build ./...`
Expected: clean (the recorder seam is `windows && bluetooth` guarded; stub build unaffected).

- [ ] **Step 7: Commit Task 3**

```bash
git add server/internal/ble/central_adapter.go server/internal/ble/central_adapter_test.go server/cmd/server/main.go server/internal/server/*.go server/internal/ble/central.go
git commit -m "feat(ble): wire BLE stuck-detection auto-restart (cooldown + monitor + self-restart)"
```

---

## Self-Review Notes

- **Spec coverage:** §1.1 阈值(2 轮) → Task 1 `ConnectFailThreshold`. §1.2 不触发(scan 不算) → Task 1 只在 connectLocked 上报. §2 自我重启 → Task 2 NewSelfRestarter + DETACHED + graceful 3s. §2.2 排空 3s → restartGraceLimit. §3 冷却 60s → Task 3 env 解析 + Task 1 coolDown 字段. §4 仅 windows&&bluetooth → 三个文件均 `//go:build windows && bluetooth`. §5 组件 → ble_health.go/restart_windows.go/main.go 接线.
- **Type consistency:** `NewBleHealthMonitor(coolDown bool, restart func()) *BleHealthMonitor`; `RecordConnect(ok bool)`; `connectRecorder` interface; `NewSelfRestarter(s *server.Server) func()`; `buildRestartChildCommand(exePath string, args []string, ts int64) *exec.Cmd` — 一致 across tasks.
- **Known wiring ambiguity:** Task 3 Step 5 leaves the recorder-threading path (constructor param vs `SetBleConnectRecorder`) to the implementer, constrained to minimal ripple + matching existing patterns. Flagged in the brief, not a placeholder.
- **Test realism:** Task 1 纯逻辑 fake restart; Task 2 测试纯 helper `buildRestartChildCommand`（不 exec/exit）; Task 3 测试 recorder 注入. No test exec's the real restarter (would exit the test process).

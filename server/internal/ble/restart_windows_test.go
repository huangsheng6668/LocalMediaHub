//go:build windows && bluetooth

package ble

import (
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
	wantEnv := restartTsEnv + "=" + itoa(ts)
	found := false
	for _, e := range cmd.Env {
		if e == wantEnv {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("%s env not set on child; env=%v", wantEnv, cmd.Env)
	}
	// Detached process creation flag (CREATE_DETACHED_PROCESS = 0x8) must be set
	// so the child survives the parent's os.Exit.
	if cmd.SysProcAttr == nil {
		t.Fatalf("cmd.SysProcAttr is nil; expected CREATE_DETACHED_PROCESS")
	}
	if cmd.SysProcAttr.CreationFlags != detachedProcess {
		t.Fatalf("CreationFlags=0x%x want 0x%x", cmd.SysProcAttr.CreationFlags, detachedProcess)
	}
}

// itoa avoids importing strconv just for the test assertion readability and
// keeps the test free of imports beyond testing/time.
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

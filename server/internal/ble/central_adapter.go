//go:build bluetooth

// This file is compiled only with the "bluetooth" build tag, pulling in the
// real tinygo.org/x/bluetooth stack. Default builds (no tag) use
// central_adapter_stub.go instead, so the server compiles without a Bluetooth
// stack and treats BLE absence as non-fatal (zero-regression).

package ble

import (
	"context"
	"errors"
	"log"
	"log/slog"
	"strings"
	"sync"
	"time"

	"tinygo.org/x/bluetooth"
)

// tinyGoCentralScanner implements CentralScanner against tinygo-org/bluetooth.
// Construction never panics: if the adapter is missing or Enable fails,
// NewCentralScanner returns (nil, err) and callers continue without BLE.
type tinyGoCentralScanner struct {
	adapter   *bluetooth.Adapter
	device    *bluetooth.Device
	cmdChar   *bluetooth.DeviceCharacteristic
	stateChar *bluetooth.DeviceCharacteristic

	// recorder observes each Connect round's outcome (ok/fail) so the server
	// can inject a *BleHealthMonitor that triggers a self-restart after
	// ConnectFailThreshold consecutive failures (windows && bluetooth build).
	// nil = observe nothing. Set once via SetConnectRecorder and only read
	// inside connectLocked (which runs under opMu), so no extra
	// synchronization is required.
	recorder ConnectRecorder

	// opMu serializes Scan / Connect / Disconnect. tinygo-org/bluetooth v0.15.0
	// on Windows (via winrt-go + go-ole) crashes the whole process with a
	// native fault when two of these race — a concurrent Scan yields
	// "a scan is already in progress" and a Disconnect racing a dying GATT
	// session dereferences a freed COM IUnknown* inside GattSession.Close
	// (signal 0xc0000005). The auto-connect retry path in the Android client
	// can fire a second scan+connect while the first is still winding down, so
	// the Central MUST handle these operations strictly one at a time.
	opMu sync.Mutex
}

// NewCentralScanner enables the default Bluetooth adapter and returns a
// Central-role scanner. Returns (nil, err) when the adapter is missing,
// disabled, or permission-denied. Callers must treat the error as non-fatal.
func NewCentralScanner() (CentralScanner, error) {
	a := bluetooth.DefaultAdapter
	if err := a.Enable(); err != nil {
		slog.Warn("BLE adapter unavailable; BLE channel disabled", "error", err)
		return nil, err
	}
	return &tinyGoCentralScanner{adapter: a}, nil
}

// Scan discovers peripherals advertising serviceUUID for up to 3 seconds
// (or until ctx is cancelled, whichever is first). Returns the deduplicated
// list of advertising devices. Never blocks forever.
func (t *tinyGoCentralScanner) Scan(ctx context.Context, serviceUUID string) ([]Device, error) {
	t.opMu.Lock()
	defer t.opMu.Unlock()

	uuid, err := bluetooth.ParseUUID(serviceUUID)
	if err != nil {
		return nil, err
	}

	// Hard-cap the scan at 3s so a caller that forgets a deadline cannot hang
	// the scan goroutine indefinitely. Matches the plan's global scan timeout.
	scanCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	var found []Device
	var scanCallbackCount int
	errCh := make(chan error, 1)

	slog.Info("BLE Central scan starting", "serviceUUID", serviceUUID)

	// adapter.Scan blocks until StopScan; run it in a goroutine so the select
	// below can race it against scanCtx.Done().
	go func() {
		errCh <- t.adapter.Scan(func(_ *bluetooth.Adapter, d bluetooth.ScanResult) {
			scanCallbackCount++
			hasUUID := hasServiceUUIDMatch(d, uuid, serviceUUID)
			rawUUIDs := d.ServiceUUIDs()
			uuidStrs := make([]string, len(rawUUIDs))
			for i, u := range rawUUIDs {
				uuidStrs[i] = u.String()
			}
			slog.Info("BLE scan result",
				"addr", d.Address.String(),
				"name", d.LocalName(),
				"rssi", int(d.RSSI),
				"hasServiceUUID", hasUUID,
				"serviceUUIDs", uuidStrs,
			)
			if !hasUUID {
				return
			}
			// d.Address.String() works via Go method promotion:
			// Address -> MACAddress -> MAC.String().
			found = append(found, Device{
				ID:   d.Address.String(),
				Name: d.LocalName(),
				// RSSI is int16 in v0.15.0; narrow to int for the Device struct.
				RSSI: int(d.RSSI),
			})
		})
	}()

	select {
	case <-scanCtx.Done():
		// Trigger the scan callback to return. The error (if any) from
		// StopScan is ignored: the scan is ending anyway.
		_ = t.adapter.StopScan()
		// Drain any synchronous error the Scan goroutine wrote before observing
		// the stop. Non-blocking; if Scan has not returned yet its error is
		// dropped on the floor (acceptable: caller asked to stop).
		var scanErr error
		select {
		case scanErr = <-errCh:
		default:
		}
		slog.Info("BLE Central scan finished (timeout)",
			"callbacks", scanCallbackCount, "matched", len(found), "scanErr", scanErr)
		return dedup(found), nil
	case err := <-errCh:
		slog.Info("BLE Central scan finished (err)", "callbacks", scanCallbackCount, "err", err)
		return found, err
	}
}

// Connect establishes a GATT connection to id (a MAC string like
// "AA:BB:CC:DD:EE:FF") and discovers the Command (Write) and State (Notify)
// characteristics on SERVICE_UUID. On success, device + characteristic
// pointers are cached for subsequent WriteCommand / WaitNotify calls.
func (t *tinyGoCentralScanner) Connect(ctx context.Context, id string) error {
	t.opMu.Lock()
	defer t.opMu.Unlock()
	return t.connectLocked(ctx, id)
}

// SetConnectRecorder injects a ConnectRecorder (typically a *BleHealthMonitor
// built in server.New's wireBleAutoRestart) that observes each connectLocked
// round's outcome. The recorder is read only inside connectLocked, which runs
// under opMu, so the write here does not need its own lock — the caller
// invokes this exactly once, before the first Connect. nil is treated as
// "observe nothing" by the deferred call in connectLocked.
func (t *tinyGoCentralScanner) SetConnectRecorder(r ConnectRecorder) {
	t.recorder = r
}

// connectLocked is the unlocked body of Connect. Caller MUST hold t.opMu.
// Split out so Connect's own Disconnect call does not re-acquire the lock
// (sync.Mutex is non-reentrant → deadlock).
//
// The named return err is captured by the deferred recorder call so the
// outcome (ok = err == nil) is reported on EVERY exit path — early MAC-parse
// failure, retry-loop exhaustion, missing-characteristic failure, and
// success alike. Exactly one RecordConnect fires per invocation, matching the
// BleHealthMonitor "one Connect round = one record" contract (spec §1.1).
func (t *tinyGoCentralScanner) connectLocked(ctx context.Context, id string) (err error) {
	defer func() {
		if t.recorder != nil {
			t.recorder.RecordConnect(err == nil)
		}
	}()
	t.disconnectLocked()
	t.cmdChar = nil
	t.stateChar = nil

	addr, err := bluetooth.ParseMAC(id)
	if err != nil {
		log.Printf("BLE Connect invalid MAC=%s err=%v", id, err)
		return err
	}

	// Retry the adapter.Connect + DiscoverServices pair. After the remote peer
	// (Android app) is killed, the WinRT BLE stack keeps a residual GATT
	// session that makes the immediately-following DiscoverServices fail with
	// "async operation failed with status 2". Re-Enable-ing the adapter and
	// waiting a moment lets WinRT tear the stale session down, after which a
	// fresh Connect succeeds. Recover the device ref only after both steps
	// succeed so a failed attempt never leaves a dangling t.device behind.
	var device bluetooth.Device
	var svcs []bluetooth.DeviceService
	err = nil
	for attempt := 1; attempt <= 3; attempt++ {
		// Drop any residual adapter state before each attempt. Re-Enable on
		// Windows just re-runs the LE watcher setup; it does not fault.
		_ = t.adapter.Enable()
		device, err = t.adapter.Connect(
			bluetooth.Address{MACAddress: bluetooth.MACAddress{MAC: addr}},
			bluetooth.ConnectionParams{},
		)
		if err != nil {
			log.Printf("BLE adapter.Connect err (attempt %d)=%v", attempt, err)
		} else {
			svcs, err = device.DiscoverServices(nil)
			if err == nil {
				break // success
			}
			log.Printf("BLE Connect DiscoverServices err (attempt %d)=%v", attempt, err)
		}
		// Back off so WinRT can reclaim the stale session before retrying.
		if attempt < 3 {
			select {
			case <-ctx.Done():
				return ctx.Err()
			case <-time.After(time.Duration(attempt) * 2 * time.Second):
			}
		}
	}
	if err != nil {
		return err
	}
	t.device = &device

	for i := range svcs {
		uStr := svcs[i].UUID().String()
		log.Printf("BLE Connect discovered service UUID=%s", uStr)
		if matchUUIDPrefix(svcs[i].UUID(), ServiceUUID) {
			chars, err := svcs[i].DiscoverCharacteristics(nil)
			if err != nil || len(chars) == 0 {
				continue
			}
			for j := range chars {
				cStr := chars[j].UUID().String()
				log.Printf("BLE Connect discovered char UUID=%s", cStr)
				if matchUUIDPrefix(chars[j].UUID(), CommandCharUUID) {
					t.cmdChar = &chars[j]
				}
				if matchUUIDPrefix(chars[j].UUID(), StateCharUUID) {
					t.stateChar = &chars[j]
				}
			}
			if t.cmdChar != nil && t.stateChar != nil {
				break
			}
		}
	}

	if t.cmdChar == nil {
		log.Printf("BLE Connect failed: command char %s not found", CommandCharUUID)
		return errNoCommandChar
	}
	if t.stateChar == nil {
		log.Printf("BLE Connect failed: state char %s not found", StateCharUUID)
		return errNoStateChar
	}
	log.Printf("BLE Connect success for device=%s", id)
	return nil
}

// Disconnect drops the GATT connection and clears cached characteristics.
// Safe to call when already disconnected (no-op).
func (t *tinyGoCentralScanner) Disconnect() {
	t.opMu.Lock()
	defer t.opMu.Unlock()
	t.disconnectLocked()
}

// disconnectLocked is the unlocked body. Caller MUST hold t.opMu.
//
// It deliberately does NOT call any method on the prior device — not
// Disconnect, not Connected, nothing. tinygo-org/bluetooth v0.15.0 on Windows
// (via winrt-go + go-ole) leaves BOTH the Go-side BluetoothLEDevice AND
// GattSession COM pointers dangling once the remote peer (Android) tears the
// GATT link down (e.g. the app is killed). ANY method call on those objects
// (GattSession.Close, BluetoothLEDevice.GetConnectionStatus, ...) routes
// through go-ole queryInterface, dereferences the freed IUnknown*, and raises
// signal 0xc0000005 — a fatal fault the Go runtime cannot recover (recover
// only catches panics, not faults), taking the whole server down.
//
// The only safe action is to drop our Go-side reference and let the
// runtime/COM finalizers reclaim the objects. Trade-off: the WinRT-side
// session lingers briefly until GC, which can make the immediately-following
// Connect's DiscoverServices fail ("async operation failed with status 2");
// the Android auto-connect retry succeeds on a later attempt once the stale
// session clears. A brief reconnect failure is strictly better than a hard
// server crash (which also takes down Wi-Fi for all clients).
func (t *tinyGoCentralScanner) disconnectLocked() {
	t.device = nil
	t.cmdChar = nil
	t.stateChar = nil
}

// WriteCommand writes payload (already frame-encoded by the caller) to the
// Command characteristic using Write Without Response semantics, matching the
// Android Peripheral's PROPERTY_WRITE (write-no-response) configuration.
func (t *tinyGoCentralScanner) WriteCommand(_ context.Context, payload []byte) error {
	if t.cmdChar == nil {
		log.Printf("BLE WriteCommand failed: cmdChar is nil")
		return errNoCommandChar
	}
	n, err := t.cmdChar.WriteWithoutResponse(payload)
	if err != nil {
		log.Printf("BLE WriteWithoutResponse err=%v, falling back to Write...", err)
		n, err = t.cmdChar.Write(payload)
	}
	log.Printf("BLE WriteCommand wrote n=%d err=%v", n, err)
	return err
}

func (t *tinyGoCentralScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	if t.stateChar == nil {
		return nil, errNoStateChar
	}

	notifyCh := make(chan []byte, 1)
	handler := func(data []byte) {
		cp := append([]byte(nil), data...)
		log.Printf("BLE WaitNotify received callback len=%d data=%x", len(cp), cp)
		select {
		case notifyCh <- cp:
		default:
		}
	}
	if err := t.stateChar.EnableNotifications(handler); err != nil {
		log.Printf("BLE EnableNotifications err=%v", err)
		return nil, err
	}

	select {
	case data := <-notifyCh:
		return data, nil
	case <-ctx.Done():
		log.Printf("BLE WaitNotify ctx.Done err=%v", ctx.Err())
		return nil, ctx.Err()
	}
}

// mustUUID parses a UUID string at runtime; it panics on parse failure. The
// UUIDs in this package are compile-time constants (protocol.go), so a parse
// failure here indicates a typo in those constants and is a programmer error.
func mustUUID(s string) bluetooth.UUID {
	u, err := bluetooth.ParseUUID(s)
	if err != nil {
		panic(err)
	}
	return u
}

// dedup removes later duplicate scan results for the same device ID. The same
// physical device frequently emits multiple advertisement packets during a
// 3-second scan window; the caller (UI list) wants one row per device.
func dedup(devices []Device) []Device {
	seen := make(map[string]bool, len(devices))
	out := devices[:0]
	for _, d := range devices {
		if seen[d.ID] {
			continue
		}
		seen[d.ID] = true
		out = append(out, d)
	}
	return out
}

// matchUUIDPrefix returns true if u matches targetUUIDStr exactly or by 8-char hex prefix.
func matchUUIDPrefix(u bluetooth.UUID, targetUUIDStr string) bool {
	uStr := strings.ToLower(u.String())
	tStr := strings.ToLower(targetUUIDStr)
	if uStr == tStr {
		return true
	}
	if len(uStr) >= 8 && len(tStr) >= 8 && uStr[0:8] == tStr[0:8] {
		return true
	}
	return false
}

// hasServiceUUIDMatch returns true if d matches targetUUID or its short/padded representation.
// WinRT on Windows may format custom 128-bit UUIDs like "fa6a3001-8b2c-4e6f-9988-123456789abc"
// as "fa6a3001-8b2c-4e6f-0000-000000000000". We check exact match, 4-char short match,
// and 8-char hex prefix match.
func hasServiceUUIDMatch(d bluetooth.ScanResult, targetUUID bluetooth.UUID, serviceUUIDStr string) bool {
	if d.HasServiceUUID(targetUUID) {
		return true
	}
	targetLower := strings.ToLower(serviceUUIDStr)
	targetPrefix := targetLower
	if len(targetPrefix) >= 8 {
		targetPrefix = targetPrefix[0:8]
	}
	for _, u := range d.ServiceUUIDs() {
		uStr := strings.ToLower(u.String())
		if uStr == targetLower {
			return true
		}
		// 16-bit short UUID (e.g. "fc01")
		if len(uStr) == 4 && strings.HasPrefix(targetLower, "0000"+uStr) {
			return true
		}
		// Match 8-char hex prefix (e.g. "fa6a3001" or "0000fc01")
		if len(uStr) >= 8 && uStr[0:8] == targetPrefix {
			return true
		}
	}
	return false
}

var (
	errNoService     = errors.New("ble: service not found on device")
	errNoCommandChar = errors.New("ble: command characteristic not found")
	errNoStateChar   = errors.New("ble: state characteristic not found")
)

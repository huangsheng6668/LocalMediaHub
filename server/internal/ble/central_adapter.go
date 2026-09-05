// Real BLE Central adapter built on tinygo.org/x/bluetooth (vendored fork at
// third_party/bluetooth). This is compiled into the standard server build —
// there is no separate BLE build variant. Adapter absence at runtime is
// non-fatal: if the radio is missing or Enable fails, NewCentralScanner
// returns (nil, err) and the server continues over Wi-Fi/HTTP (zero-regression).

package ble

import (
	"context"
	"errors"
	"log"
	"log/slog"
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
	// ConnectFailThreshold consecutive failures (windows build).
	// nil = observe nothing. Set once via SetConnectRecorder and only read
	// inside connectLocked (which runs under opMu), so no extra
	// synchronization is required.
	recorder ConnectRecorder

	// opMu serializes Scan / Connect / Disconnect. tinygo-org/bluetooth v0.15.0
	// on Windows (via winrt-go + go-ole) crashes the whole process with a
	// native fault when two of these race — a concurrent Scan yields a
	// "a scan is already in progress" and a Disconnect racing a dying GATT
	// session dereferences a freed COM IUnknown* inside GattSession.Close
	// (signal 0xc0000005). The auto-connect retry path in the Android client
	// can fire a second scan+connect while the first is still winding down, so
	// the Central MUST handle these operations strictly one at a time.
	opMu sync.Mutex

	// notifyQueue holds every State-characteristic notification received
	// since connectLocked armed the ONE persistent subscription handler.
	// Real-device Phase 9 finding: arming per WaitNotify call dropped any
	// frame that arrived while no call was blocked (the phone answers a
	// challenge within milliseconds, and its second-direction challenge
	// lands right after the response satisfies the waiting call) — the
	// handshake then dead-waited and the data-phase chunk stream would have
	// lost frames the same way. A bounded queue bridges the gaps; frames are
	// consumed by WaitNotify with no re-arming race left.
	notifyQueue chan []byte

	// negotiatedMTU is the ATT MTU read back after connect (0 until then).
	// Chunk sizing reads it via MTU(); guarded by opMu.
	negotiatedMTU int
}

// notifyQueueCap bounds the bridging queue. The peer's chunker throttles to
// one in-flight frame per round trip, so 64 frames is generous headroom for
// a burst; overflow drops the NEWEST frame with a log line (fail-visible).
const notifyQueueCap = 64

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

	// matchedAddrs feeds the finish log: Task 11 (M-6) keeps scan logging to
	// hit count + hit device addresses only.
	matchedAddrs := func() []string {
		addrs := make([]string, 0, len(found))
		for _, d := range found {
			addrs = append(addrs, d.ID)
		}
		return addrs
	}

	// adapter.Scan blocks until StopScan; run it in a goroutine so the select
	// below can race it against scanCtx.Done().
	go func() {
		errCh <- t.adapter.Scan(func(_ *bluetooth.Adapter, d bluetooth.ScanResult) {
			scanCallbackCount++
			rawUUIDs := d.ServiceUUIDs()
			uuidStrs := make([]string, len(rawUUIDs))
			for i, u := range rawUUIDs {
				uuidStrs[i] = u.String()
			}
			// Task 11 (H-1d): exact-match only. d.HasServiceUUID is the stack's
			// own bit-level 128-bit equality; hasServiceUUIDMatch (uuid_match.go)
			// is the normalized string equivalent for platform formatting
			// variance. The former prefix/short-UUID fallbacks are gone — a
			// device whose advertised UUID merely shares our prefix is NOT us.
			hasUUID := d.HasServiceUUID(uuid) || hasServiceUUIDMatch(uuidStrs)
			if !hasUUID {
				return
			}
			// Task 11 (M-6): log redaction — non-matching nearby devices are
			// not logged at all (name/RSSI/UUID lists of every passer-by are
			// bystander privacy data); a hit logs the address only.
			slog.Info("BLE scan hit", "addr", d.Address.String())
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
			"callbacks", scanCallbackCount, "matched", len(found),
			"matchedAddrs", matchedAddrs(), "scanErr", scanErr)
		return dedup(found), nil
	case err := <-errCh:
		slog.Info("BLE Central scan finished (err)",
			"callbacks", scanCallbackCount, "matched", len(found),
			"matchedAddrs", matchedAddrs(), "err", err)
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
	// Drop the persistent notification bridge with the dead session; frames
	// still in flight land in a detached channel and are garbage-collected.
	t.notifyQueue = nil
	t.negotiatedMTU = 0

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
	// Phase 9 (Task 10): MTU step of the connect flow. See requestMtu247.
	t.requestMtu247()

	// Arm the ONE persistent notification handler for this connection's
	// lifetime. Real-device finding: arming inside each WaitNotify dropped
	// frames that arrived while no call was blocked (see notifyQueue).
	t.notifyQueue = make(chan []byte, notifyQueueCap)
	if err := t.stateChar.EnableNotifications(t.enqueueNotify); err != nil {
		log.Printf("BLE EnableNotifications (persistent) err=%v", err)
		t.notifyQueue = nil
		return err
	}
	log.Printf("BLE Connect success for device=%s", id)
	return nil
}

// enqueueNotify is the persistent State-characteristic handler installed
// once per connection by connectLocked. Task 11 (M-6): log redaction — only
// length and the leading frame-type byte are logged, never contents (frames
// carry auth nonces/MACs and library data).
func (t *tinyGoCentralScanner) enqueueNotify(data []byte) {
	cp := append([]byte(nil), data...)
	if len(cp) == 0 {
		log.Printf("BLE notify frame len=0 (ignored)")
		return
	}
	log.Printf("BLE notify frame len=%d frameType=%#02x", len(cp), cp[0])
	q := t.notifyQueue
	if q == nil {
		return
	}
	select {
	case q <- cp:
	default:
		log.Printf("BLE notify queue full; dropping frame len=%d frameType=%#02x", len(cp), cp[0])
	}
}

// requestMtu247 is the Task 10 MTU step of the connect flow.
//
// The protocol's frame budget assumes a 247-byte ATT MTU: chunk payloads are
// capped at 200 B (spec §1.2), so the largest v2 authed frame is
// 3 (header) + 200 + 8 (seq) + 16 (HMAC) = 227 B — comfortably inside a
// 247-byte PDU (244 usable bytes).
//
// WinRT has no app-driven ATT MTU exchange exposed through the vendored
// winrt-go bindings (BluetoothLEDevice.RequestMaxPayloadSize is not bound;
// hand-rolling the IBluetoothLEDevice5 vtable call risks the same native
// COM fault class documented on disconnectLocked, which takes the whole
// server down). The equivalent on this stack: Windows automatically
// negotiates the maximum PDU size when the GATT session is established, so
// this step reads the negotiated value back via GattSession.MaxPduSize (the
// fork's DeviceCharacteristic.GetMTU) and logs it as a diagnostic gate. If
// the link stayed at the 23-byte default (adapter/OS combination that did
// not auto-negotiate), large single writes fail and the existing short-frame
// decode-error fallback applies (brief: "若 adapter 不支持则保持 23 并由既有
// 短帧解码错误兜底"). Errors here are non-fatal by design.
func (t *tinyGoCentralScanner) requestMtu247() {
	mtu, err := t.cmdChar.GetMTU()
	if err != nil {
		log.Printf("BLE MTU readback failed err=%v; assuming 23-byte default, short-frame fallback applies", err)
		return
	}
	t.negotiatedMTU = int(mtu)
	if int(mtu) >= 247 {
		log.Printf("BLE negotiated ATT MTU=%d (>=247): full-size frames fit a single PDU", mtu)
	} else {
		log.Printf("BLE negotiated ATT MTU=%d (<247): single-PDU writes capped at %d bytes; short-frame fallback applies", mtu, mtu-3)
	}
}

// MTU returns the negotiated ATT MTU for the current session (0 before a
// successful connect / readback). Callers size chunk payloads from it — see
// Central.ServeApiRequest.
func (t *tinyGoCentralScanner) MTU() int {
	t.opMu.Lock()
	defer t.opMu.Unlock()
	return t.negotiatedMTU
}

// Disconnect drops the GATT connection and clears cached characteristics.
// Safe to call when already disconnected (no-op).
func (t *tinyGoCentralScanner) Disconnect() {
	t.opMu.Lock()
	defer t.opMu.Unlock()
	t.disconnectLocked()
	// Drop the session-bound state along with the link; WaitNotify on a
	// disconnected scanner fails fast instead of dead-waiting on a queue
	// that can never fill again.
	t.notifyQueue = nil
	t.negotiatedMTU = 0
	t.cmdChar = nil
	t.stateChar = nil
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
	// A large book streams thousands of writes; keep the per-write success
	// line out of the default log (errors still log loudly).
	slog.Debug("BLE WriteCommand ok", "n", n)
	return err
}

func (t *tinyGoCentralScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	if t.stateChar == nil {
		return nil, errNoStateChar
	}
	q := t.notifyQueue
	if q == nil {
		// Defensive: connectLocked always arms the queue before returning
		// success; nil here means a post-Disconnect or failed-connect call.
		return nil, errNoStateChar
	}
	select {
	case data := <-q:
		return data, nil
	case <-ctx.Done():
		log.Printf("BLE WaitNotify ctx.Done err=%v", ctx.Err())
		return nil, ctx.Err()
	}
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

// matchUUIDPrefix reports whether u equals targetUUIDStr exactly, after
// normalization (dashes stripped, lower-cased — see normalizeUUIDString).
//
// Task 11 (H-1d): the former 8-char hex prefix fallback is REMOVED. The
// prefix branch accepted any service/characteristic whose UUID merely shared
// our "fa6a3001"/"fa6a3002"/"fa6a3003" prefix, so a hostile peripheral could
// shadow the real characteristic layout. Full 128-bit equality only; the
// historical name is kept to minimize churn at the connectLocked call sites.
func matchUUIDPrefix(u bluetooth.UUID, targetUUIDStr string) bool {
	return normalizeUUIDString(u.String()) == normalizeUUIDString(targetUUIDStr)
}

var (
	errNoService     = errors.New("ble: service not found on device")
	errNoCommandChar = errors.New("ble: command characteristic not found")
	errNoStateChar   = errors.New("ble: state characteristic not found")
)

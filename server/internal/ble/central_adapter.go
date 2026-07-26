//go:build bluetooth

// This file is compiled only with the "bluetooth" build tag, pulling in the
// real tinygo.org/x/bluetooth stack. Default builds (no tag) use
// central_adapter_stub.go instead, so the server compiles without a Bluetooth
// stack and treats BLE absence as non-fatal (zero-regression).

package ble

import (
	"context"
	"errors"
	"log/slog"
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
	uuid, err := bluetooth.ParseUUID(serviceUUID)
	if err != nil {
		return nil, err
	}

	// Hard-cap the scan at 3s so a caller that forgets a deadline cannot hang
	// the scan goroutine indefinitely. Matches the plan's global scan timeout.
	scanCtx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()

	var found []Device
	errCh := make(chan error, 1)

	// adapter.Scan blocks until StopScan; run it in a goroutine so the select
	// below can race it against scanCtx.Done().
	go func() {
		errCh <- t.adapter.Scan(func(_ *bluetooth.Adapter, d bluetooth.ScanResult) {
			// ScanResult embeds AdvertisementPayload, whose ServiceUUIDs is a
			// method (not a field) in tinygo-org/bluetooth v0.15.0. HasServiceUUID
			// is the library-recommended check and avoids building the UUID list.
			if !d.HasServiceUUID(uuid) {
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
		select {
		case <-errCh:
		default:
		}
		return dedup(found), nil
	case err := <-errCh:
		return found, err
	}
}

// Connect establishes a GATT connection to id (a MAC string like
// "AA:BB:CC:DD:EE:FF") and discovers the Command (Write) and State (Notify)
// characteristics on SERVICE_UUID. On success, device + characteristic
// pointers are cached for subsequent WriteCommand / WaitNotify calls.
func (t *tinyGoCentralScanner) Connect(_ context.Context, id string) error {
	addr, err := bluetooth.ParseMAC(id)
	if err != nil {
		return err
	}
	// bluetooth.Address embeds MACAddress, which embeds MAC. The MAC field is
	// NOT directly accessible on Address (nesting is two levels), so the
	// literal must spell out the MACAddress wrapper.
	device, err := t.adapter.Connect(
		bluetooth.Address{MACAddress: bluetooth.MACAddress{MAC: addr}},
		bluetooth.ConnectionParams{},
	)
	if err != nil {
		return err
	}
	t.device = &device

	svcUUID := mustUUID(ServiceUUID)
	svcs, err := device.DiscoverServices([]bluetooth.UUID{svcUUID})
	if err != nil {
		return err
	}
	if len(svcs) == 0 {
		return errNoService
	}
	svc := svcs[0]

	cmdUUID := mustUUID(CommandCharUUID)
	stateUUID := mustUUID(StateCharUUID)
	chars, err := svc.DiscoverCharacteristics([]bluetooth.UUID{cmdUUID, stateUUID})
	if err != nil {
		return err
	}
	for i := range chars {
		switch chars[i].UUID() {
		case cmdUUID:
			t.cmdChar = &chars[i]
		case stateUUID:
			t.stateChar = &chars[i]
		}
	}
	if t.cmdChar == nil {
		return errNoCommandChar
	}
	if t.stateChar == nil {
		return errNoStateChar
	}
	return nil
}

// Disconnect drops the GATT connection and clears cached characteristics.
// Safe to call when already disconnected (no-op).
func (t *tinyGoCentralScanner) Disconnect() {
	if t.device != nil {
		_ = t.device.Disconnect()
		t.device = nil
	}
	t.cmdChar = nil
	t.stateChar = nil
}

// WriteCommand writes payload (already frame-encoded by the caller) to the
// Command characteristic using Write Without Response semantics, matching the
// Android Peripheral's PROPERTY_WRITE (write-no-response) configuration.
func (t *tinyGoCentralScanner) WriteCommand(_ context.Context, payload []byte) error {
	if t.cmdChar == nil {
		return errNoCommandChar
	}
	_, err := t.cmdChar.WriteWithoutResponse(payload)
	return err
}

// WaitNotify subscribes to the State characteristic and blocks until the first
// notification (the echo) arrives, or ctx is cancelled.
//
// Note: tinygo-org/bluetooth v0.15.0 does NOT expose a DisableNotifications
// method on DeviceCharacteristic. Notifications stay armed for the life of the
// connection; Disconnect() tears down the OS-level GATT session which clears
// them. This is acceptable for the MVP echo flow (one notify per send).
func (t *tinyGoCentralScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	if t.stateChar == nil {
		return nil, errNoStateChar
	}

	notifyCh := make(chan []byte, 1)
	handler := func(data []byte) {
		// Copy the data: the tinygo callback contract does not guarantee the
		// slice remains valid after the callback returns.
		cp := append([]byte(nil), data...)
		select {
		case notifyCh <- cp:
		default:
			// Drop subsequent notifications; we wait for exactly one echo.
		}
	}
	if err := t.stateChar.EnableNotifications(handler); err != nil {
		return nil, err
	}

	select {
	case data := <-notifyCh:
		return data, nil
	case <-ctx.Done():
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

var (
	errNoService     = errors.New("ble: service not found on device")
	errNoCommandChar = errors.New("ble: command characteristic not found")
	errNoStateChar   = errors.New("ble: state characteristic not found")
)

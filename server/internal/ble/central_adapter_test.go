package ble

import "testing"

// NewCentralScanner must never panic when no Bluetooth hardware is present.
// In the default build (no -tags bluetooth) the stub returns (nil, err); with
// the bluetooth tag the real adapter may still be absent (no radio, disabled,
// or permission denied). Either way callers treat the error as non-fatal and
// the function must not panic.
func TestNewCentralScannerDoesNotPanic(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("NewCentralScanner panicked: %v", r)
		}
	}()
	_, _ = NewCentralScanner()
}

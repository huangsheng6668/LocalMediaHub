package ble

import "testing"

// NewTinyGoAdapter must never panic when no Bluetooth hardware is present.
// It returns (nil, err) in that case; production callers treat failure as
// non-fatal. This test only asserts the no-panic contract — actual BLE
// behavior requires hardware and is verified manually.
func TestNewTinyGoAdapterDoesNotPanic(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("NewTinyGoAdapter panicked: %v", r)
		}
	}()
	_, _ = NewTinyGoAdapter()
}

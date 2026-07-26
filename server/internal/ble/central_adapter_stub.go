//go:build !bluetooth

// Default-build stub. Compiled when the "bluetooth" build tag is NOT set.
// NewCentralScanner returns (nil, err); every method on the stub scanner
// returns the "ble: unavailable" error. Nothing here may panic, so callers
// (server main, handler) treat BLE absence as non-fatal (zero-regression).

package ble

import (
	"context"
	"errors"
	"log/slog"
)

type stubCentralScanner struct{}

// NewCentralScanner returns a non-functional scanner that signals BLE
// unavailability. The error is logged at info level (not warn) because this
// is the expected state for default builds on machines without a Bluetooth
// stack configured at build time.
func NewCentralScanner() (CentralScanner, error) {
	slog.Info("BLE build not enabled (no -tags bluetooth); BLE channel disabled")
	return nil, errors.New("ble: built without bluetooth tag")
}

func (stubCentralScanner) Scan(context.Context, string) ([]Device, error) {
	return nil, errors.New("ble: unavailable")
}

func (stubCentralScanner) Connect(context.Context, string) error {
	return errors.New("ble: unavailable")
}

func (stubCentralScanner) Disconnect() {}

func (stubCentralScanner) WriteCommand(context.Context, []byte) error {
	return errors.New("ble: unavailable")
}

func (stubCentralScanner) WaitNotify(context.Context) ([]byte, error) {
	return nil, errors.New("ble: unavailable")
}

//go:build !bluetooth

package ble

import (
	"errors"
	"log/slog"
)

// stubAdapter is used when the "bluetooth" build tag is NOT set (default
// builds). It makes BLE permanently unavailable without breaking compilation
// on machines without a Bluetooth stack / CGO setup.
type stubAdapter struct{}

// NewTinyGoAdapter returns a stub error when built without the "bluetooth"
// tag. This keeps default `go build`/`go test` green on CI without Bluetooth
// hardware; production hardware verification builds with -tags bluetooth.
func NewTinyGoAdapter() (Adapter, error) {
	slog.Warn("BLE build not enabled (no -tags bluetooth); BLE channel disabled")
	return nil, errors.New("ble: built without bluetooth tag")
}

func (s *stubAdapter) StartAdvertising(string) error { return nil }
func (s *stubAdapter) StopAdvertising()              {}
func (s *stubAdapter) SetWriteHandler(func([]byte))  {}
func (s *stubAdapter) Notify([]byte) error           { return nil }

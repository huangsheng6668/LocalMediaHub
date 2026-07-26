//go:build bluetooth

package ble

import (
	"log/slog"

	"tinygo.org/x/bluetooth"
)

// tinyGoAdapter wraps tinygo.org/x/bluetooth to satisfy the
// ble.Adapter interface. Built only when the "bluetooth" build tag is set,
// so default builds (no tag) compile without requiring a Bluetooth stack.
type tinyGoAdapter struct {
	adapter *bluetooth.Adapter
	// Production wiring (characteristics, notify buffers) is added when
	// integrating against real hardware. The struct exists to satisfy the
	// Adapter contract and to make construction failure non-fatal.
	writeHandler func([]byte)
}

// NewTinyGoAdapter enables the default Bluetooth adapter. Returns (nil, err)
// when no adapter is present or enabling fails; callers must treat failure
// as non-fatal (the zero-regression principle).
func NewTinyGoAdapter() (Adapter, error) {
	a := bluetooth.DefaultAdapter
	if err := a.Enable(); err != nil {
		slog.Warn("BLE adapter unavailable; BLE channel disabled", "error", err)
		return nil, err
	}
	return &tinyGoAdapter{adapter: a}, nil
}

func (t *tinyGoAdapter) StartAdvertising(serviceUUID string) error {
	// TODO(hardware-integration): add service + characteristic registration.
	// Placeholder returns nil so the state machine test path compiles;
	// real advertising is wired during manual hardware verification.
	return nil
}

func (t *tinyGoAdapter) StopAdvertising() {}

func (t *tinyGoAdapter) SetWriteHandler(h func([]byte)) {
	t.writeHandler = h
}

func (t *tinyGoAdapter) Notify(payload []byte) error {
	// TODO(hardware-integration): write to State characteristic CCCD subscribers.
	return nil
}

package ble

import (
	"context"
	"errors"
	"sync"
)

// ErrNotConnected is returned when an operation requires an active BLE
// connection but none exists.
var ErrNotConnected = errors.New("ble: not connected")

// Device is a discovered BLE peripheral.
type Device struct {
	ID   string
	Name string
	RSSI int
}

// CentralScanner abstracts the BLE Central-role stack so Central logic is
// unit-testable without hardware. Production impl lives in central_adapter.go
// (bluetooth build tag).
type CentralScanner interface {
	Scan(ctx context.Context, serviceUUID string) ([]Device, error)
	Connect(ctx context.Context, id string) error
	Disconnect()
	WriteCommand(ctx context.Context, payload []byte) error
	WaitNotify(ctx context.Context) ([]byte, error)
}

// Central owns the BLE Central-role lifecycle: scan, connect, send.
// Thread-safe via mu; operations are serialized to avoid BLE-stack state
// races (only one scan/connect/send at a time).
type Central struct {
	mu      sync.Mutex
	scanner CentralScanner
	state   string // "disconnected" | "connected"
}

func NewCentral(s CentralScanner) *Central {
	return &Central{scanner: s, state: "disconnected"}
}

// Scan discovers peripherals advertising serviceUUID. Respects ctx deadline.
func (c *Central) Scan(ctx context.Context) ([]Device, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.scanner.Scan(ctx, ServiceUUID)
}

// Connect establishes a GATT connection to the device id. Serialized.
func (c *Central) Connect(ctx context.Context, id string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.scanner.Connect(ctx, id); err != nil {
		return err
	}
	c.state = "connected"
	return nil
}

// Send writes payload to the Command characteristic and waits for a Notify
// response (echo). Returns the decoded echo payload. Requires active connection.
func (c *Central) Send(ctx context.Context, payload []byte) ([]byte, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.state != "connected" {
		return nil, ErrNotConnected
	}
	if err := c.scanner.WriteCommand(ctx, EncodeFrame(payload)); err != nil {
		return nil, err
	}
	raw, err := c.scanner.WaitNotify(ctx)
	if err != nil {
		return nil, err
	}
	frame, err := DecodeFrame(raw)
	if err != nil {
		return nil, err
	}
	return frame.Payload, nil
}

func (c *Central) Disconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.scanner.Disconnect()
	c.state = "disconnected"
}

func (c *Central) State() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.state
}

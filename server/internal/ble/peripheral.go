package ble

import "sync"

// Adapter abstracts the underlying BLE stack (tinygo-org/bluetooth in
// production) so Peripheral logic is unit-testable without hardware.
type Adapter interface {
	StartAdvertising(serviceUUID string) error
	StopAdvertising()
	SetWriteHandler(h func([]byte))
	Notify(payload []byte) error
}

// Peripheral owns the BLE GATT service lifecycle. Thread-safe.
type Peripheral struct {
	mu      sync.Mutex
	adapter Adapter
	state   string // "stopped" | "advertising"
	onWrite func([]byte)
}

func NewPeripheral(adapter Adapter) *Peripheral {
	return &Peripheral{adapter: adapter, state: "stopped"}
}

// Start registers write handler and begins advertising. Returns nil on
// success; never panics — callers (server main) treat failure as non-fatal.
func (p *Peripheral) Start() error {
	p.mu.Lock()
	defer p.mu.Unlock()

	p.adapter.SetWriteHandler(func(raw []byte) {
		p.mu.Lock()
		h := p.onWrite
		p.mu.Unlock()
		if h == nil {
			return
		}
		frame, err := DecodeFrame(raw)
		if err != nil {
			return
		}
		h(frame.Payload)
	})

	if err := p.adapter.StartAdvertising(ServiceUUID); err != nil {
		return err
	}
	p.state = "advertising"
	return nil
}

func (p *Peripheral) Stop() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.adapter.StopAdvertising()
	p.state = "stopped"
}

func (p *Peripheral) State() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.state
}

// Broadcast encodes payload as a Frame and pushes it via Notify (S -> C).
func (p *Peripheral) Broadcast(payload []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.adapter.Notify(EncodeFrame(payload))
}

// OnWrite registers the callback invoked when the Central writes to the
// Command characteristic. Payload is the decoded Frame payload.
func (p *Peripheral) OnWrite(h func([]byte)) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.onWrite = h
}

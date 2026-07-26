package ble

import (
	"sync"
	"testing"
)

// fakeAdapter records calls and lets the test simulate write events.
type fakeAdapter struct {
	mu           sync.Mutex
	started      bool
	stopped      bool
	broadcasts   [][]byte
	writeHandler func([]byte)
}

func (f *fakeAdapter) StartAdvertising(serviceUUID string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.started = true
	return nil
}

func (f *fakeAdapter) StopAdvertising() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.stopped = true
}

func (f *fakeAdapter) SetWriteHandler(h func([]byte)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.writeHandler = h
}

func (f *fakeAdapter) Notify(payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.broadcasts = append(f.broadcasts, payload)
	return nil
}

func TestPeripheralStartAdvertises(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	if err := p.Start(); err != nil {
		t.Fatalf("Start returned error: %v", err)
	}
	if !fa.started {
		t.Fatal("adapter was not started")
	}
	if p.State() != "advertising" {
		t.Fatalf("state = %q, want advertising", p.State())
	}
}

func TestPeripheralBroadcastEncodesFrame(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	_ = p.Start()

	if err := p.Broadcast([]byte("ping")); err != nil {
		t.Fatalf("Broadcast returned error: %v", err)
	}
	fa.mu.Lock()
	defer fa.mu.Unlock()
	if len(fa.broadcasts) != 1 {
		t.Fatalf("expected 1 broadcast, got %d", len(fa.broadcasts))
	}
	frame, err := DecodeFrame(fa.broadcasts[0])
	if err != nil {
		t.Fatalf("broadcast was not a valid frame: %v", err)
	}
	if string(frame.Payload) != "ping" {
		t.Fatalf("payload = %q, want ping", string(frame.Payload))
	}
}

func TestPeripheralWriteHandlerDecodesFrame(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	_ = p.Start()

	received := make(chan []byte, 1)
	p.OnWrite(func(payload []byte) {
		received <- payload
	})

	// Simulate Central writing an encoded frame.
	fa.mu.Lock()
	h := fa.writeHandler
	fa.mu.Unlock()
	if h == nil {
		t.Fatal("write handler not set")
	}
	h(EncodeFrame([]byte("pong")))

	select {
	case got := <-received:
		if string(got) != "pong" {
			t.Fatalf("handler payload = %q, want pong", string(got))
		}
	default:
		t.Fatal("write handler not invoked")
	}
}

func TestPeripheralStopClearsState(t *testing.T) {
	fa := &fakeAdapter{}
	p := NewPeripheral(fa)
	_ = p.Start()
	p.Stop()
	if p.State() != "stopped" {
		t.Fatalf("state = %q, want stopped", p.State())
	}
	if !fa.stopped {
		t.Fatal("adapter was not stopped")
	}
}

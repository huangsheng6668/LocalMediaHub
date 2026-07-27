package ble

import (
	"context"
	"sync"
	"testing"
	"time"
)

type fakeScanner struct {
	mu          sync.Mutex
	devices     []Device
	connectedID string
	written     []byte
	notifyResp  []byte // what the peripheral echoes back via notify
	notifyErr   error
	scanCalled  bool
}

// Scan returns any pre-set devices immediately. When no devices are configured
// it blocks until ctx is cancelled (so the timeout test exercises a real
// deadline rather than an immediate empty return).
func (f *fakeScanner) Scan(ctx context.Context, serviceUUID string) ([]Device, error) {
	f.mu.Lock()
	f.scanCalled = true
	devs := f.devices
	f.mu.Unlock()
	if devs != nil {
		return devs, nil
	}
	<-ctx.Done()
	return nil, ctx.Err()
}

func (f *fakeScanner) Connect(ctx context.Context, id string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connectedID = id
	return nil
}

func (f *fakeScanner) Disconnect() {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.connectedID = ""
}

func (f *fakeScanner) WriteCommand(ctx context.Context, payload []byte) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.written = payload
	return nil
}

func (f *fakeScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.notifyErr != nil {
		return nil, f.notifyErr
	}
	return f.notifyResp, nil
}

func TestCentralScanReturnsDevices(t *testing.T) {
	fs := &fakeScanner{devices: []Device{{ID: "AA:BB", Name: "Pixel", RSSI: -45}}}
	c := NewCentral(fs)
	got, err := c.Scan(context.Background())
	if err != nil {
		t.Fatalf("Scan error: %v", err)
	}
	if len(got) != 1 || got[0].ID != "AA:BB" {
		t.Fatalf("got %+v", got)
	}
}

func TestCentralConnectSetsState(t *testing.T) {
	fs := &fakeScanner{}
	c := NewCentral(fs)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect error: %v", err)
	}
	if c.State() != "connected" {
		t.Fatalf("state=%q want connected", c.State())
	}
}

func TestCentralSendEncodesAndReturnsEcho(t *testing.T) {
	fs := &fakeScanner{notifyResp: EncodeFrame([]byte("pong"))}
	c := NewCentral(fs)
	_ = c.Connect(context.Background(), "AA:BB")
	echo, err := c.Send(context.Background(), []byte("ping"))
	if err != nil {
		t.Fatalf("Send error: %v", err)
	}
	if string(echo) != "pong" {
		t.Fatalf("echo=%q want pong", string(echo))
	}
	// Verify written payload was encoded.
	fs.mu.Lock()
	defer fs.mu.Unlock()
	frame, err := DecodeFrame(fs.written)
	if err != nil {
		t.Fatalf("written not a valid frame: %v", err)
	}
	if string(frame.Payload) != "ping" {
		t.Fatalf("written payload=%q want ping", string(frame.Payload))
	}
}

func TestCentralSendWhenNotConnectedErrors(t *testing.T) {
	fs := &fakeScanner{}
	c := NewCentral(fs)
	_, err := c.Send(context.Background(), []byte("ping"))
	if err == nil {
		t.Fatal("expected error when not connected")
	}
}

func TestCentralScanTimeout(t *testing.T) {
	fs := &fakeScanner{} // no devices → blocks until ctx done
	c := NewCentral(fs)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Millisecond)
	defer cancel()
	_, err := c.Scan(ctx)
	if err == nil {
		t.Fatal("expected timeout error")
	}
}

func TestCentralConnectSerializesConcurrentCalls(t *testing.T) {
	// Two concurrent Connect calls: second must wait for first (no panic, no race).
	fs := &fakeScanner{}
	c := NewCentral(fs)
	var wg sync.WaitGroup
	errs := make([]error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			errs[i] = c.Connect(context.Background(), "AA:BB")
		}(i)
	}
	wg.Wait()
	for _, e := range errs {
		if e != nil {
			t.Fatalf("connect error: %v", e)
		}
	}
}

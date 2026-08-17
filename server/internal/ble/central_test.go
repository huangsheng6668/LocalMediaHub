package ble

import (
	"context"
	"crypto/hmac"
	"errors"
	"sync"
	"testing"
	"time"
)

// centralTestToken is the shared secret used by authenticated test Centrals
// and their blePeerFake counterparts.
const centralTestToken = "unit-test-token"

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

// SetConnectRecorder is a no-op: fakeScanner drives Central logic only and
// never exercises the connectLocked recorder path (that lives in the
// bluetooth-tagged tinyGoCentralScanner). Required to satisfy the
// CentralScanner interface after the recorder seam was added.
func (f *fakeScanner) SetConnectRecorder(ConnectRecorder) {}

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
	peer := newBlePeerFake(centralTestToken)
	c := NewCentral(peer)
	c.SetAuthToken(centralTestToken)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect error: %v", err)
	}
	if c.State() != "connected" {
		t.Fatalf("state=%q want connected", c.State())
	}
}

func TestCentralSendEncodesAndReturnsEcho(t *testing.T) {
	key := DeriveBleAuthKey(centralTestToken)
	peer := newBlePeerFake(centralTestToken)
	c := NewCentral(peer)
	c.SetAuthToken(centralTestToken)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect error: %v", err)
	}
	// Queue the peer's v2 echo reply (seq 0 = first frame of the connection).
	peer.push(EncodeAuthedFrame([]byte("pong"), 0, key))
	echo, err := c.Send(context.Background(), []byte("ping"))
	if err != nil {
		t.Fatalf("Send error: %v", err)
	}
	if string(echo) != "pong" {
		t.Fatalf("echo=%q want pong", string(echo))
	}
	// Verify the written payload was an authed v2 frame carrying "ping" at
	// seq 0 (the first outbound data frame of the connection).
	writes := peer.recordedWrites()
	if len(writes) != 1 {
		t.Fatalf("expected 1 written frame, got %d", len(writes))
	}
	payload, seq, derr := DecodeAuthedFrame(writes[0], key)
	if derr != nil {
		t.Fatalf("written not a valid v2 authed frame: %v", derr)
	}
	if string(payload) != "ping" || seq != 0 {
		t.Fatalf("written payload=%q seq=%d want ping/0", string(payload), seq)
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

// TestNewCentralScannerDoesNotPanic asserts the central construction contract
// that server.New depends on: NewCentralScanner must NEVER panic when the
// Bluetooth adapter is missing, disabled, or built without the bluetooth tag.
// Instead it returns (nil, err) and callers continue without BLE. This file
// has no build tag, so the test runs in BOTH builds: under the default build
// it exercises the stub path (central_adapter_stub.go), and under -tags
// bluetooth it exercises the real tinygo path (central_adapter.go) which calls
// adapter.Enable() — on any host without a usable radio Enable fails and
// returns (nil, err) rather than faulting. A recover guard double-checks the
// no-panic invariant even if a future regression raises a native fault.
func TestNewCentralScannerDoesNotPanic(t *testing.T) {
	defer func() {
		if r := recover(); r != nil {
			t.Fatalf("NewCentralScanner panicked: %v", r)
		}
	}()
	scanner, err := NewCentralScanner()
	if err != nil {
		// Error is expected on hosts without BLE; the contract is "no panic",
		// not "success". A nil scanner with an error is the documented
		// unavailable-adapter outcome.
		if scanner != nil {
			t.Fatalf("NewCentralScanner returned non-nil scanner WITH error: %v (scanner=%T)", err, scanner)
		}
		return
	}
	if scanner == nil {
		t.Fatal("NewCentralScanner returned (nil, nil) — caller would dereference nil")
	}
}

func TestCentralConnectSerializesConcurrentCalls(t *testing.T) {
	// Two concurrent Connect calls: second must wait for first (no panic, no race).
	// Each call drives its own full handshake against the peer fake.
	peer := newBlePeerFake(centralTestToken)
	c := NewCentral(peer)
	c.SetAuthToken(centralTestToken)
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

// peerChallengeNonce is the blePeerFake phone's fixed challenge nonce
// (deterministic so the fake can rebuild the expected response MAC).
var peerChallengeNonce = []byte{9, 8, 7, 6, 5, 4, 3, 2}

// blePeerFake simulates the Android Peripheral for the Phase 9 (H-1a) auth +
// data exchange: it answers the Central's v1 handshake challenge with a valid
// response followed by its own challenge, validates the Central's response
// MAC, and records v2 data-frame writes via the embedded collectScanner.
// WaitNotify pops queued notification frames in order; once the queue is
// exhausted it closes doneCh (once) and blocks until unblockCh closes or ctx
// ends — mirroring scriptedScanner's listener-test contract. Tests that do
// not run the listener leave doneCh/unblockCh nil and simply block on ctx.
type blePeerFake struct {
	collectScanner
	peerKey []byte

	mu        sync.Mutex
	frames    [][]byte
	nextIdx   int
	doneCh    chan struct{}
	unblockCh chan struct{}
	dropped   bool // Disconnect observed
}

func newBlePeerFake(token string) *blePeerFake {
	return &blePeerFake{peerKey: DeriveBleAuthKey(token)}
}

// push queues a notification frame the fake phone will deliver via WaitNotify.
func (p *blePeerFake) push(frame []byte) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.frames = append(p.frames, append([]byte(nil), frame...))
}

// WriteCommand implements the phone-side handshake logic: a v1
// CmdAuthChallenge(dir=CentralToPeripheral) is answered with the response MAC
// plus the phone's own challenge (both queued as v1 notifications); the
// Central's CmdAuthResponse is MAC-validated; anything else (v2 data frames)
// is recorded via the embedded collectScanner.
func (p *blePeerFake) WriteCommand(ctx context.Context, payload []byte) error {
	if frame, err := DecodeFrame(payload); err == nil && len(frame.Payload) > 0 {
		switch CmdID(frame.Payload[0]) {
		case CmdAuthChallenge:
			dir, nonce, derr := DecodeAuthChallengePayload(frame.Payload)
			if derr != nil || dir != AuthDirCentralToPeripheral {
				return errors.New("peer fake: malformed central challenge")
			}
			mac := AuthResponseMAC(p.peerKey, nonce, dir)
			p.push(EncodeFrame(EncodeAuthResponsePayload(nonce, mac)))
			p.push(EncodeFrame(EncodeAuthChallengePayload(AuthDirPeripheralToCentral, peerChallengeNonce)))
			return nil
		case CmdAuthResponse:
			rn, rm, derr := DecodeAuthResponsePayload(frame.Payload)
			if derr != nil || !hmac.Equal(rm, AuthResponseMAC(p.peerKey, rn, AuthDirPeripheralToCentral)) {
				return errors.New("peer fake: central auth response rejected")
			}
			return nil
		}
	}
	// v2 data frame (v1 decode fails on the 0x02 version byte) — record it.
	return p.collectScanner.WriteCommand(ctx, payload)
}

// WaitNotify pops queued notification frames in order. When the queue is
// (momentarily) exhausted it closes doneCh once, then re-checks every few
// milliseconds so a test can push follow-up frames late (e.g. the replay
// frame after the first request's chunk write is observed) while still
// exiting on unblockCh/ctx.
func (p *blePeerFake) WaitNotify(ctx context.Context) ([]byte, error) {
	for {
		p.mu.Lock()
		if p.nextIdx < len(p.frames) {
			f := p.frames[p.nextIdx]
			p.nextIdx++
			p.mu.Unlock()
			return f, nil
		}
		p.mu.Unlock()
		if p.doneCh != nil {
			select {
			case <-p.doneCh:
			default:
				close(p.doneCh)
			}
		}
		if p.unblockCh != nil {
			select {
			case <-p.unblockCh:
				return nil, context.Canceled
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(5 * time.Millisecond):
			}
		} else {
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(5 * time.Millisecond):
			}
		}
	}
}

// SetConnectRecorder is a no-op test double stub (see fakeScanner note).
func (p *blePeerFake) SetConnectRecorder(ConnectRecorder) {}

func (p *blePeerFake) Disconnect() {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.dropped = true
}

func (p *blePeerFake) wasDisconnected() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.dropped
}

// recordedWrites snapshots the recorded v2 data-frame writes.
func (p *blePeerFake) recordedWrites() [][]byte {
	p.collectScanner.mu.Lock()
	defer p.collectScanner.mu.Unlock()
	out := make([][]byte, len(p.collectScanner.written))
	copy(out, p.collectScanner.written)
	return out
}

// TestCentralConnectRequiresAuthKey verifies the central-side half of the
// open-auth-mode gate: with an empty server token the auth key stays nil and
// Connect must refuse before the link can carry any data.
func TestCentralConnectRequiresAuthKey(t *testing.T) {
	peer := newBlePeerFake(centralTestToken)
	c := NewCentral(peer) // no SetAuthToken -> nil authKey
	if err := c.Connect(context.Background(), "AA:BB"); err != ErrNoAuthKey {
		t.Fatalf("expected ErrNoAuthKey, got %v", err)
	}
	if c.State() != "disconnected" {
		t.Fatalf("state=%q want disconnected", c.State())
	}
}

// TestCentralHandshakeSuccessAndChunkTransfer (brief Step 4, case 1): with
// the correct key the mutual handshake completes and CMD_JSON_CHUNK data
// frames flow as v2 authed frames with strictly increasing seq — never as
// bare v1 frames.
func TestCentralHandshakeSuccessAndChunkTransfer(t *testing.T) {
	key := DeriveBleAuthKey(centralTestToken)
	peer := newBlePeerFake(centralTestToken)
	c := NewCentral(peer)
	c.SetAuthToken(centralTestToken)
	// ~2KB body forces multiple chunks at the 200-byte spec §1.2 cap.
	body := make([]byte, 2048)
	for i := range body {
		body[i] = 'x'
	}
	c.SetApiProvider(&jsonBlockProvider{body: body})

	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect (handshake) error: %v", err)
	}
	if c.State() != "connected" {
		t.Fatalf("state=%q want connected", c.State())
	}

	req, encErr := EncodeApiReqPayload(EndpointBookChapter, "/books/novel.txt", 0)
	if encErr != nil {
		t.Fatalf("EncodeApiReqPayload: %v", encErr)
	}
	written, err := c.ServeApiRequest(context.Background(), req)
	if err != nil {
		t.Fatalf("ServeApiRequest: %v", err)
	}
	if written < 2 {
		t.Fatalf("expected multiple chunks, got %d", written)
	}

	writes := peer.recordedWrites()
	if len(writes) != written {
		t.Fatalf("written=%d but peer recorded %d frames", written, len(writes))
	}
	var lastSeq uint64
	for i, raw := range writes {
		payload, seq, derr := DecodeAuthedFrame(raw, key)
		if derr != nil {
			t.Fatalf("frame %d is not a v2 authed frame: %v", i, derr)
		}
		if _, v1err := DecodeFrame(raw); v1err != ErrBadVersion {
			t.Fatalf("frame %d must be v2-only on the wire, DecodeFrame err=%v", i, v1err)
		}
		if i > 0 && seq <= lastSeq {
			t.Fatalf("frame %d seq %d not strictly greater than %d", i, seq, lastSeq)
		}
		lastSeq = seq
		if len(payload) == 0 || CmdID(payload[0]) != CmdJsonChunk {
			t.Fatalf("frame %d is not CMD_JSON_CHUNK", i)
		}
	}
}

// TestCentralHandshakeWrongKeyDisconnects (brief Step 4, case 2): when the
// peer proves with a different key the response MAC fails to verify, Connect
// returns an ErrHandshakeFailed-wrapped error and the link is dropped.
func TestCentralHandshakeWrongKeyDisconnects(t *testing.T) {
	peer := newBlePeerFake("phone-secret") // phone derives a DIFFERENT key
	c := NewCentral(peer)
	c.SetAuthToken("central-secret")

	err := c.Connect(context.Background(), "AA:BB")
	if err == nil {
		t.Fatal("expected handshake failure with wrong key")
	}
	if !errors.Is(err, ErrHandshakeFailed) {
		t.Fatalf("want ErrHandshakeFailed, got %v", err)
	}
	if c.State() != "disconnected" {
		t.Fatalf("state=%q want disconnected after failed handshake", c.State())
	}
	if !peer.wasDisconnected() {
		t.Fatal("expected scanner Disconnect after failed handshake")
	}
}

// TestCentralV2ReplaySeqRejected (brief Step 4, case 3): after a successful
// handshake, a replayed v2 frame (same seq) must be rejected with
// ErrReplaySeq semantics — the link is dropped and the replayed request is
// never served.
func TestCentralV2ReplaySeqRejected(t *testing.T) {
	key := DeriveBleAuthKey(centralTestToken)
	peer := newBlePeerFake(centralTestToken)
	peer.doneCh = make(chan struct{})
	peer.unblockCh = make(chan struct{})
	c := NewCentral(peer)
	c.SetAuthToken(centralTestToken)
	c.SetApiProvider(&jsonBlockProvider{body: []byte(`hi`)}) // 1 chunk per request
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect (handshake) error: %v", err)
	}

	req, encErr := EncodeApiReqPayload(EndpointBookChapter, "/books/a.txt", 0)
	if encErr != nil {
		t.Fatalf("EncodeApiReqPayload: %v", encErr)
	}
	v2req := EncodeAuthedFrame(req, 0, key)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	listenerDone := make(chan struct{})
	go func() {
		_ = c.RunApiListener(ctx)
		close(listenerDone)
	}()

	// Phase A: deliver the legitimate request first and wait for its single
	// chunk write. Waiting BEFORE injecting the replay makes the assertion
	// deterministic (the replay verdict cannot race the first dispatch).
	peer.push(v2req)
	waitFor := func(cond func() bool) bool {
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if cond() {
				return true
			}
			time.Sleep(5 * time.Millisecond)
		}
		return cond()
	}
	if !waitFor(func() bool { return len(peer.recordedWrites()) == 1 }) {
		t.Fatal("legitimate request was not served before replay injection")
	}

	// Phase B: replay the identical frame (same seq). The listener must
	// reject it (ErrReplaySeq semantics) and drop the link, with no second
	// chunk write.
	peer.push(v2req)
	if !waitFor(func() bool { return peer.wasDisconnected() && c.State() == "disconnected" }) {
		t.Fatal("replayed v2 seq must drop the link")
	}
	if writes := peer.recordedWrites(); len(writes) != 1 {
		t.Fatalf("replayed request must not be served twice, got %d chunk writes", len(writes))
	}

	defer close(peer.unblockCh)
	cancel()
	<-listenerDone
}

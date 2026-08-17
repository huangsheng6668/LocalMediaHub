package ble

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"errors"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

// ErrNotConnected is returned when an operation requires an active BLE
// connection but none exists.
var ErrNotConnected = errors.New("ble: not connected")

// ErrNoApiProvider is returned by ServeApiRequest when no ApiProvider has been
// injected via SetApiProvider.
var ErrNoApiProvider = errors.New("ble: API provider not configured")

// ErrNoAuthKey is returned by Connect when no auth key is configured (empty
// server token / open-auth mode). The BLE channel MUST NOT enter the data
// phase without a key: every frame would be "authenticated" with a publicly
// computable MAC (Phase 9 / H-1a).
var ErrNoAuthKey = errors.New("ble: auth key not configured; BLE data channel unavailable in open-auth mode")

// ErrNotAuthenticated is returned by data-phase operations (Send,
// ServeApiRequest) when the mutual-challenge handshake has not completed for
// the current connection (Phase 9 / H-1a).
var ErrNotAuthenticated = errors.New("ble: channel not authenticated")

// ErrHandshakeFailed wraps the concrete cause when the post-connect
// mutual-challenge handshake does not complete within the 5s budget or the
// peer's proof does not verify. The connection is dropped either way.
var ErrHandshakeFailed = errors.New("ble: authentication handshake failed")

// bleHandshakeTimeout is the Phase 9 (H-1a) budget for completing both
// challenge directions after the GATT connection is established. On expiry
// the connection is dropped (fail closed). 10s (raised from 5s) leaves room
// for LE Just Works pairing — the phone bonds on connection and the link
// needs 1-3s to encrypt before our CCCD write stops failing with ATT 0x05.
const bleHandshakeTimeout = 10 * time.Second

// handshakeAttempts / handshakeRetryBackoff / handshakeAttemptNotifyWait pace
// the challenge-exchange retries. Each attempt re-writes the challenge and
// waits at most handshakeAttemptNotifyWait for the first notification, so a
// response lost to the CCCD-arm sequencing race (or an ATT 0x05 during
// optional link encryption) costs one short attempt instead of the whole
// budget. 3 attempts × 2.5s + backoffs fits the 10s handshake window and the
// caller's ~15s HTTP budget with GATT establishment time.
const (
	handshakeAttempts          = 3
	handshakeRetryBackoff      = 500 * time.Millisecond
	handshakeAttemptNotifyWait = 2500 * time.Millisecond
)

// authListenerHandshakePoll is how long RunApiListener pauses between checks
// while Connect drives the handshake. Connect owns the notify stream during
// that window; a second concurrent WaitNotify arm would steal/replace its
// CCCD handler (the adapter's EnableNotifications is one-shot per call).
const authListenerHandshakePoll = 20 * time.Millisecond

// Device is a discovered BLE peripheral.
type Device struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	RSSI int    `json:"rssi"`
}

// ConnectRecorder observes the outcome of each Connect round (one
// connectLocked invocation = one round). The bluetooth build's scanner calls
// RecordConnect(true) on success and RecordConnect(false) on failure; main.go
// injects a *BleHealthMonitor (windows && bluetooth build) so the monitor can
// trigger a self-restart after ConnectFailThreshold consecutive failures.
//
// The interface lives here (no build tag) so it can be referenced uniformly by
// the real scanner, the non-bluetooth stub, and callers — only the concrete
// *BleHealthMonitor implementation is windows && bluetooth guarded (ble_health.go).
// This keeps the non-bluetooth build compiling without dragging the monitor in.
type ConnectRecorder interface {
	RecordConnect(ok bool)
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

	// SetConnectRecorder injects a ConnectRecorder that observes each Connect
	// round's outcome. The real (bluetooth) scanner wires the recorder into
	// connectLocked's deferred path; the stub is a no-op (no Connect ever
	// succeeds without a Bluetooth stack). nil recorder = observe nothing.
	// Safe to call before or after Connect; the scanner holds the reference
	// and guards nil internally.
	SetConnectRecorder(r ConnectRecorder)
}

// Central owns the BLE Central-role lifecycle: scan, connect, send.
// Thread-safe via mu; operations are serialized to avoid BLE-stack state
// races (only one scan/connect/send at a time).
type Central struct {
	mu          sync.Mutex
	scanner     CentralScanner
	state       string // "disconnected" | "connected"
	apiProvider ApiProvider

	// Phase 9 (H-1a) auth state. authKey is DeriveBleAuthKey(server token)
	// — nil in open-auth mode, which refuses the data phase entirely.
	// authenticated flips true only after both handshake challenges verify;
	// localSeq / remoteSeq enforce per-direction strictly-increasing v2 seq.
	// handshaking pauses RunApiListener's WaitNotify arms while Connect owns
	// the notify stream for the handshake.
	authKey       []byte
	authenticated bool
	localSeq      uint64
	remoteSeq     uint64
	haveRemoteSeq bool
	handshaking   bool

	// sendMu serializes outbound v2 data writes (I-1 fix): the seq
	// reservation and the WriteCommand radio call form ONE critical section
	// for every data-frame write path (Send + ServeApiRequest chunk loop).
	// Without it, two per-request dispatch goroutines can interleave as
	// "A reserves seq=0 → B reserves seq=1 → B's radio write completes first",
	// putting frames on the wire as [1,0] — a strict-increase receiver
	// (Task 9 Android) would then kill the link for legitimate concurrent
	// requests. Tradeoff (per review): c.mu is NOT held across WriteCommand
	// because the radio op can block on GATT flow control and c.mu guards
	// short paths (State, listener gates); instead this dedicated mutex
	// matches the physical reality that a single BLE link carries one GATT
	// write at a time anyway. Lock order: sendMu → c.mu, never reversed —
	// Connect's v1 handshake writes take neither lock (they run under c.mu
	// before authenticated flips true, so they can never overlap data writes).
	sendMu sync.Mutex

	// echoCh (buffer 1) carries echo replies from the listener — the notify
	// stream's SOLE consumer — to a waiting Send. Real-device Phase 9
	// finding: with Send and the listener both dequeuing, whichever grabbed
	// the echo starved the other; a single consumer with per-destination
	// routing removes the race entirely. Stale replies with no waiter are
	// dropped on the floor.
	echoCh chan []byte
}

// ApiProvider returns the JSON response body for an endpoint/path/index triple
// decoded from a CMD_API_REQ frame. The concrete implementation lives in
// api_provider.go and adapts service.BookService + config.Scan into this
// contract; the interface lives in package ble so the BLE layer has no compile-
// time dependency on package service (avoids a cycle and keeps the listener
// unit-testable with a stub).
type ApiProvider interface {
	HandleBleRequest(ctx context.Context, endpoint byte, path string, index int) ([]byte, error)
}

func NewCentral(s CentralScanner) *Central {
	return &Central{scanner: s, state: "disconnected", echoCh: make(chan []byte, 1)}
}

// SetAuthToken derives and stores the BLE auth key from the server's Bearer
// token. An EMPTY token stores a nil key — DeriveBleAuthKey("") would be a
// publicly computable constant (the domain prefix is in the source), so
// open-auth mode keeps the key nil and Connect/data paths refuse to run.
// Call once at startup, before any Connect.
func (c *Central) SetAuthToken(token string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if token == "" {
		c.authKey = nil
		return
	}
	c.authKey = DeriveBleAuthKey(token)
}

// resetAuthLocked clears the per-connection auth state. Caller holds mu.
func (c *Central) resetAuthLocked() {
	c.authenticated = false
	c.localSeq = 0
	c.remoteSeq = 0
	c.haveRemoteSeq = false
}

// acceptRemoteSeqLocked applies the strictly-increasing receive gate to a
// decoded v2 frame's seq: the first frame of a connection is accepted with any
// seq, every later one must be strictly greater than the max seen. Returns
// ErrReplaySeq on rollback/replay WITHOUT updating the max. Caller holds mu.
func (c *Central) acceptRemoteSeqLocked(seq uint64) error {
	if c.haveRemoteSeq && seq <= c.remoteSeq {
		return ErrReplaySeq
	}
	c.remoteSeq, c.haveRemoteSeq = seq, true
	return nil
}

// sendAuthedFrame atomically reserves the next outbound seq and writes one
// v2 authed frame — the I-1 fix. The seq reservation and the radio write are
// a single critical section under sendMu (see the field comment for the
// locking tradeoff: no blocking radio op ever runs under c.mu). A failed
// write still consumes its seq, leaving a gap — the receive gate only
// requires strict increase, so gaps are harmless.
func (c *Central) sendAuthedFrame(ctx context.Context, scanner CentralScanner, payload []byte, key []byte) error {
	c.sendMu.Lock()
	defer c.sendMu.Unlock()
	c.mu.Lock()
	seq := c.localSeq
	c.localSeq++
	c.mu.Unlock()
	return scanner.WriteCommand(ctx, EncodeAuthedFrame(payload, seq, key))
}

// failConnection drops the GATT link and resets state + auth. Used by the
// listener's post-auth violation paths (bad MAC, seq replay) and the pre-auth
// policy gate: fail closed, let the client reconnect and re-handshake.
func (c *Central) failConnection() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.scanner.Disconnect()
	c.state = "disconnected"
	c.resetAuthLocked()
	slog.Warn("BLE connection dropped (authentication policy)")
}

// SetApiProvider injects the request handler used by ServeApiRequest. Required
// before ServeApiRequest will return data; calling it with nil disables request
// streaming (ServeApiRequest returns ErrNoApiProvider).
func (c *Central) SetApiProvider(p ApiProvider) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.apiProvider = p
}

// Scan discovers peripherals advertising serviceUUID. Respects ctx deadline.
func (c *Central) Scan(ctx context.Context) ([]Device, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.scanner.Scan(ctx, ServiceUUID)
}

// Connect establishes a GATT connection to the device id and immediately
// drives the Phase 9 (H-1a) mutual-challenge handshake over the fresh link:
//
//  1. PC sends v1 CmdAuthChallenge(dir=CentralToPeripheral, nonce1)
//  2. phone replies v1 CmdAuthResponse(nonce1, mac1); PC verifies mac1
//  3. phone sends v1 CmdAuthChallenge(dir=PeripheralToCentral, nonce2)
//  4. PC replies v1 CmdAuthResponse(nonce2, mac2)
//  5. authenticated=true; data frames are v2 (EncodeAuthedFrame /
//     DecodeAuthedFrame) with strictly-increasing seq per direction
//
// The handshake must complete within bleHandshakeTimeout; on timeout, a bad
// MAC, or any non-handshake v1 command the link is dropped and the error is
// returned (fail closed). With no auth key (open-auth mode) Connect refuses
// before touching the radio.
//
// Integration-point note: the adapter exposes no explicit "CCCD subscribed"
// callback — the only CCCD write happens inside WaitNotify's
// EnableNotifications. Connect is therefore the closest existing hook: it is
// where connection establishment is observed, and the handshake's first
// WaitNotify arm follows the challenge write back-to-back, so the peer's
// response cannot be lost to a long un-subscribed window. While the handshake
// runs, the handshaking flag pauses RunApiListener's own WaitNotify arms so
// the two never compete for the notify stream.
func (c *Central) Connect(ctx context.Context, id string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.resetAuthLocked()
	if len(c.authKey) == 0 {
		slog.Warn("BLE connect refused: no auth key configured (open-auth mode)")
		return ErrNoAuthKey
	}
	c.handshaking = true
	defer func() { c.handshaking = false }()
	if err := c.scanner.Connect(ctx, id); err != nil {
		return err
	}
	c.state = "connected"
	if err := c.handshakeLocked(ctx); err != nil {
		c.scanner.Disconnect()
		c.state = "disconnected"
		c.resetAuthLocked()
		slog.Warn("BLE auth handshake failed; connection dropped", "device", id, "error", err)
		return err
	}
	c.authenticated = true
	slog.Info("BLE channel authenticated", "device", id)
	return nil
}

// handshakeLocked runs the two-way challenge/response exchange. Caller holds
// mu (Connect). Every error is wrapped in ErrHandshakeFailed so callers can
// classify failures without matching on causes.
func (c *Central) handshakeLocked(ctx context.Context) error {
	hsCtx, cancel := context.WithTimeout(ctx, bleHandshakeTimeout)
	defer cancel()

	key := c.authKey
	nonce1 := make([]byte, 8)
	if _, err := rand.Read(nonce1); err != nil {
		return fmt.Errorf("%w: cannot read challenge nonce: %w", ErrHandshakeFailed, err)
	}

	// Pairing race (real-device Phase 9 finding): the phone issues
	// createBond() when our GATT connection lands, but LE Just Works pairing
	// takes 1-3s to encrypt the link. Until then the phone's stack rejects
	// our CCCD write with ATT 0x05 (insufficient authentication) — and the
	// challenge write itself is a write-without-response that the phone's
	// bond guard silently drops.
	//
	// Real-device sequencing race (found once encryption was optional): the
	// challenge write is issued BEFORE WaitNotify arms the CCCD subscription,
	// so a phone that answers instantly can fire its notification while our
	// CCCD is still unwritten — the stack drops it and nothing re-arrives.
	// Each attempt therefore gets its OWN notify deadline and re-writes the
	// challenge: on the second attempt the CCCD is long since armed and the
	// fresh response is guaranteed to land. The same nonce1 is reused, so a
	// late-arriving response to an earlier attempt still verifies.
	var pendingFrame []byte
	for attempt := 1; ; attempt++ {
		if err := hsCtx.Err(); err != nil {
			return fmt.Errorf("%w: %w", ErrHandshakeFailed, err)
		}
		err := c.scanner.WriteCommand(hsCtx, EncodeFrame(EncodeAuthChallengePayload(AuthDirCentralToPeripheral, nonce1)))
		if err == nil {
			attemptCtx, cancel := context.WithTimeout(hsCtx, handshakeAttemptNotifyWait)
			var raw []byte
			raw, err = c.scanner.WaitNotify(attemptCtx)
			cancel()
			if err == nil {
				pendingFrame = raw
				break
			}
		}
		if attempt >= handshakeAttempts || hsCtx.Err() != nil {
			return fmt.Errorf("%w: challenge exchange: %w", ErrHandshakeFailed, err)
		}
		slog.Warn("BLE handshake attempt produced no response; retrying with a fresh challenge",
			"attempt", attempt, "err", err)
		select {
		case <-hsCtx.Done():
			return fmt.Errorf("%w: %w", ErrHandshakeFailed, hsCtx.Err())
		case <-time.After(handshakeRetryBackoff):
		}
	}

	// Order-agnostic: accept the peer's response and its own challenge in
	// either arrival order; both must verify before authenticated is set.
	var peerVerified, repliedPeerChallenge bool
	for !(peerVerified && repliedPeerChallenge) {
		if err := hsCtx.Err(); err != nil {
			return fmt.Errorf("%w: %w", ErrHandshakeFailed, err)
		}
		var raw []byte
		if pendingFrame != nil {
			raw, pendingFrame = pendingFrame, nil
		} else {
			var err error
			raw, err = c.scanner.WaitNotify(hsCtx)
			if err != nil {
				return fmt.Errorf("%w: wait notify: %w", ErrHandshakeFailed, err)
			}
		}
		frame, ferr := DecodeFrame(raw)
		if ferr != nil {
			// Pre-auth only v1 frames carrying the two AUTH commands are
			// admissible (brief Step 4); anything else fails the link.
			return fmt.Errorf("%w: undecodable pre-auth frame: %w", ErrHandshakeFailed, ferr)
		}
		if len(frame.Payload) == 0 {
			continue
		}
		switch CmdID(frame.Payload[0]) {
		case CmdAuthResponse:
			rn, rm, derr := DecodeAuthResponsePayload(frame.Payload)
			if derr != nil {
				return fmt.Errorf("%w: malformed auth response: %w", ErrHandshakeFailed, derr)
			}
			if !bytes.Equal(rn, nonce1) {
				return fmt.Errorf("%w: auth response nonce mismatch", ErrHandshakeFailed)
			}
			if !hmac.Equal(rm, AuthResponseMAC(key, nonce1, AuthDirCentralToPeripheral)) {
				return fmt.Errorf("%w: auth response MAC mismatch (wrong key?)", ErrHandshakeFailed)
			}
			peerVerified = true
		case CmdAuthChallenge:
			dir, nonce2, derr := DecodeAuthChallengePayload(frame.Payload)
			if derr != nil {
				return fmt.Errorf("%w: malformed auth challenge: %w", ErrHandshakeFailed, derr)
			}
			if dir != AuthDirPeripheralToCentral {
				return fmt.Errorf("%w: unexpected challenge dir %#x", ErrHandshakeFailed, dir)
			}
			mac2 := AuthResponseMAC(key, nonce2, AuthDirPeripheralToCentral)
			if werr := c.scanner.WriteCommand(hsCtx, EncodeFrame(EncodeAuthResponsePayload(nonce2, mac2))); werr != nil {
				return fmt.Errorf("%w: response write: %w", ErrHandshakeFailed, werr)
			}
			repliedPeerChallenge = true
		default:
			return fmt.Errorf("%w: non-handshake command %#x before authentication", ErrHandshakeFailed, byte(CmdID(frame.Payload[0])))
		}
	}
	return nil
}

// Send writes payload to the Command characteristic and waits for a Notify
// response (echo). Returns the decoded echo payload. Requires an active,
// AUTHENTICATED connection (Phase 9 / H-1a): the write is a v2 authed frame
// (localSeq, strictly increasing) and the reply must decode as v2 with a
// strictly-increasing seq — a reply that fails MAC or replays a seq drops
// the connection.
//
// I-1: the seq reservation and radio write go through sendAuthedFrame
// (sendMu) so a concurrent ServeApiRequest chunk stream cannot interleave
// out of seq order with this write. The echo wait (echoCh, fed by the
// listener) happens AFTER sendMu is released — holding a radio wait under
// the write lock would stall unrelated chunk streams for the whole echo
// round-trip. The listener validated the reply's MAC and seq before
// routing it, so no re-validation happens here.
func (c *Central) Send(ctx context.Context, payload []byte) ([]byte, error) {
	c.mu.Lock()
	if c.state != "connected" {
		c.mu.Unlock()
		return nil, ErrNotConnected
	}
	if !c.authenticated || len(c.authKey) == 0 {
		c.mu.Unlock()
		return nil, ErrNotAuthenticated
	}
	key := c.authKey
	scanner := c.scanner
	c.mu.Unlock()

	if err := c.sendAuthedFrame(ctx, scanner, payload, key); err != nil {
		return nil, err
	}
	// The listener is the notify stream's sole consumer: it validates the
	// reply (MAC + strictly-increasing seq) and routes echo payloads here.
	// Stale echoes from an earlier Send are dropped by the buffer, so the
	// first frame delivered on this channel is ours.
	select {
	case resp := <-c.echoCh:
		return resp, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func (c *Central) Disconnect() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.scanner.Disconnect()
	c.state = "disconnected"
	c.resetAuthLocked()
}

func (c *Central) State() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.state
}

// ServeApiRequest handles a single CMD_API_REQ received via the State
// characteristic. notifyPayload is the decoded frame payload (the bytes after
// the physical header, seq and MAC already verified by the receive gate). The
// method decodes the request via DecodeApiReqPayload, fetches the JSON
// response bytes from the injected ApiProvider, splits them into MTU-safe
// CMD_JSON_CHUNK frames via ChunkJsonBytes, and writes each frame to the
// Command characteristic via WriteCommand as a v2 authed frame with a
// strictly-increasing localSeq (Phase 9 / H-1a).
//
// The caller is responsible for obtaining notifyPayload (typically by calling
// WaitNotify + the authenticated decode path). This keeps the Central's
// one-shot WaitNotify contract intact: a long-lived subscription loop belongs
// to the adapter that owns the BLE notification handler, not to this
// serialized facade. Returns the number of chunk frames written.
//
// Provider errors are surfaced to the caller with n==0 (no frames written) —
// the stream is aborted before the first WriteCommand so the receiver does not
// observe a partial response. WriteCommand errors mid-stream return the count
// written so far plus the error; the receiver is expected to time out and
// re-request.
func (c *Central) ServeApiRequest(ctx context.Context, notifyPayload []byte) (int, error) {
	// DecodeApiReqPayload's contract: any malformed payload (short, or leading
	// byte != CmdApiReq) yields a non-nil error with zero return values. The
	// error return below is the sole validation gate.
	endpoint, path, index, err := DecodeApiReqPayload(notifyPayload)
	if err != nil {
		return 0, err
	}

	c.mu.Lock()
	provider := c.apiProvider
	connected := c.state == "connected"
	authenticated := c.authenticated && len(c.authKey) > 0
	authKey := c.authKey
	scanner := c.scanner
	c.mu.Unlock()

	if provider == nil {
		return 0, ErrNoApiProvider
	}
	if !connected {
		return 0, ErrNotConnected
	}
	if !authenticated {
		return 0, ErrNotAuthenticated
	}

	jsonBytes, err := provider.HandleBleRequest(ctx, endpoint, path, index)
	if err != nil {
		return 0, err
	}

	chunkPayloads, _, err := ChunkJsonBytes(jsonBytes)
	if err != nil {
		return 0, err
	}

	written := 0
	for _, payload := range chunkPayloads {
		// Each chunk goes out as a v2 authed frame via sendAuthedFrame: the
		// seq reservation and the radio write are one critical section (I-1
		// fix), so concurrent per-request dispatch goroutines always put
		// frames on the wire in strictly increasing seq order. A failed write
		// consumes its seq, leaving a gap — harmless for the strict-increase
		// receive gate. Errors abort the stream: a partial write leaves the
		// receiver to time out and re-request; we don't try to resume
		// mid-stream.
		if werr := c.sendAuthedFrame(ctx, scanner, payload, authKey); werr != nil {
			slog.Warn("BLE JSON chunk write failed",
				"endpoint", endpoint, "path", path, "index", index,
				"written", written, "total", len(chunkPayloads), "error", werr)
			return written, werr
		}
		written++
	}
	return written, nil
}

// apiListenerRetryBackoff is the production retry cadence for RunApiListener's
// WaitNotify loop. In production the listener is started in server.New() BEFORE
// any BLE client connects, so tinyGoCentralScanner's WaitNotify returns
// errNoStateChar immediately (non-blocking) on every call until a client POSTs
// /api/v1/ble/connect. Without a backoff the goroutine would busy-spin,
// flooding logs and burning CPU from startup until first connect. One second is
// cheap on CPU, bounded on log volume (~1 line/s), and well below the user-
// perceived connect latency once a client actually shows up (the loop exits the
// retry path on the first successful WaitNotify).
const apiListenerRetryBackoff = 1 * time.Second

// RunApiListener is the long-lived CMD_API_REQ dispatcher mandated by spec
// §3.1. It loops over the scanner's one-shot WaitNotify, decodes each notified
// frame, and — when the leading CmdID is CmdApiReq (0x11) — hands the payload
// to ServeApiRequest in a goroutine so one slow request cannot stall the next.
//
// The loop returns when ctx is cancelled, when no ApiProvider is configured at
// startup (returns ErrNoApiProvider immediately — without a provider there is
// nothing to serve), or when WaitNotify returns a non-context transient error
// (logged at Debug and retried after an apiListenerRetryBackoff sleep — the
// adapter's EnableNotifications contract is one-shot per call, so a retry
// simply re-arms it; the backoff keeps the goroutine from busy-spinning/log-
// flooding before the first client connects).
//
// This method does NOT own the BLE connection lifecycle: the caller (server
// startup) Connects once before invoking RunApiListener, and Disconnects on
// shutdown via ctx cancellation. The listener is intentionally minimal — no
// subscription abstraction, no per-request routing table — matching the
// existing WaitNotify one-shot contract in central_adapter.go.
func (c *Central) RunApiListener(ctx context.Context) error {
	return c.runApiListener(ctx, apiListenerRetryBackoff)
}

// runApiListener is the testable core of RunApiListener: identical to
// RunApiListener but with the retry backoff injected so unit tests can exercise
// the sleep path with a real-but-tiny value instead of waiting a full second
// per iteration. The backoff applies ONLY to the transient-error (retry)
// branch; the success path, the frame-decode continue paths, and the ctx-
// cancelled exit path all return/retry immediately, unchanged.
func (c *Central) runApiListener(ctx context.Context, retryBackoff time.Duration) error {
	c.mu.Lock()
	provider := c.apiProvider
	c.mu.Unlock()
	if provider == nil {
		return ErrNoApiProvider
	}

	slog.Info("BLE API listener started")
	for {
		if err := ctx.Err(); err != nil {
			slog.Info("BLE API listener exiting (ctx cancelled)", "error", err)
			return err
		}

		// Phase 9 (H-1a): while Connect drives the handshake it owns the
		// notify stream (the adapter's EnableNotifications is one-shot per
		// WaitNotify call — a second arm here would steal/replace the
		// handshake's CCCD handler). Poll until the handshake resolves.
		c.mu.Lock()
		handshaking := c.handshaking
		c.mu.Unlock()
		if handshaking {
			select {
			case <-time.After(authListenerHandshakePoll):
			case <-ctx.Done():
				return ctx.Err()
			}
			continue
		}

		// Sole consumer of the notify stream (see Send): dequeues are never
		// raced, and every frame is routed to its destination here.
		raw, err := c.scanner.WaitNotify(ctx)
		if err != nil {
			if ctx.Err() != nil {
				slog.Info("BLE API listener exiting (ctx cancelled during WaitNotify)", "error", err)
				return ctx.Err()
			}
			// Transient error (adapter reset, transient GATT fault, or — the
			// common production case — no Central connected yet, so
			// EnableNotifications reports errNoStateChar). Debug, not Warn: at
			// startup this fires once per retryBackoff until a client connects,
			// and a Warn line per second is noise the operator cannot act on.
			// The retryBackoff sleep below is load-bearing: without it the
			// goroutine would busy-spin and flood logs/CPU from server.New()
			// until the first BLE client POSTs /api/v1/ble/connect.
			slog.Debug("BLE API listener WaitNotify error; retrying", "error", err)
			select {
			case <-time.After(retryBackoff):
			case <-ctx.Done():
				return ctx.Err()
			}
			continue
		}
		c.handleNotifyFrame(ctx, raw)
	}
}

// handleNotifyFrame routes one raw notification through the Phase 9 (H-1a)
// receive policy:
//
//   - !authenticated: only v1 frames carrying the two AUTH commands are
//     admissible. The handshake itself is owned by Connect (under the
//     handshaking pause), so an auth frame arriving here is a raced/stale
//     notification and is dropped; any OTHER command pre-auth is a protocol
//     violation and drops the link (no data phase before authentication).
//   - authenticated: the frame must decode as v2 (DecodeAuthedFrame — a bad
//     MAC / wrong structure drops the link) and pass the strictly-increasing
//     remoteSeq gate (rollback/replay drops the link with ErrReplaySeq).
//
// Surviving CMD_API_REQ payloads are dispatched to ServeApiRequest in a
// goroutine, as before.
func (c *Central) handleNotifyFrame(ctx context.Context, raw []byte) {
	c.mu.Lock()
	authenticated := c.authenticated
	authKey := c.authKey
	c.mu.Unlock()

	if !authenticated {
		frame, ferr := DecodeFrame(raw)
		if ferr != nil {
			slog.Warn("BLE API listener: dropped undecodable pre-auth frame", "error", ferr)
			return
		}
		if len(frame.Payload) == 0 {
			return
		}
		if cmd := CmdID(frame.Payload[0]); cmd == CmdAuthChallenge || cmd == CmdAuthResponse {
			// Handshake-owner is Connect; these raced past its window.
			slog.Debug("BLE API listener: dropped stale handshake frame")
			return
		}
		slog.Warn("BLE API listener: non-handshake command before authentication; dropping link",
			"cmd", byte(frame.Payload[0]))
		c.failConnection()
		return
	}

	payload, seq, aerr := DecodeAuthedFrame(raw, authKey)
	if aerr != nil {
		slog.Warn("BLE API listener: v2 frame failed authentication; dropping link", "error", aerr)
		c.failConnection()
		return
	}
	c.mu.Lock()
	rerr := c.acceptRemoteSeqLocked(seq)
	c.mu.Unlock()
	if rerr != nil {
		slog.Warn("BLE API listener: seq rollback/replay rejected; dropping link", "seq", seq, "error", rerr)
		c.failConnection()
		return
	}
	if len(payload) == 0 {
		return
	}
	if CmdID(payload[0]) != CmdApiReq {
		// Echo reply for a waiting Send — the echo protocol mirrors the raw
		// payload we wrote (no command prefix), so anything authenticated
		// that is not an API request is an echo. The listener is the sole
		// consumer, so no one else can starve the delivery; stale replies
		// with no waiter are dropped by the buffer.
		select {
		case c.echoCh <- payload:
		default:
		}
		return
	}
	// Dispatch in a goroutine so a slow request (large book, disk I/O)
	// does not block subsequent requests on the same notify stream.
	// ServeApiRequest re-checks connection + auth state under the Central
	// lock, so racing a Disconnect is safe (it returns ErrNotConnected /
	// ErrNotAuthenticated).
	reqPayload := append([]byte(nil), payload...)
	go func() {
		n, sErr := c.ServeApiRequest(ctx, reqPayload)
		if sErr != nil {
			slog.Warn("BLE API listener: ServeApiRequest failed", "error", sErr, "written", n)
		}
	}()
}

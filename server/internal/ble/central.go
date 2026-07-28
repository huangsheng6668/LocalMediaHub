package ble

import (
	"context"
	"errors"
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
	return &Central{scanner: s, state: "disconnected"}
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

// ServeApiRequest handles a single CMD_API_REQ received via the State
// characteristic. notifyPayload is the decoded frame payload (the bytes after
// the 3-byte physical header). The method decodes the request via
// DecodeApiReqPayload, fetches the JSON response bytes from the injected
// ApiProvider, splits them into MTU-safe CMD_JSON_CHUNK frames via
// ChunkJsonBytes, and writes each frame to the Command characteristic via
// WriteCommand.
//
// The caller is responsible for obtaining notifyPayload (typically by calling
// WaitNotify + DecodeFrame). This keeps the Central's one-shot WaitNotify
// contract intact: a long-lived subscription loop belongs to the adapter that
// owns the BLE notification handler, not to this serialized facade. Returns the
// number of chunk frames written.
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
	scanner := c.scanner
	c.mu.Unlock()

	if provider == nil {
		return 0, ErrNoApiProvider
	}
	if !connected {
		return 0, ErrNotConnected
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
		// WriteCommand expects a frame-encoded payload (3-byte header + payload).
		// Errors abort the stream: a partial write leaves the receiver to time
		// out and re-request; we don't try to resume mid-stream.
		if werr := scanner.WriteCommand(ctx, EncodeFrame(payload)); werr != nil {
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
		frame, ferr := DecodeFrame(raw)
		if ferr != nil {
			slog.Warn("BLE API listener: dropped undecodable frame", "error", ferr)
			continue
		}
		if len(frame.Payload) == 0 {
			continue
		}
		if CmdID(frame.Payload[0]) != CmdApiReq {
			// Non-API notifications (e.g. echo replies from the connectivity
			// loop) are ignored by the listener; the connection-verification
			// path uses Send() directly.
			continue
		}
		// Dispatch in a goroutine so a slow request (large book, disk I/O)
		// does not block subsequent requests on the same notify stream.
		// ServeApiRequest re-checks connection + provider state under the
		// Central lock, so racing a Disconnect is safe (it returns
		// ErrNotConnected).
		payload := append([]byte(nil), frame.Payload...)
		go func() {
			n, sErr := c.ServeApiRequest(ctx, payload)
			if sErr != nil {
				slog.Warn("BLE API listener: ServeApiRequest failed", "error", sErr, "written", n)
			}
		}()
	}
}

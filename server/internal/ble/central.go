package ble

import (
	"context"
	"errors"
	"log/slog"
	"sync"

	"github.com/localmediahub/server/internal/service/bookparser"
)

// ErrNotConnected is returned when an operation requires an active BLE
// connection but none exists.
var ErrNotConnected = errors.New("ble: not connected")

// ErrNoChapterProvider is returned by ServeChapterRequest when no
// ChapterProvider has been injected via SetChapterProvider.
var ErrNoChapterProvider = errors.New("ble: chapter provider not configured")

// Device is a discovered BLE peripheral.
type Device struct {
	ID   string `json:"id"`
	Name string `json:"name"`
	RSSI int    `json:"rssi"`
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
	mu       sync.Mutex
	scanner  CentralScanner
	state    string // "disconnected" | "connected"
	chapters ChapterProvider
}

// ChapterProvider returns the ordered blocks for a chapter. service.BookService
// satisfies this structurally; the interface lives in package ble so the BLE
// layer has no compile-time dependency on package service (avoids a cycle and
// keeps the listener unit-testable with a stub).
type ChapterProvider interface {
	GetChapterBlocks(ctx context.Context, path string, idx int, clientIP string) ([]bookparser.Block, error)
}

func NewCentral(s CentralScanner) *Central {
	return &Central{scanner: s, state: "disconnected"}
}

// SetChapterProvider injects the chapter source used by ServeChapterRequest.
// Required before ServeChapterRequest will return data; calling it with nil
// disables chapter streaming (ServeChapterRequest returns ErrNoChapterProvider).
func (c *Central) SetChapterProvider(p ChapterProvider) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.chapters = p
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

// ServeChapterRequest handles a single CMD_BOOK_CHAPTER_REQ received via the
// State characteristic. notifyPayload is the decoded frame payload (the bytes
// after the 3-byte physical header). The method decodes the request, fetches
// the chapter blocks from the injected ChapterProvider, splits them into
// MTU-safe CMD_BOOK_CHAPTER_CHUNK frames, and writes each frame to the Command
// characteristic via WriteCommand.
//
// The caller is responsible for obtaining notifyPayload (typically by calling
// WaitNotify + DecodeFrame). This keeps the Central's one-shot WaitNotify
// contract intact: a long-lived subscription loop belongs to the adapter that
// owns the BLE notification handler, not to this serialized facade. Returns
// the number of chunk frames written.
func (c *Central) ServeChapterRequest(ctx context.Context, notifyPayload []byte, clientIP string) (int, error) {
	// DecodeBookChapterReqPayload's contract: any malformed payload (short,
	// or leading byte != CmdBookChapterReq) yields a non-nil error and a zero
	// CmdID. The error return below is the sole validation gate — a separate
	// `cmdID != CmdBookChapterReq` re-check used to live here but was dead
	// code (unreachable after the err != nil return).
	cmdID, path, idx, err := DecodeBookChapterReqPayload(notifyPayload)
	if err != nil {
		return 0, err
	}
	_ = cmdID // validated inside the decoder; nothing to re-check here

	c.mu.Lock()
	provider := c.chapters
	connected := c.state == "connected"
	scanner := c.scanner
	c.mu.Unlock()

	if provider == nil {
		return 0, ErrNoChapterProvider
	}
	if !connected {
		return 0, ErrNotConnected
	}

	blocks, err := provider.GetChapterBlocks(ctx, path, idx, clientIP)
	if err != nil {
		return 0, err
	}

	chunkPayloads, _, err := ChunkChapterBlocks(blocks)
	if err != nil {
		return 0, err
	}

	written := 0
	for _, payload := range chunkPayloads {
		// WriteCommand expects a frame-encoded payload (3-byte header + payload).
		// Errors abort the stream: a partial write leaves the receiver to time
		// out and re-request; we don't try to resume mid-stream.
		if werr := scanner.WriteCommand(ctx, EncodeFrame(payload)); werr != nil {
			slog.Warn("BLE chapter chunk write failed",
				"path", path, "chapter", idx,
				"written", written, "total", len(chunkPayloads), "error", werr)
			return written, werr
		}
		written++
	}
	return written, nil
}

// RunChapterListener is the long-lived CMD_BOOK_CHAPTER_REQ dispatcher
// mandated by spec §3.1. It loops over the scanner's one-shot WaitNotify,
// decodes each notified frame, and — when the leading CmdID is
// CmdBookChapterReq (0x11) — hands the payload to ServeChapterRequest in a
// goroutine so one slow chapter fetch cannot stall the next request.
//
// The loop returns when ctx is cancelled, when WaitNotify returns a
// non-context error (logged and the loop continues — the adapter's
// EnableNotifications contract is one-shot per call, so we just retry), or
// when no ChapterProvider is configured at startup (returns
// ErrNoChapterProvider immediately — without a provider there is nothing to
// serve).
//
// This method does NOT own the BLE connection lifecycle: the caller (server
// startup) Connects once before invoking RunChapterListener, and Disconnects
// on shutdown via ctx cancellation. The listener is intentionally minimal —
// no subscription abstraction, no per-request routing table — matching the
// existing WaitNotify one-shot contract in central_adapter.go.
func (c *Central) RunChapterListener(ctx context.Context) error {
	c.mu.Lock()
	provider := c.chapters
	c.mu.Unlock()
	if provider == nil {
		return ErrNoChapterProvider
	}

	slog.Info("BLE chapter listener started")
	for {
		if err := ctx.Err(); err != nil {
			slog.Info("BLE chapter listener exiting (ctx cancelled)", "error", err)
			return err
		}
		raw, err := c.scanner.WaitNotify(ctx)
		if err != nil {
			if ctx.Err() != nil {
				slog.Info("BLE chapter listener exiting (ctx cancelled during WaitNotify)", "error", err)
				return ctx.Err()
			}
			// Transient error (adapter reset, transient GATT fault). Log and
			// retry on the next iteration — the adapter's WaitNotify is
			// one-shot, so a retry simply re-arms EnableNotifications.
			slog.Warn("BLE chapter listener WaitNotify error; retrying", "error", err)
			continue
		}
		frame, ferr := DecodeFrame(raw)
		if ferr != nil {
			slog.Warn("BLE chapter listener: dropped undecodable frame", "error", ferr)
			continue
		}
		if len(frame.Payload) == 0 {
			continue
		}
		if CmdID(frame.Payload[0]) != CmdBookChapterReq {
			// Non-chapter notifications (e.g. echo replies from the
			// connectivity loop) are ignored by the listener; the
			// connection-verification path uses Send() directly.
			continue
		}
		// Dispatch in a goroutine so a slow chapter fetch (large book, disk
		// I/O) does not block subsequent requests on the same notify stream.
		// ServeChapterRequest re-checks connection + provider state under the
		// Central lock, so racing a Disconnect is safe (it returns
		// ErrNotConnected).
		payload := append([]byte(nil), frame.Payload...)
		go func() {
			n, sErr := c.ServeChapterRequest(ctx, payload, "")
			if sErr != nil {
				slog.Warn("BLE chapter listener: ServeChapterRequest failed", "error", sErr, "written", n)
			}
		}()
	}
}

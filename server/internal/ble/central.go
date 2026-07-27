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

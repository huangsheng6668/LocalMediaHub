package ble

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/localmediahub/server/internal/service/bookparser"
)

// collectScanner is a CentralScanner fake that records every WriteCommand
// frame so ServeChapterRequest's multi-chunk stream can be inspected. It
// mirrors fakeScanner's shape (central_test.go) but accumulates writes
// instead of overwriting the last one.
type collectScanner struct {
	mu       sync.Mutex
	written  [][]byte
	writeErr error
}

func (c *collectScanner) Scan(context.Context, string) ([]Device, error) {
	return nil, nil
}
func (c *collectScanner) Connect(context.Context, string) error { return nil }
func (c *collectScanner) Disconnect()                           {}
func (c *collectScanner) WriteCommand(_ context.Context, payload []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.writeErr != nil {
		return c.writeErr
	}
	c.written = append(c.written, append([]byte(nil), payload...))
	return nil
}
func (c *collectScanner) WaitNotify(context.Context) ([]byte, error) {
	return nil, errors.New("not used in this test")
}

// stubChapterProvider is a minimal ChapterProvider for unit testing.
type stubChapterProvider struct {
	blocks []bookparser.Block
	err    error
	last   struct {
		path string
		idx  int
		ip   string
	}
}

func (s *stubChapterProvider) GetChapterBlocks(_ context.Context, path string, idx int, ip string) ([]bookparser.Block, error) {
	s.last.path = path
	s.last.idx = idx
	s.last.ip = ip
	return s.blocks, s.err
}

func TestServeChapterRequestStreamsChunks(t *testing.T) {
	// 30 blocks at ~64 bytes each forces multiple chunks at the 244-byte cap.
	blocks := make([]bookparser.Block, 30)
	for i := range blocks {
		blocks[i] = bookparser.Block{Type: "text", Value: "padding-padding-padding-padding-padding"}
	}
	provider := &stubChapterProvider{blocks: blocks}
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	c.SetChapterProvider(provider)
	_ = c.Connect(context.Background(), "AA:BB")

	req, encErr := EncodeBookChapterReqPayload("/books/novel.txt", 4)
	if encErr != nil {
		t.Fatalf("EncodeBookChapterReqPayload: %v", encErr)
	}
	written, err := c.ServeChapterRequest(context.Background(), req, "10.0.0.5")
	if err != nil {
		t.Fatalf("ServeChapterRequest: %v", err)
	}
	if written < 2 {
		t.Fatalf("expected multiple chunks, got %d", written)
	}
	if written != len(scanner.written) {
		t.Fatalf("written=%d but scanner recorded %d frames", written, len(scanner.written))
	}

	// Provider was called with the decoded args.
	if provider.last.path != "/books/novel.txt" || provider.last.idx != 4 || provider.last.ip != "10.0.0.5" {
		t.Fatalf("provider called with path=%q idx=%d ip=%q",
			provider.last.path, provider.last.idx, provider.last.ip)
	}

	// Every written frame is a valid CMD_BOOK_CHAPTER_CHUNK frame whose
	// payload fits in the MTU and whose ChunkIndex is its position in the stream.
	total := -1
	for i, raw := range scanner.written {
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d decode error: %v", i, derr)
		}
		if len(frame.Payload) > maxPayloadLen {
			t.Fatalf("frame %d payload %d > max %d", i, len(frame.Payload), maxPayloadLen)
		}
		tot, cidx, totalBlocks, _, perr := DecodeBookChapterChunkPayload(frame.Payload)
		if perr != nil {
			t.Fatalf("frame %d chunk decode error: %v", i, perr)
		}
		if total == -1 {
			total = tot
		} else if tot != total {
			t.Fatalf("frame %d TotalChunks %d differs from earlier %d", i, tot, total)
		}
		if cidx != i {
			t.Fatalf("frame %d has ChunkIndex %d", i, cidx)
		}
		if totalBlocks != len(blocks) {
			t.Fatalf("frame %d TotalBlocks %d want %d", i, totalBlocks, len(blocks))
		}
	}
	if total != written {
		t.Fatalf("TotalChunks=%d but wrote %d frames", total, written)
	}
}

func TestServeChapterRequestRequiresProvider(t *testing.T) {
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	_ = c.Connect(context.Background(), "AA:BB")
	req, _ := EncodeBookChapterReqPayload("/books/x", 0)
	_, err := c.ServeChapterRequest(context.Background(), req, "")
	if err != ErrNoChapterProvider {
		t.Fatalf("expected ErrNoChapterProvider, got %v", err)
	}
}

func TestServeChapterRequestRequiresConnection(t *testing.T) {
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	c.SetChapterProvider(&stubChapterProvider{})
	req, _ := EncodeBookChapterReqPayload("/books/x", 0)
	_, err := c.ServeChapterRequest(context.Background(), req, "")
	if err != ErrNotConnected {
		t.Fatalf("expected ErrNotConnected, got %v", err)
	}
}

func TestServeChapterRequestRejectsWrongCmdID(t *testing.T) {
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	c.SetChapterProvider(&stubChapterProvider{})
	_ = c.Connect(context.Background(), "AA:BB")
	// A well-formed chapter-request payload (satisfies the length check) but
	// whose leading byte is not CmdBookChapterReq. The decoder returns the
	// zero CmdID and ErrBadCmdID; ServeChapterRequest surfaces the error
	// without re-checking cmdID (that re-check was dead code, now removed).
	bad, _ := EncodeBookChapterReqPayload("/x", 0)
	bad[0] = byte(CmdEcho)
	_, err := c.ServeChapterRequest(context.Background(), bad, "")
	if err != ErrBadCmdID {
		t.Fatalf("expected ErrBadCmdID, got %v", err)
	}
}

// scriptedScanner is a CentralScanner fake whose WaitNotify returns a fixed
// sequence of prebuilt frames, then blocks until the test unblocks it (or the
// listener ctx is cancelled, which it surfaces to RunChapterListener as
// context.Canceled so the loop exits cleanly).
type scriptedScanner struct {
	collectScanner
	frames    [][]byte // prebuilt physical frames WaitNotify returns in order
	nextIdx   int
	doneCh    chan struct{} // closed by WaitNotify once frames are exhausted
	unblockCh chan struct{} // test closes this to unblock the final WaitNotify
}

func (s *scriptedScanner) WaitNotify(ctx context.Context) ([]byte, error) {
	if s.nextIdx < len(s.frames) {
		f := s.frames[s.nextIdx]
		s.nextIdx++
		return f, nil
	}
	// Frames exhausted: signal the test, then block until either the test
	// unblocks us or the listener ctx is cancelled (RunChapterListener's
	// caller cancels ctx on shutdown). Returning context.Canceled makes the
	// loop's WaitNotify-error path re-check ctx.Err() and exit cleanly.
	select {
	case <-s.doneCh:
	default:
		close(s.doneCh)
	}
	select {
	case <-s.unblockCh:
		return nil, context.Canceled
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

// TestRunChapterListenerDispatchesChapterRequests exercises the long-lived
// listener loop (spec §3.1) end-to-end: a stub scanner yields two
// CMD_BOOK_CHAPTER_REQ frames; RunChapterListener must decode each and hand
// it to ServeChapterRequest, which writes the expected CMD_BOOK_CHAPTER_CHUNK
// frames via WriteCommand. Verifies the multi-goroutine dispatch path and the
// non-chapter frame ignore path.
func TestRunChapterListenerDispatchesChapterRequests(t *testing.T) {
	blocks := []bookparser.Block{
		{Type: "text", Value: "hello-listener"},
	}
	provider := &stubChapterProvider{blocks: blocks}
	scanner := &scriptedScanner{
		doneCh:    make(chan struct{}),
		unblockCh: make(chan struct{}),
	}
	// Build two physical chapter-request frames (encoded as the Android
	// Peripheral would notify them) plus one non-chapter frame the listener
	// MUST silently ignore.
	req1, _ := EncodeBookChapterReqPayload("/books/a.txt", 1)
	req2, _ := EncodeBookChapterReqPayload("/books/b.txt", 2)
	echo := EncodeFrame([]byte{byte(CmdEcho), 0xAA})
	scanner.frames = [][]byte{
		EncodeFrame(req1),
		echo,
		EncodeFrame(req2),
	}

	c := NewCentral(scanner)
	c.SetChapterProvider(provider)
	_ = c.Connect(context.Background(), "AA:BB")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	listenerDone := make(chan struct{})
	go func() {
		_ = c.RunChapterListener(ctx)
		close(listenerDone)
	}()

	// Wait until the scanner has yielded all scripted frames.
	<-scanner.doneCh
	// The listener dispatched each chapter request in its own goroutine; wait
	// for BOTH ServeChapterRequest calls to finish writing their chunk frames
	// before cancelling. Cancelling mid-dispatch would abort an in-flight
	// WriteCommand and under-count writes (the production listener detaches
	// each request so a shutdown doesn't truncate a chapter mid-stream).
	waitForWrites := func(n int) {
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			scanner.collectScanner.mu.Lock()
			got := len(scanner.collectScanner.written)
			scanner.collectScanner.mu.Unlock()
			if got >= n {
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
	}
	waitForWrites(2)
	// Allow the listener's final WaitNotify to return (so the goroutine can
	// exit once ctx is cancelled).
	defer close(scanner.unblockCh)
	cancel()
	<-listenerDone

	// The listener must have dispatched both chapter requests to
	// ServeChapterRequest, which writes the chunk frames via WriteCommand.
	// The echo frame must NOT produce any write (the listener ignores it).
	scanner.collectScanner.mu.Lock()
	written := len(scanner.collectScanner.written)
	scanner.collectScanner.mu.Unlock()
	if written < 2 {
		t.Fatalf("expected >=2 chunk writes (one per chapter req), got %d", written)
	}

	// Every written frame must be a CMD_BOOK_CHAPTER_CHUNK payload.
	for i, raw := range scanner.collectScanner.written {
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d decode: %v", i, derr)
		}
		_, _, _, _, perr := DecodeBookChapterChunkPayload(frame.Payload)
		if perr != nil {
			t.Fatalf("frame %d chunk decode: %v", i, perr)
		}
	}
}

// TestRunChapterListenerRequiresProvider verifies the startup gate: with no
// ChapterProvider injected, RunChapterListener refuses to start.
func TestRunChapterListenerRequiresProvider(t *testing.T) {
	scanner := &scriptedScanner{
		doneCh:    make(chan struct{}),
		unblockCh: make(chan struct{}),
	}
	c := NewCentral(scanner)
	_ = c.Connect(context.Background(), "AA:BB")
	if err := c.RunChapterListener(context.Background()); err != ErrNoChapterProvider {
		t.Fatalf("expected ErrNoChapterProvider, got %v", err)
	}
}

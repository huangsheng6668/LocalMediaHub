package ble

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"
)

// collectScanner is a CentralScanner fake that records every WriteCommand
// frame so ServeApiRequest's multi-chunk stream can be inspected. It mirrors
// fakeScanner's shape (central_test.go) but accumulates writes instead of
// overwriting the last one.
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

// jsonBlockProvider is a minimal ApiProvider that always returns a fixed JSON
// body, recording the last (path, idx, ip) it was called with. Used to exercise
// ServeApiRequest's multi-chunk stream path without depending on service.BookService.
type jsonBlockProvider struct {
	body []byte
	err  error
	last struct {
		ep   byte
		path string
		idx  int
	}
}

func (p *jsonBlockProvider) HandleBleRequest(_ context.Context, endpoint byte, path string, index int) ([]byte, error) {
	p.last.ep = endpoint
	p.last.path = path
	p.last.idx = index
	return p.body, p.err
}

// TestServeApiRequestStreamsMultipleChunks exercises the legacy multi-chunk
// streaming behaviour (formerly TestServeChapterRequestStreamsChunks) via the
// generalized ApiProvider surface: a large JSON body forces multiple chunks at
// the 200-byte spec ceiling, every chunk must decode as a CMD_JSON_CHUNK frame
// with sequential ChunkIndex and a stable TotalChunks count.
func TestServeApiRequestStreamsMultipleChunks(t *testing.T) {
	// ~2KB JSON body forces ~12 chunks at the 200-byte spec §1.2 cap.
	body := make([]byte, 2048)
	for i := range body {
		body[i] = 'x'
	}
	provider := &jsonBlockProvider{body: body}
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	c.SetApiProvider(provider)
	_ = c.Connect(context.Background(), "AA:BB")

	req, encErr := EncodeApiReqPayload(EndpointBookChapter, "/books/novel.txt", 4)
	if encErr != nil {
		t.Fatalf("EncodeApiReqPayload: %v", encErr)
	}
	written, err := c.ServeApiRequest(context.Background(), req, "10.0.0.5")
	if err != nil {
		t.Fatalf("ServeApiRequest: %v", err)
	}
	if written < 2 {
		t.Fatalf("expected multiple chunks, got %d", written)
	}
	if written != len(scanner.written) {
		t.Fatalf("written=%d but scanner recorded %d frames", written, len(scanner.written))
	}

	// Provider was called with the decoded args.
	if provider.last.ep != EndpointBookChapter ||
		provider.last.path != "/books/novel.txt" ||
		provider.last.idx != 4 {
		t.Fatalf("provider called with ep=%#x path=%q idx=%d",
			provider.last.ep, provider.last.path, provider.last.idx)
	}

	// Every written frame is a valid CMD_JSON_CHUNK frame whose payload fits
	// in the MTU and whose ChunkIndex is its position in the stream.
	total := -1
	for i, raw := range scanner.written {
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d decode error: %v", i, derr)
		}
		if len(frame.Payload) > maxPayloadLen {
			t.Fatalf("frame %d payload %d > max %d", i, len(frame.Payload), maxPayloadLen)
		}
		tot, cidx, totalBytes, _, perr := DecodeJsonChunkPayload(frame.Payload)
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
		if totalBytes != len(body) {
			t.Fatalf("frame %d TotalBytes %d want %d", i, totalBytes, len(body))
		}
	}
	if total != written {
		t.Fatalf("TotalChunks=%d but wrote %d frames", total, written)
	}
}

// scriptedScanner is a CentralScanner fake whose WaitNotify returns a fixed
// sequence of prebuilt frames, then blocks until the test unblocks it (or the
// listener ctx is cancelled, which it surfaces to RunApiListener as
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
	// unblocks us or the listener ctx is cancelled (RunApiListener's caller
	// cancels ctx on shutdown). Returning context.Canceled makes the loop's
	// WaitNotify-error path re-check ctx.Err() and exit cleanly.
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

// TestRunApiListenerDispatchesApiRequests exercises the long-lived listener
// loop (spec §3.1) end-to-end: a stub scanner yields two CMD_API_REQ frames;
// RunApiListener must decode each and hand it to ServeApiRequest, which writes
// the expected CMD_JSON_CHUNK frames via WriteCommand. Verifies the multi-
// goroutine dispatch path and the non-CMD_API_REQ frame ignore path.
func TestRunApiListenerDispatchesApiRequests(t *testing.T) {
	provider := &jsonBlockProvider{body: []byte(`hello-listener`)}
	scanner := &scriptedScanner{
		doneCh:    make(chan struct{}),
		unblockCh: make(chan struct{}),
	}
	// Build two physical CMD_API_REQ frames (encoded as the Android Peripheral
	// would notify them) plus one non-API frame the listener MUST silently
	// ignore.
	req1, _ := EncodeApiReqPayload(EndpointBookChapter, "/books/a.txt", 1)
	req2, _ := EncodeApiReqPayload(EndpointBookChapter, "/books/b.txt", 2)
	echo := EncodeFrame([]byte{byte(CmdEcho), 0xAA})
	scanner.frames = [][]byte{
		EncodeFrame(req1),
		echo,
		EncodeFrame(req2),
	}

	c := NewCentral(scanner)
	c.SetApiProvider(provider)
	_ = c.Connect(context.Background(), "AA:BB")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	listenerDone := make(chan struct{})
	go func() {
		_ = c.RunApiListener(ctx)
		close(listenerDone)
	}()

	// Wait until the scanner has yielded all scripted frames.
	<-scanner.doneCh
	// The listener dispatched each request in its own goroutine; wait for BOTH
	// ServeApiRequest calls to finish writing their chunk frames before
	// cancelling. Cancelling mid-dispatch would abort an in-flight WriteCommand
	// and under-count writes (the production listener detaches each request so
	// a shutdown doesn't truncate a response mid-stream).
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

	// The listener must have dispatched both requests to ServeApiRequest,
	// which writes the chunk frames via WriteCommand. The echo frame must NOT
	// produce any write (the listener ignores it).
	scanner.collectScanner.mu.Lock()
	written := len(scanner.collectScanner.written)
	scanner.collectScanner.mu.Unlock()
	if written < 2 {
		t.Fatalf("expected >=2 chunk writes (one per request), got %d", written)
	}

	// Every written frame must be a CMD_JSON_CHUNK payload.
	for i, raw := range scanner.collectScanner.written {
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d decode: %v", i, derr)
		}
		_, _, _, _, perr := DecodeJsonChunkPayload(frame.Payload)
		if perr != nil {
			t.Fatalf("frame %d chunk decode: %v", i, perr)
		}
	}
}

// TestRunApiListenerRequiresProvider verifies the startup gate: with no
// ApiProvider injected, RunApiListener refuses to start.
func TestRunApiListenerRequiresProvider(t *testing.T) {
	scanner := &scriptedScanner{
		doneCh:    make(chan struct{}),
		unblockCh: make(chan struct{}),
	}
	c := NewCentral(scanner)
	_ = c.Connect(context.Background(), "AA:BB")
	if err := c.RunApiListener(context.Background()); err != ErrNoApiProvider {
		t.Fatalf("expected ErrNoApiProvider, got %v", err)
	}
}

// transientErrorScanner is a CentralScanner fake whose WaitNotify always
// returns a transient error (mirroring the pre-connect production state where
// tinyGoCentralScanner.WaitNotify returns errNoStateChar immediately, non-
// blocking). It counts WaitNotify calls so a test can assert the listener is
// NOT busy-spinning (i.e. the retry backoff is in effect).
type transientErrorScanner struct {
	collectScanner
	callsMu sync.Mutex
	calls   int
}

func (s *transientErrorScanner) WaitNotify(context.Context) ([]byte, error) {
	s.callsMu.Lock()
	s.calls++
	s.callsMu.Unlock()
	return nil, errors.New("ble: state characteristic not found")
}

func (s *transientErrorScanner) callCount() int {
	s.callsMu.Lock()
	defer s.callsMu.Unlock()
	return s.calls
}

// TestRunApiListenerBacksOffOnTransientError verifies the Important fix from
// the final re-review: when WaitNotify returns a transient error in a tight
// loop (the production pre-connect state), RunApiListener must NOT busy-spin.
// A stub whose WaitNotify always errors is run for a fixed wall-clock window
// with a real-but-tiny injected retry backoff; the call count over that window
// must be bounded (roughly window/backoff plus a small margin), proving the
// goroutine sleeps between retries instead of hammering EnableNotifications
// and flooding logs from server startup until first connect.
func TestRunApiListenerBacksOffOnTransientError(t *testing.T) {
	scanner := &transientErrorScanner{}
	c := NewCentral(scanner)
	c.SetApiProvider(&jsonBlockProvider{body: []byte(`{}`)})
	_ = c.Connect(context.Background(), "AA:BB")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	// Inject a small but real backoff so the test exercises the actual sleep
	// path without slowing the suite. With a 20ms backoff, a busy-spinning
	// loop would rack up thousands of calls in 100ms; a backed-off loop
	// manages at most ~5-6 (100ms/20ms + 1 for the final cancelled iteration).
	const backoff = 20 * time.Millisecond
	const window = 100 * time.Millisecond

	listenerDone := make(chan struct{})
	go func() {
		_ = c.runApiListener(ctx, backoff)
		close(listenerDone)
	}()

	// Sample call count after the wall-clock window elapses.
	time.Sleep(window)
	got := scanner.callCount()
	cancel()
	<-listenerDone

	// Upper bound: window/backoff + a generous margin for scheduler jitter
	// and the final post-cancel iteration. A busy-spin (no backoff) would
	// blow past this by 2-3 orders of magnitude.
	maxAllowed := int(window/backoff) + 2
	if got > maxAllowed {
		t.Fatalf("RunApiListener busy-spun on transient error: %d WaitNotify calls in %s "+
			"(backoff=%s, expected <= %d)", got, window, backoff, maxAllowed)
	}
	// Lower bound sanity: at least one retry must have happened (otherwise the
	// goroutine never entered the loop or crashed before sleeping).
	if got < 2 {
		t.Fatalf("RunApiListener did not retry WaitNotify at all: %d calls", got)
	}
}

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

// SetConnectRecorder is a no-op test double stub (see fakeScanner note).
func (c *collectScanner) SetConnectRecorder(ConnectRecorder) {}

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
// the 200-byte spec ceiling, every chunk must decode as an authed v2
// CMD_JSON_CHUNK frame with sequential ChunkIndex, a stable TotalChunks count
// and a strictly increasing seq.
func TestServeApiRequestStreamsMultipleChunks(t *testing.T) {
	key := DeriveBleAuthKey(centralTestToken)
	// ~2KB JSON body forces ~12 chunks at the 200-byte spec §1.2 cap.
	body := make([]byte, 2048)
	for i := range body {
		body[i] = 'x'
	}
	provider := &jsonBlockProvider{body: body}
	scanner := newBlePeerFake(centralTestToken)
	c := NewCentral(scanner)
	c.SetApiProvider(provider)
	c.SetAuthToken(centralTestToken)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect (handshake): %v", err)
	}

	req, encErr := EncodeApiReqPayload(EndpointBookChapter, "/books/novel.txt", 4)
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
	writes := scanner.recordedWrites()
	if written != len(writes) {
		t.Fatalf("written=%d but scanner recorded %d frames", written, len(writes))
	}

	// Provider was called with the decoded args.
	if provider.last.ep != EndpointBookChapter ||
		provider.last.path != "/books/novel.txt" ||
		provider.last.idx != 4 {
		t.Fatalf("provider called with ep=%#x path=%q idx=%d",
			provider.last.ep, provider.last.path, provider.last.idx)
	}

	// Every written frame is a valid authed v2 CMD_JSON_CHUNK frame whose
	// payload fits in the MTU and whose ChunkIndex is its position in the
	// stream, with strictly increasing seq.
	total := -1
	var lastSeq uint64
	for i, raw := range writes {
		payload, seq, derr := DecodeAuthedFrame(raw, key)
		if derr != nil {
			t.Fatalf("frame %d not a v2 authed frame: %v", i, derr)
		}
		if i > 0 && seq <= lastSeq {
			t.Fatalf("frame %d seq %d not strictly greater than %d", i, seq, lastSeq)
		}
		lastSeq = seq
		if len(payload) > maxPayloadLen {
			t.Fatalf("frame %d payload %d > max %d", i, len(payload), maxPayloadLen)
		}
		tot, cidx, totalBytes, _, perr := DecodeJsonChunkPayload(payload)
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

// SetConnectRecorder is a no-op test double stub (see fakeScanner note).
func (s *scriptedScanner) SetConnectRecorder(ConnectRecorder) {}

// TestRunApiListenerDispatchesApiRequests exercises the long-lived listener
// loop (spec §3.1) end-to-end over an authenticated channel: a stub peer
// yields two v2 authed CMD_API_REQ frames plus one v2 echo frame the listener
// MUST silently ignore; RunApiListener must decode each and hand it to
// ServeApiRequest, which writes the expected authed CMD_JSON_CHUNK frames via
// WriteCommand. Verifies the multi-goroutine dispatch path and the post-auth
// v2 receive gate (strictly increasing remote seq).
func TestRunApiListenerDispatchesApiRequests(t *testing.T) {
	key := DeriveBleAuthKey(centralTestToken)
	provider := &jsonBlockProvider{body: []byte(`hello-listener`)}
	scanner := newBlePeerFake(centralTestToken)
	scanner.doneCh = make(chan struct{})
	scanner.unblockCh = make(chan struct{})
	c := NewCentral(scanner)
	c.SetApiProvider(provider)
	c.SetAuthToken(centralTestToken)
	if err := c.Connect(context.Background(), "AA:BB"); err != nil {
		t.Fatalf("Connect (handshake): %v", err)
	}
	// Build two v2 authed CMD_API_REQ frames (as the authenticated Android
	// Peripheral would notify them) plus one v2 echo frame the listener MUST
	// silently ignore. Seq is strictly increasing per the receive gate. Pushed
	// only AFTER the handshake so Connect's handshake WaitNotify does not
	// consume them.
	req1, _ := EncodeApiReqPayload(EndpointBookChapter, "/books/a.txt", 1)
	req2, _ := EncodeApiReqPayload(EndpointBookChapter, "/books/b.txt", 2)
	scanner.push(EncodeAuthedFrame(req1, 0, key))
	scanner.push(EncodeAuthedFrame([]byte{byte(CmdEcho), 0xAA}, 1, key))
	scanner.push(EncodeAuthedFrame(req2, 2, key))

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
			if len(scanner.recordedWrites()) >= n {
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
	writes := scanner.recordedWrites()
	if len(writes) < 2 {
		t.Fatalf("expected >=2 chunk writes (one per request), got %d", len(writes))
	}

	// Every written frame must be an authed v2 CMD_JSON_CHUNK payload with a
	// strictly increasing seq.
	var lastSeq uint64
	for i, raw := range writes {
		payload, seq, derr := DecodeAuthedFrame(raw, key)
		if derr != nil {
			t.Fatalf("frame %d not a v2 authed frame: %v", i, derr)
		}
		if i > 0 && seq <= lastSeq {
			t.Fatalf("frame %d seq %d not strictly greater than %d", i, seq, lastSeq)
		}
		lastSeq = seq
		_, _, _, _, perr := DecodeJsonChunkPayload(payload)
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

// SetConnectRecorder is a no-op test double stub (see fakeScanner note).
func (s *transientErrorScanner) SetConnectRecorder(ConnectRecorder) {}

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

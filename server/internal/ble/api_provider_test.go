package ble

import (
	"context"
	"errors"
	"testing"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service/bookparser"
)

// stubApiProvider is a minimal ApiProvider for unit testing the routing layer
// (ServeApiRequest). It records every call so a test can assert which endpoint
// was decoded from the wire payload and which path/index were forwarded.
type stubApiProvider struct {
	calls []struct {
		ep   byte
		path string
		idx  int
	}
	response []byte
	err      error
}

func (s *stubApiProvider) HandleBleRequest(_ context.Context, endpoint byte, path string, index int) ([]byte, error) {
	s.calls = append(s.calls, struct {
		ep   byte
		path string
		idx  int
	}{endpoint, path, index})
	return s.response, s.err
}

// newCentralWithProvider wires up a Central backed by a collectScanner (which
// records WriteCommand frames) and the given ApiProvider, then transitions the
// Central to the "connected" state so ServeApiRequest can proceed. Mirrors the
// shape of existing helpers in central_chapter_test.go.
func newCentralWithProvider(p ApiProvider) (*Central, *collectScanner) {
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	c.SetApiProvider(p)
	_ = c.Connect(context.Background(), "AA:BB")
	return c, scanner
}

// TestServeApiRequestRoutesEndpointToProvider verifies the dispatcher contract:
// ServeApiRequest must decode the CMD_API_REQ payload (endpoint/path/index),
// forward them to the injected ApiProvider, and stream back the provider's JSON
// response as one or more CMD_JSON_CHUNK frames. The returned chunk count must
// be > 0 (an empty body still yields one chunk per ChunkJsonBytes's contract).
func TestServeApiRequestRoutesEndpointToProvider(t *testing.T) {
	stub := &stubApiProvider{response: []byte(`[{"name":"x"}]`)}
	c, scanner := newCentralWithProvider(stub)
	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)

	n, err := c.ServeApiRequest(context.Background(), payload)
	if err != nil {
		t.Fatalf("ServeApiRequest: %v", err)
	}
	if len(stub.calls) != 1 {
		t.Fatalf("expected exactly one provider call, got %d", len(stub.calls))
	}
	if stub.calls[0].ep != EndpointFolders {
		t.Fatalf("endpoint routed=%#x want %#x", stub.calls[0].ep, EndpointFolders)
	}
	if n == 0 {
		t.Fatalf("expected chunks written, got 0")
	}
	if n != len(scanner.written) {
		t.Fatalf("returned chunk count %d != scanner.written %d", n, len(scanner.written))
	}
	// Every written frame must decode as a CMD_JSON_CHUNK payload carrying the
	// total body length of the marshalled response.
	wantBytes := len(stub.response)
	for i, raw := range scanner.written {
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d decode: %v", i, derr)
		}
		_, cidx, totalBytes, _, perr := DecodeJsonChunkPayload(frame.Payload)
		if perr != nil {
			t.Fatalf("frame %d chunk decode: %v", i, perr)
		}
		if cidx != i {
			t.Fatalf("frame %d has ChunkIndex %d", i, cidx)
		}
		if totalBytes != wantBytes {
			t.Fatalf("frame %d TotalBytes %d want %d", i, totalBytes, wantBytes)
		}
	}
}

// TestServeApiRequestRoutesAllEndpoints verifies every defined Endpoint constant
// is decoded from the wire payload and forwarded to the provider unchanged —
// the routing layer MUST be a pass-through, not endpoint-specific.
func TestServeApiRequestRoutesAllEndpoints(t *testing.T) {
	cases := []struct {
		name     string
		endpoint byte
		path     string
		index    int
	}{
		{"book_chapter", EndpointBookChapter, "/books/novel.txt", 7},
		{"folders", EndpointFolders, "", 0},
		{"browse_folder", EndpointBrowseFolder, "/media/sdcard", 0},
		{"book_info", EndpointBookInfo, "/books/novel.epub", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			stub := &stubApiProvider{response: []byte(`{}`)}
			c, _ := newCentralWithProvider(stub)
			payload, _ := EncodeApiReqPayload(tc.endpoint, tc.path, tc.index)
			if _, err := c.ServeApiRequest(context.Background(), payload); err != nil {
				t.Fatalf("ServeApiRequest: %v", err)
			}
			if len(stub.calls) != 1 {
				t.Fatalf("want 1 call, got %d", len(stub.calls))
			}
			got := stub.calls[0]
			if got.ep != tc.endpoint || got.path != tc.path || got.idx != tc.index {
				t.Fatalf("routed ep=%#x path=%q idx=%d, want ep=%#x path=%q idx=%d",
					got.ep, got.path, got.idx, tc.endpoint, tc.path, tc.index)
			}
		})
	}
}

// TestServeApiRequestProviderErrorIsSurfaced verifies that when the provider
// returns an error, ServeApiRequest propagates it to the caller and writes no
// chunk frames (the stream is aborted before the first WriteCommand).
func TestServeApiRequestProviderErrorIsSurfaced(t *testing.T) {
	stub := &stubApiProvider{err: errors.New("boom")}
	c, scanner := newCentralWithProvider(stub)
	payload, _ := EncodeApiReqPayload(EndpointBookInfo, "/p", 0)
	n, err := c.ServeApiRequest(context.Background(), payload)
	if err == nil {
		t.Fatalf("expected error surfaced, got nil (n=%d)", n)
	}
	if n != 0 {
		t.Fatalf("expected 0 chunks on error, got %d", n)
	}
	if len(scanner.written) != 0 {
		t.Fatalf("expected no frames written on error, got %d", len(scanner.written))
	}
}

// TestServeApiRequestRequiresProvider reproduces the ErrNoApiProvider startup
// gate: with no provider injected, ServeApiRequest must refuse to serve.
func TestServeApiRequestRequiresProvider(t *testing.T) {
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	_ = c.Connect(context.Background(), "AA:BB")
	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	_, err := c.ServeApiRequest(context.Background(), payload)
	if err != ErrNoApiProvider {
		t.Fatalf("expected ErrNoApiProvider, got %v", err)
	}
}

// TestServeApiRequestRequiresConnection verifies ServeApiRequest refuses to
// write frames when the BLE Central is not connected (defends against races with
// Disconnect).
func TestServeApiRequestRequiresConnection(t *testing.T) {
	c, _ := newCentralWithProvider(&stubApiProvider{response: []byte(`[]`)})
	c.Disconnect()
	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	_, err := c.ServeApiRequest(context.Background(), payload)
	if err != ErrNotConnected {
		t.Fatalf("expected ErrNotConnected, got %v", err)
	}
}

// TestServeApiRequestRejectsMalformedPayload verifies the decoder gate: a
// payload whose leading CmdID is not CmdApiReq yields ErrBadCmdID before the
// provider is consulted.
func TestServeApiRequestRejectsMalformedPayload(t *testing.T) {
	stub := &stubApiProvider{response: []byte(`[]`)}
	c, _ := newCentralWithProvider(stub)
	bad, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	bad[0] = byte(CmdEcho) // wrong leading CmdID
	_, err := c.ServeApiRequest(context.Background(), bad)
	if err != ErrBadCmdID {
		t.Fatalf("expected ErrBadCmdID, got %v", err)
	}
	if len(stub.calls) != 0 {
		t.Fatalf("provider must not be called on decode failure, got %d calls", len(stub.calls))
	}
}

// TestServeApiRequestLargeResponseSplitsChunks verifies the multi-chunk stream
// path end-to-end: a large JSON response is split into N CMD_JSON_CHUNK frames
// each within the spec §1.2 200-byte payload cap, with sequential ChunkIndex.
func TestServeApiRequestLargeResponseSplitsChunks(t *testing.T) {
	// ~4KB JSON body forces many chunks at the 200-byte spec ceiling.
	body := make([]byte, 4096)
	for i := range body {
		body[i] = 'x'
	}
	stub := &stubApiProvider{response: body}
	c, scanner := newCentralWithProvider(stub)
	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	n, err := c.ServeApiRequest(context.Background(), payload)
	if err != nil {
		t.Fatalf("ServeApiRequest: %v", err)
	}
	if n < 2 {
		t.Fatalf("expected multiple chunks, got %d", n)
	}
	total := -1
	for i, raw := range scanner.written {
		frame, derr := DecodeFrame(raw)
		if derr != nil {
			t.Fatalf("frame %d decode: %v", i, derr)
		}
		if len(frame.Payload) > maxPayloadLen {
			t.Fatalf("frame %d payload %d > max %d", i, len(frame.Payload), maxPayloadLen)
		}
		tc, cidx, totalBytes, _, perr := DecodeJsonChunkPayload(frame.Payload)
		if perr != nil {
			t.Fatalf("frame %d chunk decode: %v", i, perr)
		}
		if total == -1 {
			total = tc
		} else if tc != total {
			t.Fatalf("frame %d TotalChunks %d differs from earlier %d", i, tc, total)
		}
		if cidx != i {
			t.Fatalf("frame %d has ChunkIndex %d", i, cidx)
		}
		if totalBytes != len(body) {
			t.Fatalf("frame %d TotalBytes %d want %d", i, totalBytes, len(body))
		}
	}
	if total != n {
		t.Fatalf("TotalChunks=%d but wrote %d frames", total, n)
	}
}

// TestServeApiRequestPropagatesContextCancel verifies ServeApiRequest respects
// a cancelled ctx when writing the chunk stream: WriteCommand is never invoked
// because the context check (or the scanner's ctx-awareness) aborts early.
//
// NOTE: collectScanner.WriteCommand ignores ctx today; this test instead uses
// the abort-on-write-error contract: a scanner that returns an error on the
// first write surfaces that error from ServeApiRequest, with n=0 chunks.
func TestServeApiRequestAbortsOnFirstWriteError(t *testing.T) {
	stub := &stubApiProvider{response: []byte(`hello world this is a json body`)}

	// Build the central with a scanner that fails the first WriteCommand.
	scanner := &collectScanner{writeErr: errors.New("gatt write failed")}
	c := NewCentral(scanner)
	c.SetApiProvider(stub)
	_ = c.Connect(context.Background(), "AA:BB")

	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	n, err := c.ServeApiRequest(context.Background(), payload)
	if err == nil {
		t.Fatalf("expected write error surfaced, got nil (n=%d)", n)
	}
	if n != 0 {
		t.Fatalf("expected 0 chunks on first-write error, got %d", n)
	}
}

// TestServeApiRequestEmptyBodyStillEmitsOneChunk verifies the empty-body path:
// ChunkJsonBytes always emits at least one chunk so the receiver observes a
// response. The provider returning an empty JSON array (len 0 bytes after
// marshalling nothing) must still produce 1 chunk.
func TestServeApiRequestEmptyBodyStillEmitsOneChunk(t *testing.T) {
	stub := &stubApiProvider{response: []byte{}}
	c, scanner := newCentralWithProvider(stub)
	payload, _ := EncodeApiReqPayload(EndpointFolders, "", 0)
	n, err := c.ServeApiRequest(context.Background(), payload)
	if err != nil {
		t.Fatalf("ServeApiRequest: %v", err)
	}
	if n != 1 {
		t.Fatalf("expected exactly 1 chunk for empty body, got %d", n)
	}
	if len(scanner.written) != 1 {
		t.Fatalf("expected 1 frame written, got %d", len(scanner.written))
	}
}

// recordingBookService is a minimal bookService stub that records every call
// and fails the test if invoked. Used by the path-traversal rejection tests to
// prove the validation gate runs BEFORE BookService is consulted.
type recordingBookService struct {
	calls int
}

func (r *recordingBookService) GetBook(string) (*bookparser.Book, error) {
	r.calls++
	return nil, errors.New("recordingBookService: GetBook must not be called")
}

func (r *recordingBookService) GetChapterBlocks(context.Context, string, int, string) ([]bookparser.Block, error) {
	r.calls++
	return nil, errors.New("recordingBookService: GetChapterBlocks must not be called")
}

// newBleProviderWithBooks wires a bleApiProvider against a temporary roots
// directory + recording book service. Returns the provider and the recorder so
// the test can assert the BookService was not consulted.
func newBleProviderWithBooks(t *testing.T, books bookService) (*bleApiProvider, *recordingBookService) {
	t.Helper()
	if books == nil {
		books = &recordingBookService{}
	}
	cfg := &config.Config{}
	cfg.Scan.Roots = []string{t.TempDir()}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	rec := &recordingBookService{}
	p := newBleApiProvider(cfg, rec).(*bleApiProvider)
	return p, rec
}

// TestBleApiProviderRejectsBookPathOutsideRoots verifies the security gate
// added for the path-traversal finding: EndpointBookInfo / EndpointBookChapter
// MUST validate path against scan roots + text-extension allow-list (the same
// gate the echo GetBookInfo / GetBookChapter handlers apply) BEFORE calling
// BookService. A traversal like ../../etc/passwd must yield a wrapped error and
// the BookService must not be invoked at all.
func TestBleApiProviderRejectsBookPathOutsideRoots(t *testing.T) {
	cases := []struct {
		name     string
		endpoint byte
		path     string
		index    int
	}{
		{"book_info_traversal", EndpointBookInfo, "../../etc/passwd", 0},
		{"book_info_absolute_outside", EndpointBookInfo, "/etc/shadow", 0},
		{"book_chapter_traversal", EndpointBookChapter, "../../../../etc/passwd", 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p, rec := newBleProviderWithBooks(t, nil)
			_, err := p.HandleBleRequest(context.Background(), tc.endpoint, tc.path, tc.index)
			if err == nil {
				t.Fatalf("expected path-traversal rejection for %q, got nil error", tc.path)
			}
			if rec.calls != 0 {
				t.Fatalf("BookService must NOT be called for rejected path; got %d call(s)", rec.calls)
			}
		})
	}
}

// TestBleApiProviderRejectsBookPathWrongExtension verifies the allow-list half
// of the gate: a path inside roots but with a non-text extension must also be
// rejected, mirroring the echo handler's text-extension allow-list.
func TestBleApiProviderRejectsBookPathWrongExtension(t *testing.T) {
	root := t.TempDir()
	cfg := &config.Config{}
	cfg.Scan.Roots = []string{root}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	rec := &recordingBookService{}
	p := newBleApiProvider(cfg, rec).(*bleApiProvider)

	// An absolute path inside root with a non-text extension. The validation
	// gate must reject it before BookService is consulted.
	_, err := p.HandleBleRequest(context.Background(), EndpointBookInfo, root+"/secret.bin", 0)
	if err == nil {
		t.Fatalf("expected extension-rejection, got nil error")
	}
	if rec.calls != 0 {
		t.Fatalf("BookService must NOT be called for rejected extension; got %d call(s)", rec.calls)
	}
}

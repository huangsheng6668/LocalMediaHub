package ble

import (
	"context"
	"errors"
	"sync"
	"testing"

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

	req := EncodeBookChapterReqPayload("/books/novel.txt", 4)
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
	req := EncodeBookChapterReqPayload("/books/x", 0)
	_, err := c.ServeChapterRequest(context.Background(), req, "")
	if err != ErrNoChapterProvider {
		t.Fatalf("expected ErrNoChapterProvider, got %v", err)
	}
}

func TestServeChapterRequestRequiresConnection(t *testing.T) {
	scanner := &collectScanner{}
	c := NewCentral(scanner)
	c.SetChapterProvider(&stubChapterProvider{})
	req := EncodeBookChapterReqPayload("/books/x", 0)
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
	// whose leading byte is not CmdBookChapterReq.
	bad := EncodeBookChapterReqPayload("/x", 0)
	bad[0] = byte(CmdEcho)
	_, err := c.ServeChapterRequest(context.Background(), bad, "")
	if err != ErrBadCmdID {
		t.Fatalf("expected ErrBadCmdID, got %v", err)
	}
}

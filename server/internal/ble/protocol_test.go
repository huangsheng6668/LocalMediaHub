package ble

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"

	"github.com/localmediahub/server/internal/service/bookparser"
)

func TestFrameRoundTrip(t *testing.T) {
	payload := []byte("hello-ble")
	encoded := EncodeFrame(payload)

	got, err := DecodeFrame(encoded)
	if err != nil {
		t.Fatalf("DecodeFrame returned error: %v", err)
	}
	if !bytes.Equal(got.Payload, payload) {
		t.Fatalf("payload mismatch: got %q, want %q", got.Payload, payload)
	}
}

func TestDecodeFrameRejectsTruncated(t *testing.T) {
	// Header alone (1 byte version + 2 byte length) without payload.
	// Length is big-endian: 0x00,0x05 => 5 bytes claimed, 0 provided.
	_, err := DecodeFrame([]byte{FrameVersion, 0x00, 0x05})
	if err != ErrTruncated {
		t.Fatalf("expected ErrTruncated, got %v", err)
	}
}

func TestUUIDsAreDistinct(t *testing.T) {
	if ServiceUUID == "" || CommandCharUUID == "" || StateCharUUID == "" {
		t.Fatal("UUIDs must be non-empty")
	}
	if CommandCharUUID == StateCharUUID {
		t.Fatal("Command and State characteristic UUIDs must differ")
	}
}

// TestEncodeBookChapterReqPayloadRejectsOversizedPath verifies the encoder
// returns ErrPathTooLong instead of silently truncating when the path exceeds
// the 255-byte PathLen ceiling. Silent truncation would cause the server to
// fetch the wrong chapter path — a correctness footgun.
func TestEncodeBookChapterReqPayloadRejectsOversizedPath(t *testing.T) {
	longPath := strings.Repeat("a", 256)
	got, err := EncodeBookChapterReqPayload(longPath, 0)
	if !errors.Is(err, ErrPathTooLong) {
		t.Fatalf("expected ErrPathTooLong, got %v (payload len=%d)", err, len(got))
	}
	if got != nil {
		t.Fatalf("expected nil payload on error, got %d bytes", len(got))
	}

	// Boundary: a 255-byte path must still succeed (PathLen fits in 1 byte).
	okPayload, okErr := EncodeBookChapterReqPayload(strings.Repeat("b", maxPathLen), 0)
	if okErr != nil {
		t.Fatalf("255-byte path should succeed, got %v", okErr)
	}
	if len(okPayload) != chapterReqFixedOverhead+maxPathLen {
		t.Fatalf("255-byte path payload size=%d want %d", len(okPayload), chapterReqFixedOverhead+maxPathLen)
	}
}

// TestBookChapterProtocolFraming verifies the on-wire layout for a chapter
// request: EncodeFrame wraps the payload with the 3-byte physical header
// (version + uint16 BE length), and the payload itself begins with a CmdID
// byte followed by the chapter-request fields.
func TestBookChapterProtocolFraming(t *testing.T) {
	reqPayload, err := EncodeBookChapterReqPayload("/books/test.txt", 1)
	if err != nil {
		t.Fatalf("EncodeBookChapterReqPayload returned error: %v", err)
	}
	frame := EncodeFrame(reqPayload)
	// Version 1, Length 2+1+15+2 = 20
	if frame[0] != 0x01 {
		t.Fatalf("expected version 1, got %d", frame[0])
	}
	cmdID, path, idx, err := DecodeBookChapterReqPayload(frame[3:])
	if err != nil || cmdID != CmdBookChapterReq || path != "/books/test.txt" || idx != 1 {
		t.Fatalf("decode failed cmdID=%x path=%s idx=%d err=%v", cmdID, path, idx, err)
	}
}

// TestBookChapterChunkFraming verifies a chunk frame round-trips its five
// fields and the chunk bytes are preserved verbatim.
func TestBookChapterChunkFraming(t *testing.T) {
	chunkBytes := []byte("hello-chunk")
	payload := EncodeBookChapterChunkPayload(3, 1, 7, chunkBytes)
	frame := EncodeFrame(payload)

	if frame[0] != FrameVersion {
		t.Fatalf("expected version %d, got %d", FrameVersion, frame[0])
	}

	total, idx, totalBlocks, gotChunk, err := DecodeBookChapterChunkPayload(frame[3:])
	if err != nil {
		t.Fatalf("decode returned error: %v", err)
	}
	if total != 3 || idx != 1 || totalBlocks != 7 {
		t.Fatalf("fields mismatch: total=%d idx=%d totalBlocks=%d", total, idx, totalBlocks)
	}
	if !bytes.Equal(gotChunk, chunkBytes) {
		t.Fatalf("chunk bytes mismatch: got %q want %q", gotChunk, chunkBytes)
	}
}

// TestChunkChapterBlocksSplitting verifies that ChunkChapterBlocks marshals the
// blocks to JSON, splits the result into frames whose payload never exceeds
// maxPayloadLen, and that reassembling the chunk bytes yields the same JSON.
func TestChunkChapterBlocksSplitting(t *testing.T) {
	// Build a block slice large enough to require multiple chunks at the
	// 200-byte spec §1.2 cap. Each text block is ~64 bytes of JSON.
	blocks := make([]bookparser.Block, 20)
	for i := range blocks {
		blocks[i] = bookparser.Block{Type: "text", Value: "padding-padding-padding-padding-padding"}
	}

	frames, totalBlocks, err := ChunkChapterBlocks(blocks)
	if err != nil {
		t.Fatalf("ChunkChapterBlocks returned error: %v", err)
	}
	if totalBlocks != len(blocks) {
		t.Fatalf("totalBlocks=%d want %d", totalBlocks, len(blocks))
	}
	if len(frames) < 2 {
		t.Fatalf("expected multiple chunks, got %d", len(frames))
	}

	var reassembled []byte
	var sawTotal, sawTotalBlocks int
	for i, fr := range frames {
		// Spec §1.2 mandates ≤ 200 B per chunk; the stricter cap must hold in
		// addition to the maxPayloadLen (244 B) MTU ceiling.
		if len(fr) > maxChunkBytes {
			t.Fatalf("frame %d payload exceeds spec §1.2 cap: %d > %d bytes", i, len(fr), maxChunkBytes)
		}
		if len(fr) > maxPayloadLen {
			t.Fatalf("frame %d payload too large: %d bytes", i, len(fr))
		}
		total, idx, tb, chunk, derr := DecodeBookChapterChunkPayload(fr)
		if derr != nil {
			t.Fatalf("frame %d decode error: %v", i, derr)
		}
		if i == 0 {
			sawTotal = total
			sawTotalBlocks = tb
		} else {
			if total != sawTotal {
				t.Fatalf("frame %d total mismatch: %d vs %d", i, total, sawTotal)
			}
			if tb != sawTotalBlocks {
				t.Fatalf("frame %d totalBlocks mismatch: %d vs %d", i, tb, sawTotalBlocks)
			}
		}
		if idx != i {
			t.Fatalf("frame %d has ChunkIndex %d", i, idx)
		}
		reassembled = append(reassembled, chunk...)
	}
	if sawTotal != len(frames) {
		t.Fatalf("TotalChunks=%d but produced %d frames", sawTotal, len(frames))
	}

	// The reassembled bytes must equal the canonical JSON encoding of blocks.
	want, err := json.Marshal(blocks)
	if err != nil {
		t.Fatalf("json.Marshal: %v", err)
	}
	if !bytes.Equal(reassembled, want) {
		t.Fatalf("reassembled JSON mismatch:\n got %q\nwant %q", reassembled, want)
	}
}

// TestChunkChapterBlocksEmpty verifies the empty-input edge case still
// produces a single chunk with TotalChunks=1, ChunkIndex=0.
func TestChunkChapterBlocksEmpty(t *testing.T) {
	frames, totalBlocks, err := ChunkChapterBlocks(nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(frames) != 1 || totalBlocks != 0 {
		t.Fatalf("expected 1 frame / 0 blocks, got %d frames / %d blocks", len(frames), totalBlocks)
	}
	total, idx, tb, _, derr := DecodeBookChapterChunkPayload(frames[0])
	if derr != nil || total != 1 || idx != 0 || tb != 0 {
		t.Fatalf("decode mismatch: total=%d idx=%d tb=%d err=%v", total, idx, tb, derr)
	}
}

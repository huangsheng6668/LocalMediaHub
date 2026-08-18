package ble

import (
	"bytes"
	"crypto/hmac"
	"crypto/rand"
	"errors"
	"strings"
	"testing"
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

// TestEncodeApiReqPayloadRejectsOversizedPath verifies the encoder
// returns ErrPathTooLong instead of silently truncating when the path exceeds
// the 255-byte PathLen ceiling. Silent truncation would cause the server to
// fetch the wrong chapter path — a correctness footgun.
func TestEncodeApiReqPayloadRejectsOversizedPath(t *testing.T) {
	longPath := strings.Repeat("a", 256)
	got, err := EncodeApiReqPayload(EndpointBookChapter, longPath, 0)
	if !errors.Is(err, ErrPathTooLong) {
		t.Fatalf("expected ErrPathTooLong, got %v (payload len=%d)", err, len(got))
	}
	if got != nil {
		t.Fatalf("expected nil payload on error, got %d bytes", len(got))
	}

	// Boundary: a 255-byte path must still succeed (PathLen fits in 1 byte).
	okPayload, okErr := EncodeApiReqPayload(EndpointBookChapter, strings.Repeat("b", maxPathLen), 0)
	if okErr != nil {
		t.Fatalf("255-byte path should succeed, got %v", okErr)
	}
	if len(okPayload) != apiReqFixedOverhead+maxPathLen {
		t.Fatalf("255-byte path payload size=%d want %d", len(okPayload), apiReqFixedOverhead+maxPathLen)
	}
}

// TestApiReqProtocolFraming verifies the on-wire layout for an API request:
// EncodeFrame wraps the payload with the 3-byte physical header
// (version + uint16 BE length), and the payload itself begins with a CmdID
// byte followed by the API-request fields.
func TestApiReqProtocolFraming(t *testing.T) {
	reqPayload, err := EncodeApiReqPayload(EndpointBookChapter, "/books/test.txt", 1)
	if err != nil {
		t.Fatalf("EncodeApiReqPayload returned error: %v", err)
	}
	frame := EncodeFrame(reqPayload)
	if frame[0] != 0x01 {
		t.Fatalf("expected version 1, got %d", frame[0])
	}
	endpoint, path, idx, _, err := DecodeApiReqPayload(frame[3:])
	if err != nil || endpoint != EndpointBookChapter || path != "/books/test.txt" || idx != 1 {
		t.Fatalf("decode failed endpoint=%x path=%s idx=%d err=%v", endpoint, path, idx, err)
	}
}

// TestApiReqFramingAllEndpoints verifies the encode/decode round-trip for
// every Endpoint constant mandated by spec §2.2. This guards against the
// Endpoint byte being dropped/swapped when the layout is touched.
func TestApiReqFramingAllEndpoints(t *testing.T) {
	cases := []struct {
		name     string
		endpoint byte
		path     string
		index    int
	}{
		{"folders no path", EndpointFolders, "", 0},
		{"browse with path", EndpointBrowseFolder, "/books/novel.txt", 0},
		{"book chapter path+index", EndpointBookChapter, "/books/novel.txt", 42},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			payload, err := EncodeApiReqPayload(tc.endpoint, tc.path, tc.index)
			if err != nil {
				t.Fatalf("encode err=%v", err)
			}
			if payload[0] != byte(CmdApiReq) {
				t.Fatalf("cmdID=%x want %x", payload[0], CmdApiReq)
			}
			ep, path, idx, _, err := DecodeApiReqPayload(payload)
			if err != nil {
				t.Fatalf("decode err=%v", err)
			}
			if ep != tc.endpoint || path != tc.path || idx != tc.index {
				t.Fatalf("got ep=%x path=%s idx=%d", ep, path, idx)
			}
		})
	}
}

func TestApiReqRejectsPathLongerThan255(t *testing.T) {
	longPath := strings.Repeat("a", 256)
	_, err := EncodeApiReqPayload(EndpointBookInfo, longPath, 0)
	if err != ErrPathTooLong {
		t.Fatalf("expected ErrPathTooLong got %v", err)
	}
}

// TestJsonChunkFraming verifies a chunk frame round-trips its five fields and
// the chunk bytes are preserved verbatim.
func TestJsonChunkFraming(t *testing.T) {
	chunkBytes := []byte("hello-chunk")
	payload := EncodeJsonChunkPayload(3, 1, 7, chunkBytes)
	frame := EncodeFrame(payload)

	if frame[0] != FrameVersion {
		t.Fatalf("expected version %d, got %d", FrameVersion, frame[0])
	}

	total, idx, totalBytes, gotChunk, err := DecodeJsonChunkPayload(frame[3:])
	if err != nil {
		t.Fatalf("decode returned error: %v", err)
	}
	if total != 3 || idx != 1 || totalBytes != 7 {
		t.Fatalf("fields mismatch: total=%d idx=%d totalBytes=%d", total, idx, totalBytes)
	}
	if !bytes.Equal(gotChunk, chunkBytes) {
		t.Fatalf("chunk bytes mismatch: got %q want %q", gotChunk, chunkBytes)
	}
}

func TestJsonChunkRoundTripPreservesBytes(t *testing.T) {
	jsonBytes := []byte(`[{"type":"t"},{"type":"x"}]`)
	frames, totalBytes, err := ChunkJsonBytes(jsonBytes)
	if err != nil {
		t.Fatalf("chunk err=%v", err)
	}
	if totalBytes != len(jsonBytes) {
		t.Fatalf("totalBytes=%d want %d", totalBytes, len(jsonBytes))
	}
	var reassembled []byte
	for i, fr := range frames {
		// ChunkJsonBytes returns payload bytes (no physical header); decode
		// directly. (Stripping 3 bytes here would drop the leading CmdID.)
		tc, ci, tb, chunk, derr := DecodeJsonChunkPayload(fr)
		if derr != nil {
			t.Fatalf("frame %d decode err=%v", i, derr)
		}
		if ci != i || tb != totalBytes {
			t.Fatalf("frame %d ci=%d tb=%d", i, ci, tb)
		}
		_ = tc
		reassembled = append(reassembled, chunk...)
	}
	if !bytes.Equal(reassembled, jsonBytes) {
		t.Fatalf("reassembled mismatch")
	}
	for _, fr := range frames {
		if len(fr) > maxChunkBytes {
			t.Fatalf("frame exceeds 200B cap: %d", len(fr))
		}
	}
}

// TestChunkJsonBytesSplitting verifies that ChunkJsonBytes splits the input
// into frames whose payload never exceeds maxPayloadLen or maxChunkBytes, and
// that reassembling the chunk bytes yields the same input bytes.
func TestChunkJsonBytesSplitting(t *testing.T) {
	// Build a JSON byte slice large enough to require multiple chunks at the
	// 200-byte spec §1.2 cap.
	jsonBytes := []byte(`{"blocks":[`)
	for i := 0; i < 20; i++ {
		if i > 0 {
			jsonBytes = append(jsonBytes, ',')
		}
		jsonBytes = append(jsonBytes, []byte(`{"type":"text","value":"padding-padding-padding-padding-padding"}`)...)
	}
	jsonBytes = append(jsonBytes, ']', '}')

	frames, totalBytes, err := ChunkJsonBytes(jsonBytes)
	if err != nil {
		t.Fatalf("ChunkJsonBytes returned error: %v", err)
	}
	if totalBytes != len(jsonBytes) {
		t.Fatalf("totalBytes=%d want %d", totalBytes, len(jsonBytes))
	}
	if len(frames) < 2 {
		t.Fatalf("expected multiple chunks, got %d", len(frames))
	}

	var reassembled []byte
	var sawTotal int
	for i, fr := range frames {
		if len(fr) > maxChunkBytes {
			t.Fatalf("frame %d payload exceeds spec §1.2 cap: %d > %d bytes", i, len(fr), maxChunkBytes)
		}
		if len(fr) > maxPayloadLen {
			t.Fatalf("frame %d payload too large: %d bytes", i, len(fr))
		}
		total, idx, tb, chunk, derr := DecodeJsonChunkPayload(fr)
		if derr != nil {
			t.Fatalf("frame %d decode error: %v", i, derr)
		}
		if i == 0 {
			sawTotal = total
		} else {
			if total != sawTotal {
				t.Fatalf("frame %d total mismatch: %d vs %d", i, total, sawTotal)
			}
		}
		if idx != i {
			t.Fatalf("frame %d has ChunkIndex %d", i, idx)
		}
		if tb != totalBytes {
			t.Fatalf("frame %d totalBytes=%d want %d", i, tb, totalBytes)
		}
		reassembled = append(reassembled, chunk...)
	}
	if sawTotal != len(frames) {
		t.Fatalf("TotalChunks=%d but produced %d frames", sawTotal, len(frames))
	}
	if !bytes.Equal(reassembled, jsonBytes) {
		t.Fatalf("reassembled JSON mismatch:\n got %q\nwant %q", reassembled, jsonBytes)
	}
}

// TestChunkJsonBytesEmpty verifies the empty-input edge case still produces a
// single chunk with TotalChunks=1, ChunkIndex=0.
func TestChunkJsonBytesEmpty(t *testing.T) {
	frames, totalBytes, err := ChunkJsonBytes(nil)
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if len(frames) != 1 || totalBytes != 0 {
		t.Fatalf("expected 1 frame / 0 bytes, got %d frames / %d bytes", len(frames), totalBytes)
	}
	total, idx, tb, _, derr := DecodeJsonChunkPayload(frames[0])
	if derr != nil || total != 1 || idx != 0 || tb != 0 {
		t.Fatalf("decode mismatch: total=%d idx=%d tb=%d err=%v", total, idx, tb, derr)
	}
}

// TestAuthedFrameRoundTripAndTamper is the Phase 9 (H-1a) protocol-layer gate:
// a v2 authed frame must round-trip payload + seq, and any tampering (bit flip
// or wrong key) must surface as ErrBadMAC — never as a silently accepted frame.
func TestAuthedFrameRoundTripAndTamper(t *testing.T) {
	key := DeriveBleAuthKey("sekrit")
	frame := EncodeAuthedFrame([]byte{byte(CmdEcho), 1, 2}, 7, key)
	payload, seq, err := DecodeAuthedFrame(frame, key)
	if err != nil || seq != 7 || payload[0] != byte(CmdEcho) {
		t.Fatalf("round trip failed: %v %d", err, seq)
	}
	frame[len(frame)-1] ^= 0xFF
	if _, _, err := DecodeAuthedFrame(frame, key); err != ErrBadMAC {
		t.Fatalf("tampered frame must fail with ErrBadMAC, got %v", err)
	}
	wrong := DecodeAuthedFrame // nil key path
	if _, _, err := DecodeAuthedFrame(frame, DeriveBleAuthKey("other")); err != ErrBadMAC {
		t.Fatalf("wrong key must fail, got %v", err)
	}
	_ = wrong
}

// TestAuthChallengeResponsePayload verifies the handshake payload codecs and
// the challenge-response MAC derivation: mac = HMAC-SHA256(key, nonce||dir)[:16].
func TestAuthChallengeResponsePayload(t *testing.T) {
	key := DeriveBleAuthKey("sekrit")
	nonce := make([]byte, 8)
	rand.Read(nonce)
	ch := EncodeAuthChallengePayload(AuthDirCentralToPeripheral, nonce)
	dir, gotNonce, err := DecodeAuthChallengePayload(ch)
	if err != nil || dir != AuthDirCentralToPeripheral || !bytes.Equal(gotNonce, nonce) {
		t.Fatalf("challenge payload broken: %v", err)
	}
	mac := AuthResponseMAC(key, nonce, AuthDirCentralToPeripheral)
	resp := EncodeAuthResponsePayload(nonce, mac)
	rn, rm, err := DecodeAuthResponsePayload(resp)
	if err != nil || !bytes.Equal(rn, nonce) || !hmac.Equal(rm, mac) {
		t.Fatalf("response payload broken: %v", err)
	}
}

func TestApiReqSegmentPayloadIndex2RoundTrip(t *testing.T) {
	payload, err := EncodeApiReqSegmentPayload(EndpointBookChapterSegment, "/books/novel.txt", 3, 456)
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	ep, path, idx, idx2, derr := DecodeApiReqPayload(payload)
	if derr != nil || ep != EndpointBookChapterSegment || path != "/books/novel.txt" || idx != 3 || idx2 != 456 {
		t.Fatalf("round trip: %v %q %d %d %v", ep, path, idx, idx2, derr)
	}
	// Legacy payloads (no trailing field) decode with index2 = 0.
	legacy, _ := EncodeApiReqPayload(EndpointBookChapter, "/b.txt", 7)
	_, _, _, legacyIdx2, lerr := DecodeApiReqPayload(legacy)
	if lerr != nil || legacyIdx2 != 0 {
		t.Fatalf("legacy decode must yield index2=0: %d %v", legacyIdx2, lerr)
	}
}

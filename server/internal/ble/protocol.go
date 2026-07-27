// Package ble implements the BLE GATT control channel protocol shared by
// the Go server (Peripheral) and the Android client (Central).
//
// Frame wire format (big-endian):
//
//	[0]    version (currently 0x01)
//	[1:3]  uint16 payload length
//	[3:]   payload bytes
package ble

import (
	"encoding/binary"
	"encoding/json"
	"errors"

	"github.com/localmediahub/server/internal/service/bookparser"
)

// CmdID is the application-layer command identifier carried in the first byte
// of every frame payload. It routes a decoded payload to its handler.
type CmdID byte

const (
	// CmdEcho is the legacy ping/pong command (already in use today).
	CmdEcho CmdID = 0x01
	// CmdBookChapterReq is sent Android (Peripheral) -> PC (Central) via the
	// State characteristic Notify to request the chapter at ChapterIndex for
	// the book at Path. Layout:
	//	[CmdID 1B][ChapterIndex 2B BE][PathLen 1B][Path Bytes]
	CmdBookChapterReq CmdID = 0x11
	// CmdBookChapterChunk is sent PC (Central) -> Android (Peripheral) via
	// the Command characteristic Write to stream the marshalled blocks of a
	// chapter, split into MTU-safe pieces. Layout:
	//	[CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBlocks 2B BE][ChunkLen 2B BE][Chunk Bytes]
	CmdBookChapterChunk CmdID = 0x12
)

// Chapter-block transport header sizes (excluding the variable Path / Chunk
// bytes). Used to size buffers and to validate short payloads on decode.
const (
	chapterReqFixedOverhead = 1 + 2 + 1 // CmdID + ChapterIndex + PathLen
	chunkFixedOverhead      = 1 + 2 + 2 + 2 + 2 // CmdID + TotalChunks + ChunkIndex + TotalBlocks + ChunkLen
	// maxPathLen is the largest Path string accepted on decode. The PathLen
	// field is a single byte (max 255), so the wire-format hard ceiling is 255.
	maxPathLen = 255
	// maxChunkBytes is the binding per-chunk payload ceiling mandated by spec
	// §1.2 ("≤ 200 字节"). It is stricter than maxPayloadLen (244 B, the MTU
	// ceiling) and is honored here so both the spec constraint and the wire
	// layout are satisfied simultaneously.
	maxChunkBytes = 200
)

// UUIDs for the BLE GATT service and its characteristics. These MUST match
// the constants in android BleProtocol.kt. 128-bit lowercase hex.
const (
	ServiceUUID     = "fa6a3001-8b2c-4e6f-9988-123456789abc"
	CommandCharUUID = "fa6a3002-8b2c-4e6f-9988-123456789abc" // Write, Central -> Peripheral
	StateCharUUID   = "fa6a3003-8b2c-4e6f-9988-123456789abc" // Notify, Peripheral -> Central
)

const FrameVersion byte = 0x01

const maxPayloadLen = 244 // fits in negotiated 247-byte MTU minus 3-byte header

var (
	ErrTruncated  = errors.New("ble: frame truncated")
	ErrTooLarge   = errors.New("ble: payload exceeds max length")
	ErrBadVersion = errors.New("ble: unsupported frame version")
	// ErrBadCmdID is returned by a payload decoder when the leading CmdID byte
	// does not match the command the decoder was asked to interpret.
	ErrBadCmdID = errors.New("ble: unexpected CmdID")
	// ErrPathTooLong is returned by EncodeBookChapterReqPayload when the path
	// length exceeds the PathLen field's 1-byte ceiling (255 B). Returning an
	// error rather than silently truncating avoids the server fetching a wrong
	// chapter path.
	ErrPathTooLong = errors.New("ble: chapter request path exceeds 255 bytes")
)

type Frame struct {
	Payload []byte
}

func EncodeFrame(payload []byte) []byte {
	buf := make([]byte, 3+len(payload))
	buf[0] = FrameVersion
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(payload)))
	copy(buf[3:], payload)
	return buf
}

func DecodeFrame(data []byte) (Frame, error) {
	if len(data) < 3 {
		return Frame{}, ErrTruncated
	}
	if data[0] != FrameVersion {
		return Frame{}, ErrBadVersion
	}
	length := int(binary.BigEndian.Uint16(data[1:3]))
	if length > maxPayloadLen {
		return Frame{}, ErrTooLarge
	}
	if len(data) < 3+length {
		return Frame{}, ErrTruncated
	}
	return Frame{Payload: append([]byte(nil), data[3:3+length]...)}, nil
}

// EncodeBookChapterReqPayload builds the payload for CMD_BOOK_CHAPTER_REQ:
//   [CmdID 1B][ChapterIndex 2B BE][PathLen 1B][Path Bytes]
// Returns (nil, ErrPathTooLong) if len(path) exceeds the PathLen field's
// 1-byte ceiling (maxPathLen, 255 B). The caller MUST surface this error —
// silently truncating the path would cause the server to fetch the wrong
// chapter.
func EncodeBookChapterReqPayload(path string, chapterIndex int) ([]byte, error) {
	pb := []byte(path)
	if len(pb) > maxPathLen {
		return nil, ErrPathTooLong
	}
	out := make([]byte, chapterReqFixedOverhead+len(pb))
	out[0] = byte(CmdBookChapterReq)
	binary.BigEndian.PutUint16(out[1:3], uint16(chapterIndex))
	out[3] = byte(len(pb))
	copy(out[4:], pb)
	return out, nil
}

// DecodeBookChapterReqPayload parses a CMD_BOOK_CHAPTER_REQ payload (the
// bytes after the 3-byte physical header). Returns the decoded CmdID, path
// and chapter index, or an error if the payload is malformed.
//
// Contract: on ANY error path the returned CmdID is the zero value (0). The
// caller therefore MUST treat a non-nil error as terminal and must not act on
// the returned CmdID. Returning the offending byte on CmdID-mismatch was an
// earlier inconsistency that tempted callers to re-check CmdID after the
// error return (dead code given this contract).
func DecodeBookChapterReqPayload(payload []byte) (CmdID, string, int, error) {
	if len(payload) < chapterReqFixedOverhead {
		return 0, "", 0, ErrTruncated
	}
	cmd := CmdID(payload[0])
	if cmd != CmdBookChapterReq {
		return 0, "", 0, ErrBadCmdID
	}
	idx := int(binary.BigEndian.Uint16(payload[1:3]))
	pathLen := int(payload[3])
	if len(payload) < chapterReqFixedOverhead+pathLen {
		return 0, "", 0, ErrTruncated
	}
	path := string(payload[4 : 4+pathLen])
	return cmd, path, idx, nil
}

// EncodeBookChapterChunkPayload builds the payload for CMD_BOOK_CHAPTER_CHUNK:
//   [CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBlocks 2B BE][ChunkLen 2B BE][Chunk Bytes]
// chunk must already be <= maxPayloadLen - chunkFixedOverhead; the encoder
// does not re-split oversized input (the chunker enforces the bound).
func EncodeBookChapterChunkPayload(totalChunks, chunkIndex, totalBlocks int, chunk []byte) []byte {
	out := make([]byte, chunkFixedOverhead+len(chunk))
	out[0] = byte(CmdBookChapterChunk)
	binary.BigEndian.PutUint16(out[1:3], uint16(totalChunks))
	binary.BigEndian.PutUint16(out[3:5], uint16(chunkIndex))
	binary.BigEndian.PutUint16(out[5:7], uint16(totalBlocks))
	binary.BigEndian.PutUint16(out[7:9], uint16(len(chunk)))
	copy(out[9:], chunk)
	return out
}

// DecodeBookChapterChunkPayload parses a CMD_BOOK_CHAPTER_CHUNK payload (the
// bytes after the 3-byte physical header). Returns TotalChunks, ChunkIndex,
// TotalBlocks, the chunk bytes, or an error if the payload is malformed.
func DecodeBookChapterChunkPayload(payload []byte) (int, int, int, []byte, error) {
	if len(payload) < chunkFixedOverhead {
		return 0, 0, 0, nil, ErrTruncated
	}
	cmd := CmdID(payload[0])
	if cmd != CmdBookChapterChunk {
		return 0, 0, 0, nil, ErrBadCmdID
	}
	total := int(binary.BigEndian.Uint16(payload[1:3]))
	idx := int(binary.BigEndian.Uint16(payload[3:5]))
	totalBlocks := int(binary.BigEndian.Uint16(payload[5:7]))
	chunkLen := int(binary.BigEndian.Uint16(payload[7:9]))
	if len(payload) < chunkFixedOverhead+chunkLen {
		return 0, 0, 0, nil, ErrTruncated
	}
	chunk := append([]byte(nil), payload[9:9+chunkLen]...)
	return total, idx, totalBlocks, chunk, nil
}

// ChunkChapterBlocks marshals blocks to JSON (encoding/json) and splits the
// result into N CMD_BOOK_CHAPTER_CHUNK payload frames, each no larger than
// maxChunkBytes (the spec §1.2 "≤ 200 字节" cap, which is stricter than the
// 244 B MTU ceiling). totalBlocks is the number of source blocks (carried by
// every chunk so receivers can size UI buffers without waiting for the full
// reassembly).
//
// The returned frames are payloads (no physical header); callers wrap each
// with EncodeFrame before writing to the Command characteristic. The slice is
// non-empty even for zero blocks (a single empty chunk signals "no content").
func ChunkChapterBlocks(blocks []bookparser.Block) ([][]byte, int, error) {
	jsonBytes, err := json.Marshal(blocks)
	if err != nil {
		return nil, 0, err
	}
	totalBlocks := len(blocks)

	// Per-chunk payload capacity = min(MTU ceiling, spec §1.2 ceiling) minus
	// the fixed chunk header. Honoring the stricter 200 B cap satisfies both
	// the wire layout (≤ maxPayloadLen) and the spec's operational constraint.
	maxChunk := maxChunkBytes
	if maxPayloadLen < maxChunk {
		maxChunk = maxPayloadLen
	}
	maxChunk -= chunkFixedOverhead
	if maxChunk <= 0 {
		return nil, 0, ErrTooLarge
	}

	totalChunks := (len(jsonBytes) + maxChunk - 1) / maxChunk
	// Always emit at least one chunk so the receiver observes a response even
	// when the JSON body is empty (blocks == nil/[]).
	if totalChunks == 0 {
		totalChunks = 1
	}

	frames := make([][]byte, 0, totalChunks)
	for idx := 0; idx < totalChunks; idx++ {
		offset := idx * maxChunk
		end := offset + maxChunk
		if end > len(jsonBytes) {
			end = len(jsonBytes)
		}
		chunk := jsonBytes[offset:end]
		frames = append(frames, EncodeBookChapterChunkPayload(totalChunks, idx, totalBlocks, chunk))
	}
	return frames, totalBlocks, nil
}

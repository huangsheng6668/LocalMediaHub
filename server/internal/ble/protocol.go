// Package ble implements the BLE GATT control channel protocol shared by
// the Go server (Central) and the Android client (Peripheral).
//
// Frame wire format (big-endian):
//
//	v1 (handshake + legacy):
//	  [0]    version (0x01)
//	  [1:3]  uint16 payload length
//	  [3:]   payload bytes
//
//	v2 (authenticated data frames, Phase 9 / H-1a):
//	  [0]      version (0x02)
//	  [1:3]    uint16 payload length (≤ 220)
//	  [3:]     payload bytes
//	  [n:n+8]  uint64 seq (big-endian, strictly increasing per direction)
//	  [n+8:]   truncated HMAC-SHA256 (16 B) over [0 : n+8]
//
// Handshake (challenge/response) frames are carried as v1 frames; after both
// sides authenticate, all data frames are v2 and reject seq rollback/replay.
package ble

import (
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/binary"
	"errors"
)

// CmdID is the application-layer command identifier carried in the first byte
// of every frame payload. It routes a decoded payload to its handler.
type CmdID byte

const (
	// CmdEcho is the legacy ping/pong command (already in use today).
	CmdEcho CmdID = 0x01
	// CmdApiReq is sent Android (Peripheral) -> PC (Central) via the State
	// characteristic Notify to request a generic API response for the given
	// Endpoint and Path at Index. Layout (spec §2.2):
	//	[CmdID 1B][Endpoint 1B][PathLen 1B][Path Bytes][Index 2B BE]
	CmdApiReq CmdID = 0x11
	// CmdJsonChunk is sent PC (Central) -> Android (Peripheral) via the
	// Command characteristic Write to stream an arbitrary JSON body, split
	// into MTU-safe pieces. Layout (spec §2.2):
	//	[CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBytes 2B BE][ChunkLen 2B BE][Chunk Bytes]
	CmdJsonChunk CmdID = 0x12
	// CmdAuthChallenge is the Phase 9 (H-1a) mutual-challenge handshake
	// command. Layout: [CmdID 1B][Dir 1B][Nonce 8B]. Carried in a v1 frame;
	// the PC (Central) challenges with AuthDirCentralToPeripheral, the phone
	// (Peripheral) challenges back with AuthDirPeripheralToCentral.
	CmdAuthChallenge CmdID = 0x20
	// CmdAuthResponse answers a CmdAuthChallenge. Layout:
	// [CmdID 1B][Nonce 8B][MAC 16B] where MAC = HMAC-SHA256(key, nonce||dir)[:16].
	CmdAuthResponse CmdID = 0x21
)

// AuthDir identifies which role issued a handshake challenge. The direction
// byte is bound into the response MAC so a challenge captured in one
// direction cannot be replayed as the other direction's proof.
const (
	AuthDirCentralToPeripheral byte = 0x01 // PC 验证手机
	AuthDirPeripheralToCentral byte = 0x02 // 手机验证 PC
)

// Endpoint identifies the server-side API a CMD_API_REQ should be routed to.
// Carried in byte 1 of the CMD_API_REQ payload (spec §2.2).
const (
	// EndpointBookChapter routes to the chapter-blocks API (legacy
	// CMD_BOOK_CHAPTER_REQ behavior). index selects the chapter.
	EndpointBookChapter byte = 0x01
	// EndpointFolders routes to the folders listing API. path is ignored,
	// index is reserved (pass 0).
	EndpointFolders byte = 0x02
	// EndpointBrowseFolder routes to the directory listing API. path is the
	// folder to list; index is reserved (pass 0).
	EndpointBrowseFolder byte = 0x03
	// EndpointBookInfo routes to the book metadata API. path is the book file.
	EndpointBookInfo byte = 0x04
)

// API-request / chunk transport header sizes (excluding the variable Path /
// Chunk bytes). Used to size buffers and to validate short payloads on decode.
const (
	apiReqFixedOverhead = 1 + 1 + 1 + 2     // CmdID + Endpoint + PathLen + Index
	chunkFixedOverhead  = 1 + 2 + 2 + 2 + 2 // CmdID + TotalChunks + ChunkIndex + TotalBytes + ChunkLen
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

// FrameVersion2 marks authenticated data frames (Phase 9 / H-1a). After the
// mutual-challenge handshake completes, only v2 frames are accepted.
const FrameVersion2 byte = 0x02

const maxPayloadLen = 244 // fits in negotiated 247-byte MTU minus 3-byte header

const authedOverhead = 8 + 16                              // seq + truncated HMAC
const maxAuthedPayloadLen = maxPayloadLen - authedOverhead // 220

var (
	ErrTruncated  = errors.New("ble: frame truncated")
	ErrTooLarge   = errors.New("ble: payload exceeds max length")
	ErrBadVersion = errors.New("ble: unsupported frame version")
	// ErrBadCmdID is returned by a payload decoder when the leading CmdID byte
	// does not match the command the decoder was asked to interpret.
	ErrBadCmdID = errors.New("ble: unexpected CmdID")
	// ErrPathTooLong is returned by EncodeApiReqPayload when the path length
	// exceeds the PathLen field's 1-byte ceiling (255 B). Returning an error
	// rather than silently truncating avoids the server fetching a wrong
	// chapter path.
	ErrPathTooLong = errors.New("ble: chapter request path exceeds 255 bytes")
	// ErrBadMAC is returned by DecodeAuthedFrame when the truncated HMAC does
	// not verify — a tampered frame or a wrong/no key (Phase 9 / H-1a).
	ErrBadMAC = errors.New("ble: frame authentication failed")
	// ErrReplaySeq is returned by the Central's receive gate when a v2 frame's
	// seq rolls back or repeats the max seq already seen in that direction
	// (replay / reorder rejection, Phase 9 / H-1a).
	ErrReplaySeq = errors.New("ble: seq replay or rollback rejected")
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

// DeriveBleAuthKey derives the 32-byte BLE channel key from the server's
// Bearer token. Both sides derive the same key from the same token; the
// "lmh-ble-v1:" domain-separation prefix keeps this hash distinct from any
// other use of the token. MUST NOT be called with an empty token — a key
// derived from "" would be publicly computable (the prefix is a constant);
// the Central stores a nil key for an empty token and refuses the data phase.
func DeriveBleAuthKey(token string) []byte {
	h := sha256.Sum256([]byte("lmh-ble-v1:" + token))
	return h[:]
}

// EncodeAuthedFrame wraps payload in a v2 authenticated frame:
//
//	[0x02][len 2B BE][payload ≤220B][seq 8B BE][hmac 16B]
//
// HMAC-SHA256(key) covers [0 : 3+len+8] (version, length, payload, seq) and
// is truncated to 16 bytes. Callers pass a strictly-increasing per-direction
// seq; the receiver rejects rollback/replay.
func EncodeAuthedFrame(payload []byte, seq uint64, key []byte) []byte {
	if len(payload) > maxAuthedPayloadLen {
		payload = payload[:maxAuthedPayloadLen] // 调用方 ChunkJsonBytes 已限 200B，防御性截断
	}
	buf := make([]byte, 3+len(payload)+authedOverhead)
	buf[0] = FrameVersion2
	binary.BigEndian.PutUint16(buf[1:3], uint16(len(payload)))
	copy(buf[3:], payload)
	binary.BigEndian.PutUint64(buf[3+len(payload):], seq)
	mac := hmac.New(sha256.New, key)
	mac.Write(buf[:3+len(payload)+8])
	copy(buf[3+len(payload)+8:], mac.Sum(nil)[:16])
	return buf
}

// DecodeAuthedFrame verifies and parses a v2 authenticated frame. Returns the
// payload, the seq, or ErrBadMAC when the truncated HMAC fails to verify
// (tampered frame / wrong key). The seq is NOT checked here — replay/rollback
// rejection is stateful (max-seen per direction) and lives in the Central.
func DecodeAuthedFrame(data, key []byte) ([]byte, uint64, error) {
	if len(data) < 3+authedOverhead {
		return nil, 0, ErrTruncated
	}
	length := int(binary.BigEndian.Uint16(data[1:3]))
	if length > maxAuthedPayloadLen || len(data) < 3+length+authedOverhead {
		return nil, 0, ErrTooLarge
	}
	mac := hmac.New(sha256.New, key)
	mac.Write(data[:3+length+8])
	want := mac.Sum(nil)[:16]
	if subtle.ConstantTimeCompare(data[3+length+8:3+length+24], want) != 1 {
		return nil, 0, ErrBadMAC
	}
	seq := binary.BigEndian.Uint64(data[3+length : 3+length+8])
	payload := append([]byte(nil), data[3:3+length]...)
	return payload, seq, nil
}

// EncodeAuthChallengePayload builds the CmdAuthChallenge payload:
// [CmdID 1B][dir 1B][nonce 8B]. Carried inside a v1 frame (EncodeFrame).
func EncodeAuthChallengePayload(dir byte, nonce []byte) []byte {
	out := make([]byte, 10)
	out[0] = byte(CmdAuthChallenge)
	out[1] = dir
	copy(out[2:], nonce)
	return out
}

// DecodeAuthChallengePayload parses a CmdAuthChallenge payload, returning the
// direction byte and the 8-byte nonce.
func DecodeAuthChallengePayload(p []byte) (byte, []byte, error) {
	if len(p) < 10 || CmdID(p[0]) != CmdAuthChallenge {
		return 0, nil, ErrTruncated
	}
	return p[1], append([]byte(nil), p[2:10]...), nil
}

// AuthResponseMAC computes the proof for a challenge: a 16-byte truncated
// HMAC-SHA256 over nonce || dir. Binding the direction byte prevents a
// challenge issued in one direction being answered as the other's.
func AuthResponseMAC(key, nonce []byte, dir byte) []byte {
	m := hmac.New(sha256.New, key)
	m.Write(nonce)
	m.Write([]byte{dir})
	return m.Sum(nil)[:16]
}

// EncodeAuthResponsePayload builds the CmdAuthResponse payload:
// [CmdID 1B][nonce 8B][mac 16B]. Carried inside a v1 frame (EncodeFrame).
func EncodeAuthResponsePayload(nonce, mac16 []byte) []byte {
	out := make([]byte, 25)
	out[0] = byte(CmdAuthResponse)
	copy(out[1:9], nonce)
	copy(out[9:], mac16)
	return out
}

// DecodeAuthResponsePayload parses a CmdAuthResponse payload, returning the
// echoed nonce and the 16-byte MAC proof.
func DecodeAuthResponsePayload(p []byte) ([]byte, []byte, error) {
	if len(p) < 25 || CmdID(p[0]) != CmdAuthResponse {
		return nil, nil, ErrTruncated
	}
	return append([]byte(nil), p[1:9]...), append([]byte(nil), p[9:25]...), nil
}

// EncodeApiReqPayload builds the payload for CMD_API_REQ (spec §2.2):
//   [CmdID 1B][Endpoint 1B][PathLen 1B][Path Bytes][Index 2B BE]
// Returns (nil, ErrPathTooLong) if len(path) exceeds the PathLen field's
// 1-byte ceiling (maxPathLen, 255 B). The caller MUST surface this error —
// silently truncating the path would cause the server to fetch the wrong
// chapter path.
func EncodeApiReqPayload(endpoint byte, path string, index int) ([]byte, error) {
	pb := []byte(path)
	if len(pb) > maxPathLen {
		return nil, ErrPathTooLong
	}
	out := make([]byte, apiReqFixedOverhead+len(pb))
	out[0] = byte(CmdApiReq)
	out[1] = endpoint
	out[2] = byte(len(pb))
	copy(out[3:], pb)
	binary.BigEndian.PutUint16(out[3+len(pb):5+len(pb)], uint16(index))
	return out, nil
}

// DecodeApiReqPayload parses a CMD_API_REQ payload (the bytes after the
// 3-byte physical header). Returns endpoint, path, index, or an error if the
// payload is malformed.
//
// Contract: on ANY error path the returned values are zero values. The caller
// therefore MUST treat a non-nil error as terminal.
func DecodeApiReqPayload(payload []byte) (endpoint byte, path string, index int, err error) {
	if len(payload) < apiReqFixedOverhead {
		return 0, "", 0, ErrTruncated
	}
	if CmdID(payload[0]) != CmdApiReq {
		return 0, "", 0, ErrBadCmdID
	}
	endpoint = payload[1]
	pathLen := int(payload[2])
	if len(payload) < apiReqFixedOverhead+pathLen {
		return 0, "", 0, ErrTruncated
	}
	path = string(payload[3 : 3+pathLen])
	index = int(binary.BigEndian.Uint16(payload[3+pathLen : 5+pathLen]))
	return endpoint, path, index, nil
}

// EncodeJsonChunkPayload builds the payload for CMD_JSON_CHUNK (spec §2.2):
//   [CmdID 1B][TotalChunks 2B BE][ChunkIndex 2B BE][TotalBytes 2B BE][ChunkLen 2B BE][Chunk Bytes]
// chunk must already be <= maxPayloadLen - chunkFixedOverhead; the encoder
// does not re-split oversized input (the chunker enforces the bound).
func EncodeJsonChunkPayload(totalChunks, chunkIndex, totalBytes int, chunk []byte) []byte {
	out := make([]byte, chunkFixedOverhead+len(chunk))
	out[0] = byte(CmdJsonChunk)
	binary.BigEndian.PutUint16(out[1:3], uint16(totalChunks))
	binary.BigEndian.PutUint16(out[3:5], uint16(chunkIndex))
	binary.BigEndian.PutUint16(out[5:7], uint16(totalBytes))
	binary.BigEndian.PutUint16(out[7:9], uint16(len(chunk)))
	copy(out[9:], chunk)
	return out
}

// DecodeJsonChunkPayload parses a CMD_JSON_CHUNK payload (the bytes after the
// 3-byte physical header). Returns TotalChunks, ChunkIndex, TotalBytes, the
// chunk bytes, or an error if the payload is malformed.
func DecodeJsonChunkPayload(payload []byte) (totalChunks, chunkIndex, totalBytes int, chunk []byte, err error) {
	if len(payload) < chunkFixedOverhead {
		return 0, 0, 0, nil, ErrTruncated
	}
	if CmdID(payload[0]) != CmdJsonChunk {
		return 0, 0, 0, nil, ErrBadCmdID
	}
	totalChunks = int(binary.BigEndian.Uint16(payload[1:3]))
	chunkIndex = int(binary.BigEndian.Uint16(payload[3:5]))
	totalBytes = int(binary.BigEndian.Uint16(payload[5:7]))
	chunkLen := int(binary.BigEndian.Uint16(payload[7:9]))
	if len(payload) < chunkFixedOverhead+chunkLen {
		return 0, 0, 0, nil, ErrTruncated
	}
	chunk = append([]byte(nil), payload[9:9+chunkLen]...)
	return totalChunks, chunkIndex, totalBytes, chunk, nil
}

// ChunkJsonBytes splits an already-serialized JSON byte slice into N
// CMD_JSON_CHUNK payload frames, each no larger than maxChunkBytes (the spec
// §1.2 "≤ 200 字节" cap, which is stricter than the 244 B MTU ceiling).
// totalBytes is len(jsonBytes) and is carried by every chunk so receivers can
// size progress UI without waiting for the full reassembly.
//
// The returned frames are payloads (no physical header); callers wrap each
// with EncodeFrame before writing to the Command characteristic. The slice is
// non-empty even for empty input (a single empty chunk signals "no content").
func ChunkJsonBytes(jsonBytes []byte) ([][]byte, int, error) {
	totalBytes := len(jsonBytes)

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
	// when the JSON body is empty.
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
		frames = append(frames, EncodeJsonChunkPayload(totalChunks, idx, totalBytes, chunk))
	}
	return frames, totalBytes, nil
}

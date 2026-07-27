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
	"errors"
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

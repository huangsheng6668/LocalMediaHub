package ble

import (
	"bytes"
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

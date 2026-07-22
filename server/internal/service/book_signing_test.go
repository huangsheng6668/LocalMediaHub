package service

import (
	"strings"
	"testing"
)

// TestSignImageDeterministic verifies the same (clientIP, path, manifestID)
// tuple yields the same signature across repeated calls against the same
// signer. This is a load-bearing property: the rewrite loop in
// GetChapterBlocks signs the URL, and GetBookImage recomputes the HMAC for
// verification — a mismatch would make every image 401.
func TestSignImageDeterministic(t *testing.T) {
	s, err := NewBookSigner()
	if err != nil {
		t.Fatalf("NewBookSigner failed: %v", err)
	}
	a := s.SignImage("192.168.1.10", "C:/books/foo.epub", "img-1")
	b := s.SignImage("192.168.1.10", "C:/books/foo.epub", "img-1")
	if a == "" {
		t.Fatal("signature is empty")
	}
	if a != b {
		t.Fatalf("sign not deterministic: %q vs %q", a, b)
	}
}

// TestSignImageDiffersByIP verifies that binding the signature to clientIP
// actually changes the output when IP changes. Without this binding, any
// logged URL could be replayed from any host on the LAN.
func TestSignImageDiffersByIP(t *testing.T) {
	s, _ := NewBookSigner()
	a := s.SignImage("192.168.1.10", "C:/books/foo.epub", "img-1")
	b := s.SignImage("192.168.1.11", "C:/books/foo.epub", "img-1")
	if a == b {
		t.Fatalf("signatures for different IPs must differ; both = %q", a)
	}
}

// TestSignImageDiffersByManifest verifies that swapping the manifest id
// (i.e. targeting a different image inside the same epub from the same IP)
// produces a different signature. Without this, a leaked sig for image A
// would grant access to image B.
func TestSignImageDiffersByManifest(t *testing.T) {
	s, _ := NewBookSigner()
	a := s.SignImage("192.168.1.10", "C:/books/foo.epub", "img-1")
	b := s.SignImage("192.168.1.10", "C:/books/foo.epub", "img-2")
	if a == b {
		t.Fatalf("signatures for different manifest ids must differ; both = %q", a)
	}
}

// TestVerifyImageAcceptsValidSig confirms the round-trip: SignImage then
// VerifyImage returns true.
func TestVerifyImageAcceptsValidSig(t *testing.T) {
	s, _ := NewBookSigner()
	sig := s.SignImage("10.0.0.5", "/data/bar.epub", "cover")
	if !s.VerifyImage("10.0.0.5", "/data/bar.epub", "cover", sig) {
		t.Fatal("VerifyImage rejected a valid signature")
	}
}

// TestVerifyImageRejectsTamperedSig confirms a one-character mutation of the
// signature is rejected (not accepted via prefix match or length mismatch).
// Appending "x" specifically exercises both the base64 decode tolerance and
// the constant-time compare.
func TestVerifyImageRejectsTamperedSig(t *testing.T) {
	s, _ := NewBookSigner()
	sig := s.SignImage("10.0.0.5", "/data/bar.epub", "cover")
	tampered := sig + "x"
	if s.VerifyImage("10.0.0.5", "/data/bar.epub", "cover", tampered) {
		t.Fatal("VerifyImage accepted a tampered signature")
	}
	// Sanity: tampered string really is different.
	if !strings.HasPrefix(tampered, sig) {
		t.Fatal("test invariant broken: tampered does not extend sig")
	}
}

// TestVerifyImageRejectsAcrossSigners confirms a signature from one process
// (signer A) does not validate against another (signer B). This models the
// "server restart invalidates all outstanding URLs" property called out in
// the brief.
func TestVerifyImageRejectsAcrossSigners(t *testing.T) {
	a, _ := NewBookSigner()
	b, _ := NewBookSigner()
	sig := a.SignImage("10.0.0.5", "/data/bar.epub", "cover")
	if b.VerifyImage("10.0.0.5", "/data/bar.epub", "cover", sig) {
		t.Fatal("signature from signer A must not validate on signer B")
	}
}

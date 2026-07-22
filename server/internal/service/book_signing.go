// Package service / book_signing.go — HMAC-signed book image URLs.
//
// BookSigner produces short, server-side HMAC-SHA256 signatures bound to
// (clientIP, path, manifestID) so that <img src="/api/v1/books/image?..."> tags
// can authenticate without inlining the Bearer token in the URL (which would
// leak via access logs, browser history, and Referer headers).
//
// The secret is 32 random bytes generated with crypto/rand at server startup;
// it is never persisted, so every restart invalidates all outstanding signed
// URLs. There is no expiry field by design — the brief specifies "no expiry;
// server restart invalidates all".
package service

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
)

// BookSigner signs and verifies book image URLs using a per-process HMAC
// secret. The zero value is NOT usable — construct via NewBookSigner.
type BookSigner struct {
	serverSecret []byte
}

// NewBookSigner generates a fresh 32-byte HMAC secret from crypto/rand and
// returns a signer ready to use. Failure to read random bytes is fatal and
// is surfaced as an error so the caller can abort startup.
func NewBookSigner() (*BookSigner, error) {
	secret := make([]byte, 32)
	if _, err := rand.Read(secret); err != nil {
		return nil, fmt.Errorf("book signer: failed to generate secret: %w", err)
	}
	if len(secret) != 32 {
		// Defensive: crypto/rand.Read never returns short reads in practice,
		// but the cost of this guard is trivial.
		return nil, errors.New("book signer: short read from crypto/rand")
	}
	return &BookSigner{serverSecret: secret}, nil
}

// SignImage returns a base64 RawURLEncoding HMAC-SHA256 signature over
// "clientIP|path|manifestID". Inputs are concatenated as-is without escaping
// because:
//   - clientIP comes from c.RealIP() (a trusted header under our CORS + the
//     app's LAN-only deployment model).
//   - path is the validated, resolved filesystem path.
//   - manifestID is the parser's manifest id (alphanumeric in practice).
//
// Output is URL-safe without padding so it can be embedded in a ?sig= query
// parameter without further encoding.
func (s *BookSigner) SignImage(clientIP, path, manifestID string) string {
	mac := hmac.New(sha256.New, s.serverSecret)
	mac.Write([]byte(clientIP))
	mac.Write([]byte("|"))
	mac.Write([]byte(path))
	mac.Write([]byte("|"))
	mac.Write([]byte(manifestID))
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// VerifyImage recomputes the HMAC over the supplied (clientIP, path,
// manifestID) tuple and compares it against sig using
// subtle.ConstantTimeCompare. Returns true only when both length and contents
// match. Any decode/format error returns false (treated as a bad signature).
func (s *BookSigner) VerifyImage(clientIP, path, manifestID, sig string) bool {
	if sig == "" {
		return false
	}
	want, err := base64.RawURLEncoding.DecodeString(sig)
	if err != nil {
		return false
	}
	mac := hmac.New(sha256.New, s.serverSecret)
	mac.Write([]byte(clientIP))
	mac.Write([]byte("|"))
	mac.Write([]byte(path))
	mac.Write([]byte("|"))
	mac.Write([]byte(manifestID))
	expected := mac.Sum(nil)
	return subtle.ConstantTimeCompare(want, expected) == 1
}

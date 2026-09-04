package handler

import (
	"testing"

	"github.com/stretchr/testify/assert"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
)

// TestIsMediaExtIncludesText verifies that isMediaExt returns true for text
// extensions configured via cfg.Scan.TextExtensions (added in Task 3 of the
// text-reader plan), in addition to the existing video/image extensions.
// Case-insensitivity (".EPUB") must also be accepted, matching the existing
// EqualFold-based behavior for video/image extensions.
func TestIsMediaExtIncludesText(t *testing.T) {
	cfg := &config.Config{}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.ImageExtensions = []string{".jpg"}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	h := New(cfg, service.NewScanner(nil, nil, nil), nil, nil, nil, nil, nil, nil)
	assert.True(t, h.isMediaExt(".txt"))
	assert.True(t, h.isMediaExt(".EPUB"))
	assert.True(t, h.isMediaExt(".mp4"))
	assert.False(t, h.isMediaExt(".exe"))
}

// TestMediaExtensionsIncludesText verifies that mediaExtensions() includes the
// configured text extensions alongside video/image, so that downstream folder
// browsing / zip-download code that iterates over mediaExtensions() also picks
// up text files (fully wired in Task 8; here we only assert the slice contents).
func TestMediaExtensionsIncludesText(t *testing.T) {
	cfg := &config.Config{}
	cfg.Scan.TextExtensions = []string{".txt"}
	h := New(cfg, nil, nil, nil, nil, nil, nil, nil)
	all := h.mediaExtensions()
	assert.Contains(t, all, ".txt")
}

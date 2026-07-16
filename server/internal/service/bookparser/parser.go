// Package bookparser parses local ebook files into a Book structure with
// chapter metadata. The full text is NOT retained — ChapterText re-reads
// the file on demand to keep cache entries small.
package bookparser

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	MaxTxtSize  = 50 * 1024 * 1024
	MaxEpubSize = 100 * 1024 * 1024
)

var (
	ErrUnsupported = errors.New("bookparser: format not supported")
	ErrTooLarge    = errors.New("bookparser: file too large")
	ErrInvalidEpub = errors.New("bookparser: invalid epub structure")
	ErrEncrypted   = errors.New("bookparser: drm-encrypted ebook")
	ErrIoFailure   = errors.New("bookparser: io failure")
)

type Chapter struct {
	Title      string `json:"title"`
	Index      int    `json:"index"`
	CharStart  int    `json:"char_start,omitempty"`
	CharEnd    int    `json:"char_end,omitempty"`
	ManifestID string `json:"manifest_id,omitempty"`
}

type Book struct {
	Path     string    `json:"path"`
	Format   string    `json:"format"`
	Title    string    `json:"title"`
	Charset  string    `json:"charset,omitempty"`
	Chapters []Chapter `json:"chapters"`
	ModTime  time.Time `json:"mod_time"`

	// epub-only resolver state (populated by parseEpub). Never serialized.
	epubManifest map[string]string `json:"-"`
	epubOpfDir   string            `json:"-"`
}

// Parse routes by lowercased extension. mobi/azw3/unknown return a Book
// with Format="unsupported" and ErrUnsupported — callers must check both.
func Parse(path string) (*Book, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".txt":
		return parseTxt(path, info)
	case ".epub":
		return parseEpub(path, info)
	default:
		return parseUnsupported(path, info)
	}
}

func (b *Book) ChapterText(idx int) (string, error) {
	if idx < 0 || idx >= len(b.Chapters) {
		return "", fmt.Errorf("chapter index out of range")
	}
	switch b.Format {
	case "txt":
		return b.txtChapterText(idx)
	case "epub":
		return b.epubChapterText(idx)
	default:
		return "", ErrUnsupported
	}
}

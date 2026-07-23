// Package bookparser parses local ebook files into a Book structure with
// chapter metadata. The full text is NOT retained — ChapterBlocks re-reads
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
	Volume     string `json:"volume,omitempty"`
	VolIndex   int    `json:"vol_index,omitempty"`
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

// Block is one ordered unit of a chapter's content. A chapter is a []Block.
// Text blocks carry plain UTF-8 text in Value; image blocks carry a URL or
// data: URI in Src. The service layer rewrites relative epub paths to
// /api/v1/books/image URLs before returning blocks to clients.
type Block struct {
	Type  string `json:"type"`            // "text" | "image"
	Value string `json:"value,omitempty"` // text block
	Src   string `json:"src,omitempty"`   // image block
}

// ChapterBlocks returns the ordered content blocks for chapter idx.
// Image blocks' Src is the raw epub href (relative path, absolute path,
// data: URI, or http(s):// URL). Callers (BookService) rewrite the
// relative ones to /api/v1/books/image endpoint URLs.
func (b *Book) ChapterBlocks(idx int) ([]Block, error) {
	if idx < 0 || idx >= len(b.Chapters) {
		return nil, fmt.Errorf("chapter index out of range: %d", idx)
	}
	switch b.Format {
	case "txt":
		return b.txtChapterBlocks(idx)
	case "epub":
		return b.epubChapterBlocks(idx)
	default:
		return nil, fmt.Errorf("%w: format %s", ErrUnsupported, b.Format)
	}
}

// EpubManifest exposes the parsed OPF manifest (id → href) for service-layer
// image-src rewriting. Returns nil for non-epub books. Callers must NOT
// mutate the map.
func (b *Book) EpubManifest() map[string]string { return b.epubManifest }

// EpubOpfDir exposes the directory of the OPF file inside the epub zip,
// used to resolve relative hrefs. Empty for non-epub books.
func (b *Book) EpubOpfDir() string { return b.epubOpfDir }

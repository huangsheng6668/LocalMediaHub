// Package service / book.go — BookService with mtime-keyed cache and
// singleflight deduplication.
//
// BookService parses text files (txt/epub via bookparser, plus mobi/azw3 which
// bookparser reports as Format="unsupported") and caches the resulting *Book by
// absolute path keyed on the file's mtime. When a subsequent GetBook call sees
// the same path with the same mtime, it returns the cached *Book without
// re-parsing. Concurrent GetBook calls for the same path are coalesced via
// singleflight so the parser runs at most once per in-flight batch.
//
// Callers must NOT mutate the returned *Book — it is shared across cache hits.
package service

import (
	"archive/zip"
	"context"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/service/bookparser"
)

// BookService parses books and caches the result keyed by (path, mtime).
// The zero value is not usable — construct via NewBookService.
type BookService struct {
	mu     sync.RWMutex
	cache  map[string]*bookparser.Book
	sf     singleflight.Group
	signer *BookSigner
}

// NewBookService returns a BookService ready to serve GetBook calls.
func NewBookService() *BookService {
	return &BookService{cache: make(map[string]*bookparser.Book)}
}

// SetSigner injects the per-process BookSigner used to sign rewritten
// <img> src URLs. When nil (open mode, no token configured) GetChapterBlocks
// produces the legacy unsigned URL and the /books/image endpoint falls back
// to the deprecated ?token= query parameter. Production wiring lives in
// server.New; tests that don't care about signing can leave it unset.
func (s *BookService) SetSigner(signer *BookSigner) {
	s.signer = signer
}

// GetBook returns the parsed *Book for path. On a cache hit (same path AND
// same mtime as the previously cached parse) the cached *Book is returned
// without re-parsing. Concurrent calls for the same path are coalesced via
// singleflight.
//
// Unsupported formats (mobi/azw3) are still cached — bookparser.Parse returns
// (Book{Format:"unsupported"}, ErrUnsupported) and GetBook swallows that
// specific error so callers get a usable *Book with Format set.
//
// The returned *Book is shared; callers must not mutate it.
func (s *BookService) GetBook(path string) (*bookparser.Book, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", bookparser.ErrIoFailure, err)
	}

	s.mu.RLock()
	cached, ok := s.cache[path]
	s.mu.RUnlock()
	if ok && cached.ModTime.Equal(info.ModTime()) {
		return cached, nil
	}

	v, err, _ := s.sf.Do(path, func() (interface{}, error) {
		b, perr := bookparser.Parse(path)
		if perr != nil && !errors.Is(perr, bookparser.ErrUnsupported) {
			return nil, perr
		}
		s.mu.Lock()
		s.cache[path] = b
		s.mu.Unlock()
		return b, nil
	})
	if err != nil {
		return nil, err
	}
	return v.(*bookparser.Book), nil
}

// GetChapterBlocks returns the ordered content blocks for chapter idx of the
// book at path. Image blocks' Src is rewritten to a
// /api/v1/books/image?path=...&manifest=... URL that the client can fetch
// through the authenticated book-image endpoint, unless the original Src is
// empty or a data: URI — data: URIs are passed through unchanged (epub-spec
// legal and allowed by CSP img-src). Absolute http(s):// URLs are stripped to
// "" (Phase 9 / L-11): CSP blocks the external fetch anyway, so blanking the
// src makes the client render a placeholder instead of a silently broken
// image. If no manifest entry matches the Src, the Src is set to "" so
// clients can render a placeholder (e.g. "[本图片无法显示]").
//
// When a BookSigner has been injected via SetSigner (production), the
// rewritten URL also carries &sig=<hmac> bound to (clientIP, path,
// manifestID); the /books/image endpoint verifies this signature instead of
// reading the Bearer token from the query string. When the signer is nil
// (open mode), the legacy unsigned URL is produced and the endpoint falls
// back to the deprecated ?token= query parameter.
//
// ctx is accepted for future cancellation propagation (GetBook + the parser
// are currently synchronous); clientIP is the requester's IP used for HMAC
// binding. The returned slice is a fresh copy; callers may mutate it. The
// underlying *Book remains shared and must not be mutated.
func (s *BookService) GetChapterBlocks(ctx context.Context, path string, idx int, clientIP string) ([]bookparser.Block, error) {
	_ = ctx // reserved for future cancellation; GetBook/ChapterBlocks are synchronous today
	b, err := s.GetBook(path)
	if err != nil {
		return nil, err
	}
	blocks, err := b.ChapterBlocks(idx)
	if err != nil {
		return nil, err
	}
	// ChapterBlocks may return the parser's backing slice (epub path) or a
	// fresh slice (placeholder fallback). Copy defensively so we never
	// mutate the cached *Book's internal state.
	out := make([]bookparser.Block, len(blocks))
	copy(out, blocks)
	for i := range out {
		if out[i].Type != "image" {
			continue
		}
		src := out[i].Src
		if src == "" {
			continue
		}
		if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
			// Phase 9 (L-11)：外联图片剥离 —— CSP img-src 'self' data: 本就拦截，
			// 服务端置空让客户端渲染占位符而不是静默破图；data: 合法保留。
			out[i].Src = ""
			continue
		}
		if strings.HasPrefix(src, "data:") {
			continue
		}
		manifestID := reverseLookupManifest(b.EpubManifest(), src)
		if manifestID == "" {
			out[i].Src = ""
			continue
		}
		base := fmt.Sprintf("/api/v1/books/image?path=%s&manifest=%s",
			url.QueryEscape(path), url.QueryEscape(manifestID))
		if s.signer != nil {
			sig := s.signer.SignImage(clientIP, path, manifestID)
			base += "&sig=" + sig
		}
		out[i].Src = base
	}
	return out, nil
}

// reverseLookupManifest returns the manifest id whose href matches src after
// NormalizeHref is applied to both sides. NormalizeHref strips #fragment
// suffixes and normalises slash direction, so a src of "images/foo.jpg#bar"
// matches a manifest href of "images\foo.jpg". Returns "" if no entry matches
// or the manifest is nil/empty.
func reverseLookupManifest(manifest map[string]string, src string) string {
	if manifest == nil || src == "" {
		return ""
	}
	normalized := bookparser.NormalizeHref(src)
	if normalized == "" {
		return ""
	}
	for id, href := range manifest {
		if bookparser.NormalizeHref(href) == normalized {
			return id
		}
	}
	return ""
}

// ReadImageBytes opens the epub at path, looks up manifestID in the OPF
// manifest, and returns the raw image bytes plus a MIME content type inferred
// from the file extension.
//
// Security: fullPath is rejected if it starts with "/", "../", or equals ".." —
// defends against path traversal even though manifest hrefs come from parsed
// XML and should be safe.
//
// Size: capped at bookparser.MaxEpubEntrySize (16 MiB) per image —
// maliciously large entries return an error wrapping bookparser.ErrTooLarge.
func (s *BookService) ReadImageBytes(path, manifestID string) ([]byte, string, error) {
	b, err := s.GetBook(path)
	if err != nil {
		return nil, "", err
	}
	if b.Format != "epub" {
		return nil, "", fmt.Errorf("%w: image fetch only for epub", bookparser.ErrUnsupported)
	}
	manifest := b.EpubManifest()
	href, ok := manifest[manifestID]
	if !ok {
		return nil, "", fmt.Errorf("%w: manifest id not found: %s", bookparser.ErrInvalidEpub, manifestID)
	}
	fullPath := bookparser.JoinZipPath(b.EpubOpfDir(), href)
	// Defensive: block any path that would escape the zip root.
	if strings.HasPrefix(fullPath, "../") || fullPath == ".." || strings.HasPrefix(fullPath, "/") {
		return nil, "", fmt.Errorf("%w: invalid manifest href: %s", bookparser.ErrInvalidEpub, fullPath)
	}
	zr, err := zip.OpenReader(path)
	if err != nil {
		return nil, "", fmt.Errorf("%w: %v", bookparser.ErrIoFailure, err)
	}
	defer zr.Close()
	rc, err := zr.Open(fullPath)
	if err != nil {
		return nil, "", fmt.Errorf("%w: image not found in epub: %s", bookparser.ErrInvalidEpub, fullPath)
	}
	defer rc.Close()
	data, err := bookparser.ReadCapped(rc, bookparser.MaxEpubEntrySize)
	if err != nil {
		return nil, "", err
	}
	return data, mimeByExtension(filepath.Ext(fullPath)), nil
}

// mimeByExtension maps common image extensions to MIME types. Returns
// "application/octet-stream" for unknown extensions.
func mimeByExtension(ext string) string {
	switch strings.ToLower(ext) {
	case ".jpg", ".jpeg":
		return "image/jpeg"
	case ".png":
		return "image/png"
	case ".gif":
		return "image/gif"
	case ".webp":
		return "image/webp"
	case ".svg":
		return "image/svg+xml"
	case ".bmp":
		return "image/bmp"
	default:
		return "application/octet-stream"
	}
}

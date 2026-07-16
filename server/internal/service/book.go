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
	"errors"
	"fmt"
	"os"
	"sync"

	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/service/bookparser"
)

// BookService parses books and caches the result keyed by (path, mtime).
// The zero value is not usable — construct via NewBookService.
type BookService struct {
	mu    sync.RWMutex
	cache map[string]*bookparser.Book
	sf    singleflight.Group
}

// NewBookService returns a BookService ready to serve GetBook calls.
func NewBookService() *BookService {
	return &BookService{cache: make(map[string]*bookparser.Book)}
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

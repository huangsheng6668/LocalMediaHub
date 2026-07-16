package service

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestBookServiceCachesByMtime(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.txt")
	require.NoError(t, os.WriteFile(p, []byte("hello"), 0644))

	s := NewBookService()
	b1, err := s.GetBook(p)
	require.NoError(t, err)
	b2, err := s.GetBook(p)
	require.NoError(t, err)
	assert.Same(t, b1, b2, "expected identical *Book on cache hit")
}

func TestBookServiceReparseAfterMtimeChange(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.txt")
	require.NoError(t, os.WriteFile(p, []byte("v1"), 0644))

	s := NewBookService()
	b1, _ := s.GetBook(p)
	require.NoError(t, os.Chtimes(p, time.Now().Add(time.Second), time.Now().Add(time.Second)))
	b2, _ := s.GetBook(p)
	assert.NotSame(t, b1, b2, "expected new *Book after mtime change")
}

func TestBookServiceConcurrentCallsDontPanic(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "x.txt")
	require.NoError(t, os.WriteFile(p, []byte("data"), 0644))

	s := NewBookService()
	done := make(chan struct{})
	for i := 0; i < 10; i++ {
		go func() {
			defer func() { done <- struct{}{} }()
			_, _ = s.GetBook(p)
		}()
	}
	for i := 0; i < 10; i++ {
		<-done
	}
	b, err := s.GetBook(p)
	require.NoError(t, err)
	assert.NotNil(t, b)
}

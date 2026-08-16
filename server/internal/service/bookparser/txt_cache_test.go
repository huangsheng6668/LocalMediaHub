package bookparser

import (
	"errors"
	"testing"
	"time"
)

func TestTxtCacheSlice(t *testing.T) {
	text := "First paragraph.\n\nSecond paragraph.\n\nThird paragraph."
	blocks := GetChapterBlocksFromText(text, 0, len([]rune(text)))
	if len(blocks) != 3 {
		t.Fatalf("expected 3 blocks, got %d", len(blocks))
	}
	if blocks[0].Value != "First paragraph." {
		t.Errorf("got %q, want %q", blocks[0].Value, "First paragraph.")
	}

	// Test empty slice fallback
	emptyBlocks := GetChapterBlocksFromText(text, 5, 5)
	if len(emptyBlocks) != 1 || emptyBlocks[0].Value != "[本章节为空]" {
		t.Errorf("expected empty chapter fallback block, got %v", emptyBlocks)
	}

	// Test GetRuneCount
	if count := GetRuneCount("Hello, 世界"); count != 9 {
		t.Errorf("GetRuneCount got %d, want 9", count)
	}
}

func TestGlobalTxtCache(t *testing.T) {
	cache := &txtCache{
		entries: make(map[string]*txtCacheEntry),
		maxCap:  2,
	}

	loadCount := 0
	loadFn := func(content string) func() (string, string, error) {
		return func() (string, string, error) {
			loadCount++
			return content, "UTF-8", nil
		}
	}
	mt1 := time.Date(2025, 1, 1, 0, 0, 0, 0, time.UTC)

	// First load file1
	text, cs, err := cache.GetOrLoad("file1.txt", mt1, loadFn("content1"))
	if err != nil || text != "content1" || cs != "UTF-8" {
		t.Fatalf("failed to load file1: %v, %s, %s", err, text, cs)
	}
	if loadCount != 1 {
		t.Fatalf("expected loadCount 1, got %d", loadCount)
	}

	// Second load file1 (cached)
	text, cs, err = cache.GetOrLoad("file1.txt", mt1, loadFn("content1"))
	if err != nil || text != "content1" {
		t.Fatalf("failed cached load file1: %v", err)
	}
	if loadCount != 1 {
		t.Fatalf("expected loadCount to remain 1, got %d", loadCount)
	}

	// Same path but different modtime → fresh load (edited file must not
	// serve stale text; regression guard for the modtime-keyed cache).
	mt2 := mt1.Add(time.Hour)
	text, _, err = cache.GetOrLoad("file1.txt", mt2, loadFn("content1-edited"))
	if err != nil || text != "content1-edited" {
		t.Fatalf("failed modtime-variant load: %v, %s", err, text)
	}
	if loadCount != 2 {
		t.Fatalf("expected loadCount 2 after modtime change, got %d", loadCount)
	}

	// Load file2
	_, _, _ = cache.GetOrLoad("file2.txt", mt1, loadFn("content2"))
	// Load file3 (should evict the oldest entry, file1@mt1)
	_, _, _ = cache.GetOrLoad("file3.txt", mt1, loadFn("content3"))

	// Reload file1@mt1 (should trigger loadFn again due to eviction)
	text, _, _ = cache.GetOrLoad("file1.txt", mt1, loadFn("content1"))
	if loadCount != 5 {
		t.Errorf("expected loadCount 5 after eviction reload, got %d", loadCount)
	}

	// Test load error
	errErr := errors.New("read error")
	_, _, err = cache.GetOrLoad("error.txt", mt1, func() (string, string, error) {
		return "", "", errErr
	})
	if !errors.Is(err, errErr) {
		t.Errorf("expected err %v, got %v", errErr, err)
	}
}

// TestGlobalTxtCacheRunes verifies the runes variant shares the same cache
// entry as GetOrLoad (one load + one rune conversion total) and slices
// correctly — the regression this guards: every chapter request used to
// re-run []rune(text) over the whole book.
func TestGlobalTxtCacheRunes(t *testing.T) {
	cache := &txtCache{
		entries: make(map[string]*txtCacheEntry),
		maxCap:  4,
	}
	mt := time.Date(2025, 2, 2, 0, 0, 0, 0, time.UTC)
	loads := 0
	loadFn := func() (string, string, error) {
		loads++
		return "第一章\n\nbody\n", "UTF-8", nil
	}

	_, _, err := cache.GetOrLoad("book.txt", mt, loadFn)
	if err != nil {
		t.Fatalf("GetOrLoad: %v", err)
	}
	_, runes, err := cache.GetOrLoadRunes("book.txt", mt, loadFn)
	if err != nil {
		t.Fatalf("GetOrLoadRunes: %v", err)
	}
	if loads != 1 {
		t.Fatalf("expected exactly one load across both variants, got %d", loads)
	}
	if string(runes) != "第一章\n\nbody\n" {
		t.Fatalf("runes mismatch: %q", string(runes))
	}

	blocks := GetChapterBlocksFromRunes(runes, 3, len(runes)) // skip "第一章"
	if len(blocks) != 1 || blocks[0].Value != "body" {
		t.Fatalf("unexpected blocks: %+v", blocks)
	}
}

package bookparser

import (
	"errors"
	"testing"
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

	// First load file1
	text, cs, err := cache.GetOrLoad("file1.txt", loadFn("content1"))
	if err != nil || text != "content1" || cs != "UTF-8" {
		t.Fatalf("failed to load file1: %v, %s, %s", err, text, cs)
	}
	if loadCount != 1 {
		t.Fatalf("expected loadCount 1, got %d", loadCount)
	}

	// Second load file1 (cached)
	text, cs, err = cache.GetOrLoad("file1.txt", loadFn("content1"))
	if err != nil || text != "content1" {
		t.Fatalf("failed cached load file1: %v", err)
	}
	if loadCount != 1 {
		t.Fatalf("expected loadCount to remain 1, got %d", loadCount)
	}

	// Load file2
	_, _, _ = cache.GetOrLoad("file2.txt", loadFn("content2"))
	// Load file3 (should evict file1)
	_, _, _ = cache.GetOrLoad("file3.txt", loadFn("content3"))

	// Reload file1 (should trigger loadFn again due to eviction)
	text, _, _ = cache.GetOrLoad("file1.txt", loadFn("content1"))
	if loadCount != 4 {
		t.Errorf("expected loadCount 4 after eviction reload, got %d", loadCount)
	}

	// Test load error
	errErr := errors.New("read error")
	_, _, err = cache.GetOrLoad("error.txt", func() (string, string, error) {
		return "", "", errErr
	})
	if !errors.Is(err, errErr) {
		t.Errorf("expected err %v, got %v", errErr, err)
	}
}

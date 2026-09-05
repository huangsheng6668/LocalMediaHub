package bookparser

import (
	"strings"
	"sync"
	"time"
)

type txtCacheEntry struct {
	text    string
	charset string
	// runes is the []rune form of text, converted once at load time. Chapter
	// slicing operates on rune offsets (char_start/char_end), so without this
	// cache every chapter request re-converted the whole book (potentially
	// MBs of text) via []rune(text) — O(book) work per chapter fetch.
	runes []rune
}

type txtCache struct {
	mu      sync.RWMutex
	entries map[string]*txtCacheEntry
	order   []string
	maxCap  int
}

var globalTxtCache = &txtCache{
	entries: make(map[string]*txtCacheEntry),
	maxCap:  20,
}

// getOrLoadEntry returns the cached entry for path+modTime, loading (and
// converting to []rune exactly once) on miss. The eviction policy is FIFO by
// insertion order (historically documented as LRU but access does not refresh
// recency — the 20-entry cap makes the distinction immaterial in practice).
func (c *txtCache) getOrLoadEntry(key string, loadFn func() (string, string, error)) (*txtCacheEntry, error) {
	c.mu.RLock()
	if entry, ok := c.entries[key]; ok {
		c.mu.RUnlock()
		return entry, nil
	}
	c.mu.RUnlock()

	text, charset, err := loadFn()
	if err != nil {
		return nil, err
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if entry, ok := c.entries[key]; ok {
		return entry, nil
	}

	if len(c.order) >= c.maxCap {
		oldest := c.order[0]
		c.order = c.order[1:]
		delete(c.entries, oldest)
	}
	entry := &txtCacheEntry{text: text, charset: charset, runes: []rune(text)}
	c.entries[key] = entry
	c.order = append(c.order, key)
	return entry, nil
}

// GetOrLoad returns the cached decoded text for path, keyed by path + the
// file's modtime. Including the modtime means editing a book file produces a
// new cache key instead of silently serving the stale text (which also made
// BookService's modtime-based metadata revalidation ineffective — the
// re-parsed chapters were still sliced from the old text).
func (c *txtCache) GetOrLoad(path string, modTime time.Time, loadFn func() (string, string, error)) (string, string, error) {
	key := path + "|" + modTime.Format(time.RFC3339Nano)
	entry, err := c.getOrLoadEntry(key, loadFn)
	if err != nil {
		return "", "", err
	}
	return entry.text, entry.charset, nil
}

// GetOrLoadRunes is GetOrLoad plus the cached []rune form, so chapter slicing
// can reuse the one-time conversion instead of re-running []rune(text) per
// chapter request.
func (c *txtCache) GetOrLoadRunes(path string, modTime time.Time, loadFn func() (string, string, error)) (string, []rune, error) {
	key := path + "|" + modTime.Format(time.RFC3339Nano)
	entry, err := c.getOrLoadEntry(key, loadFn)
	if err != nil {
		return "", nil, err
	}
	return entry.text, entry.runes, nil
}

// GetChapterBlocksFromRunes splits the rune range [charStart, charEnd) into
// paragraph blocks (blank-line separated; tabs treated as line breaks).
func GetChapterBlocksFromRunes(runes []rune, charStart, charEnd int) []Block {
	start := clampInt(charStart, 0, len(runes))
	end := clampInt(charEnd, 0, len(runes))
	if start > end {
		start = end
	}
	slice := string(runes[start:end])
	slice = strings.ReplaceAll(slice, "\r\n", "\n")
	slice = strings.ReplaceAll(slice, "\t", "\n")

	paras := strings.Split(slice, "\n")
	blocks := make([]Block, 0, len(paras))
	for _, p := range paras {
		if s := strings.TrimSpace(p); s != "" {
			blocks = append(blocks, Block{Type: "text", Value: s})
		}
	}
	if len(blocks) == 0 {
		return []Block{{Type: "text", Value: "[本章节为空]"}}
	}
	return blocks
}

func clampInt(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

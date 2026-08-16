package bookparser

import (
	"strings"
	"sync"
	"time"
	"unicode/utf8"
)

type txtCacheEntry struct {
	text    string
	charset string
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

// GetOrLoad returns the cached decoded text for path, keyed by path + the
// file's modtime. Including the modtime means editing a book file produces a
// new cache key instead of silently serving the stale text (which also made
// BookService's modtime-based metadata revalidation ineffective — the
// re-parsed chapters were still sliced from the old text).
func (c *txtCache) GetOrLoad(path string, modTime time.Time, loadFn func() (string, string, error)) (string, string, error) {
	key := path + "|" + modTime.Format(time.RFC3339Nano)

	c.mu.RLock()
	if entry, ok := c.entries[key]; ok {
		c.mu.RUnlock()
		return entry.text, entry.charset, nil
	}
	c.mu.RUnlock()

	text, charset, err := loadFn()
	if err != nil {
		return "", "", err
	}

	c.mu.Lock()
	defer c.mu.Unlock()

	if entry, ok := c.entries[key]; ok {
		return entry.text, entry.charset, nil
	}

	if len(c.order) >= c.maxCap {
		oldest := c.order[0]
		c.order = c.order[1:]
		delete(c.entries, oldest)
	}
	c.entries[key] = &txtCacheEntry{text: text, charset: charset}
	c.order = append(c.order, key)
	return text, charset, nil
}

func GetChapterBlocksFromText(text string, charStart, charEnd int) []Block {
	runes := []rune(text)
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

func GetRuneCount(text string) int {
	return utf8.RuneCountInString(text)
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


package bookparser

import (
	"strings"
	"sync"
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

func (c *txtCache) GetOrLoad(path string, loadFn func() (string, string, error)) (string, string, error) {
	c.mu.RLock()
	if entry, ok := c.entries[path]; ok {
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

	if entry, ok := c.entries[path]; ok {
		return entry.text, entry.charset, nil
	}

	if len(c.order) >= c.maxCap {
		oldest := c.order[0]
		c.order = c.order[1:]
		delete(c.entries, oldest)
	}
	c.entries[path] = &txtCacheEntry{text: text, charset: charset}
	c.order = append(c.order, path)
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
	paras := strings.Split(slice, "\n\n")
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

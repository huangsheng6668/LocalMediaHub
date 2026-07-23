# Server Bookparser Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** High-performance chapter slicing LRU cache, non-standard chapter title regex rules, and volume hierarchy support in the Go server `bookparser` engine.

**Architecture:** Create `rules.go` for centralized regex rules, create `txt_cache.go` for LRU decoded text caching and fast slicing, extend `parser.go` with Volume fields, and refactor `txt.go` with full test coverage and benchmarks.

**Tech Stack:** Go 1.22+, `golang.org/x/text`, standard library `sync`, `regexp`, `bufio`.

## Global Constraints

- Absolute file paths in project repository: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/`
- Zero external third-party dependencies outside standard library and `golang.org/x/text`.
- Handler/Service functions must remain thread-safe and free of global mutable state.

---

### Task 1: Extend Chapter Struct and Create rules.go

**Files:**
- Modify: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/parser.go:1-35`
- Create: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/rules.go`
- Test: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/rules_test.go`

**Interfaces:**
- Produces: `Chapter` struct with `Volume` (string) and `VolIndex` (int) fields.
- Produces: `IsVolumeHeader(line string) (bool, string)`
- Produces: `IsChapterHeader(line string) bool`

- [ ] **Step 1: Write unit tests for rules.go**

Create `E:/github_project/LocalMediaHub/server/internal/service/bookparser/rules_test.go`:

```go
package bookparser

import "testing"

func TestIsVolumeHeader(t *testing.T) {
	tests := []struct {
		line     string
		wantMatch bool
		wantVol   string
	}{
		{"第一卷 创世纪", true, "第一卷 创世纪"},
		{"【第一卷】 序幕", true, "【第一卷】 序幕"},
		{"Volume 1 The Beginning", true, "Volume 1 The Beginning"},
		{"第1章 决战", false, ""},
	}

	for _, tt := range tests {
		gotMatch, gotVol := IsVolumeHeader(tt.line)
		if gotMatch != tt.wantMatch {
			t.Errorf("IsVolumeHeader(%q) match = %v, want %v", tt.line, gotMatch, tt.wantMatch)
		}
		if gotMatch && gotVol != tt.wantVol {
			t.Errorf("IsVolumeHeader(%q) vol = %q, want %q", tt.line, gotVol, tt.wantVol)
		}
	}
}

func TestIsEnclosedChapterHeader(t *testing.T) {
	tests := []struct {
		line string
		want bool
	}{
		{"【第123章 决战】", true},
		{"=== 第5章 启程 ===", true},
		{"102. 再次重逢", true},
		{"这是一普通正文句子。", false},
	}

	for _, tt := range tests {
		if got := IsChapterHeader(tt.line); got != tt.want {
			t.Errorf("IsChapterHeader(%q) = %v, want %v", tt.line, got, tt.want)
		}
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/bookparser -run TestIsVolumeHeader`
Expected: FAIL with undefined `IsVolumeHeader`.

- [ ] **Step 3: Modify parser.go and create rules.go**

In `E:/github_project/LocalMediaHub/server/internal/service/bookparser/parser.go`, update `Chapter` struct:

```go
type Chapter struct {
	Title     string `json:"title"`
	Index     int    `json:"index"`
	CharStart int    `json:"char_start"`
	CharEnd   int    `json:"char_end"`
	Volume    string `json:"volume,omitempty"`
	VolIndex  int    `json:"vol_index,omitempty"`
}
```

Create `E:/github_project/LocalMediaHub/server/internal/service/bookparser/rules.go`:

```go
package bookparser

import (
	"regexp"
	"strings"
	"unicode/utf8"
)

var volumeRules = []*regexp.Regexp{
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷\s*.*`),
	regexp.MustCompile(`^(?:Volume|Vol\.)\s*\d+.*`),
	regexp.MustCompile(`^[^\p{L}\p{N}]*卷\s*[一二三四五六七八九十0-9]+\s*.*`),
}

var chapterRules = []*regexp.Regexp{
	regexp.MustCompile(`^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+(?:\s*[-~～至到—–—]\s*[一二三四五六七八九十百千零0-9０-９]+)?\s*完?\s*[章节回卷集部篇]`),
	regexp.MustCompile(`^[【\[（(]\s*第?\s*[一二三四五六七八九十百千零0-9０-９]+\s*[章节回]\s*.*[】\]）)]`),
	regexp.MustCompile(`^(?:={3,}|-{3,}|\*{3,})\s*第?\s*[一二三四五六七八九十0-9]+\s*[章节]?.*`),
	regexp.MustCompile(`^\d{1,4}\.\s+.*`),
	regexp.MustCompile(`^[一二三四五六七八九十]+[、\.]\s*.*`),
	regexp.MustCompile(`^(?:Chapter|Section|Volume|Book)\s+\d+`),
	regexp.MustCompile(`^楔子($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^序[章言]($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^尾声($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^前言($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^后记($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^终章($|[\s　：:～~、，,;；])`),
	regexp.MustCompile(`^[^\p{L}\p{N}]*番外(?:篇|章|[\s　：:～~、，,;；\-_—\d一二三四五六七八九十0-9０-９]|$)`),
}

func IsVolumeHeader(line string) (bool, string) {
	trim := strings.TrimSpace(line)
	if utf8.RuneCountInString(trim) > 60 {
		return false, ""
	}
	for _, re := range volumeRules {
		if re.MatchString(trim) {
			return true, trim
		}
	}
	return false, ""
}

func IsChapterHeader(line string) bool {
	trim := strings.TrimSpace(line)
	if utf8.RuneCountInString(trim) > 60 {
		return false
	}
	if strings.HasSuffix(trim, "。") || strings.HasSuffix(trim, "！") || strings.HasSuffix(trim, "？") {
		return false
	}
	for _, re := range chapterRules {
		if re.MatchString(trim) {
			return true
		}
	}
	return false
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/service/bookparser -run TestIsVolumeHeader`
Expected: PASS

- [ ] **Step 5: Commit Task 1**

```bash
git add server/internal/service/bookparser/parser.go server/internal/service/bookparser/rules.go server/internal/service/bookparser/rules_test.go
git commit -m "feat(bookparser): add volume and enclosed chapter rules to rules.go"
```

---

### Task 2: Create txt_cache.go for LRU Text Caching & Fast Slicing

**Files:**
- Create: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt_cache.go`
- Test: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt_cache_test.go`

**Interfaces:**
- Produces: `globalTxtCache.GetOrLoad(path string, decodeFn func() (string, string, error)) (string, string, error)`
- Produces: `GetChapterBlocksFromText(text string, charStart, charEnd int) []Block`

- [ ] **Step 1: Write unit test for txt_cache.go**

Create `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt_cache_test.go`:

```go
package bookparser

import (
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
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/bookparser -run TestTxtCacheSlice`
Expected: FAIL with undefined `GetChapterBlocksFromText`.

- [ ] **Step 3: Create txt_cache.go**

Create `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt_cache.go`:

```go
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/service/bookparser -run TestTxtCacheSlice`
Expected: PASS

- [ ] **Step 5: Commit Task 2**

```bash
git add server/internal/service/bookparser/txt_cache.go server/internal/service/bookparser/txt_cache_test.go
git commit -m "feat(bookparser): add txt_cache.go for LRU text caching and fast chapter block extraction"
```

---

### Task 3: Refactor txt.go to Support Volume Hierarchy & Benchmark Tests

**Files:**
- Modify: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt.go`
- Modify: `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt_test.go`

**Interfaces:**
- Consumes: `rules.go` (`IsVolumeHeader`, `IsChapterHeader`), `txt_cache.go` (`globalTxtCache`, `GetChapterBlocksFromText`)

- [ ] **Step 1: Add Benchmark tests in txt_test.go**

In `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt_test.go`, add benchmark function:

```go
func BenchmarkParseTxt(b *testing.B) {
	// Benchmark parsing of sample TXT
	content := []byte("第一卷 创世\n第1章 诞生\n测试内容\n第2章 崛起\n测试内容2\n")
	tmpFile := b.TempDir() + "/bench.txt"
	if err := os.WriteFile(tmpFile, content, 0644); err != nil {
		b.Fatal(err)
	}
	info, _ := os.Stat(tmpFile)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = parseTxt(tmpFile, info)
	}
}
```

- [ ] **Step 2: Run benchmark to verify baseline**

Run: `cd server && go test ./internal/service/bookparser -bench=BenchmarkParseTxt -benchmem`
Expected: Benchmark executes cleanly.

- [ ] **Step 3: Refactor txt.go to use rules.go, txt_cache, and volume tracking**

Update `E:/github_project/LocalMediaHub/server/internal/service/bookparser/txt.go`:

```go
package bookparser

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

func parseTxt(path string, info os.FileInfo) (*Book, error) {
	if info.Size() > MaxTxtSize {
		return nil, fmt.Errorf("%w: %d bytes", ErrTooLarge, info.Size())
	}
	text, charset, err := globalTxtCache.GetOrLoad(path, func() (string, string, error) {
		raw, err := os.ReadFile(path)
		if err != nil {
			return "", "", fmt.Errorf("%w: %v", ErrIoFailure, err)
		}
		decoded, cs := decodeTxt(raw)
		return decoded, cs, nil
	})
	if err != nil {
		return nil, err
	}

	chapters := splitChapters(text, filepath.Base(path))
	return &Book{
		Path:     path,
		Format:   "txt",
		Title:    filepath.Base(path),
		Charset:  charset,
		Chapters: chapters,
		ModTime:  info.ModTime(),
	}, nil
}

func splitChapters(text, fallbackTitle string) []Chapter {
	var marks []chapterMark
	type markMeta struct {
		chapKey string
		volume  string
		volIdx  int
	}
	var metas []markMeta

	currentVolume := ""
	currentVolIdx := -1

	off := 0
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		trim := strings.TrimSpace(line)
		trim = pagePrefixRegex.ReplaceAllString(trim, "")

		if isVol, volTitle := IsVolumeHeader(trim); isVol {
			currentVolume = volTitle
			currentVolIdx++
			off += utf8.RuneCountInString(line) + 1
			continue
		}

		if isEndMarker(trim) {
			off += utf8.RuneCountInString(line) + 1
			continue
		}

		if IsChapterHeader(trim) {
			key := extractChapKey(trim)
			t := trim
			if idx := strings.Index(t, "www."); idx > 0 {
				t = strings.TrimSpace(t[:idx])
			}
			if utf8.RuneCountInString(t) > 50 {
				runes := []rune(t)
				t = string(runes[:50])
			}
			m := chapterMark{title: t, start: off}
			marks = append(marks, m)
			metas = append(metas, markMeta{chapKey: key, volume: currentVolume, volIdx: currentVolIdx})
		}
		off += utf8.RuneCountInString(line) + 1
	}

	if len(marks) == 0 {
		return []Chapter{{Title: fallbackTitle, Index: 0, CharStart: 0, CharEnd: utf8.RuneCountInString(text)}}
	}

	var chapters []Chapter
	if marks[0].start > 0 {
		preamble := strings.TrimRight(text[:marks[0].start], "\n")
		if strings.TrimSpace(preamble) != "" {
			chapters = append(chapters, Chapter{Title: "序言", Index: 0, CharStart: 0, CharEnd: marks[0].start})
		}
	}
	for i, m := range marks {
		start := m.start
		var end int
		if i+1 < len(marks) {
			end = marks[i+1].start
		} else {
			end = utf8.RuneCountInString(text)
		}
		meta := metas[i]
		chapters = append(chapters, Chapter{
			Title:     m.title,
			Index:     len(chapters),
			CharStart: start,
			CharEnd:   end,
			Volume:    meta.volume,
			VolIndex:  meta.volIdx,
		})
	}
	for i := range chapters {
		chapters[i].Index = i
	}
	return chapters
}

func (b *Book) txtChapterBlocks(idx int) ([]Block, error) {
	if idx < 0 || idx >= len(b.Chapters) {
		return nil, fmt.Errorf("chapter index out of range: %d", idx)
	}
	c := b.Chapters[idx]
	text, _, err := globalTxtCache.GetOrLoad(b.Path, func() (string, string, error) {
		raw, err := os.ReadFile(b.Path)
		if err != nil {
			return "", "", fmt.Errorf("%w: %v", ErrIoFailure, err)
		}
		decoded, cs := decodeTxt(raw)
		return decoded, cs, nil
	})
	if err != nil {
		return nil, err
	}
	return GetChapterBlocksFromText(text, c.CharStart, c.CharEnd), nil
}
```

- [ ] **Step 4: Run unit tests and benchmarks**

Run: `cd server && go test ./internal/service/bookparser/... -v`
Expected: PASS

- [ ] **Step 5: Run all server tests**

Run: `cd server && go test ./...`
Expected: PASS (0 failures across all server packages).

- [ ] **Step 6: Commit Task 3**

```bash
git add server/internal/service/bookparser/txt.go server/internal/service/bookparser/txt_test.go
git commit -m "refactor(bookparser): refactor txt parsing with LRU caching, volume hierarchy, and non-standard chapter rules"
```

---

## Plan Self-Review Checklist

1. **Spec coverage**:
   - High performance slicing cache -> Covered in Task 2 & Task 3.
   - Non-standard chapter title rules -> Covered in Task 1.
   - Volume hierarchy -> Covered in Task 1 & Task 3.
2. **Placeholder scan**: Clean, no TBD/TODOs.
3. **Type consistency**: `Chapter` Volume fields matched across `parser.go` and `txt.go`.

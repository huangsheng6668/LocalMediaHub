# Server Bookparser Optimization Design Spec

**Date**: 2026-07-23  
**Status**: Approved  
**Target**: `server/internal/service/bookparser/`

---

## 1. Overview & Goals

Enhance the Go server's `bookparser` subpackage for TXT and EPUB parsing with high-performance chapter slicing, LRU text caching, non-standard chapter title detection, and volume-hierarchy support.

### Key Goals:
1. **Performance & Slicing Cache**: Replace per-request full-file reading (`ReadFile` + `decodeTxt` + `[]rune`) with a thread-safe LRU text cache and byte/character offset mapping, reducing large file chapter load time from ~200ms to < 5ms.
2. **Enhanced Chapter Title Detection**: Expand regex rules to cover non-standard titles (e.g. `【第X章】`, `=== Chapter X ===`, `123. Title`, `卷一 Title`).
3. **Volume (卷) Hierarchy Support**: Parse volume headings and add `Volume` / `VolIndex` fields to `Chapter` struct while maintaining 100% backward compatibility for existing clients.

---

## 2. Architecture & Component Boundaries

### 2.1 File Map

- **[NEW]** `server/internal/service/bookparser/txt_cache.go`
  - LRU cache for decoded book text and byte/rune offset maps.
  - Fast byte-range chapter slice reading.
- **[NEW]** `server/internal/service/bookparser/rules.go`
  - Centralized chapter and volume regex patterns, false-positive filters, and helper matchers.
- **[MODIFY]** `server/internal/service/bookparser/parser.go`
  - Extend `Chapter` struct with `Volume` (string) and `VolIndex` (int) fields.
- **[MODIFY]** `server/internal/service/bookparser/txt.go`
  - Refactor `splitChapters` and `txtChapterBlocks` to use `rules.go` and `txt_cache.go`.
- **[MODIFY]** `server/internal/service/bookparser/txt_test.go`
  - Add benchmark tests (`BenchmarkParseTxt`, `BenchmarkTxtChapterBlocks`) and unit tests for non-standard titles and multi-volume TXT files.

---

## 3. Detailed Specifications

### 3.1 Chapter & Volume Struct Extension (`parser.go`)

```go
type Chapter struct {
	Title     string `json:"title"`
	Index     int    `json:"index"`
	CharStart int    `json:"char_start"`
	CharEnd   int    `json:"char_end"`
	Volume    string `json:"volume,omitempty"`   // e.g. "第一卷 创世纪"
	VolIndex  int    `json:"vol_index,omitempty"` // 0-indexed volume index
}
```

### 3.2 Enhanced Rules (`rules.go`)

1. **Volume Rules (`VolumeRules`)**:
   - `^[^\p{L}\p{N}]*第\s*[一二三四五六七八九十百千零0-9０-９]+\s*卷\s*.*`
   - `^(?:Volume|Vol\.)\s*\d+.*`
   - `^[^\p{L}\p{N}]*卷\s*[一二三四五六七八九十0-9]+\s*.*`

2. **Enclosed & Non-Standard Chapter Rules (`ChapterRules`)**:
   - `^[【\[（(]\s*第?\s*[一二三四五六七八九十百千零0-9０-９]+\s*[章节回]\s*.*[】\]）)]`
   - `^(?:={3,}|-{3,}|\*{3,})\s*第?\s*[一二三四五六七八九十0-9]+\s*[章节]?.*`
   - `^\d{1,4}\.\s+.*`
   - `^[一二三四五六七八九十]+[、\.]\s*.*`

3. **False Positive Guard (`isEndMarker`, `isFalsePositiveTitle`)**:
   - Lines with ending punctuation (`。`, `！`, `？`) are rejected as chapter titles.
   - Lines exceeding 50 runes are rejected as chapter titles.

### 3.3 Text Slicing Cache (`txt_cache.go`)

- Implements an LRU cache holding decoded string references for up to 20 recently read TXT files.
- Safe for concurrent read access using `sync.RWMutex`.
- Provides `GetChapterSlice(text string, charStart, charEnd int) []Block` for fast sub-string extraction without re-decoding from disk.

---

## 4. Verification & Test Plan

### 4.1 Automated Tests
- Command: `cd server && go test ./internal/service/bookparser/... -v`
- Benchmarks: `cd server && go test ./internal/service/bookparser/... -bench=. -benchmem`

### 4.2 Full Server Tests
- Command: `cd server && go test ./...`
- Verification: Ensure zero regressions in book HTTP endpoints and path validations.

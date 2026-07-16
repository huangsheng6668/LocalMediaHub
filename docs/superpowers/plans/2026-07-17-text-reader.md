# Novel Reader (txt + epub) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add txt/epub novel reading to LocalMediaHub — server parses chapters, Android + Web clients read them, progress persisted locally.

**Architecture:** Server adds `MediaType="text"` scan path + new `bookparser` subpackage (txt/epub/unsupported) + `BookService` mtime-cached parser + two `/api/v1/books/*` JSON endpoints. Android adds `TextReaderActivity` + ViewModel + DataStore progress; Browse/HomeScreen/DownloadsScreen get a `"text"` branch in every `when(mediaType)`. Web adds `textReader.js` + `bookshelf.js` + two hash routes. Offline reading via metadata sidecar JSON downloaded alongside the book.

**Tech Stack:** Go 1.25 / Echo v4 / `archive/zip` / `golang.org/x/net/html` / `golang.org/x/text/encoding/simplifiedchinese`; Android Kotlin / Compose / Hilt / OkHttp+Gson / DataStore; Web vanilla JS modules.

**Spec:** `docs/superpowers/specs/2026-07-17-text-reader-design.md`

## Global Constraints

- Text extensions default: `.txt`, `.epub`, `.mobi`, `.azw3` (spec line 57).
- txt size cap: 50MB → `ErrTooLarge`. epub size cap: 100MB → `ErrTooLarge` (spec line 149, 173).
- First-period txt rule profile is fixed `common`; no profile switching UI (spec line 36).
- epub images → `[图片]` placeholder, image-only chapters → `[本章节为图片版，暂不支持]` (spec line 167).
- mobi/azw3 scanned but greyed out, never parsed (spec line 15, 183-195).
- Progress stored client-side only: Android DataStore / Web localStorage (spec line 30, 298-301).
- Web has no JS test framework — do not introduce one. Cover via Go handler tests + manual acceptance (spec line 550-552).
- All endpoints reuse existing `ValidateAccessibleMediaPath` + token middleware; no new security model (spec line 235, 309-311).
- CI gates: `cd server && go test ./...` AND `cd android && ./gradlew testDebugUnitTest assembleDebug` must pass (spec line 565).
- No new modules: `golang.org/x/net/html` and `golang.org/x/text` are already indirect deps, this plan promotes them to direct (spec line 175-181).
- Sync policy per AGENTS.md: every commit auto-pushes to master — keep commits granular and meaningful.

---

## File Structure

### Server (Go)

| File | Responsibility | Status |
|------|----------------|--------|
| `server/internal/config/config.go` | Add `ScanConfig.TextExtensions`, `DefaultTextExtensions`, mirror in `ScanConfigPublic` + `Public()` | Modify |
| `server/internal/service/scanner.go` | Add `textExts` field, `text` branch in Scan, `cache["text"]`, `TextExts()` getter | Modify |
| `server/internal/service/scanner_test.go` | Cover text scan/cache | Modify |
| `server/internal/service/bookparser/parser.go` | `Book`/`Chapter` types, `Parse()` entry, error sentinels | Create |
| `server/internal/service/bookparser/txt.go` | Encoding detect + `common` rule set + offset slicing | Create |
| `server/internal/service/bookparser/txt_test.go` | Unit tests | Create |
| `server/internal/service/bookparser/epub.go` | ZIP + OPF spine + NCX/nav TOC + ChapterText | Create |
| `server/internal/service/bookparser/epub_test.go` | Unit tests | Create |
| `server/internal/service/bookparser/unsupported.go` | mobi/azw3 stub returning `ErrUnsupported` | Create |
| `server/internal/service/bookparser/parser_test.go` | Parse routing tests | Create |
| `server/internal/service/book.go` | `BookService` with mtime cache + singleflight | Create |
| `server/internal/service/book_test.go` | Cache hit/miss + singleflight | Create |
| `server/internal/server/handler/handler.go` | Add `books` field to `Handler`, extend `New()` signature, extend `isMediaExt`/`mediaExtensions` | Modify |
| `server/internal/server/handler/handler_test.go` | isMediaExt/mediaExtensions tests | Create or extend |
| `server/internal/server/handler/books.go` | `GetBookInfo` + `GetBookChapter` HTTP handlers | Create |
| `server/internal/server/handler/books_test.go` | Endpoint tests | Create |
| `server/internal/server/handler/media.go` | Extend `MediaOriginal` allowed exts to include text | Modify |
| `server/internal/server/handler/system.go` | `SystemBrowse` mediaType branching: text for text exts | Modify |
| `server/internal/server/handler/folders.go` | `BrowseFolder` + `DownloadFolderZip` include text exts | Modify |
| `server/internal/server/handler/tags.go` | `buildTaggedMediaFallback` text branch | Modify |
| `server/internal/server/server.go` | Construct `BookService`, pass to `handler.New`, register `/api/v1/books` group | Modify |
| `server/internal/server/server_test.go` | Update `NewScanner` call signature | Modify |
| `server/internal/web/router.js` | Add `#/read` + `#/bookshelf` routes | Modify |
| `server/internal/web/api.js` | Add `getBookInfo` + `getBookChapter` | Modify |
| `server/internal/web/browserView.js` | Render text files as doc cards; route to `#/read` | Modify |
| `server/internal/web/textReader.js` | Reader logic, TOC, progress | Create |
| `server/internal/web/bookshelf.js` | Scan localStorage, render shelf | Create |
| `server/internal/web/dashboard.js` | "My bookshelf" section on home | Modify |

### Android (Kotlin)

| File | Responsibility | Status |
|------|----------------|--------|
| `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt` | Add `Book`, `BookChapter`, `BookChapterContent` | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt` | Add `getBookInfo` + `getBookChapter` | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt` | Add `book_progress` key + API + `BookProgress` data class | Modify |
| `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt` | Cover book_progress | Create or extend |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt` | Reader VM with StateFlow + progress | Create |
| `android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt` | Standalone Activity, singleTop | Create |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt` | Compose UI | Create |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/MediaItems.kt` | Add `TextCard` composable | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt` | Rewrite mediaType branches as `when` with text case | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt` + browse sub-components | Add `"text"` branch in every `when(mediaType)` | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/DownloadsScreen.kt` | 3 fixes per spec line 416 | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/data/DownloadManager.kt` | Comment clarifying text download URL | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt` | Sidecar metadata JSON download | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt` | `recentBooks` StateFlow | Modify |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt` + `ui/component/home/HomeComponents.kt` | Bookshelf card | Modify |
| `android/app/src/main/AndroidManifest.xml` | Register `TextReaderActivity` | Modify |

---

## Task 1: Config — TextExtensions field

**Files:**
- Modify: `server/internal/config/config.go` (ScanConfig + ScanConfigPublic + Public() + LoadFromBytes)
- Test: `server/internal/config/config_test.go`

**Interfaces:**
- Produces: `DefaultTextExtensions []string` exported var; `ScanConfig.TextExtensions []string` field; `ScanConfigPublic.TextExtensions []string` field; `Config.Public()` populates it.

- [ ] **Step 1: Write the failing test**

Append to `server/internal/config/config_test.go`:

```go
func TestScanConfigDefaultTextExtensions(t *testing.T) {
	cfg, err := LoadFromBytes([]byte(`scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
`))
	if err != nil {
		t.Fatalf("LoadFromBytes: %v", err)
	}
	if len(cfg.Scan.TextExtensions) == 0 {
		t.Fatalf("expected default TextExtensions to be populated when omitted")
	}
	want := map[string]bool{".txt": true, ".epub": true, ".mobi": true, ".azw3": true}
	for _, e := range cfg.Scan.TextExtensions {
		if !want[e] {
			t.Fatalf("unexpected ext %q in default TextExtensions", e)
		}
	}
}

func TestPublicExposesTextExtensions(t *testing.T) {
	cfg, err := LoadFromBytes([]byte(`scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
  text_extensions: [".txt", ".epub"]
`))
	if err != nil {
		t.Fatalf("LoadFromBytes: %v", err)
	}
	pub := cfg.Public()
	if len(pub.Scan.TextExtensions) != 2 {
		t.Fatalf("expected 2 text extensions in public, got %d (%v)", len(pub.Scan.TextExtensions), pub.Scan.TextExtensions)
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/config/ -run TestScanConfigDefaultTextExtensions -v`
Expected: FAIL — `cfg.Scan.TextExtensions undefined`.

- [ ] **Step 3: Implement minimal code**

In `server/internal/config/config.go`:

(a) After `ImageExtensions` field in `ScanConfig` struct (around line 29), add:
```go
TextExtensions []string `yaml:"text_extensions,omitempty" json:"text_extensions,omitempty"`
```

(b) Above `LoadFromBytes`, add a package-level var:
```go
// DefaultTextExtensions is used when scan.text_extensions is omitted.
var DefaultTextExtensions = []string{".txt", ".epub", ".mobi", ".azw3"}
```

(c) In `LoadFromBytes`, after the existing `if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) > 0` block, add:
```go
if len(cfg.Scan.TextExtensions) == 0 {
	cfg.Scan.TextExtensions = append([]string(nil), DefaultTextExtensions...)
}
```

(d) Add `TextExtensions []string `json:"text_extensions,omitempty"`` field to `ScanConfigPublic` (after ImageExtensions).

(e) In `Config.Public()`, update the `Scan:` field initializer to include `TextExtensions: c.Scan.TextExtensions,`.

- [ ] **Step 4: Run tests to verify pass**

Run: `cd server && go test ./internal/config/ -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go
git commit -m "feat(config): add scan.text_extensions with default [txt epub mobi azw3]"
```

---

## Task 2: Scanner — text mediaType + cache["text"]

**Files:**
- Modify: `server/internal/service/scanner.go` (Scanner struct + NewScanner + Scan func)
- Modify: `server/internal/service/scanner_test.go`
- Modify: `server/internal/server/server.go:47` (NewScanner call site)
- Modify: `server/internal/server/server_test.go:69`
- Modify: every test file calling `service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)` — found at: `folders_test.go:33,68,108,155,182,213,266,323`, `admin_test.go:47,87`, `search_test.go:56,109`, `tags_test.go:33`

**Interfaces:**
- Produces: `NewScanner(videoExts, imageExts, textExts []string) *Scanner`; `(*Scanner).TextExts() map[string]bool`; scanner populates `cache["text"]` and `cacheByDir` with `MediaType=="text"` files.

- [ ] **Step 1: Write the failing test**

Append to `server/internal/service/scanner_test.go`:

```go
func TestScanTextFiles(t *testing.T) {
	dir := t.TempDir()
	require.NoError(t, os.WriteFile(filepath.Join(dir, "novel.txt"), []byte("第一章 开始\n正文"), 0644))
	require.NoError(t, os.WriteFile(filepath.Join(dir, "book.epub"), []byte("PK\x03\x04"), 0644))

	scanner := NewScanner([]string{".mp4"}, []string{".jpg"}, []string{".txt", ".epub"})
	files, err := scanner.Scan(context.Background(), []string{dir})
	require.NoError(t, err)
	require.Len(t, files, 2)

	scanner.mu.RLock()
	textFiles := scanner.cache["text"]
	scanner.mu.RUnlock()
	assert.Len(t, textFiles, 2)
	for _, f := range textFiles {
		assert.Equal(t, "text", f.MediaType)
	}
}

func TestScannerTextExtsGetter(t *testing.T) {
	s := NewScanner(nil, nil, []string{".txt", ".EPUB"})
	assert.True(t, s.TextExts()[".txt"])
	assert.True(t, s.TextExts()[".epub"])
}
```

Ensure the test file imports include `context`, `os`, `path/filepath`, `github.com/stretchr/testify/require`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/ -run TestScanTextFiles -v`
Expected: FAIL — `too many arguments in call to NewScanner`.

- [ ] **Step 3: Modify scanner.go**

(a) Add field to `Scanner` struct (after `imageExts`):
```go
textExts map[string]bool
```

(b) Rewrite `NewScanner`:
```go
func NewScanner(videoExts, imageExts, textExts []string) *Scanner {
	vExts := make(map[string]bool)
	for _, e := range videoExts {
		vExts[strings.ToLower(e)] = true
	}
	iExts := make(map[string]bool)
	for _, e := range imageExts {
		iExts[strings.ToLower(e)] = true
	}
	tExts := make(map[string]bool)
	for _, e := range textExts {
		tExts[strings.ToLower(e)] = true
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Scanner{
		cache:       make(map[string][]models.MediaFile),
		cacheTTL:    60 * time.Second,
		videoExts:   vExts,
		imageExts:   iExts,
		textExts:    tExts,
		bgCtx:       ctx,
		bgCancel:    cancel,
		cacheDirs:   nil,
		cacheDirMap: nil,
	}
}
```

(c) Add getter after `ImageExts()`:
```go
// TextExts returns the text extension map for handler use.
func (s *Scanner) TextExts() map[string]bool {
	return s.textExts
}
```

(d) In `Scan()` walk callback (around current line 154-165), change the mediaType decision:
```go
s.mu.RLock()
isVideo := s.videoExts[ext]
isImage := s.imageExts[ext]
isText := s.textExts[ext]
s.mu.RUnlock()

if isVideo {
	mediaType = "video"
} else if isImage {
	mediaType = "image"
} else if isText {
	mediaType = "text"
} else {
	return nil
}
```

(e) After `imageFiles := make([]models.MediaFile, 0)` add `textFiles := make([]models.MediaFile, 0)`. In the merge `switch f.MediaType` add:
```go
case "text":
	textFiles = append(textFiles, f)
```

(f) In the lock-protected cache writes block add:
```go
s.cache["text"] = textFiles
```

(g) Filter text files out of the OnScanComplete callback (books have no thumbnails). Change the final block:
```go
if callback != nil {
	thumbOnly := make([]models.MediaFile, 0, len(allFiles))
	for _, f := range allFiles {
		if f.MediaType == "text" {
			continue
		}
		thumbOnly = append(thumbOnly, f)
	}
	go callback(thumbOnly)
}
```

- [ ] **Step 4: Update all NewScanner call sites**

Every test file and `server.go:47` that currently calls `NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions)` must become `NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)`. Update all occurrences in `folders_test.go`, `admin_test.go`, `search_test.go`, `tags_test.go`, `server_test.go`. Tests using nil cfg defaults pass `nil` as the third arg if they don't exercise text scanning.

In `server.go:47`:
```go
scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
```

- [ ] **Step 5: Run tests to verify pass**

Run: `cd server && go test ./internal/service/ ./internal/server/... -v`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/internal/service/scanner.go server/internal/service/scanner_test.go server/internal/server/server.go server/internal/server/server_test.go server/internal/server/handler/folders_test.go server/internal/server/handler/admin_test.go server/internal/server/handler/search_test.go server/internal/server/handler/tags_test.go
git commit -m "feat(scanner): scan text files into cache[text] + TextExts() getter"
```

---

## Task 3: handler.go — books field + isMediaExt/mediaExtensions text support

**Files:**
- Modify: `server/internal/server/handler/handler.go` (Handler struct + New + isMediaExt + mediaExtensions)
- Create or extend: `server/internal/server/handler/handler_test.go`
- Modify: every test file that calls `handler.New(...)` — folders_test.go, admin_test.go, tags_test.go (each currently has `New(cfg, ..., nil, nil)`)
- Modify: `server/internal/server/server.go:90` (production New call)

**Interfaces:**
- Produces: `Handler` has field `books *service.BookService`; `handler.New(cfg, scanner, tags, streaming, thumbnail, books *service.BookService)`. **Note: Task 7 constructs the actual BookService; this task only adds the parameter slot.** Until Task 7 lands, callers pass `nil` for books. No code path touches `h.books` until Task 8 wires it, so the build stays green.
- Produces: `isMediaExt` returns true for text exts; `mediaExtensions()` includes text.

- [ ] **Step 1: Write the failing test**

`server/internal/server/handler/handler_test.go` (append if exists):

```go
package handler

import (
	"testing"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
	"github.com/stretchr/testify/assert"
)

func TestIsMediaExtIncludesText(t *testing.T) {
	cfg := &config.Config{}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.ImageExtensions = []string{".jpg"}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	h := New(cfg, service.NewScanner(nil, nil, nil), nil, nil, nil, nil)
	assert.True(t, h.isMediaExt(".txt"))
	assert.True(t, h.isMediaExt(".EPUB"))
	assert.True(t, h.isMediaExt(".mp4"))
	assert.False(t, h.isMediaExt(".exe"))
}

func TestMediaExtensionsIncludesText(t *testing.T) {
	cfg := &config.Config{}
	cfg.Scan.TextExtensions = []string{".txt"}
	h := New(cfg, nil, nil, nil, nil, nil)
	all := h.mediaExtensions()
	assert.Contains(t, all, ".txt")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/handler/ -run TestIsMediaExtIncludesText -v`
Expected: FAIL — `not enough arguments in call to New`.

- [ ] **Step 3: Implement handler.go changes**

(a) Add field to `Handler` struct:
```go
books *service.BookService
```

(b) Rewrite `New`:
```go
func New(
	cfg *config.Config,
	scanner *service.Scanner,
	tags *service.TagsService,
	streaming *service.StreamingService,
	thumbnail *service.ThumbnailService,
	books *service.BookService,
) *Handler {
	return &Handler{
		cfg:       cfg,
		scanner:   scanner,
		tags:      tags,
		streaming: streaming,
		thumbnail: thumbnail,
		books:     books,
	}
}
```

(c) Extend `isMediaExt` with a third loop over `h.cfg.Scan.TextExtensions`.

(d) Extend `mediaExtensions` to include `h.cfg.Scan.TextExtensions...` in the slice.

- [ ] **Step 4: Update all callers of handler.New**

(a) `server/internal/server/server.go:90`:
```go
h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService, nil)
```
(Production BookService wired in Task 8.)

(b) Every test file calling `handler.New(cfg, ..., nil, nil)` must add a trailing `nil` argument. Files: `folders_test.go`, `admin_test.go`, `tags_test.go`. Use search-replace per file.

- [ ] **Step 5: Run tests to verify pass**

Run: `cd server && go test ./...`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add server/internal/server/handler/handler.go server/internal/server/handler/handler_test.go server/internal/server/handler/folders_test.go server/internal/server/handler/admin_test.go server/internal/server/handler/tags_test.go server/internal/server/server.go
git commit -m "feat(handler): extend Handler with books slot + isMediaExt/mediaExtensions cover text"
```

---

## Task 4: bookparser — parser.go types, Parse routing, errors

**Files:**
- Create: `server/internal/service/bookparser/parser.go`
- Create: `server/internal/service/bookparser/parser_test.go`
- Create: `server/internal/service/bookparser/txt.go` (stub)
- Create: `server/internal/service/bookparser/epub.go` (stub)
- Create: `server/internal/service/bookparser/unsupported.go`

**Interfaces:**
- Produces:
  - `type Chapter struct` with json tags `title,index,char_start,char_end,manifest_id`
  - `type Book struct` with json tags `path,format,title,charset,chapters,mod_time`; plus unexported `epubManifest map[string]string` and `epubOpfDir string` (both `json:"-"`)
  - `func Parse(path string) (*Book, error)` — routes by extension
  - `func (b *Book) ChapterText(idx int) (string, error)`
  - Error sentinels: `ErrUnsupported`, `ErrTooLarge`, `ErrInvalidEpub`, `ErrEncrypted`, `ErrIoFailure`
  - Constants: `MaxTxtSize = 50 * 1024 * 1024`, `MaxEpubSize = 100 * 1024 * 1024`

- [ ] **Step 1: Write the failing test**

`server/internal/service/bookparser/parser_test.go`:

```go
package bookparser

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func TestParseUnsupportedForUnknownExt(t *testing.T) {
	dir := t.TempDir()
	unknown := filepath.Join(dir, "x.unknown")
	require.NoError(t, os.WriteFile(unknown, []byte("data"), 0644))
	b, err := Parse(unknown)
	assert.ErrorIs(t, err, ErrUnsupported)
	require.NotNil(t, b)
	assert.Equal(t, "unsupported", b.Format)
}

func TestParseIoFailureForMissingFile(t *testing.T) {
	_, err := Parse(filepath.Join(t.TempDir(), "missing.txt"))
	assert.ErrorIs(t, err, ErrIoFailure)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/bookparser/ -v`
Expected: FAIL — package does not exist.

- [ ] **Step 3: Implement parser.go**

`server/internal/service/bookparser/parser.go`:

```go
// Package bookparser parses local ebook files into a Book structure with
// chapter metadata. The full text is NOT retained — ChapterText re-reads
// the file on demand to keep cache entries small.
package bookparser

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

const (
	MaxTxtSize  = 50 * 1024 * 1024
	MaxEpubSize = 100 * 1024 * 1024
)

var (
	ErrUnsupported = errors.New("bookparser: format not supported")
	ErrTooLarge    = errors.New("bookparser: file too large")
	ErrInvalidEpub = errors.New("bookparser: invalid epub structure")
	ErrEncrypted   = errors.New("bookparser: drm-encrypted ebook")
	ErrIoFailure   = errors.New("bookparser: io failure")
)

type Chapter struct {
	Title      string `json:"title"`
	Index      int    `json:"index"`
	CharStart  int    `json:"char_start,omitempty"`
	CharEnd    int    `json:"char_end,omitempty"`
	ManifestID string `json:"manifest_id,omitempty"`
}

type Book struct {
	Path     string    `json:"path"`
	Format   string    `json:"format"`
	Title    string    `json:"title"`
	Charset  string    `json:"charset,omitempty"`
	Chapters []Chapter `json:"chapters"`
	ModTime  time.Time `json:"mod_time"`

	// epub-only resolver state (populated by parseEpub). Never serialized.
	epubManifest map[string]string `json:"-"`
	epubOpfDir   string            `json:"-"`
}

// Parse routes by lowercased extension. mobi/azw3/unknown return a Book
// with Format="unsupported" and ErrUnsupported — callers must check both.
func Parse(path string) (*Book, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	ext := strings.ToLower(filepath.Ext(path))
	switch ext {
	case ".txt":
		return parseTxt(path, info)
	case ".epub":
		return parseEpub(path, info)
	default:
		return parseUnsupported(path, info)
	}
}

func (b *Book) ChapterText(idx int) (string, error) {
	if idx < 0 || idx >= len(b.Chapters) {
		return "", fmt.Errorf("chapter index out of range")
	}
	switch b.Format {
	case "txt":
		return b.txtChapterText(idx)
	case "epub":
		return b.epubChapterText(idx)
	default:
		return "", ErrUnsupported
	}
}
```

Create stubs so the package compiles. `unsupported.go`:
```go
package bookparser

import (
	"os"
	"path/filepath"
)

func parseUnsupported(path string, info os.FileInfo) (*Book, error) {
	return &Book{
		Path:     path,
		Format:   "unsupported",
		Title:    filepath.Base(path),
		Chapters: nil,
		ModTime:  info.ModTime(),
	}, ErrUnsupported
}
```

Stub `txt.go`:
```go
package bookparser

import (
	"fmt"
	"os"
)

func parseTxt(path string, info os.FileInfo) (*Book, error) {
	return nil, fmt.Errorf("not yet implemented")
}

func (b *Book) txtChapterText(idx int) (string, error) {
	return "", fmt.Errorf("not yet implemented")
}
```

Stub `epub.go`:
```go
package bookparser

import (
	"fmt"
	"os"
)

func parseEpub(path string, info os.FileInfo) (*Book, error) {
	return nil, fmt.Errorf("not yet implemented")
}

func (b *Book) epubChapterText(idx int) (string, error) {
	return "", fmt.Errorf("not yet implemented")
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd server && go test ./internal/service/bookparser/ -v`
Expected: PASS for `TestParseUnsupportedForUnknownExt` and `TestParseIoFailureForMissingFile`.

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/bookparser/
git commit -m "feat(bookparser): skeleton with Parse routing + error sentinels"
```

---

## Task 5: bookparser/txt.go — encoding detection + common rule set + slicing

**Files:**
- Modify: `server/internal/service/bookparser/txt.go`
- Create: `server/internal/service/bookparser/txt_test.go`

**Interfaces:**
- Produces: fully-working `parseTxt(path, info) (*Book, error)` and `(*Book).txtChapterText(idx int) (string, error)`.

- [ ] **Step 1: Write the failing test**

`server/internal/service/bookparser/txt_test.go`:

```go
package bookparser

import (
	"os"
	"path/filepath"
	"testing"
	"unicode/utf8"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"golang.org/x/text/encoding/simplifiedchinese"
)

func writeBytes(t *testing.T, path string, data []byte) {
	t.Helper()
	require.NoError(t, os.WriteFile(path, data, 0644))
}

func TestTxtUtf8BomDetected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "u8.txt")
	body := []byte{0xEF, 0xBB, 0xBF}
	body = append(body, []byte("第一章 开始\n正文一\n第二章 结束\n正文二")...)
	writeBytes(t, p, body)
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, "txt", b.Format)
	assert.Equal(t, "UTF-8", b.Charset)
	assert.GreaterOrEqual(t, len(b.Chapters), 2)
}

func TestTxtGB18030Detected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "gb.txt")
	enc := simplifiedchinese.GBK.NewEncoder()
	s, err := enc.String("第一章 开始\n正文内容")
	require.NoError(t, err)
	writeBytes(t, p, []byte(s))
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, "GB18030", b.Charset)
	txt, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.True(t, utf8.ValidString(txt))
	assert.Contains(t, txt, "开始")
}

func TestTxtNoChapterMatchBecomesSingleChapter(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "plain.txt")
	writeBytes(t, p, []byte("这是一本没有任何章节标记的书。"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 1)
	assert.Equal(t, "plain.txt", b.Chapters[0].Title)
}

func TestTxtChapterOffsetsRoundTrip(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "r.txt")
	writeBytes(t, p, []byte("第一章 A\n第一章正文\n第二章 B\n第二章正文"))
	b, err := Parse(p)
	require.NoError(t, err)
	require.Len(t, b.Chapters, 2)
	c0, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.Contains(t, c0, "第一章正文")
	assert.NotContains(t, c0, "第二章")
	c1, err := b.ChapterText(1)
	require.NoError(t, err)
	assert.Contains(t, c1, "第二章正文")
}

func TestTxtTooLargeRejected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "big.txt")
	f, err := os.Create(p)
	require.NoError(t, err)
	require.NoError(t, f.Truncate(int64(MaxTxtSize + 1)))
	require.NoError(t, f.Close())
	_, err = Parse(p)
	assert.ErrorIs(t, err, ErrTooLarge)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/bookparser/ -run TestTxt -v`
Expected: FAIL — `not yet implemented`.

- [ ] **Step 3: Implement txt.go**

Replace stub `txt.go` with:

```go
package bookparser

import (
	"bufio"
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/text/encoding/simplifiedchinese"
	"golang.org/x/text/transform"
)

var commonRules = []*regexp.Regexp{
	regexp.MustCompile(`^第[一二三四五六七八九十百千零0-9]+[章节回卷集部篇]`),
	regexp.MustCompile(`^Chapter\s+\d+`),
	regexp.MustCompile(`^楔子`),
	regexp.MustCompile(`^序章`),
	regexp.MustCompile(`^尾声`),
	regexp.MustCompile(`^前言`),
	regexp.MustCompile(`^后记`),
}

func parseTxt(path string, info os.FileInfo) (*Book, error) {
	if info.Size() > MaxTxtSize {
		return nil, fmt.Errorf("%w: %d bytes", ErrTooLarge, info.Size())
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	decoded, charset := decodeTxt(raw)
	chapters := splitChapters(decoded, filepath.Base(path))
	return &Book{
		Path:     path,
		Format:   "txt",
		Title:    filepath.Base(path),
		Charset:  charset,
		Chapters: chapters,
		ModTime:  info.ModTime(),
	}, nil
}

func decodeTxt(raw []byte) (string, string) {
	if bytes.HasPrefix(raw, []byte{0xEF, 0xBB, 0xBF}) {
		return string(raw[3:]), "UTF-8"
	}
	if utf8.Valid(raw) {
		return string(raw), "UTF-8"
	}
	dec := simplifiedchinese.GB18030.NewDecoder()
	gb, _, err := transform.String(dec, string(raw))
	if err == nil && utf8.ValidString(gb) {
		return gb, "GB18030"
	}
	return string(raw), "UTF-8"
}

type chapterMark struct {
	title string
	start int
}

func splitChapters(text, fallbackTitle string) []Chapter {
	var marks []chapterMark
	off := 0
	scanner := bufio.NewScanner(strings.NewReader(text))
	scanner.Buffer(make([]byte, 0, 64*1024), 1024*1024)
	for scanner.Scan() {
		line := scanner.Text()
		trim := strings.TrimSpace(line)
		for _, re := range commonRules {
			if re.MatchString(trim) {
				marks = append(marks, chapterMark{title: trim, start: off})
				break
			}
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
		chapters = append(chapters, Chapter{
			Title:     m.title,
			Index:     len(chapters),
			CharStart: start,
			CharEnd:   end,
		})
	}
	for i := range chapters {
		chapters[i].Index = i
	}
	return chapters
}

func (b *Book) txtChapterText(idx int) (string, error) {
	raw, err := os.ReadFile(b.Path)
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	decoded, _ := decodeTxt(raw)
	runes := []rune(decoded)
	c := b.Chapters[idx]
	if c.CharEnd > len(runes) {
		c.CharEnd = len(runes)
	}
	if c.CharStart > len(runes) {
		c.CharStart = len(runes)
	}
	return string(runes[c.CharStart:c.CharEnd]), nil
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd server && go test ./internal/service/bookparser/ -run TestTxt -v`
Expected: PASS.

- [ ] **Step 5: Promote x/text to direct dependency**

Run: `cd server && go mod tidy`
Verify: `cd server && grep "golang.org/x/text" go.mod` — line should no longer have `// indirect`.

- [ ] **Step 6: Commit**

```bash
git add server/internal/service/bookparser/txt.go server/internal/service/bookparser/txt_test.go server/go.mod server/go.sum
git commit -m "feat(bookparser): txt parsing with BOM/GB18030 detection + common chapter rules"
```

---

## Task 6: bookparser/epub.go — ZIP + OPF spine + NCX/nav TOC + ChapterText

**Files:**
- Modify: `server/internal/service/bookparser/epub.go`
- Create: `server/internal/service/bookparser/epub_test.go`

**Interfaces:**
- Produces: fully-working `parseEpub(path, info) (*Book, error)` and `(*Book).epubChapterText(idx int) (string, error)`. Book carries unexported `epubManifest` + `epubOpfDir` for ChapterText resolution.

- [ ] **Step 1: Write the failing test**

`server/internal/service/bookparser/epub_test.go`:

```go
package bookparser

import (
	"archive/zip"
	"os"
	"path/filepath"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func buildMinimalEpub(t *testing.T, path string) {
	t.Helper()
	f, err := os.Create(path)
	require.NoError(t, err)
	defer f.Close()
	w := zip.NewWriter(f)

	mustWrite := func(name, body string) {
		fw, err := w.Create(name)
		require.NoError(t, err)
		_, err = fw.Write([]byte(body))
		require.NoError(t, err)
	}

	mustWrite("mimetype", "application/epub+zip")
	mustWrite("META-INF/container.xml", `<?xml version="1.0"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>`)
	mustWrite("OEBPS/content.opf", `<?xml version="1.0"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>Test Book</dc:title>
  </metadata>
  <manifest>
    <item id="ch1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="ch2.xhtml" media-type="application/xhtml+xml"/>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
</package>`)
	mustWrite("OEBPS/ch1.xhtml", `<html><body><p>First chapter body.</p></body></html>`)
	mustWrite("OEBPS/ch2.xhtml", `<html><body><p>Second chapter body.</p></body></html>`)
	mustWrite("OEBPS/toc.ncx", `<?xml version="1.0"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <navMap>
    <navPoint id="n1"><navLabel><text>Chapter One</text></navLabel><content src="ch1.xhtml"/></navPoint>
    <navPoint id="n2"><navLabel><text>Chapter Two</text></navLabel><content src="ch2.xhtml"/></navPoint>
  </navMap>
</ncx>`)
	require.NoError(t, w.Close())
}

func TestEpubParseExtractsTitleAndChapters(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	buildMinimalEpub(t, p)
	b, err := Parse(p)
	require.NoError(t, err)
	assert.Equal(t, "epub", b.Format)
	assert.Equal(t, "Test Book", b.Title)
	require.Len(t, b.Chapters, 2)
	assert.Equal(t, "ch1", b.Chapters[0].ManifestID)
	assert.Equal(t, "Chapter One", b.Chapters[0].Title)
}

func TestEpubChapterTextExtract(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "book.epub")
	buildMinimalEpub(t, p)
	b, err := Parse(p)
	require.NoError(t, err)
	c0, err := b.ChapterText(0)
	require.NoError(t, err)
	assert.Contains(t, c0, "First chapter body.")
	assert.NotContains(t, c0, "<p>")
}

func TestEpubTooLargeRejected(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "big.epub")
	f, err := os.Create(p)
	require.NoError(t, err)
	require.NoError(t, f.Truncate(int64(MaxEpubSize + 1)))
	require.NoError(t, f.Close())
	_, err = Parse(p)
	assert.ErrorIs(t, err, ErrTooLarge)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/bookparser/ -run TestEpub -v`
Expected: FAIL — `not yet implemented`.

- [ ] **Step 3: Implement epub.go**

Replace stub `epub.go` with the full implementation below. Note: the `Book` struct fields `epubManifest` and `epubOpfDir` were declared in Task 4's parser.go.

```go
package bookparser

import (
	"archive/zip"
	"bytes"
	"encoding/xml"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"

	"golang.org/x/net/html"
)

func parseEpub(path string, info os.FileInfo) (*Book, error) {
	if info.Size() > MaxEpubSize {
		return nil, fmt.Errorf("%w: %d bytes", ErrTooLarge, info.Size())
	}
	zr, err := zip.OpenReader(path)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidEpub, err)
	}
	defer zr.Close()

	opfPath, err := readContainerOpfPath(&zr.Reader)
	if err != nil {
		return nil, err
	}
	opf, err := readZipFile(zr.File, opfPath)
	if err != nil {
		return nil, fmt.Errorf("%w: missing OPF: %v", ErrInvalidEpub, err)
	}
	opfData, err := parseOpf(opf)
	if err != nil {
		return nil, err
	}
	if len(opfData.spine) == 0 {
		return nil, fmt.Errorf("%w: empty spine", ErrInvalidEpub)
	}

	chapters := make([]Chapter, 0, len(opfData.spine))
	for i, idref := range opfData.spine {
		chapters = append(chapters, Chapter{
			Title:      fmt.Sprintf("第 %d 章", i+1),
			Index:      i,
			ManifestID: idref,
		})
	}

	opfDir := filepath.ToSlash(filepath.Dir(opfPath))
	toc, _ := readToc(&zr.Reader, opfData.tocID, opfData.manifest, opfDir)
	for href, title := range toc {
		for i := range chapters {
			idref := chapters[i].ManifestID
			if h, ok := opfData.manifest[idref]; ok && normalizeHref(h) == normalizeHref(href) {
				chapters[i].Title = title
			}
		}
	}

	book := &Book{
		Path:     path,
		Format:   "epub",
		Title:    opfData.title,
		Chapters: chapters,
		ModTime:  info.ModTime(),
	}
	book.epubManifest = opfData.manifest
	book.epubOpfDir = opfDir
	return book, nil
}

func (b *Book) epubChapterText(idx int) (string, error) {
	c := b.Chapters[idx]
	href, ok := b.epubManifest[c.ManifestID]
	if !ok {
		return "[本章节解析失败]", nil
	}
	fullPath := joinZipPath(b.epubOpfDir, href)
	zr, err := zip.OpenReader(b.Path)
	if err != nil {
		return "", fmt.Errorf("%w: %v", ErrIoFailure, err)
	}
	defer zr.Close()
	rc, err := zr.Open(fullPath)
	if err != nil {
		return "[本章节解析失败]", nil
	}
	defer rc.Close()
	body, err := io.ReadAll(rc)
	if err != nil {
		return "[本章节解析失败]", nil
	}
	text, imgOnly := extractXhtmlText(body)
	if imgOnly {
		return "[本章节为图片版，暂不支持]", nil
	}
	return text, nil
}

func readContainerOpfPath(zr *zip.Reader) (string, error) {
	f, err := zr.Open("META-INF/container.xml")
	if err != nil {
		return "", fmt.Errorf("%w: missing container.xml: %v", ErrInvalidEpub, err)
	}
	defer f.Close()
	data, _ := io.ReadAll(f)
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		t, err := dec.Token()
		if err != nil {
			return "", fmt.Errorf("%w: malformed container.xml", ErrInvalidEpub)
		}
		se, ok := t.(xml.StartElement)
		if !ok {
			continue
		}
		if se.Name.Local == "rootfile" {
			for _, a := range se.Attr {
				if a.Name.Local == "full-path" {
					return a.Value, nil
				}
			}
		}
	}
}

type opfParsed struct {
	title    string
	manifest map[string]string
	spine    []string
	tocID    string
}

func parseOpf(data []byte) (*opfParsed, error) {
	out := &opfParsed{manifest: map[string]string{}}
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		t, err := dec.Token()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("%w: malformed OPF", ErrInvalidEpub)
		}
		if se, ok := t.(xml.StartElement); ok {
			switch se.Name.Local {
			case "item":
				var id, href string
				for _, a := range se.Attr {
					if a.Name.Local == "id" {
						id = a.Value
					}
					if a.Name.Local == "href" {
						href = a.Value
					}
				}
				if id != "" {
					out.manifest[id] = href
				}
			case "itemref":
				for _, a := range se.Attr {
					if a.Name.Local == "idref" {
						out.spine = append(out.spine, a.Value)
					}
				}
			case "spine":
				for _, a := range se.Attr {
					if a.Name.Local == "toc" {
						out.tocID = a.Value
					}
				}
			}
		}
	}
	out.title = extractDcTitle(data)
	return out, nil
}

func extractDcTitle(data []byte) string {
	dec := xml.NewDecoder(bytes.NewReader(data))
	for {
		t, err := dec.Token()
		if err != nil {
			return ""
		}
		if se, ok := t.(xml.StartElement); ok && se.Name.Local == "title" {
			var s struct {
				Chardata string `xml:",chardata"`
			}
			if err := dec.DecodeElement(&s, &se); err == nil {
				return strings.TrimSpace(s.Chardata)
			}
		}
	}
}

func readToc(zr *zip.Reader, ncxID string, manifest map[string]string, opfDir string) (map[string]string, error) {
	for _, href := range manifest {
		full := joinZipPath(opfDir, href)
		if f, err := zr.Open(full); err == nil {
			data, _ := io.ReadAll(f)
			f.Close()
			if toc := parseNavToc(data); len(toc) > 0 {
				return toc, nil
			}
		}
	}
	if ncxID != "" {
		if href, ok := manifest[ncxID]; ok {
			full := joinZipPath(opfDir, href)
			if f, err := zr.Open(full); err == nil {
				data, _ := io.ReadAll(f)
				f.Close()
				return parseNcx(data), nil
			}
		}
	}
	return nil, fmt.Errorf("no toc")
}

func parseNcx(data []byte) map[string]string {
	out := map[string]string{}
	type ncxNav struct {
		Label struct {
			Text string `xml:"text"`
		} `xml:"navLabel"`
		Content struct {
			Src string `xml:"src,attr"`
		} `xml:"content"`
	}
	type ncxRoot struct {
		NavMap struct {
			Points []ncxNav `xml:"navPoint"`
		} `xml:"navMap"`
	}
	var root ncxRoot
	if err := xml.Unmarshal(data, &root); err != nil {
		return out
	}
	for _, p := range root.NavMap.Points {
		out[p.Content.Src] = strings.TrimSpace(p.Label.Text)
	}
	return out
}

func parseNavToc(data []byte) map[string]string {
	out := map[string]string{}
	doc, err := html.Parse(bytes.NewReader(data))
	if err != nil {
		return out
	}
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode && n.Data == "a" {
			href := ""
			for _, a := range n.Attr {
				if a.Key == "href" {
					href = a.Val
					break
				}
			}
			if href != "" {
				out[href] = strings.TrimSpace(textOf(n))
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	return out
}

func textOf(n *html.Node) string {
	var sb strings.Builder
	var w func(*html.Node)
	w = func(node *html.Node) {
		if node.Type == html.TextNode {
			sb.WriteString(node.Data)
		}
		for c := node.FirstChild; c != nil; c = c.NextSibling {
			w(c)
		}
	}
	w(n)
	return sb.String()
}

func extractXhtmlText(data []byte) (string, bool) {
	doc, err := html.Parse(bytes.NewReader(data))
	if err != nil {
		return "[本章节解析失败]", false
	}
	var sb strings.Builder
	imgCount, textCount := 0, 0
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		if n.Type == html.ElementNode {
			switch n.Data {
			case "img", "image":
				imgCount++
				sb.WriteString("[图片]")
				return
			case "p", "div", "br", "h1", "h2", "h3":
				if sb.Len() > 0 {
					sb.WriteString("\n\n")
				}
			}
		}
		if n.Type == html.TextNode {
			s := strings.TrimSpace(n.Data)
			if s != "" {
				sb.WriteString(s)
				textCount++
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	walk(doc)
	if textCount == 0 && imgCount > 0 {
		return "", true
	}
	return strings.TrimSpace(sb.String()), false
}

func readZipFile(files []*zip.File, name string) ([]byte, error) {
	for _, f := range files {
		if f.Name == name {
			rc, err := f.Open()
			if err != nil {
				return nil, err
			}
			defer rc.Close()
			return io.ReadAll(rc)
		}
	}
	return nil, fmt.Errorf("not found: %s", name)
}

func joinZipPath(dir, href string) string {
	if dir == "" || dir == "." {
		return normalizeHref(href)
	}
	return normalizeHref(filepath.ToSlash(filepath.Join(dir, href)))
}

func normalizeHref(s string) string {
	s = filepath.ToSlash(s)
	if i := strings.IndexByte(s, '#'); i >= 0 {
		s = s[:i]
	}
	return s
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd server && go test ./internal/service/bookparser/ -v`
Expected: PASS (all txt + epub tests).

- [ ] **Step 5: Promote x/net to direct**

Run: `cd server && go mod tidy`
Verify: `cd server && grep "golang.org/x/net" go.mod` — no `// indirect`.

- [ ] **Step 6: Commit**

```bash
git add server/internal/service/bookparser/epub.go server/internal/service/bookparser/epub_test.go server/go.mod server/go.sum
git commit -m "feat(bookparser): epub parsing with OPF spine + NCX/nav TOC + chapter text extraction"
```

---

## Task 7: service/book.go — BookService mtime cache + singleflight

**Files:**
- Create: `server/internal/service/book.go`
- Create: `server/internal/service/book_test.go`

**Interfaces:**
- Produces:
  - `func NewBookService() *BookService`
  - `func (s *BookService) GetBook(path string) (*bookparser.Book, error)` — mtime check, singleflight dedup, returns cached `*Book` on hit. Callers must not mutate the returned Book.

- [ ] **Step 1: Write the failing test**

`server/internal/service/book_test.go`:

```go
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
	assert.Same(t b1, b2, "expected identical *Book on cache hit")
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
```

Note: `assert.Same(t b1, b2, ...)` is a typo in this snippet — must be `assert.Same(t, b1, b2, ...)`. Fix when transcribing.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/ -run TestBookService -v`
Expected: FAIL — `NewBookService undefined`.

- [ ] **Step 3: Implement book.go**

`server/internal/service/book.go`:

```go
package service

import (
	"errors"
	"fmt"
	"os"
	"sync"

	"golang.org/x/sync/singleflight"

	"github.com/localmediahub/server/internal/service/bookparser"
)

type BookService struct {
	mu    sync.RWMutex
	cache map[string]*bookparser.Book
	sf    singleflight.Group
}

func NewBookService() *BookService {
	return &BookService{cache: make(map[string]*bookparser.Book)}
}

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
```

- [ ] **Step 4: Run tests to verify pass**

Run: `cd server && go test ./internal/service/ -run TestBookService -v`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/book.go server/internal/service/book_test.go
git commit -m "feat(service): BookService with mtime-keyed cache + singleflight"
```

---

## Task 8: handler/books.go + server.go routing + Go handler 联动

**Files:**
- Create: `server/internal/server/handler/books.go`
- Create: `server/internal/server/handler/books_test.go`
- Modify: `server/internal/server/handler/media.go` (MediaOriginal allowed exts)
- Modify: `server/internal/server/handler/system.go` (SystemBrowse mediaType text branch)
- Modify: `server/internal/server/handler/folders.go` (BrowseFolder + DownloadFolderZip text exts)
- Modify: `server/internal/server/handler/tags.go` (buildTaggedMediaFallback text branch)
- Modify: `server/internal/server/server.go` (NewBookService + handler.New 6th arg + registerRoutes)

**Interfaces:**
- Produces:
  - `(*Handler).GetBookInfo(c echo.Context) error` — `GET /api/v1/books/info?path=...` → 200 Book JSON, 403 path invalid, 413/422/500 by error, 200+`format:"unsupported"` for mobi/azw3.
  - `(*Handler).GetBookChapter(c echo.Context) error` — `GET /api/v1/books/chapter?path=...&index=N` → 200 `{"title": "...", "content": "..."}`, 400 bad index, 403 path invalid.

- [ ] **Step 1: Write the failing test**

`server/internal/server/handler/books_test.go`:

```go
package handler

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"github.com/labstack/echo/v4"
	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/service"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

func newBooksHandler(t *testing.T) (*Handler, string) {
	t.Helper()
	dir := t.TempDir()
	cfg := &config.Config{}
	cfg.Scan.VideoExtensions = []string{".mp4"}
	cfg.Scan.ImageExtensions = []string{".jpg"}
	cfg.Scan.TextExtensions = []string{".txt", ".epub"}
	cfg.Scan.Roots = []string{dir}
	scanner := service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions)
	books := service.NewBookService()
	h := New(cfg, scanner, nil, nil, nil, books)
	return h, dir
}

func TestGetBookInfoTxt(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := filepath.Join(dir, "n.txt")
	require.NoError(t, os.WriteFile(p, []byte("第一章\nbody"), 0644))

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/info?path="+p, nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookInfo(c))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"format":"txt"`)
}

func TestGetBookInfoPathOutsideRoots403(t *testing.T) {
	h, _ := newBooksHandler(t)
	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/info?path=/etc/passwd", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookInfo(c))
	assert.Equal(t, http.StatusForbidden, rec.Code)
}

func TestGetBookChapterReturnsJSON(t *testing.T) {
	h, dir := newBooksHandler(t)
	p := filepath.Join(dir, "n.txt")
	require.NoError(t, os.WriteFile(p, []byte("第一章 A\nbody text here"), 0644))

	e := echo.New()
	req := httptest.NewRequest(http.MethodGet, "/api/v1/books/chapter?path="+p+"&index=0", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	require.NoError(t, h.GetBookChapter(c))
	assert.Equal(t, http.StatusOK, rec.Code)
	assert.Contains(t, rec.Body.String(), `"title":`)
	assert.Contains(t, rec.Body.String(), "body text here")
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/handler/ -run TestGetBook -v`
Expected: FAIL — `h.GetBookInfo undefined`.

- [ ] **Step 3: Implement books.go**

`server/internal/server/handler/books.go`:

```go
package handler

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/labstack/echo/v4"

	"github.com/localmediahub/server/internal/service"
	"github.com/localmediahub/server/internal/service/bookparser"
)

type chapterResponse struct {
	Title   string `json:"title"`
	Content string `json:"content"`
}

func (h *Handler) GetBookInfo(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	allowedExts := append([]string{}, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(
		pathStr,
		h.cfg.Scan.GetRoots(),
		h.cfg.GetSystemAllowedRoots(),
		allowedExts,
	)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	if h.books == nil {
		return respondInternalError(c, errors.New("book service unavailable"))
	}
	b, err := h.books.GetBook(resolved)
	if err != nil {
		return mapBookError(c, err)
	}
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, b)
}

func (h *Handler) GetBookChapter(c echo.Context) error {
	pathStr := c.QueryParam("path")
	if pathStr == "" {
		return respondError(c, http.StatusBadRequest, "path required")
	}
	idx, err := strconv.Atoi(c.QueryParam("index"))
	if err != nil || idx < 0 {
		return respondError(c, http.StatusBadRequest, "invalid index")
	}
	allowedExts := append([]string{}, h.cfg.Scan.TextExtensions...)
	resolved, err := service.ValidateAccessibleMediaPath(
		pathStr,
		h.cfg.Scan.GetRoots(),
		h.cfg.GetSystemAllowedRoots(),
		allowedExts,
	)
	if err != nil {
		return respondError(c, http.StatusForbidden, "access denied")
	}
	if h.books == nil {
		return respondInternalError(c, errors.New("book service unavailable"))
	}
	b, err := h.books.GetBook(resolved)
	if err != nil {
		return mapBookError(c, err)
	}
	if idx >= len(b.Chapters) {
		return respondError(c, http.StatusBadRequest, "index out of range")
	}
	text, err := b.ChapterText(idx)
	if err != nil {
		return mapBookError(c, err)
	}
	setJsonCacheBrief(c)
	return c.JSON(http.StatusOK, chapterResponse{Title: b.Chapters[idx].Title, Content: text})
}

func mapBookError(c echo.Context, err error) error {
	switch {
	case errors.Is(err, bookparser.ErrTooLarge):
		return respondError(c, http.StatusRequestEntityTooLarge, "file too large")
	case errors.Is(err, bookparser.ErrInvalidEpub), errors.Is(err, bookparser.ErrEncrypted):
		return respondError(c, http.StatusUnprocessableEntity, "invalid ebook")
	case errors.Is(err, bookparser.ErrIoFailure):
		return respondInternalError(c, err)
	default:
		return respondInternalError(c, err)
	}
}
```

- [ ] **Step 4: Wire production server.go**

(a) After scanner construction (~line 47), add:
```go
bookService := service.NewBookService()
```

(b) Update handler.New call (~line 90):
```go
h := handler.New(cfg, scanner, tagsService, streamingService, thumbnailService, bookService)
```

(c) In `registerRoutes`, after the `media := api.Group("/media", authMw)` block, add:
```go
books := api.Group("/books", authMw)
books.GET("/info", h.GetBookInfo)
books.GET("/chapter", h.GetBookChapter)
```

- [ ] **Step 5: Update media.go MediaOriginal allowed exts**

In `server/internal/server/handler/media.go`, find where `MediaOriginal` calls `ValidateAccessibleMediaPath`. Replace the single `ImageExtensions` arg with a combined slice:

```go
allowedExts := make([]string, 0, len(h.cfg.Scan.ImageExtensions)+len(h.cfg.Scan.TextExtensions))
allowedExts = append(allowedExts, h.cfg.Scan.ImageExtensions...)
allowedExts = append(allowedExts, h.cfg.Scan.TextExtensions...)
resolved, err := service.ValidateAccessibleMediaPath(
	pathStr,
	h.cfg.Scan.GetRoots(),
	h.cfg.GetSystemAllowedRoots(),
	allowedExts,
)
```

- [ ] **Step 6: Update system.go SystemBrowse mediaType branching**

Find the mediaType decision in `SystemBrowse`. Replace the binary image/video branching with a three-way check that includes text. Match the surrounding code style; conceptually:

```go
mediaType := "video"
matched := false
for _, imgExt := range h.cfg.Scan.ImageExtensions {
	if strings.EqualFold(ext, imgExt) {
		mediaType = "image"
		matched = true
		break
	}
}
if !matched {
	for _, txtExt := range h.cfg.Scan.TextExtensions {
		if strings.EqualFold(ext, txtExt) {
			mediaType = "text"
			break
		}
	}
}
```

- [ ] **Step 7: Update folders.go BrowseFolder + DownloadFolderZip**

Add a helper in `folders.go` and use it whenever a `MediaFile` is built from a disk entry:

```go
func (h *Handler) classifyMediaType(ext string) string {
	ext = strings.ToLower(ext)
	if h.scanner.VideoExts()[ext] {
		return "video"
	}
	if h.scanner.ImageExts()[ext] {
		return "image"
	}
	if h.scanner.TextExts()[ext] {
		return "text"
	}
	return ""
}
```

Replace existing inline classification logic in `BrowseFolder` and `DownloadFolderZip` with calls to `h.classifyMediaType`. Files with empty classification are skipped (as before for non-media).

- [ ] **Step 8: Update tags.go buildTaggedMediaFallback**

Find `buildTaggedMediaFallback`. Add a text branch alongside the existing video/image branches:

```go
if h.scanner.TextExts()[strings.ToLower(ext)] {
	return "text"
}
```

- [ ] **Step 9: Run all Go tests**

Run: `cd server && go test ./...`
Expected: PASS.

- [ ] **Step 10: Manual smoke**

Run: `cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless` (background)
In another shell:
```
curl -s -H "Authorization: Bearer <token>" "http://localhost:<port>/api/v1/books/info?path=<some .txt>" | head
```
Expected: JSON with `"format":"txt"` and a `chapters` array. Kill the server when done.

- [ ] **Step 11: Commit**

```bash
git add server/internal/server/handler/books.go server/internal/server/handler/books_test.go server/internal/server/handler/media.go server/internal/server/handler/system.go server/internal/server/handler/folders.go server/internal/server/handler/tags.go server/internal/server/server.go
git commit -m "feat(server): /api/v1/books endpoints + text mediaType in system/browse/folders/tags"
```

---

## Task 9: Android — Models + MediaRepository

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`

**Interfaces:**
- Produces: `BookChapter`, `Book`, `BookChapterContent` data classes (per spec lines 319-342); `MediaRepository.getBookInfo(path)` + `MediaRepository.getBookChapter(path, index)`.

- [ ] **Step 1: Read MediaRepository to find httpGet signature**

Read `android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt`. Note the existing `httpGet<T>` helper signature, how `baseUrl` is obtained, and how paths are URL-encoded. Use the same pattern.

- [ ] **Step 2: Add Book models**

In `Models.kt`, after `MediaFile`, add:

```kotlin
@Parcelize
data class BookChapter(
    val index: Int,
    val title: String,
    @SerializedName("char_start") val charStart: Int = 0,
    @SerializedName("char_end") val charEnd: Int = 0,
    @SerializedName("manifest_id") val manifestId: String? = null,
) : Parcelable

@Parcelize
data class Book(
    val path: String,
    val format: String,
    val title: String,
    val charset: String? = null,
    val chapters: List<BookChapter>,
    @SerializedName("mod_time") val modTime: String,
) : Parcelable

data class BookChapterContent(
    val title: String,
    val content: String,
)
```

- [ ] **Step 3: Add repository methods**

In `MediaRepository.kt` (using the existing httpGet/baseUrl/encode pattern found in Step 1):

```kotlin
suspend fun getBookInfo(path: String): NetworkResult<Book> =
    httpGet(
        "${baseUrl}/api/v1/books/info?path=${urlEncode(path)}",
        Book::class.java,
    )

suspend fun getBookChapter(path: String, index: Int): NetworkResult<BookChapterContent> =
    httpGet(
        "${baseUrl}/api/v1/books/chapter?path=${urlEncode(path)}&index=$index",
        BookChapterContent::class.java,
    )
```

Match the existing `urlEncode` / `encode` helper name used in the file.

- [ ] **Step 4: Compile-check**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/Models.kt android/app/src/main/java/com/juziss/localmediahub/data/MediaRepository.kt
git commit -m "feat(android): Book/BookChapter/BookChapterContent models + repository calls"
```

---

## Task 10: Android — RecentActivityStore book_progress

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt`
- Modify or create: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt`

**Interfaces:**
- Produces:
  - `data class BookProgress(val path: String, val chapterIndex: Int, val scrollOffsetPx: Int, val lastReadAt: Long)`
  - `suspend fun getBookProgress(path: String): BookProgress?`
  - `suspend fun saveBookProgress(progress: BookProgress)`
  - `suspend fun clearBookProgress(path: String)`
  - `suspend fun getAllBookProgress(): List<BookProgress>`
  - `fun getAllBookProgressFlow(): Flow<List<BookProgress>>` (for HomeViewModel bookshelf)
  - Persistence key: `book_progress` (Gson-serialized `Map<String, BookProgress>`)

- [ ] **Step 1: Write the failing test**

`android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt` (extend existing file if present; otherwise create with the test runner + imports already configured in another store test):

```kotlin
@RunWith(RobolectricTestRunner::class)
class RecentActivityStoreBookProgressTest {
    private lateinit var store: RecentActivityStore

    @Before
    fun setup() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = RecentActivityStore(ctx)
    }

    @Test
    fun saveAndGetRoundTrip() = runTest {
        val p = BookProgress(path = "/x/novel.txt", chapterIndex = 3, scrollOffsetPx = 123, lastReadAt = 1000L)
        store.saveBookProgress(p)
        val got = store.getBookProgress("/x/novel.txt")
        assertEquals(p, got)
    }

    @Test
    fun clearRemovesEntry() = runTest {
        store.saveBookProgress(BookProgress("/x/a.txt", 1, 0, 1L))
        store.clearBookProgress("/x/a.txt")
        assertNull(store.getBookProgress("/x/a.txt"))
    }

    @Test
    fun getAllReturnsSortedByLastReadAtDesc() = runTest {
        store.saveBookProgress(BookProgress("/x/old.txt", 0, 0, 100L))
        store.saveBookProgress(BookProgress("/x/new.txt", 0, 0, 999L))
        val all = store.getAllBookProgress()
        assertEquals(2, all.size)
        assertEquals("/x/new.txt", all[0].path)
    }
}
```

Use existing test runner + `runTest` conventions (Robolectric + kotlinx-coroutines-test).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BookProgress*"`
Expected: FAIL — `BookProgress` unresolved.

- [ ] **Step 3: Implement in RecentActivityStore.kt**

(a) Add the data class near other entry types:
```kotlin
data class BookProgress(
    val path: String,
    val chapterIndex: Int,
    val scrollOffsetPx: Int,
    val lastReadAt: Long,
)
```

(b) Add Gson TypeToken near other tokens:
```kotlin
private val typeMapBookProgress = object : TypeToken<MutableMap<String, BookProgress>>() {}.type
```

(c) Add DataStore key + flows + API:
```kotlin
private val bookProgressKey = preferencesKey<String>("book_progress")

val bookProgressFlow: Flow<Map<String, BookProgress>> = dataStore.data
    .map { prefs ->
        val raw = prefs[bookProgressKey] ?: "{}"
        gson.fromJson(raw, typeMapBookProgress) ?: emptyMap()
    }

fun getAllBookProgressFlow(): Flow<List<BookProgress>> = bookProgressFlow
    .map { map -> map.values.sortedByDescending { it.lastReadAt } }

suspend fun getBookProgress(path: String): BookProgress? = bookProgressFlow.first()[path]

suspend fun saveBookProgress(progress: BookProgress) {
    dataStore.edit { prefs ->
        val current: MutableMap<String, BookProgress> =
            gson.fromJson(prefs[bookProgressKey] ?: "{}", typeMapBookProgress) ?: mutableMapOf()
        current[progress.path] = progress
        prefs[bookProgressKey] = gson.toJson(current)
    }
}

suspend fun clearBookProgress(path: String) {
    dataStore.edit { prefs ->
        val current: MutableMap<String, BookProgress> =
            gson.fromJson(prefs[bookProgressKey] ?: "{}", typeMapBookProgress) ?: mutableMapOf()
        current.remove(path)
        prefs[bookProgressKey] = if (current.isEmpty()) "" else gson.toJson(current)
    }
}

suspend fun getAllBookProgress(): List<BookProgress> = getAllBookProgressFlow().first()
```

Match the existing `gson` instance reuse (the file already uses Gson for other entries). Match the existing DataStore delegate name (the snippet uses `dataStore` — adjust to the file's actual name).

- [ ] **Step 4: Run tests to verify pass**

Run: `cd android && ./gradlew testDebugUnitTest --tests "*BookProgress*"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreTest.kt
git commit -m "feat(android): book_progress persistence in RecentActivityStore"
```

---

## Task 11: Android — TextReaderViewModel + Activity + Screen

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt`
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
- Modify: `android/app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces:
  - `@HiltViewModel class TextReaderViewModel @Inject constructor(repo, store)` exposing `book`, `currentIndex`, `chapterText`, `isLoading`, `error` StateFlows and `loadBook(path)` / `loadChapter(index)` / `nextChapter()` / `prevChapter()`
  - `class TextReaderActivity : ComponentActivity()` with `EXTRA_PATH` / `EXTRA_IS_LOCAL` constants and `newIntent(ctx, path, isLocal)` factory
  - `@Composable fun TextReaderScreen(viewModel, onBack)`

- [ ] **Step 1: Implement TextReaderViewModel**

`viewmodel/TextReaderViewModel.kt`:

```kotlin
package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.BookProgress
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.NetworkResult
import com.juziss.localmediahub.data.RecentActivityStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TextReaderViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val store: RecentActivityStore,
) : ViewModel() {

    private val _book = MutableStateFlow<com.juziss.localmediahub.data.Book?>(null)
    val book: StateFlow<com.juziss.localmediahub.data.Book?> = _book.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _chapterText = MutableStateFlow("")
    val chapterText: StateFlow<String> = _chapterText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun loadBook(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val r = repo.getBookInfo(path)) {
                is NetworkResult.Success -> {
                    val b = r.data
                    _book.value = b
                    if (b.format == "unsupported") {
                        _error.value = "暂不支持该格式"
                        _isLoading.value = false
                        return@launch
                    }
                    val saved = store.getBookProgress(path)
                    val idx = saved?.chapterIndex?.coerceIn(0, b.chapters.lastIndex.coerceAtLeast(0)) ?: 0
                    loadChapter(idx)
                }
                is NetworkResult.Error -> {
                    _error.value = r.message ?: "加载失败"
                    _isLoading.value = false
                }
            }
        }
    }

    fun loadChapter(index: Int) {
        val b = _book.value ?: return
        if (index !in b.chapters.indices) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val r = repo.getBookChapter(b.path, index)) {
                is NetworkResult.Success -> {
                    _currentIndex.value = index
                    _chapterText.value = r.data.content
                    store.saveBookProgress(
                        BookProgress(
                            path = b.path,
                            chapterIndex = index,
                            scrollOffsetPx = 0,
                            lastReadAt = System.currentTimeMillis(),
                        )
                    )
                }
                is NetworkResult.Error -> _error.value = r.message ?: "加载失败"
            }
            _isLoading.value = false
        }
    }

    fun nextChapter() {
        val b = _book.value ?: return
        if (_currentIndex.value < b.chapters.lastIndex) loadChapter(_currentIndex.value + 1)
    }

    fun prevChapter() {
        if (_currentIndex.value > 0) loadChapter(_currentIndex.value - 1)
    }
}
```

Match the project's actual `NetworkResult` sealed hierarchy (Success/Error shape may differ — adjust field access accordingly).

- [ ] **Step 2: Implement TextReaderActivity**

`TextReaderActivity.kt`:

```kotlin
package com.juziss.localmediahub

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import com.juziss.localmediahub.ui.screen.TextReaderScreen
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class TextReaderActivity : ComponentActivity() {

    private val viewModel: TextReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH).orEmpty()
        setContent {
            LaunchedEffect(path) { viewModel.loadBook(path) }
            TextReaderScreen(viewModel = viewModel, onBack = { finish() })
        }
    }

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_IS_LOCAL = "extra_is_local"

        fun newIntent(ctx: Context, path: String, isLocal: Boolean = false): Intent =
            Intent(ctx, TextReaderActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_IS_LOCAL, isLocal)
    }
}
```

- [ ] **Step 3: Implement TextReaderScreen**

`ui/screen/TextReaderScreen.kt`:

```kotlin
package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.viewmodel.TextReaderViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextReaderScreen(viewModel: TextReaderViewModel, onBack: () -> Unit) {
    val book by viewModel.book
    val text by viewModel.chapterText
    val idx by viewModel.currentIndex
    val isLoading by viewModel.isLoading
    val error by viewModel.error

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text("目录", modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                LazyColumn {
                    items(book?.chapters ?: emptyList()) { ch ->
                        NavigationDrawerItem(
                            label = { Text(ch.title) },
                            selected = ch.index == idx,
                            onClick = {
                                viewModel.loadChapter(ch.index)
                                scope.launch { drawerState.close() }
                            },
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(book?.chapters?.getOrNull(idx)?.title ?: book?.title ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") }
                    },
                    actions = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.List, contentDescription = "目录")
                        }
                    },
                )
            },
            bottomBar = {
                BottomAppBar {
                    Text("第 ${idx + 1} / ${book?.chapters?.size ?: 0} 章", modifier = Modifier.padding(16.dp))
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { viewModel.prevChapter() }) { Text("上一章") }
                    TextButton(onClick = { viewModel.nextChapter() }) { Text("下一章") }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                if (isLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                error?.let { Text(it, modifier = Modifier.align(Alignment.Center).padding(16.dp)) }
                if (error == null && !isLoading) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        contentPadding = PaddingValues(vertical = 16.dp),
                    ) {
                        items(text.split("\n\n").filter { it.isNotBlank() }) { para ->
                            Text(para, modifier = Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Register activity in AndroidManifest.xml**

Inside `<application>`, add:
```xml
<activity
    android:name=".TextReaderActivity"
    android:exported="false"
    android:launchMode="singleTop"
    android:label="阅读" />
```

- [ ] **Step 5: Compile-check**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt android/app/src/main/AndroidManifest.xml
git commit -m "feat(android): TextReaderActivity + ViewModel + Screen (online)"
```

---

## Task 12: Android — when(mediaType) full audit

**Files:** Modify per the table in spec lines 405-418:
- `MainActivity.kt` (3 sites — CRITICAL)
- `DownloadsScreen.kt` (3 sites — CRITICAL)
- `BrowseContent.kt` (4 sites)
- `BrowseStateContent.kt` (3 sites)
- `BrowseSearchView.kt`
- `HomeComponents.kt` (4 sites)
- `DownloadManager.kt` (comment only)
- `MediaItems.kt` (add TextCard composable)

**Interfaces:**
- Produces: `@Composable fun TextCard(name, format, isUnsupported, isSelected, onClick, onLongClick, modifier)` — renders an icon + filename + optional "暂不支持" badge + selection overlay.

- [ ] **Step 1: Inventory all mediaType branches**

Run: `cd android && grep -rn '"video"\|"image"\|mediaType' app/src/main/java/com/juziss/localmediahub/`
Capture the full list — every `when(file.mediaType)` and `if (file.mediaType == "video")`. Each must end with either a `"text" ->` branch or an `else ->` that does not crash on unknown types.

- [ ] **Step 2: Add TextCard composable**

In `MediaItems.kt`, add:

```kotlin
@Composable
fun TextCard(
    name: String,
    format: String,
    isUnsupported: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (isUnsupported) MaterialTheme.colorScheme.surfaceVariant
        else MaterialTheme.colorScheme.surface
    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = if (isUnsupported) MaterialTheme.colorScheme.outline
                           else MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            if (isUnsupported) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    color = MaterialTheme.colorScheme.outline,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text("暂不支持", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (isSelected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)))
            }
        }
    }
}
```

Imports needed: `androidx.compose.foundation.combinedClickable`, `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.Description`, `androidx.compose.ui.draw.background`, etc. Match existing imports in MediaItems.kt.

- [ ] **Step 3: Rewrite MainActivity mediaType branches**

For each of the 3 sites currently doing `if (file.mediaType == "video") { ... } else { /* image */ }`, convert to:

```kotlin
when (file.mediaType) {
    "video" -> { /* existing video branch */ }
    "image" -> { /* existing image branch */ }
    "text" -> {
        val ext = file.extension.lowercase()
        if (ext == ".txt" || ext == ".epub") {
            startActivity(TextReaderActivity.newIntent(this, file.path))
        } else {
            Toast.makeText(this, "暂不支持该格式", Toast.LENGTH_SHORT).show()
        }
    }
}
```

- [ ] **Step 4: Rewrite DownloadsScreen three sites**

(a) Click handler — `when` shape as above, with `isLocal = true` for TextReaderActivity (these are downloaded files).

(b) Thumbnail/icon area:
```kotlin
when (file.mediaType) {
    "image" -> { /* Coil thumbnail */ }
    "video" -> { /* Film icon */ }
    "text" -> { Icon(Icons.Filled.Description, ...) }
    else -> {}
}
```

(c) Type label:
```kotlin
val typeLabel = when (file.mediaType) {
    "video" -> "视频"
    "image" -> "图片"
    "text" -> "小说"
    else -> ""
}
```

- [ ] **Step 5: Browse / Search / Home sites**

For each `when(file.mediaType)` in `BrowseContent.kt`, `BrowseStateContent.kt`, `BrowseSearchView.kt`, `HomeComponents.kt` add:

```kotlin
"text" -> TextCard(
    name = file.name,
    format = file.extension,
    isUnsupported = (file.extension.lowercase() !in setOf(".txt", ".epub")),
    isSelected = /* from existing selection state */,
    onClick = { /* existing onOpen(file) callback — caller routes to TextReaderActivity */ },
    onLongClick = { /* existing long-press to enter selection mode */ },
)
```

`HomeComponents.kt` bookshelf-specific filtering (exclude unsupported) is handled in Task 13.

- [ ] **Step 6: Comment DownloadManager URL selection**

In `DownloadManager.kt`, find `if (video) streamUrl else imageUrl`. Add a comment above:

```kotlin
// Text files use imageUrl (= /api/v1/media/original) because Task 8
// extended MediaOriginal to allow TextExtensions. This is the download URL
// for books; the TextReaderActivity consumes the local file or the
// /api/v1/books/* endpoints for chapter rendering.
```

- [ ] **Step 7: Compile + manual smoke**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Manual: install APK, browse to a folder with a `.txt`, verify the TextCard renders with the document icon and clicking opens the reader.

- [ ] **Step 8: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/
git commit -m "feat(android): text mediaType branches across Browse/Home/Downloads/MainActivity"
```

---

## Task 13: Android — HomeViewModel + bookshelf card

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/HomeViewModel.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt` (add `getAllBookProgressFlow` if not already added in Task 10)
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt`

**Interfaces:**
- Produces:
  - `data class RecentBookEntry(val path: String, val title: String, val chapterIndex: Int, val lastReadAt: Long, val format: String)`
  - `HomeViewModel.recentBooks: StateFlow<List<RecentBookEntry>>` — top-10 by lastReadAt, txt/epub only
  - `@Composable fun BookshelfCard(books, onOpen)` — LazyRow of book cards, hidden when list empty

- [ ] **Step 1: Ensure RecentActivityStore exposes getAllBookProgressFlow**

Task 10 Step 3(c) already declares `getAllBookProgressFlow()`. If it was not added there, add it now (see Task 10 snippet).

- [ ] **Step 2: Add RecentBookEntry + recentBooks StateFlow**

In `HomeViewModel.kt` (or a new file `data/RecentBookEntry.kt` if preferred — keep simple, put in HomeViewModel.kt for now):

```kotlin
data class RecentBookEntry(
    val path: String,
    val title: String,
    val chapterIndex: Int,
    val lastReadAt: Long,
    val format: String,
)
```

In `HomeViewModel.kt`:

```kotlin
val recentBooks: StateFlow<List<RecentBookEntry>> =
    store.getAllBookProgressFlow()
        .map { list ->
            list
                .filter { isSupportedFormat(it.path) }
                .take(10)
                .map { p ->
                    RecentBookEntry(
                        path = p.path,
                        title = File(p.path).nameWithoutExtension,
                        chapterIndex = p.chapterIndex,
                        lastReadAt = p.lastReadAt,
                        format = formatFromPath(p.path),
                    )
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

private fun isSupportedFormat(path: String): Boolean {
    val ext = path.substringAfterLast('.', "").lowercase()
    return ext == "txt" || ext == "epub"
}

private fun formatFromPath(path: String): String =
    path.substringAfterLast('.', "").lowercase()
```

Imports: `kotlinx.coroutines.flow.map`, `kotlinx.coroutines.flow.stateIn`, `kotlinx.coroutines.flow.SharingStarted`, `java.io.File`.

- [ ] **Step 3: Implement BookshelfCard**

In `HomeComponents.kt`:

```kotlin
@Composable
fun BookshelfCard(
    books: List<RecentBookEntry>,
    onOpen: (RecentBookEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) return
    Surface(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Column {
            Text("我的书架", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(books) { entry ->
                    Column(
                        modifier = Modifier
                            .padding(8.dp)
                            .width(100.dp)
                            .clickable { onOpen(entry) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Description, contentDescription = null, modifier = Modifier.size(48.dp))
                        Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        Text("第 ${entry.chapterIndex + 1} 章", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Place card in HomeScreen**

In `HomeScreen.kt`, observe `recentBooks` from HomeViewModel. Insert `BookshelfCard(...)` between "最近活动" and "继续播放" cards:

```kotlin
val recentBooks by homeViewModel.recentBooks.collectAsState()
// ... in the screen body, in the right position:
BookshelfCard(
    books = recentBooks,
    onOpen = { entry ->
        context.startActivity(TextReaderActivity.newIntent(context, entry.path))
    },
)
```

(`context` from `LocalContext.current`.)

- [ ] **Step 5: Compile + manual smoke**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Manual: read a chapter in some txt via the reader (creates a progress entry), return to HomeScreen, verify "我的书架" appears with the entry.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/
git commit -m "feat(android): HomeScreen bookshelf card powered by book_progress"
```

---

## Task 14: Android — offline sidecar download

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt` (TODO marker only)

**Interfaces:**
- Produces: after downloading a `.txt` or `.epub` to `<downloads>/<filename>`, `DownloadWorker` also fetches `GET /api/v1/books/info?path=<originalPath>` and writes the response to `<downloads>/<filename>.json`. Best-effort — on failure the download still succeeds.

- [ ] **Step 1: Read DownloadWorker structure**

Read `DownloadWorker.kt` end-to-end. Identify:
- Where the file URL is built (likely `MediaOriginal` path).
- Where the file is saved on disk.
- The `Result.success()` / `Result.failure()` return points.
- The OkHttpClient + token access pattern (already in use for the main file download).

- [ ] **Step 2: Add sidecar fetch**

After the book file is successfully written, before `Result.success()`, add (for txt/epub only):

```kotlin
val ext = outFile.extension.lowercase()
if (ext == "txt" || ext == "epub") {
    try {
        val infoUrl = "${baseUrl}/api/v1/books/info?path=${urlEncode(remotePath)}"
        val infoResp = okHttpClient.newCall(
            Request.Builder().url(infoUrl).header("Authorization", "Bearer $token").build()
        ).execute()
        infoResp.use { r ->
            if (r.isSuccessful) {
                val sidecar = File(outFile.parentFile, "${outFile.name}.json")
                sidecar.outputStream().use { out ->
                    r.body?.byteStream()?.copyTo(out)
                }
            }
        }
    } catch (e: Exception) {
        android.util.Log.w("DownloadWorker", "sidecar fetch failed for ${outFile.name}: ${e.message}")
    }
}
```

Match the actual `okHttpClient` / `baseUrl` / `token` / `urlEncode` accessors already used in the worker. `remotePath` is the original server path of the file.

- [ ] **Step 3: Add TODO marker for local-mode rendering**

At the top of `TextReaderActivity.onCreate`:

```kotlin
// TODO Task 14a (deferred): when intent.getBooleanExtra(EXTRA_IS_LOCAL, false),
// load chapter text directly from the local file + sidecar JSON instead of
// hitting /api/v1/books/*. For now, EXTRA_IS_LOCAL is ignored and online
// fetch is used (works as long as the server is reachable).
```

- [ ] **Step 4: Compile + manual smoke**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL.

Manual: download a `.txt` via DownloadsScreen, verify via `adb shell ls <app downloads dir>` that both `novel.txt` and `novel.txt.json` exist.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/DownloadWorker.kt android/app/src/main/java/com/juziss/localmediahub/TextReaderActivity.kt
git commit -m "feat(android): download sidecar book info JSON for offline reading prep"
```

---

## Task 15: Web — router + api + browserView + textReader.js

**Files:**
- Modify: `server/internal/web/router.js`
- Modify: `server/internal/web/api.js`
- Modify: `server/internal/web/browserView.js`
- Create: `server/internal/web/textReader.js`

**Interfaces:**
- Produces: `api.getBookInfo(path)` + `api.getBookChapter(path, index)` Promise-returning methods; router recognizes `#/read?path=...` and `#/bookshelf`; browserView renders text files as doc cards (txt/epub click → `#/read?path=...`, mobi/azw3 shows badge + toast); `textReader.render(container, path)` initializes the reader.

- [ ] **Step 1: Add api methods**

In `api.js`, after existing request helpers:

```js
async function getBookInfo(path) {
  return request('/api/v1/books/info?path=' + encodeURIComponent(path));
}

async function getBookChapter(path, index) {
  return request('/api/v1/books/chapter?path=' + encodeURIComponent(path) + '&index=' + encodeURIComponent(index));
}
```

Add to the module's return/export object alongside existing entries. Match the existing module pattern (likely IIFE returning an object).

- [ ] **Step 2: Add router routes**

In `router.js`:

```js
routes['#/read'] = (container, params) => textReader.render(container, params.get('path'));
routes['#/bookshelf'] = (container) => bookshelf.render(container);
```

Match the existing route registration shape.

- [ ] **Step 3: Extend browserView**

In `browserView.js`, inside the file card render, branch on mediaType before falling through to the existing video/image logic:

```js
const isText = file.media_type === 'text';
const isUnsupportedText = isText && !['.txt', '.epub'].includes((file.extension || '').toLowerCase());
if (isText) {
  cardEl.classList.add('text-card');
  if (isUnsupportedText) cardEl.classList.add('text-card--unsupported');
  cardEl.innerHTML = '';
  cardEl.appendChild(makeDocIcon(file.extension));
  cardEl.appendChild(makeFilenameLabel(file.name));
  if (isUnsupportedText) {
    cardEl.appendChild(makeBadge('暂不支持'));
  }
  cardEl.addEventListener('click', () => {
    if (isUnsupportedText) {
      toast.show('暂不支持该格式');
      return;
    }
    location.hash = '#/read?path=' + encodeURIComponent(file.path);
  });
  return;
}
// existing video/image branch continues...
```

Add helper `makeDocIcon(ext)` returning an inline-SVG element. Match the existing DOM helper pattern (`dom.js` style).

- [ ] **Step 4: Implement textReader.js**

`server/internal/web/textReader.js`:

```js
(function () {
  const STORAGE_PREFIX = 'book_progress:';

  async function render(container, path) {
    const book = await api.getBookInfo(path);
    const progress = loadProgress(path);
    const startIdx = progress ? clamp(progress.chapterIndex || 0, 0, (book.chapters || []).length - 1) : 0;

    container.innerHTML = `
      <div class="text-reader">
        <header class="text-reader__header">
          <button class="text-reader__back">←</button>
          <span class="text-reader__title"></span>
        </header>
        <div class="text-reader__content"></div>
        <footer class="text-reader__footer">
          <button class="text-reader__prev">上一章</button>
          <span class="text-reader__progress"></span>
          <button class="text-reader__next">下一章</button>
          <button class="text-reader__toc">目录</button>
        </footer>
      </div>
      <div class="text-reader__drawer text-reader__drawer--hidden"></div>
    `;

    const els = bindEls(container);
    els.title.textContent = book.title;
    els.back.addEventListener('click', () => history.back());
    els.prev.addEventListener('click', () => loadChapter(book, Math.max(0, currentIdx - 1)));
    els.next.addEventListener('click', () => loadChapter(book, Math.min((book.chapters || []).length - 1, currentIdx + 1)));
    els.toc.addEventListener('click', () => toggleDrawer(book, els.drawer));
    els.drawer.addEventListener('chapter-select', (e) => loadChapter(book, e.detail));

    let currentIdx = startIdx;
    await loadChapter(book, startIdx);

    async function loadChapter(b, idx) {
      currentIdx = idx;
      const chapter = await api.getBookChapter(b.path, idx);
      els.title.textContent = chapter.title + ' — ' + b.title;
      els.content.textContent = chapter.content; // textContent prevents XSS
      els.progress.textContent = `第 ${idx + 1} / ${(b.chapters || []).length} 章`;
      saveProgress(path, { chapterIndex: idx, scrollOffset: 0, lastReadAt: Date.now() });
      els.content.scrollTop = 0;
    }
  }

  function loadProgress(path) {
    try { return JSON.parse(localStorage.getItem(STORAGE_PREFIX + path) || 'null'); }
    catch (e) { return null; }
  }
  function saveProgress(path, p) {
    try { localStorage.setItem(STORAGE_PREFIX + path, JSON.stringify(p)); } catch (e) {}
  }
  function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

  function bindEls(root) {
    return {
      back: root.querySelector('.text-reader__back'),
      title: root.querySelector('.text-reader__title'),
      content: root.querySelector('.text-reader__content'),
      prev: root.querySelector('.text-reader__prev'),
      next: root.querySelector('.text-reader__next'),
      toc: root.querySelector('.text-reader__toc'),
      progress: root.querySelector('.text-reader__progress'),
      drawer: root.querySelector('.text-reader__drawer'),
    };
  }

  function toggleDrawer(book, drawerEl) {
    drawerEl.classList.toggle('text-reader__drawer--hidden');
    if (!drawerEl.dataset.populated) {
      drawerEl.innerHTML = '<h3>目录</h3>';
      (book.chapters || []).forEach((ch, i) => {
        const a = document.createElement('a');
        a.href = '#';
        a.textContent = ch.title;
        a.addEventListener('click', (e) => {
          e.preventDefault();
          drawerEl.dispatchEvent(new CustomEvent('chapter-select', { detail: i }));
        });
        drawerEl.appendChild(a);
      });
      drawerEl.dataset.populated = '1';
    }
  }

  window.textReader = { render };
})();
```

- [ ] **Step 5: Manual smoke**

Run: `cd server && go run ./cmd/server --headless`
Open browser to `http://localhost:<port>/`, browse to a folder containing a `.txt`, click it.
Expected: reader view renders, chapter text shows, prev/next/toc work, reload restores last chapter.

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/router.js server/internal/web/api.js server/internal/web/browserView.js server/internal/web/textReader.js
git commit -m "feat(web): /#/read reader route with TOC + progress persistence"
```

---

## Task 16: Web — bookshelf + dashboard

**Files:**
- Create: `server/internal/web/bookshelf.js`
- Modify: `server/internal/web/dashboard.js`

**Interfaces:**
- Produces: `bookshelf.render(container)` full-page; `bookshelf.renderSection(container)` dashboard embed (hidden when empty); `dashboard.js` inserts the section between "最近活动" and "继续播放".

- [ ] **Step 1: Implement bookshelf.js**

`server/internal/web/bookshelf.js`:

```js
(function () {
  const PREFIX = 'book_progress:';

  function loadAll() {
    const out = [];
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i);
      if (!key || !key.startsWith(PREFIX)) continue;
      const path = key.slice(PREFIX.length);
      const ext = (path.split('.').pop() || '').toLowerCase();
      if (ext !== 'txt' && ext !== 'epub') continue;
      try {
        const p = JSON.parse(localStorage.getItem(key));
        out.push(Object.assign({ path }, p));
      } catch (e) {}
    }
    out.sort((a, b) => (b.lastReadAt || 0) - (a.lastReadAt || 0));
    return out;
  }

  function renderCard(entry) {
    const card = document.createElement('div');
    card.className = 'bookshelf-card';
    const name = (entry.path.split(/[\\/]/).pop() || '').replace(/\.[^.]+$/, '');
    card.innerHTML = `
      <div class="bookshelf-card__icon">📄</div>
      <div class="bookshelf-card__title"></div>
      <div class="bookshelf-card__progress"></div>
    `;
    card.querySelector('.bookshelf-card__title').textContent = name;
    card.querySelector('.bookshelf-card__progress').textContent = `第 ${(entry.chapterIndex || 0) + 1} 章`;
    card.addEventListener('click', () => {
      location.hash = '#/read?path=' + encodeURIComponent(entry.path);
    });
    return card;
  }

  function render(container) {
    container.innerHTML = '';
    const list = loadAll();
    if (list.length === 0) {
      container.innerHTML = '<p>暂无阅读历史</p>';
      return;
    }
    list.forEach(e => container.appendChild(renderCard(e)));
  }

  function renderSection(container) {
    const list = loadAll();
    if (list.length === 0) return;
    container.innerHTML = `
      <section class="bookshelf-section">
        <h2>我的书架</h2>
        <div class="bookshelf-grid"></div>
      </section>
    `;
    const grid = container.querySelector('.bookshelf-grid');
    list.forEach(e => grid.appendChild(renderCard(e)));
  }

  window.bookshelf = { render, renderSection };
})();
```

- [ ] **Step 2: Embed in dashboard.js**

In `dashboard.js`, find the order of section renders. Between "最近活动" and "继续播放", add:

```js
const shelfHost = document.createElement('div');
// insert shelfHost into the dashboard container at the right position
// (mirror how the surrounding sections are placed)
bookshelf.renderSection(shelfHost);
```

If dashboard uses a templating helper, insert `<div id="bookshelf-section"></div>` in the template then call `bookshelf.renderSection(document.getElementById('bookshelf-section'))`. Match existing style.

Also ensure `bookshelf.js` is included in the HTML page via `<script src="bookshelf.js"></script>` (mirror how other modules like `browserView.js` are loaded — likely in `web.go` or an HTML template).

- [ ] **Step 3: Manual smoke**

Run server, browse to home, read at least one chapter in one book (creates localStorage entry), reload home.
Expected: "我的书架" section appears with the book card.

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/bookshelf.js server/internal/web/dashboard.js
git commit -m "feat(web): bookshelf section on dashboard + /#/bookshelf route"
```

---

## Task 17: Acceptance pass + CI gate

**Files:** None (verification only)

- [ ] **Step 1: Full Go test pass**

Run: `cd server && go test ./...`
Expected: all PASS.

- [ ] **Step 2: Full Android build + unit tests**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all unit tests pass.

- [ ] **Step 3: Manual acceptance — go through spec acceptance checklist**

From spec lines 556-563:

- [ ] Server start → txt/epub appear in Browse grid (Web + Android)
- [ ] Open GBK txt → chapter list shows "第X章"
- [ ] Open EPUB 3 → TOC shows correctly
- [ ] Prev/next/jump works
- [ ] Kill process + reopen → last chapter + scroll position restored (Android + Web)
- [ ] mobi/azw3 greyed out + Toast on both clients
- [ ] HomeScreen / dashboard "我的书架" section shows recent reads

- [ ] **Step 4: Commit any UI tweaks discovered during acceptance**

If acceptance surfaced small copy/styling fixes, commit them with clear messages. If everything passes cleanly, no commit needed.

- [ ] **Step 5: Final review of commit series**

Run: `git log --oneline -20` — review the feature commit series for sanity.

---

## Self-Review Notes

**Spec coverage:** Every section of the spec is implemented by at least one task.
- Config (Task 1), Scanner (2), bookparser skeleton (4), txt (5), epub (6), BookService (7), handlers+routes+Go 联动 (8) — server side complete
- Android Models/Repo (9), book_progress (10), Reader VM/Activity/Screen (11), mediaType audit (12), Home bookshelf (13), offline sidecar (14) — Android side complete
- Web api/router/browserView/reader (15), bookshelf/dashboard (16) — Web side complete
- Acceptance + CI (17) — verification complete

**Known follow-ups (deferred per spec scope):**
- **Task 14a (local-mode rendering):** The sidecar JSON is downloaded but not yet consumed; offline reading still re-fetches via `/api/v1/books/*` when online. The spec's "首期包含" only lists `book_progress` persistence, not full offline rendering, so this is intentionally out of scope. TODO marker placed in `TextReaderActivity.onCreate`.
- **C-phase enhancements** (fonts/themes/bookmarks/profile switching/epub image inlining) — spec'd as separate spec.

**Type consistency:**
- `BookChapter` Go json tags ↔ Kotlin `@SerializedName` align (`char_start`, `char_end`, `manifest_id`).
- `BookChapterContent` JSON shape `{title, content}` used by Go handler (Task 8) and Kotlin repository (Task 9) and Web api (Task 15).
- `BookProgress` is Kotlin-only (client-local persistence, never serialized over the wire).
- `ErrUnsupported` returns both a non-nil Book AND the sentinel; `BookService.GetBook` (Task 7) and `books.go` handler (Task 8) check via `errors.Is`.

**Placeholder scan:** All "TODO Task 14a" markers are explicitly listed here as intentional deferrals, not missing implementation. No other TBD/TODO/`add appropriate X`/`handle edge cases` placeholders in task steps.

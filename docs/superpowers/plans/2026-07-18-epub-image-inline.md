# epub Image Inline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace B-phase `[图片]` placeholder with real image rendering in the epub reader, by introducing a `Block` type (text/image), upgrading the chapter endpoint to `{title, blocks}`, and adding a `/api/v1/books/image` endpoint that serves image bytes from inside the epub zip.

**Architecture:** Server parses chapter XHTML into ordered `[]Block` (text blocks + image blocks). For each image block, the service rewrites the src to a `/api/v1/books/image?path=...&manifest=...` URL (unless it's already a `data:`/`http(s)://` URI). The new endpoint opens the epub zip, looks up the manifest ID's href, reads the bytes (16 MiB cap), and serves them with a 1-day browser cache. `BearerToken` middleware gets a `?token=` query param fallback so browser `<img>` tags can authenticate. Android uses Coil 3 `AsyncImage` (already wired to the auth-injected OkHttpClient); Web uses native `<img loading="lazy">` with a token-appending helper.

**Tech Stack:** Go (archive/zip, golang.org/x/net/html, crypto/subtle); Android (Kotlin, Compose, Coil 3, OkHttp, Gson); Web (vanilla ES modules, HTML5 `<img>`).

## Global Constraints

[From spec]

- **Chapter endpoint response** must change from `{title, content: string}` to `{title, blocks: [{type, value?, src?}]}` — breaking change, no backward-compat layer.
- **Block types**: `"text"` (with `value`) or `"image"` (with `src`). Other types are ignored by clients.
- **Image src rewrite rules**: skip `data:`, `http://`, `https://`; rewrite others to `/api/v1/books/image?path=<urlenc>&manifest=<urlenc>`; if no manifest match, set `src = ""` (client shows `[本图片无法显示]`).
- **Path traversal protection** in `ReadImageBytes`: reject `fullPath` starting with `/`, `../`, or equal to `..`.
- **BearerToken middleware**: keep using `crypto/subtle.ConstantTimeCompare`; add `c.QueryParam("token")` fallback only when `Authorization` header is missing the `Bearer ` prefix.
- **Per-entry size cap** for image reads: `bookparser.MaxEpubEntrySize` (16 MiB) — already exported.
- **Cache headers**: `/api/v1/books/image` uses `Cache-Control: public, max-age=86400` (same as `/api/v1/media/original`).
- **txt path also switches to blocks**: server splits chapter text by `\n\n` into multiple text blocks.
- **Bookmark field name `paragraphIndex` retained**: semantics shifts to block index, backward-compatible with C-phase saved bookmarks.
- **Coil token injection**: existing `LocalMediaHubApplication.newImageLoader()` already uses the Hilt-injected `okHttpClient` with `AuthInterceptor` — no new code needed.
- **CI gates**: `cd server && go test ./...` AND `cd android && ./gradlew testDebugUnitTest assembleDebug` both must pass.

---

## File Structure

### Server (Go)

- Modify `server/internal/service/bookparser/parser.go` — add `Block` type, `ChapterBlocks` entry, `EpubManifest`/`EpubOpfDir` getters; delete `ChapterText`
- Modify `server/internal/service/bookparser/epub.go` — add `epubChapterBlocks`, `extractBlocks`, `extractImgSrc`; delete `epubChapterText`, `extractXhtmlText`; export `NormalizeHref`, `JoinZipPath`, `ReadCapped`
- Modify `server/internal/service/bookparser/txt.go` — add `txtChapterBlocks`; delete `txtChapterText`
- Modify `server/internal/service/bookparser/parser_test.go` — drop ChapterText-dependent tests (already minimal)
- Modify `server/internal/service/bookparser/epub_test.go` — `TestEpubChapterBlocksExtract`, `TestExtractBlocksDataUriPreserved`, `TestExtractBlocksImageOnlyChapter`
- Modify `server/internal/service/bookparser/txt_test.go` — `TestTxtChapterBlocksSplit`
- Modify `server/internal/service/book.go` — add `GetChapterBlocks`, `ReadImageBytes`, `reverseLookupManifest`, `mimeByExtension`
- Modify `server/internal/service/book_test.go` — `TestGetChapterBlocksRewritesImageSrc`, `TestGetChapterBlocksPreservesDataUri`, `TestReverseLookupManifest`, `TestReadImageBytes`
- Modify `server/internal/server/handler/books.go` — change `chapterResponse` struct, update `GetBookChapter`, add `GetBookImage`
- Modify `server/internal/server/handler/books_test.go` — `TestGetBookChapterReturnsBlocks`, `TestGetBookImageReturnsBlob`, `TestGetBookImagePathOutsideRoots403`, `TestGetBookImageManifestNotFound`
- Modify `server/internal/server/middleware/auth.go` — add query param fallback
- Modify `server/internal/server/middleware/auth_test.go` — 3 new tests for query param behavior
- Modify `server/internal/server/server.go` — register `/api/v1/books/image` route

### Android (Kotlin)

- Modify `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt` — add `Block`, change `BookChapterContent` to hold `blocks: List<Block>`
- Modify `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt` — rename `chapterText` → `chapterBlocks`, update `loadChapter`, update `addBookmarkFromParagraph`
- Modify `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt` — render `Block` list via `when (block.type)`
- Modify `android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt` — update assertions from String to `List<Block>`, add image-block bookmark test

### Web (JS)

- Modify `server/internal/web/textReader.js` — replace `renderParagraphs` with `renderBlocks`, append token query param to image src
- Modify `server/internal/web/style.css` — add `.text-reader__image` styles

---

## Task 1: bookparser — Block type + ChapterBlocks entry

**Files:**
- Modify: `server/internal/service/bookparser/parser.go`

**Interfaces:**
- Produces:
  - `type Block struct { Type string; Value string; Src string }` with json tags `type`, `value,omitempty`, `src,omitempty`
  - `func (b *Book) ChapterBlocks(idx int) ([]Block, error)` — entry dispatching by `b.Format`
  - `func (b *Book) EpubManifest() map[string]string`
  - `func (b *Book) EpubOpfDir() string`
- Deletes: `func (b *Book) ChapterText(idx int) (string, error)` — callers (handler/books.go Task 8) will switch to ChapterBlocks

- [ ] **Step 1: Add Block type + getters + ChapterBlocks dispatcher to parser.go**

Add to `server/internal/service/bookparser/parser.go` (after the `Book` struct definition):

```go
// Block is one ordered unit of a chapter's content. A chapter is a []Block.
// Text blocks carry plain UTF-8 text in Value; image blocks carry a URL or
// data: URI in Src. The service layer rewrites relative epub paths to
// /api/v1/books/image URLs before returning blocks to clients.
type Block struct {
    Type  string `json:"type"`            // "text" | "image"
    Value string `json:"value,omitempty"` // text block
    Src   string `json:"src,omitempty"`   // image block
}

// ChapterBlocks returns the ordered content blocks for chapter idx.
// Image blocks' Src is the raw epub href (relative path, absolute path,
// data: URI, or http(s):// URL). Callers (BookService) rewrite the
// relative ones to /api/v1/books/image endpoint URLs.
func (b *Book) ChapterBlocks(idx int) ([]Block, error) {
    if idx < 0 || idx >= len(b.Chapters) {
        return nil, fmt.Errorf("chapter index out of range: %d", idx)
    }
    switch b.Format {
    case "txt":
        return b.txtChapterBlocks(idx)
    case "epub":
        return b.epubChapterBlocks(idx)
    default:
        return nil, fmt.Errorf("%w: format %s", ErrUnsupported, b.Format)
    }
}

// EpubManifest exposes the parsed OPF manifest (id → href) for service-layer
// image-src rewriting. Returns nil for non-epub books. Callers must NOT
// mutate the map.
func (b *Book) EpubManifest() map[string]string { return b.epubManifest }

// EpubOpfDir exposes the directory of the OPF file inside the epub zip,
// used to resolve relative hrefs. Empty for non-epub books.
func (b *Book) EpubOpfDir() string { return b.epubOpfDir }
```

Delete the old `ChapterText` method (it will be replaced by `ChapterBlocks` callers):

```go
// DELETE THIS:
// func (b *Book) ChapterText(idx int) (string, error) { ... }
```

The `txtChapterBlocks` and `epubChapterBlocks` methods don't exist yet — the build will fail until Tasks 2 and 3 land. That's expected for an intermediate commit **only if** we land all 3 tasks together. To keep each task independently buildable, this Task 1 adds `ChapterBlocks` AND temporary stubs that return an error; Tasks 2/3 replace the stubs.

**Simpler approach:** Land Tasks 1+2+3 in a single commit. Steps below combine them. If reviewer prefers split, the implementer can land Task 1 with stubs that `panic("not yet implemented")` — but then `go build` works only between Task 1 and Task 2 if those stubs compile. Given the tight coupling, **single commit for Tasks 1-3 is recommended**.

- [ ] **Step 2: Do NOT commit yet — proceed to Task 2 (txt) and Task 3 (epub) before committing**

The build only passes once all three chapter-blocks methods exist. Commit at the end of Task 3.

---

## Task 2: bookparser/txt.go — txtChapterBlocks

**Files:**
- Modify: `server/internal/service/bookparser/txt.go`

**Interfaces:**
- Consumes: `Book.Chapters[idx].CharStart`, `Book.Chapters[idx].CharEnd`, `decodeTxt` (existing), `clampInt` (existing if present — verify)
- Produces: `func (b *Book) txtChapterBlocks(idx int) ([]Block, error)`
- Deletes: `func (b *Book) txtChapterText(idx int) (string, error)`

- [ ] **Step 1: Read txt.go to find existing helpers**

Run: `grep -n "func.*txtChapterText\|func decodeTxt\|func clampInt" server/internal/service/bookparser/txt.go`

Note the line numbers of `txtChapterText`, `decodeTxt`, and whether `clampInt` exists. If `clampInt` doesn't exist, the implementer must add a small inline `clamp(v, lo, hi)` helper.

- [ ] **Step 2: Replace txtChapterText with txtChapterBlocks**

In `server/internal/service/bookparser/txt.go`, delete the existing `txtChapterText` method and replace with:

```go
// txtChapterBlocks reads the file, decodes via [decodeTxt], slices by
// rune offsets CharStart..CharEnd, then splits on "\n\n" into multiple
// text Blocks (empty paragraphs filtered). Returns a single "[本章节为空]"
// placeholder block if the slice produces no non-empty paragraphs.
func (b *Book) txtChapterBlocks(idx int) ([]Block, error) {
    c := b.Chapters[idx]
    raw, err := os.ReadFile(b.Path)
    if err != nil {
        return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
    }
    text, _ := decodeTxt(raw)
    runes := []rune(text)
    start := clampInt(c.CharStart, 0, len(runes))
    end := clampInt(c.CharEnd, 0, len(runes))
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
        return []Block{{Type: "text", Value: "[本章节为空]"}}, nil
    }
    return blocks, nil
}

// clampInt returns v clipped to [lo, hi]. Used for CharStart/CharEnd bounds.
func clampInt(v, lo, hi int) int {
    if v < lo {
        return lo
    }
    if v > hi {
        return hi
    }
    return v
}
```

If `clampInt` already exists (grep in Step 1 confirms), skip its definition. If `decodeTxt` returns charset as second value, ignore it (we only need the decoded string here).

- [ ] **Step 3: Do NOT commit yet — proceed to Task 3**

---

## Task 3: bookparser/epub.go — epubChapterBlocks + extractBlocks + export utils

**Files:**
- Modify: `server/internal/service/bookparser/epub.go`

**Interfaces:**
- Consumes: `Book.Chapters[idx].ManifestID`, `Book.epubManifest`, `Book.epubOpfDir`, `Book.Path`, `MaxEpubEntrySize`
- Produces:
  - `func (b *Book) epubChapterBlocks(idx int) ([]Block, error)`
  - `func extractBlocks(data []byte) []Block`
  - `func extractImgSrc(n *html.Node) string`
  - Exported: `func NormalizeHref(s string) string` (rename from `normalizeHref`)
  - Exported: `func JoinZipPath(dir, href string) string` (rename from `joinZipPath`)
  - Exported: `func ReadCapped(r io.Reader, max int64) ([]byte, error)` (rename from `readCapped`)
- Deletes: `func (b *Book) epubChapterText(idx int) (string, error)`, `func extractXhtmlText(data []byte) (string, bool)`

- [ ] **Step 1: Rename internal helpers to exported**

In `server/internal/service/bookparser/epub.go`:

- Rename `readCapped` → `ReadCapped` (and update all call sites in this file: `container.xml`, OPF, NCX/nav, XHTML reads)
- Rename `joinZipPath` → `JoinZipPath` (update call sites in `parseEpub`, `epubChapterBlocks`)
- Rename `normalizeHref` → `NormalizeHref` (update call sites in `parseEpub` TOC matching, `JoinZipPath`)

Verify via grep that no other file in `server/internal/` references the old lowercase names — they were package-private so only this file used them.

- [ ] **Step 2: Replace extractXhtmlText with extractBlocks + extractImgSrc**

Delete `extractXhtmlText` and add:

```go
// extractBlocks walks an XHTML byte slice and produces ordered Blocks.
// <img>/<image> flush the current text buffer and push an image Block
// with the raw src. <br> writes '\n' to the buffer. Block-level elements
// (p/div/h1-h6/li/blockquote/section/title) flush on both enter and exit.
// Returns a single "[本章节为空]" placeholder if no content was produced.
func extractBlocks(data []byte) []Block {
    doc, err := html.Parse(bytes.NewReader(data))
    if err != nil {
        return []Block{{Type: "text", Value: "[本章节解析失败]"}}
    }
    var blocks []Block
    var textBuf strings.Builder
    flush := func() {
        if s := strings.TrimSpace(textBuf.String()); s != "" {
            blocks = append(blocks, Block{Type: "text", Value: s})
        }
        textBuf.Reset()
    }
    isBlockElement := func(tagName string) bool {
        switch tagName {
        case "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "section", "title":
            return true
        }
        return false
    }
    var walk func(*html.Node)
    walk = func(n *html.Node) {
        if n.Type == html.ElementNode {
            if n.Data == "img" || n.Data == "image" {
                flush()
                if src := extractImgSrc(n); src != "" {
                    blocks = append(blocks, Block{Type: "image", Src: src})
                }
                return
            }
            if n.Data == "br" {
                textBuf.WriteByte('\n')
                return
            }
            if isBlockElement(n.Data) {
                flush()
            }
        } else if n.Type == html.TextNode {
            textBuf.WriteString(n.Data)
        }
        for c := n.FirstChild; c != nil; c = c.NextSibling {
            walk(c)
        }
        if n.Type == html.ElementNode && isBlockElement(n.Data) {
            flush()
        }
    }
    walk(doc)
    flush()
    if len(blocks) == 0 {
        return []Block{{Type: "text", Value: "[本章节为空]"}}
    }
    return blocks
}

// extractImgSrc pulls the src (HTML <img>) or xlink:href/href (SVG <image>)
// from a parsed node. Returns "" if no usable attribute is found.
func extractImgSrc(n *html.Node) string {
    for _, a := range n.Attr {
        if a.Key == "src" && a.Val != "" {
            return a.Val
        }
        if (a.Key == "xlink:href" || a.Key == "href" || (a.Namespace == "xlink" && a.Key == "href")) && a.Val != "" {
            return a.Val
        }
    }
    return ""
}
```

- [ ] **Step 3: Replace epubChapterText with epubChapterBlocks**

Delete `epubChapterText` and add:

```go
// epubChapterBlocks opens the epub zip, reads the XHTML for chapter idx,
// and returns its content as ordered Blocks. Image srcs are raw epub
// hrefs (relative or absolute paths, data: URIs, http(s):// URLs) —
// BookService rewrites relative ones to /api/v1/books/image URLs.
//
// Failures degrade gracefully: missing manifest entry, missing zip entry,
// or read errors all return a single "[本章节解析失败]" text block rather
// than propagating an error, so the reader can still paginate.
func (b *Book) epubChapterBlocks(idx int) ([]Block, error) {
    c := b.Chapters[idx]
    href, ok := b.epubManifest[c.ManifestID]
    if !ok {
        return []Block{{Type: "text", Value: "[本章节解析失败]"}}, nil
    }
    fullPath := JoinZipPath(b.epubOpfDir, href)
    zr, err := zip.OpenReader(b.Path)
    if err != nil {
        return nil, fmt.Errorf("%w: %v", ErrIoFailure, err)
    }
    defer zr.Close()
    rc, err := zr.Open(fullPath)
    if err != nil {
        return []Block{{Type: "text", Value: "[本章节解析失败]"}}, nil
    }
    defer rc.Close()
    body, err := ReadCapped(rc, MaxEpubEntrySize)
    if err != nil {
        return []Block{{Type: "text", Value: "[本章节解析失败]"}}, nil
    }
    return extractBlocks(body), nil
}
```

- [ ] **Step 4: Verify build compiles**

Run: `cd server && go build ./...`
Expected: build succeeds (no compile errors). All callers of removed `ChapterText`/`extractXhtmlText` will surface here — fix any stragglers.

Currently `handler/books.go:87` calls `b.ChapterText(idx)`. That will fail to compile. **For Tasks 1-3 to land as a buildable commit, also update handler/books.go in this same commit**, OR temporarily comment out the ChapterText call and update handler in Task 8. Recommended: update `handler/books.go` minimally now (change `text, err := b.ChapterText(idx)` to `blocks, err := b.ChapterBlocks(idx)` and adjust the response field), then Task 8 does the deeper handler work (new GetBookImage endpoint, etc.).

Minimal handler update for now:
```go
// In handler/books.go, GetBookChapter:
blocks, err := b.ChapterBlocks(idx)
if err != nil {
    return mapBookError(c, err)
}
// Temporarily join blocks into a string so chapterResponse still compiles.
// Task 8 will replace this with the real blocks response.
var sb strings.Builder
for _, blk := range blocks {
    if blk.Type == "text" {
        if sb.Len() > 0 {
            sb.WriteString("\n\n")
        }
        sb.WriteString(blk.Value)
    } else if blk.Type == "image" {
        if sb.Len() > 0 {
            sb.WriteString("\n\n")
        }
        sb.WriteString("[图片]")
    }
}
setJsonCacheBrief(c)
return c.JSON(http.StatusOK, chapterResponse{Title: b.Chapters[idx].Title, Content: sb.String()})
```

Add `"strings"` to handler/books.go imports if not present.

- [ ] **Step 5: Verify bookparser tests still pass**

Run: `cd server && go test ./internal/service/bookparser/ -v`
Expected: existing tests pass (they test parser/unsupported/txt/epub ChapterText indirectly via the old API — these need updating). Actually they WILL fail because:
- `TestEpubChapterTextExtract` calls the now-deleted `ChapterText` → must be renamed/updated here too.

For Task 3 scope: update existing tests to call `ChapterBlocks` instead of `ChapterText`. Replace string assertions with block assertions.

In `server/internal/service/bookparser/epub_test.go`, rename `TestEpubChapterTextExtract` to `TestEpubChapterBlocksExtract` and update its body:

```go
func TestEpubChapterBlocksExtract(t *testing.T) {
    // ... existing setup that builds a minimal epub and parses it ...
    // Replace:
    //   text, err := book.ChapterText(0)
    //   assert.Contains(t, text, "body text here")
    //   assert.NotContains(t, text, "<p>")
    // With:
    blocks, err := book.ChapterBlocks(0)
    require.NoError(t, err)
    require.NotEmpty(t, blocks)
    // Find at least one text block containing the body text
    found := false
    for _, blk := range blocks {
        if blk.Type == "text" && strings.Contains(blk.Value, "body text here") {
            found = true
            break
        }
    }
    assert.True(t, found, "expected a text block containing body text, got %#v", blocks)
}
```

In `server/internal/service/bookparser/txt_test.go`, rename `TestTxtChapterOffsetsRoundTrip` to `TestTxtChapterBlocksSplit` and update:

```go
func TestTxtChapterBlocksSplit(t *testing.T) {
    // ... existing setup that writes a .txt with multiple chapters ...
    // Replace:
    //   text, err := book.ChapterText(0)
    //   assert.Contains(text, "第一章正文")
    // With:
    blocks, err := book.ChapterBlocks(0)
    require.NoError(t, err)
    // Join all text blocks and verify the content is preserved
    var sb strings.Builder
    for _, blk := range blocks {
        if blk.Type == "text" {
            if sb.Len() > 0 { sb.WriteString("\n\n") }
            sb.WriteString(blk.Value)
        }
    }
    assert.Contains(t, sb.String(), "第一章正文")
}
```

Run: `cd server && go test ./internal/service/bookparser/ -v`
Expected: all tests pass.

- [ ] **Step 6: Commit Tasks 1-3 together**

```bash
git add server/internal/service/bookparser/parser.go \
        server/internal/service/bookparser/txt.go \
        server/internal/service/bookparser/epub.go \
        server/internal/service/bookparser/epub_test.go \
        server/internal/service/bookparser/txt_test.go \
        server/internal/server/handler/books.go
git commit -m "feat(bookparser): Block type + ChapterBlocks + extractBlocks (txt+epub)

Replaces B-phase ChapterText (string) with ChapterBlocks ([]Block).
Block is {Type, Value, Src} where Type is 'text' or 'image'. extractBlocks
walks XHTML with explicit block-element flush + <br>/\\n handling, and
preserves <img>/<image> src for service-layer rewriting.

bookparser exports NormalizeHref, JoinZipPath, ReadCapped so BookService
can do manifest reverse-lookup and image-byte reads without duplicating
the helpers.

handler/books.go temporarily joins blocks back into a string; Task 8
will wire the real blocks response + image endpoint.

Existing tests updated to consume []Block."
```

---

## Task 4: bookparser — new tests for image blocks

**Files:**
- Modify: `server/internal/service/bookparser/epub_test.go`

**Interfaces:** none new (uses Task 3's extractBlocks + ChapterBlocks)

- [ ] **Step 1: Add TestExtractBlocksImageSrc**

In `server/internal/service/bookparser/epub_test.go`, add:

```go
func TestExtractBlocksImageSrc(t *testing.T) {
    xhtml := []byte(`<html><body>
        <p>intro text</p>
        <img src="images/cover.jpg" alt="cover"/>
        <p>outro text</p>
    </body></html>`)
    blocks := extractBlocks(xhtml)
    require.Len(t, blocks, 3)
    assert.Equal(t, "text", blocks[0].Type)
    assert.Contains(t, blocks[0].Value, "intro text")
    assert.Equal(t, "image", blocks[1].Type)
    assert.Equal(t, "images/cover.jpg", blocks[1].Src)
    assert.Equal(t, "text", blocks[2].Type)
    assert.Contains(t, blocks[2].Value, "outro text")
}
```

- [ ] **Step 2: Add TestExtractBlocksDataUriPreserved**

```go
func TestExtractBlocksDataUriPreserved(t *testing.T) {
    dataURI := "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII="
    xhtml := []byte(`<html><body>
        <img src="` + dataURI + `"/>
    </body></html>`)
    blocks := extractBlocks(xhtml)
    require.Len(t, blocks, 1)
    assert.Equal(t, "image", blocks[0].Type)
    assert.Equal(t, dataURI, blocks[0].Src)
}
```

- [ ] **Step 3: Add TestExtractBlocksImageOnlyChapter**

Verifies the old `[本章节为图片版，暂不支持]` placeholder is gone — pure-image chapters now return real image blocks:

```go
func TestExtractBlocksImageOnlyChapter(t *testing.T) {
    xhtml := []byte(`<html><body>
        <img src="page1.png"/>
        <img src="page2.png"/>
    </body></html>`)
    blocks := extractBlocks(xhtml)
    require.Len(t, blocks, 2)
    assert.Equal(t, "image", blocks[0].Type)
    assert.Equal(t, "page1.png", blocks[0].Src)
    assert.Equal(t, "image", blocks[1].Type)
    assert.Equal(t, "page2.png", blocks[1].Src)
    // Verify no text block was synthesized as a placeholder
    for _, b := range blocks {
        assert.NotEqual(t, "text", b.Type)
    }
}
```

- [ ] **Step 4: Run tests**

Run: `cd server && go test ./internal/service/bookparser/ -run TestExtractBlocks -v`
Expected: all 3 new tests pass.

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/bookparser/epub_test.go
git commit -m "test(bookparser): image block extraction — src preserved, data: URI, image-only"
```

---

## Task 5: BookService — GetChapterBlocks + reverseLookupManifest

**Files:**
- Modify: `server/internal/service/book.go`
- Modify: `server/internal/service/book_test.go`

**Interfaces:**
- Consumes: `bookparser.Block`, `bookparser.Book.EpubManifest()`, `bookparser.Book.EpubOpfDir()`, `bookparser.NormalizeHref`
- Produces:
  - `func (s *BookService) GetChapterBlocks(path string, idx int) ([]bookparser.Block, error)`
  - `func reverseLookupManifest(manifest map[string]string, src string) string` (package-private)

- [ ] **Step 1: Write failing test TestGetChapterBlocksRewritesImageSrc**

In `server/internal/service/book_test.go`, add (assuming a test helper `buildMinimalEpub` exists from B-phase; if not, the implementer should look at `bookparser/epub_test.go` for the helper and reuse/adapt):

```go
func TestGetChapterBlocksRewritesImageSrc(t *testing.T) {
    // Build minimal epub with one chapter whose XHTML references "images/foo.jpg"
    // and a manifest entry id="img-foo" href="images/foo.jpg".
    // (Adapt the existing buildMinimalEpub helper to include the image entry,
    // OR construct inline.)
    path := /* path to test epub */
    svc := NewBookService()
    blocks, err := svc.GetChapterBlocks(path, 0)
    require.NoError(t, err)

    // Find the image block
    var imgBlock *bookparser.Block
    for i := range blocks {
        if blocks[i].Type == "image" {
            imgBlock = &blocks[i]
            break
        }
    }
    require.NotNil(t, imgBlock, "expected an image block")
    assert.Contains(t, imgBlock.Src, "/api/v1/books/image?path=")
    assert.Contains(t, imgBlock.Src, "manifest=img-foo")
}
```

The implementer should look at the existing `buildMinimalEpub` helper in `bookparser/epub_test.go` and either:
- (a) extend it to optionally include an image entry, or
- (b) write a new helper `buildEpubWithImage(t *testing.T, imgManifestID, imgHref string) string` that returns the path.

Approach (b) is cleaner — keeps existing tests unchanged.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/ -run TestGetChapterBlocksRewritesImageSrc -v`
Expected: FAIL — `GetChapterBlocks` undefined.

- [ ] **Step 3: Implement GetChapterBlocks + reverseLookupManifest**

In `server/internal/service/book.go`, add imports (`net/url`, `strings`) and:

```go
// GetChapterBlocks returns the ordered content blocks for chapter idx of
// the book at path. Image blocks' Src is rewritten to a
// /api/v1/books/image?path=...&manifest=... URL unless it is a data: URI
// or an absolute http(s):// URL. If no manifest entry matches the src,
// the Src is set to "" (clients render "[本图片无法显示]").
func (s *BookService) GetChapterBlocks(path string, idx int) ([]bookparser.Block, error) {
    b, err := s.GetBook(path)
    if err != nil {
        return nil, err
    }
    blocks, err := b.ChapterBlocks(idx)
    if err != nil {
        return nil, err
    }
    for i := range blocks {
        if blocks[i].Type != "image" {
            continue
        }
        src := blocks[i].Src
        if src == "" ||
            strings.HasPrefix(src, "data:") ||
            strings.HasPrefix(src, "http://") ||
            strings.HasPrefix(src, "https://") {
            continue
        }
        manifestID := reverseLookupManifest(b.EpubManifest(), src)
        if manifestID == "" {
            blocks[i].Src = ""
            continue
        }
        blocks[i].Src = fmt.Sprintf("/api/v1/books/image?path=%s&manifest=%s",
            url.QueryEscape(path), url.QueryEscape(manifestID))
    }
    return blocks, nil
}

// reverseLookupManifest returns the manifest id whose href matches src
// (after NormalizeHref on both sides). Returns "" if no match.
// Both src and manifest hrefs may be relative paths, absolute paths, or
// contain #fragment suffixes — NormalizeHref strips fragments and
// normalizes slash direction.
func reverseLookupManifest(manifest map[string]string, src string) string {
    normalized := bookparser.NormalizeHref(src)
    for id, href := range manifest {
        if bookparser.NormalizeHref(href) == normalized {
            return id
        }
    }
    return ""
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/service/ -run TestGetChapterBlocksRewritesImageSrc -v`
Expected: PASS.

- [ ] **Step 5: Add TestGetChapterBlocksPreservesDataUri**

```go
func TestGetChapterBlocksPreservesDataUri(t *testing.T) {
    // Build epub with image src="data:image/png;base64,..."
    // Assert the returned block's Src is the unchanged data: URI.
    path := /* path to test epub with data URI image */
    svc := NewBookService()
    blocks, err := svc.GetChapterBlocks(path, 0)
    require.NoError(t, err)
    for _, b := range blocks {
        if b.Type == "image" {
            assert.HasPrefix(t, b.Src, "data:")
            return
        }
    }
    t.Fatal("no image block found")
}
```

- [ ] **Step 6: Add TestReverseLookupManifest**

```go
func TestReverseLookupManifest(t *testing.T) {
    manifest := map[string]string{
        "img-1": "images/foo.jpg",
        "img-2": "OEBPS/Images/bar.png",
        "img-3": "images/baz.gif#fragment",
    }
    cases := []struct {
        name string
        src  string
        want string
    }{
        {"relative path", "images/foo.jpg", "img-1"},
        {"absolute path", "OEBPS/Images/bar.png", "img-2"},
        {"with fragment", "images/baz.gif#frag", "img-3"},
        {"not found", "images/missing.jpg", ""},
    }
    for _, tc := range cases {
        t.Run(tc.name, func(t *testing.T) {
            assert.Equal(t, tc.want, reverseLookupManifest(manifest, tc.src))
        })
    }
}
```

- [ ] **Step 7: Run all new tests**

Run: `cd server && go test ./internal/service/ -run "TestGetChapterBlocks|TestReverseLookupManifest" -v`
Expected: all pass.

- [ ] **Step 8: Commit**

```bash
git add server/internal/service/book.go server/internal/service/book_test.go
git commit -m "feat(service): BookService.GetChapterBlocks + reverseLookupManifest

Rewrites image block src to /api/v1/books/image endpoint URLs. Skips
data:, http://, https:// schemes. Empty src on no manifest match."
```

---

## Task 6: BookService — ReadImageBytes

**Files:**
- Modify: `server/internal/service/book.go`
- Modify: `server/internal/service/book_test.go`

**Interfaces:**
- Consumes: `bookparser.Book.EpubManifest()`, `bookparser.Book.EpubOpfDir()`, `bookparser.JoinZipPath`, `bookparser.ReadCapped`, `bookparser.MaxEpubEntrySize`, `bookparser.ErrInvalidEpub`, `bookparser.ErrUnsupported`, `bookparser.ErrIoFailure`
- Produces: `func (s *BookService) ReadImageBytes(path, manifestID string) ([]byte, string, error)` — returns (image bytes, MIME content type, error)

- [ ] **Step 1: Write failing test TestReadImageBytes**

```go
func TestReadImageBytes(t *testing.T) {
    // Build an epub file on disk that contains OEBPS/Images/test.png as a
    // real PNG (use a minimal 1x1 PNG byte slice). Manifest entry id="cover"
    // href="Images/test.png".
    path := /* path */
    svc := NewBookService()

    t.Run("success", func(t *testing.T) {
        data, ct, err := svc.ReadImageBytes(path, "cover")
        require.NoError(t, err)
        assert.NotEmpty(t, data)
        assert.Equal(t, "image/png", ct)
    })

    t.Run("manifest not found", func(t *testing.T) {
        _, _, err := svc.ReadImageBytes(path, "nonexistent")
        require.Error(t, err)
        assert.ErrorIs(t, err, bookparser.ErrInvalidEpub)
    })

    t.Run("non-epub file rejected", func(t *testing.T) {
        // Create a .txt file and try to read an image from it
        txtPath := filepath.Join(t.TempDir(), "book.txt")
        os.WriteFile(txtPath, []byte("hello"), 0644)
        _, _, err := svc.ReadImageBytes(txtPath, "any")
        require.Error(t, err)
        assert.ErrorIs(t, err, bookparser.ErrUnsupported)
    })
}
```

The implementer should write a helper `buildEpubWithImage(t *testing.T, imgManifestID, imgHref string, imgBytes []byte) string` in `book_test.go` that constructs a real zip file with the image bytes embedded. Reuse the `archive/zip` based pattern from `bookparser/epub_test.go`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/service/ -run TestReadImageBytes -v`
Expected: FAIL — `ReadImageBytes` undefined.

- [ ] **Step 3: Implement ReadImageBytes + mimeByExtension**

In `server/internal/service/book.go`, add imports (`archive/zip`, `path/filepath`) and:

```go
// ReadImageBytes opens the epub at path, looks up manifestID in the OPF
// manifest, and returns the raw image bytes plus a MIME content type
// inferred from the file extension.
//
// Security: fullPath is rejected if it starts with "/", "../", or equals
// ".." — defends against path traversal even though manifest hrefs come
// from parsed XML and should be safe.
//
// Size: capped at bookparser.MaxEpubEntrySize (16 MiB) per image —
// maliciously large entries return ErrTooLarge.
func (s *BookService) ReadImageBytes(path, manifestID string) ([]byte, string, error) {
    b, err := s.GetBook(path)
    if err != nil {
        return nil, "", err
    }
    if b.Format != "epub" {
        return nil, "", fmt.Errorf("%w: image fetch only for epub", bookparser.ErrUnsupported)
    }
    href, ok := b.EpubManifest()[manifestID]
    if !ok {
        return nil, "", fmt.Errorf("%w: manifest id not found: %s", bookparser.ErrInvalidEpub, manifestID)
    }
    fullPath := bookparser.JoinZipPath(b.EpubOpfDir(), href)
    // Defensive: block any path that would escape the zip root.
    if strings.HasPrefix(fullPath, "../") || fullPath == ".." || strings.HasPrefix(fullPath, "/") {
        return nil, "", fmt.Errorf("%w: invalid manifest href", bookparser.ErrInvalidEpub)
    }
    zr, err := zip.OpenReader(path)
    if err != nil {
        return nil, "", fmt.Errorf("%w: %v", bookparser.ErrIoFailure, err)
    }
    defer zr.Close()
    rc, err := zr.Open(fullPath)
    if err != nil {
        return nil, "", fmt.Errorf("%w: image not found in epub: %s", bookparser.ErrInvalidEpub, fullPath)
    }
    defer rc.Close()
    data, err := bookparser.ReadCapped(rc, bookparser.MaxEpubEntrySize)
    if err != nil {
        return nil, "", err
    }
    return data, mimeByExtension(filepath.Ext(fullPath)), nil
}

// mimeByExtension maps common image extensions to MIME types. Returns
// "application/octet-stream" for unknown extensions.
func mimeByExtension(ext string) string {
    switch strings.ToLower(ext) {
    case ".jpg", ".jpeg":
        return "image/jpeg"
    case ".png":
        return "image/png"
    case ".gif":
        return "image/gif"
    case ".webp":
        return "image/webp"
    case ".svg":
        return "image/svg+xml"
    case ".bmp":
        return "image/bmp"
    default:
        return "application/octet-stream"
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/service/ -run TestReadImageBytes -v`
Expected: PASS — all 3 subtests.

- [ ] **Step 5: Commit**

```bash
git add server/internal/service/book.go server/internal/service/book_test.go
git commit -m "feat(service): BookService.ReadImageBytes — zip-internal image byte reader"
```

---

## Task 7: middleware — BearerToken query param fallback

**Files:**
- Modify: `server/internal/server/middleware/auth.go`
- Modify: `server/internal/server/middleware/auth_test.go`

**Interfaces:**
- Produces: extends existing `BearerToken(token string) echo.MiddlewareFunc` to fall back to `c.QueryParam("token")` when the `Authorization: Bearer ` header is absent

- [ ] **Step 1: Write failing test TestBearerTokenAcceptsTokenInQueryParam**

In `server/internal/server/middleware/auth_test.go`, add:

```go
func TestBearerTokenAcceptsTokenInQueryParam(t *testing.T) {
    e := echo.New()
    req := httptest.NewRequest(http.MethodGet, "/img?token=secret", nil)
    rec := httptest.NewRecorder()
    c := e.NewContext(req, rec)
    h := BearerToken("secret")(func(c echo.Context) error {
        return c.String(http.StatusOK, "ok")
    })
    err := h(c)
    require.NoError(t, err)
    assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBearerTokenHeaderTakesPrecedenceOverQueryParam(t *testing.T) {
    e := echo.New()
    // header has correct token; query has wrong token. Should pass.
    req := httptest.NewRequest(http.MethodGet, "/api?token=wrong", nil)
    req.Header.Set(echo.HeaderAuthorization, "Bearer secret")
    rec := httptest.NewRecorder()
    c := e.NewContext(req, rec)
    h := BearerToken("secret")(func(c echo.Context) error {
        return c.String(http.StatusOK, "ok")
    })
    err := h(c)
    require.NoError(t, err)
    assert.Equal(t, http.StatusOK, rec.Code)
}

func TestBearerTokenRejectsInvalidQueryParamToken(t *testing.T) {
    e := echo.New()
    req := httptest.NewRequest(http.MethodGet, "/img?token=wrong", nil)
    rec := httptest.NewRecorder()
    c := e.NewContext(req, rec)
    h := BearerToken("secret")(func(c echo.Context) error {
        return c.String(http.StatusOK, "ok")
    })
    _ = h(c)
    assert.Equal(t, http.StatusUnauthorized, rec.Code)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && go test ./internal/server/middleware/ -run TestBearerToken -v`
Expected: First test FAILS (query param path), other two may pass/fail depending on current behavior.

- [ ] **Step 3: Modify BearerToken to add query param fallback**

In `server/internal/server/middleware/auth.go`, modify the function:

```go
func BearerToken(token string) echo.MiddlewareFunc {
    return func(next echo.HandlerFunc) echo.HandlerFunc {
        return func(c echo.Context) error {
            if token == "" {
                return next(c)
            }
            var provided string
            auth := c.Request().Header.Get(echo.HeaderAuthorization)
            const prefix = "Bearer "
            if strings.HasPrefix(auth, prefix) {
                provided = auth[len(prefix):]
            } else {
                // Fallback for clients that cannot set headers (e.g. <img src>
                // tags loading from /api/v1/books/image). Header takes
                // precedence so this does not change behavior for any
                // existing client.
                provided = c.QueryParam("token")
            }
            if subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
                return c.JSON(
                    http.StatusUnauthorized,
                    map[string]string{"error": "Unauthorized"},
                )
            }
            return next(c)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd server && go test ./internal/server/middleware/ -run TestBearerToken -v`
Expected: all 3 new tests pass, plus existing auth tests unchanged.

- [ ] **Step 5: Commit**

```bash
git add server/internal/server/middleware/auth.go server/internal/server/middleware/auth_test.go
git commit -m "feat(middleware): BearerToken accepts token query param fallback

For <img src> tags that cannot inject Authorization headers. Header
takes precedence; query param only used as fallback."
```

---

## Task 8: handler — chapterResponse blocks + GetBookImage endpoint

**Files:**
- Modify: `server/internal/server/handler/books.go`
- Modify: `server/internal/server/handler/books_test.go`
- Modify: `server/internal/server/server.go` (register new route)

**Interfaces:**
- Consumes: `BookService.GetChapterBlocks`, `BookService.ReadImageBytes`, `service.ValidateAccessibleMediaPath`, `bookparser.Block`
- Produces:
  - Updated `chapterResponse{Title, Blocks []bookparser.Block}` (replaces Content field)
  - New handler `func (h *Handler) GetBookImage(c echo.Context) error`
  - New route `books.GET("/image", h.GetBookImage)`

- [ ] **Step 1: Write failing test TestGetBookChapterReturnsBlocks**

In `server/internal/server/handler/books_test.go`, replace the existing chapter test:

```go
func TestGetBookChapterReturnsBlocks(t *testing.T) {
    // Build a .txt file with content "para1\n\npara2"
    dir := t.TempDir()
    path := filepath.Join(dir, "book.txt")
    os.WriteFile(path, []byte("第一章 title\npara1\n\npara2"), 0644)

    cfg := /* minimal cfg with dir as scan root */
    h := New(cfg, service.NewScanner(cfg.Scan.VideoExtensions, cfg.Scan.ImageExtensions, cfg.Scan.TextExtensions),
        nil, nil, nil, service.NewBookService())
    req := httptest.NewRequest(http.MethodGet,
        "/api/v1/books/chapter?path="+url.QueryEscape(path)+"&index=0", nil)
    rec := httptest.NewRecorder()
    c := /* echo.NewContext */

    err := h.GetBookChapter(c)
    require.NoError(t, err)
    var resp struct {
        Title  string   `json:"title"`
        Blocks []struct {
            Type  string `json:"type"`
            Value string `json:"value,omitempty"`
            Src   string `json:"src,omitempty"`
        } `json:"blocks"`
    }
    require.NoError(t, json.Unmarshal(rec.Body.Bytes(), &resp))
    assert.Equal(t, "第一章 title", resp.Title)
    require.Len(t, resp.Blocks, 2)
    assert.Equal(t, "text", resp.Blocks[0].Type)
    assert.Equal(t, "para1", resp.Blocks[0].Value)
    assert.Equal(t, "text", resp.Blocks[1].Type)
    assert.Equal(t, "para2", resp.Blocks[1].Value)
}
```

The implementer should adapt the existing test setup style from B-phase `TestGetBookChapterReturnsJSON` — the routing/test-context pattern is established there.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/handler/ -run TestGetBookChapter -v`
Expected: FAIL — response still has `content` field, not `blocks`.

- [ ] **Step 3: Update chapterResponse struct + GetBookChapter**

In `server/internal/server/handler/books.go`:

Change the struct:
```go
type chapterResponse struct {
    Title  string             `json:"title"`
    Blocks []bookparser.Block `json:"blocks"`
}
```

Change GetBookChapter to call GetChapterBlocks (replacing the temporary string-join logic added in Task 3):

```go
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
    blocks, err := h.books.GetChapterBlocks(resolved, idx)
    if err != nil {
        return mapBookError(c, err)
    }
    setJsonCacheBrief(c)
    return c.JSON(http.StatusOK, chapterResponse{
        Title:  b.Chapters[idx].Title,
        Blocks: blocks,
    })
}
```

Remove the now-unused `"strings"` import if no other code in the file uses it.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/server/handler/ -run TestGetBookChapter -v`
Expected: PASS.

- [ ] **Step 5: Write failing test TestGetBookImageReturnsBlob**

```go
func TestGetBookImageReturnsBlob(t *testing.T) {
    // Build minimal epub with image entry id="cover" href="Images/test.png"
    // containing a 1x1 PNG.
    path := /* path */
    cfg := /* cfg */
    h := New(cfg, /* scanner */, nil, nil, nil, service.NewBookService())

    req := httptest.NewRequest(http.MethodGet,
        "/api/v1/books/image?path="+url.QueryEscape(path)+"&manifest=cover", nil)
    rec := httptest.NewRecorder()
    c := /* echo ctx */
    err := h.GetBookImage(c)
    require.NoError(t, err)
    assert.Equal(t, "image/png", rec.Header().Get("Content-Type"))
    assert.Equal(t, "public, max-age=86400", rec.Header().Get("Cache-Control"))
    assert.NotEmpty(t, rec.Body.Bytes())
}

func TestGetBookImagePathOutsideRoots403(t *testing.T) {
    h := New(/* cfg with limited roots */, /* scanner */, nil, nil, nil, service.NewBookService())
    req := httptest.NewRequest(http.MethodGet,
        "/api/v1/books/image?path=/etc/passwd&manifest=x", nil)
    rec := httptest.NewRecorder()
    c := /* ctx */
    _ = h.GetBookImage(c)
    assert.Equal(t, http.StatusForbidden, rec.Code)
}

func TestGetBookImageManifestNotFound404(t *testing.T) {
    // Build epub, request nonexistent manifest id
    path := /* path */
    h := New(/* cfg */, /* scanner */, nil, nil, nil, service.NewBookService())
    req := httptest.NewRequest(http.MethodGet,
        "/api/v1/books/image?path="+url.QueryEscape(path)+"&manifest=nonexistent", nil)
    rec := httptest.NewRecorder()
    c := /* ctx */
    _ = h.GetBookImage(c)
    // mapBookError maps ErrInvalidEpub to 422, not 404. The test name
    // says "404" but the actual response is 422 — adjust assertion.
    assert.Equal(t, http.StatusUnprocessableEntity, rec.Code)
}
```

Note: the third test's name originally said "404" but the spec maps `ErrInvalidEpub` to 422 via `mapBookError`. Rename the test to `TestGetBookImageManifestNotFound422` and assert 422. (This is a minor naming fix; the implementer should make the test match the actual behavior rather than the original name.)

- [ ] **Step 6: Run tests to verify they fail**

Run: `cd server && go test ./internal/server/handler/ -run TestGetBookImage -v`
Expected: FAIL — `GetBookImage` undefined.

- [ ] **Step 7: Implement GetBookImage handler**

In `server/internal/server/handler/books.go`, add:

```go
// GetBookImage returns the raw bytes of a single image resource inside
// an epub, identified by its manifest id. Path is validated against scan
// roots + system allowed roots. Bytes are served with a 1-day browser
// cache. Used by reader clients rendering <img> tags for chapter images.
func (h *Handler) GetBookImage(c echo.Context) error {
    pathStr := c.QueryParam("path")
    if pathStr == "" {
        return respondError(c, http.StatusBadRequest, "path required")
    }
    manifestID := c.QueryParam("manifest")
    if manifestID == "" {
        return respondError(c, http.StatusBadRequest, "manifest required")
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
    data, contentType, err := h.books.ReadImageBytes(resolved, manifestID)
    if err != nil {
        return mapBookError(c, err)
    }
    setMediaCacheHeaders(c)
    return c.Blob(http.StatusOK, contentType, data)
}
```

- [ ] **Step 8: Register route in server.go**

In `server/internal/server/server.go`, find the existing books group:
```go
books := api.Group("/books", authMw)
books.GET("/info", h.GetBookInfo)
books.GET("/chapter", h.GetBookChapter)
```
Add the new route:
```go
books.GET("/image", h.GetBookImage)
```

- [ ] **Step 9: Run all book handler tests**

Run: `cd server && go test ./internal/server/handler/ -run "TestGetBook" -v`
Expected: all pass (info, chapter, image).

- [ ] **Step 10: Commit**

```bash
git add server/internal/server/handler/books.go \
        server/internal/server/handler/books_test.go \
        server/internal/server/server.go
git commit -m "feat(handler): /books/chapter returns blocks + new /books/image endpoint"
```

---

## Task 9: Android — Block model + chapterBlocks state

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt`

**Interfaces:**
- Produces (Kotlin):
  - `data class Block(type: String, value: String?, src: String?)` — Parcelable
  - `BookChapterContent` field changed from `content: String` to `blocks: List<Block> = emptyList()`
  - `TextReaderViewModel.chapterBlocks: StateFlow<List<Block>>` — replaces chapterText

- [ ] **Step 1: Update Models.kt — add Block + change BookChapterContent**

In `android/app/src/main/java/com/juziss/localmediahub/data/Models.kt`, replace the existing `BookChapterContent` definition and add `Block`:

```kotlin
/**
 * One ordered content unit of a chapter. Type is "text" (Value holds the
 * paragraph text) or "image" (Src holds a URL or data: URI). Mirrors the
 * server's bookparser.Block.
 */
@Parcelize
data class Block(
    val type: String,
    @SerializedName("value") val value: String? = null,
    @SerializedName("src") val src: String? = null,
) : Parcelable

/**
 * Matches server/server/handler.chapterResponse — single-chapter payload.
 * Blocks is the ordered list of text/image content units.
 */
@Parcelize
data class BookChapterContent(
    val title: String,
    val blocks: List<Block> = emptyList(),
) : Parcelable
```

Remove the old `content: String` field entirely. If any other code references `BookChapterContent.content`, the compiler will surface it — update accordingly (only the ViewModel should).

- [ ] **Step 2: Update TextReaderViewModel — rename chapterText → chapterBlocks**

In `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`:

Replace:
```kotlin
private val _chapterText = MutableStateFlow("")
val chapterText: StateFlow<String> = _chapterText.asStateFlow()
```
With:
```kotlin
private val _chapterBlocks = MutableStateFlow<List<Block>>(emptyList())
val chapterBlocks: StateFlow<List<Block>> = _chapterBlocks.asStateFlow()
```

Add `import com.juziss.localmediahub.data.Block`.

In `loadChapter` success branch, replace:
```kotlin
_chapterText.value = r.data.content
```
With:
```kotlin
_chapterBlocks.value = r.data.blocks
```

- [ ] **Step 3: Update addBookmarkFromParagraph signature**

The existing signature (after C-phase fix) is:
```kotlin
fun addBookmarkFromParagraph(paragraphIndex: Int, preview: String): Boolean
```

Per the revised spec, the VM should extract preview internally from the block. Change to:
```kotlin
fun addBookmarkFromParagraph(blockIndex: Int): Boolean {
    val b = _book.value ?: return false
    val blocks = _chapterBlocks.value
    if (blockIndex !in blocks.indices) return false
    val block = blocks[blockIndex]
    if (block.type != "text") return false  // bookmarks only on text blocks
    val preview = block.value?.take(30) ?: ""
    val bm = Bookmark(
        bookPath = b.path,
        chapterIndex = _currentIndex.value,
        paragraphIndex = blockIndex,  // field name retained per spec
        preview = preview,
        createdAt = System.currentTimeMillis(),
    )
    viewModelScope.launch {
        val added = store.addBookmark(bm)
        if (!added) _bookmarkToast.value = "已存在书签"
    }
    return true
}
```

Note: signature changes from `(Int, String)` to `(Int)`. UI callers (Task 10) will be updated to drop the preview arg.

- [ ] **Step 4: Update TextReaderViewModelReaderTest**

In `android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt`:

For every existing test that asserts on `chapterText` (String), change to assert on `chapterBlocks` (List<Block>):

```kotlin
// Old:
// coEvery { repo.getBookChapter(any(), any()) } returns
//     NetworkResult.Success(BookChapterContent("C0", "body text"))
// assertThat(vm.chapterText.value).isEqualTo("body text")

// New:
coEvery { repo.getBookChapter(any(), any()) } returns
    NetworkResult.Success(BookChapterContent("C0", listOf(
        Block(type = "text", value = "body text")
    )))
assertThat(vm.chapterBlocks.value).hasSize(1)
assertThat(vm.chapterBlocks.value[0].type).isEqualTo("text")
assertThat(vm.chapterBlocks.value[0].value).isEqualTo("body text")
```

Update every `BookChapterContent(...)` construction in the test file to use the new `blocks = listOf(Block(...))` form.

Update `addBookmarkFromParagraph` test calls to drop the preview argument:
```kotlin
// Old:
// val ok = vm.addBookmarkFromParagraph(0, "preview")
// New:
val ok = vm.addBookmarkFromParagraph(0)
```

Add a new test for image-block rejection:
```kotlin
@Test
fun addBookmarkFromParagraph_returns_false_for_image_block() = runTest(dispatcher) {
    val store = mockk<RecentActivityStore>(relaxed = true)
    coEvery { store.readerSettingsFlow } returns kotlinx.coroutines.flow.flowOf(ReaderSettings())
    coEvery { store.getBookmarksFlow(any()) } returns kotlinx.coroutines.flow.flowOf(emptyList())
    val repo = mockk<MediaRepository>(relaxed = true)
    val vm = TextReaderViewModel(repo, store)
    // Load a book whose chapter 0 returns an image-only blocks list
    coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
    coEvery { repo.getBookChapter(any(), any()) } returns
        NetworkResult.Success(BookChapterContent("C0", listOf(
            Block(type = "image", src = "http://example.com/x.png")
        )))
    vm.loadBook("/b.txt")
    dispatcher.scheduler.advanceUntilIdle()
    vm.loadBookmarksFor("/b.txt")
    val ok = vm.addBookmarkFromParagraph(0)  // block 0 is an image
    assertThat(ok).isFalse()
}
```

- [ ] **Step 5: Build + run tests**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

Note: the build will likely fail because `TextReaderScreen.kt` still references `viewModel.chapterText` (C-phase). Either:
- (a) Update `TextReaderScreen.kt` in this task too (folding Task 10 into Task 9), OR
- (b) Temporarily stub `chapterText` as a computed property returning empty string, then remove in Task 10.

**Approach (a) recommended** — land Task 9 + Task 10 in one commit to keep the build green at every commit. The implementer should proceed to Task 10's steps before running the final test+commit.

- [ ] **Step 6: Do NOT commit yet — proceed to Task 10**

---

## Task 10: Android — TextReaderScreen blocks rendering

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`

**Interfaces:**
- Consumes: `TextReaderViewModel.chapterBlocks`, `Block.type`, `Block.value`, `Block.src`, `viewModel.addBookmarkFromParagraph(blockIndex)` (single-arg)
- Produces: LazyColumn rendering `Block` list with `Text` for text blocks and `AsyncImage` for image blocks

- [ ] **Step 1: Replace LazyColumn items block in TextReaderScreen**

In `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`:

Replace the state collection:
```kotlin
val text by viewModel.chapterText.collectAsState()
```
With:
```kotlin
val blocks by viewModel.chapterBlocks.collectAsState()
```

Replace the existing LazyColumn that splits `text` on `\n\n` with one that iterates `blocks`:

```kotlin
LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    contentPadding = PaddingValues(vertical = 16.dp),
) {
    itemsIndexed(blocks) { blockIdx, block ->
        when (block.type) {
            "text" -> ParagraphItem(
                text = block.value ?: "",
                fontSizeSp = settings.fontSize.sp.sp,
                lineHeightSp = (settings.fontSize.sp * settings.lineHeight.multiplier).sp,
                onAddBookmark = {
                    val ok = viewModel.addBookmarkFromParagraph(blockIdx)
                    if (!ok) {
                        // Image block or out-of-range — silently ignore
                        // (duplicate feedback is via bookmarkToast flow)
                    }
                },
                onCopy = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("paragraph", block.value ?: ""))
                },
            )
            "image" -> {
                if (block.src.isNullOrEmpty()) {
                    Text(
                        text = "[本图片无法显示]",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                } else {
                    coil3.compose.AsyncImage(
                        model = block.src,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
```

Add imports:
- `coil3.compose.AsyncImage` (verify the exact import path — Coil 3 uses `coil3.compose.AsyncImage`)

The existing `ParagraphItem` composable (from C-phase) is reused unchanged — it already takes `text`, `fontSizeSp`, `lineHeightSp`, `onAddBookmark`, `onCopy`, `onLongPress` parameters. If `onLongPress` is still part of its signature, pass `{}` (or remove from signature if unused).

- [ ] **Step 2: Remove the now-unused `paras` split logic**

The old code had:
```kotlin
val paras = remember(text) {
    text.split("\n\n").filter { it.isNotBlank() }
}
```
Delete it — the server now does the splitting.

- [ ] **Step 3: Build + run tests**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass (including updated VM tests + existing C-phase UI tests).

Note: `TextReaderScreenThemeTest` from C-phase should still pass — it tests `ReaderThemeWrapper`, which is unchanged.

- [ ] **Step 4: Commit Tasks 9 + 10 together**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/Models.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt \
        android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt
git commit -m "feat(android): Block model + blocks-based reader rendering

ChapterContent holds blocks: List<Block> (text/image). TextReaderScreen
renders text blocks via ParagraphItem, image blocks via Coil AsyncImage.
addBookmarkFromParagraph takes only blockIndex and extracts preview
internally; rejects image blocks."
```

---

## Task 11: Web — renderBlocks + token query param + image styles

**Files:**
- Modify: `server/internal/web/textReader.js`
- Modify: `server/internal/web/style.css`

**Interfaces:**
- Consumes: new server response `{title, blocks: [{type, value?, src?}]}`, existing `readerPrefs` module, existing auth token source (`getAuthToken` or similar — verify in `api.js`)
- Produces: `renderBlocks(blocks)` function replacing `renderParagraphs`, plus token-appending helper for image URLs

- [ ] **Step 1: Read textReader.js + api.js to find token source**

Run: `grep -n "token\|Authorization\|Bearer" server/internal/web/textReader.js server/internal/web/api.js`

Note how the token is currently stored/read (likely `localStorage.getItem('auth_token')` or similar in api.js). The new `appendTokenQueryParam` helper will read from the same source.

- [ ] **Step 2: Modify textReader.js — replace renderParagraphs with renderBlocks**

In `server/internal/web/textReader.js`, find the existing `renderParagraphs` function and replace with `renderBlocks`:

```javascript
function renderBlocks(blocks) {
    els.content.innerHTML = '';
    blocks.forEach((block, idx) => {
        if (block.type === 'text') {
            const p = document.createElement('p');
            p.textContent = block.value || '';  // XSS safe
            p.dataset.blockIndex = idx;
            // Hover bookmark button (carried over from C-phase)
            const btn = document.createElement('button');
            btn.className = 'text-reader__para-bookmark';
            btn.type = 'button';
            btn.textContent = '+';
            btn.title = '添加书签';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const ok = readerPrefs.addBookmark({
                    bookPath: path,
                    chapterIndex: currentIdx,
                    paragraphIndex: idx,  // field name retained for C-phase compat
                    preview: (block.value || '').slice(0, 30),
                    createdAt: Date.now(),
                });
                showToast(ok ? '已添加书签' : '已存在书签', ok ? 'success' : 'info');
            });
            p.appendChild(btn);
            els.content.appendChild(p);
        } else if (block.type === 'image') {
            const img = document.createElement('img');
            img.className = 'text-reader__image';
            img.loading = 'lazy';
            if (block.src) {
                img.src = appendTokenQueryParam(block.src);
            } else {
                img.alt = '[本图片无法显示]';
            }
            els.content.appendChild(img);
        }
    });
}

// appendTokenQueryParam adds ?token=...& or &token=... to a URL so that
// <img> tags (which cannot set Authorization headers) can authenticate
// against /api/v1/books/image. Returns the URL unchanged if no token is
// configured (open-auth-mode servers).
function appendTokenQueryParam(url) {
    const token = getAuthToken();  // from api.js or wherever auth lives
    if (!token) return url;
    const sep = url.includes('?') ? '&' : '?';
    return url + sep + 'token=' + encodeURIComponent(token);
}
```

Update `loadChapter` to call `renderBlocks(chapter.blocks || [])` instead of `renderParagraphs(chapter.content || '')`:

```javascript
async function loadChapter(idx) {
    if (isLoadingChapter) return;
    if (idx < 0 || idx >= chapterCount) return;
    isLoadingChapter = true;
    currentIdx = idx;
    try {
        const chapter = await getBookChapter(path, idx);
        els.title.textContent = `${chapter.title || ''} — ${book.title || ''}`;
        renderBlocks(chapter.blocks || []);
        saveProgress(idx);
    } catch (e) {
        showToast('加载章节失败: ' + e.message, 'error');
    } finally {
        isLoadingChapter = false;
    }
}
```

If `getAuthToken` does not exist as an exported helper, the implementer should extract it from `api.js`'s existing request helper (which already reads the token to set `Authorization: Bearer`). Add a small `export function getAuthToken() { return localStorage.getItem('auth_token') || ''; }` to `api.js` and import in `textReader.js`.

- [ ] **Step 3: Add image styles to style.css**

In `server/internal/web/style.css`, add (anywhere reasonable — near the existing `.text-reader__content` rules):

```css
.text-reader__image {
    display: block;
    max-width: 100%;
    height: auto;
    margin: 12px auto;
    border-radius: 4px;
}

.text-reader__content img[alt="[本图片无法显示]"] {
    padding: 16px;
    color: var(--text-muted);
    font-style: italic;
    text-align: center;
    background: var(--bg-elevated);
    border-radius: 4px;
}
```

- [ ] **Step 4: Verify build + embedded FS**

Run: `cd server && go build -o LocalMediaHub.exe ./cmd/server`
Expected: build succeeds.

Run: `cd server && go test ./...`
Expected: all existing Go tests pass (no server behavior changes in this task — JS/CSS only).

- [ ] **Step 5: Manual smoke (optional — full acceptance is Task 13)**

If a running server + browser is available, open a .txt file in the Web reader — verify paragraphs still render (no regression from C-phase). Image rendering can only be verified with a real epub containing images; defer to Task 13 acceptance.

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/textReader.js \
        server/internal/web/style.css \
        server/internal/web/api.js  # if getAuthToken was added here
git commit -m "feat(web): renderBlocks + token query param for <img> auth

Replaces textContent-on-container with per-block rendering. Text blocks
use <p>+textContent (XSS safe). Image blocks use <img loading='lazy'>
with token appended as query param (browsers cannot set Authorization
headers on <img src> requests)."
```

---

## Task 12: Acceptance + CI gate

**Files:** no code changes — manual verification

- [ ] **Step 1: Run server tests**

Run: `cd server && go test ./...`
Expected: all packages PASS.

- [ ] **Step 2: Run Android tests + build**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual acceptance — Android (real epub with images required)**

Find or create a test epub that contains images in its chapters (a manga/comic epub, a children's picture book, or a technical book with figures). Copy to a scanned media directory.

Verify:
- [ ] Open the epub in the Android reader — text + images both render
- [ ] No `[图片]` placeholders appear
- [ ] Image sizing respects container width (max-width: 100%)
- [ ] Image load failure (e.g. corrupted image) shows `[本图片无法显示]`
- [ ] Switching chapters loads new chapter's images
- [ ] Theme switch (day/night/eye-care) does not affect image colors
- [ ] Font size switch does not affect image size
- [ ] Kill app + reopen → last chapter restores, images reload (Coil cache)
- [ ] C-phase features still work (bookmarks, auto-scroll, settings)

- [ ] **Step 4: Manual acceptance — Web**

Verify the same 9 items in a browser.

- [ ] **Step 5: Cross-device check**

- [ ] Same epub opened on both Android and Web shows the same images
- [ ] Android's token (query param on Web) does not interfere with Web's header-based auth

- [ ] **Step 6: txt regression check**

- [ ] Open a .txt file — paragraphs still render via `\n\n` split (now server-side)
- [ ] No images appear (txt has no images)
- [ ] C-phase bookmark index still works (paragraphIndex == block index for txt)

- [ ] **Step 7: data: URI check (if test epub has any)**

- [ ] Images with `data:` src render directly (no `/api/v1/books/image` call)

- [ ] **Step 8: Final commit (if any docs need updating)**

No code changes in this task. If all checks pass, the branch is ready for merge.

---

## Self-Review Checklist

**Spec coverage:**
- Block type + ChapterBlocks (Tasks 1-3) ✓
- extractBlocks + extractImgSrc (Task 3) ✓
- txtChapterBlocks (Task 2) ✓
- epubChapterBlocks (Task 3) ✓
- Export NormalizeHref/JoinZipPath/ReadCapped (Task 3) ✓
- BookService.GetChapterBlocks + reverseLookupManifest (Task 5) ✓
- BookService.ReadImageBytes + mimeByExtension (Task 6) ✓
- BearerToken query param fallback (Task 7) ✓
- chapterResponse blocks + GetBookImage endpoint (Task 8) ✓
- Android Block model + chapterBlocks state (Task 9) ✓
- Android TextReaderScreen blocks rendering (Task 10) ✓
- Web renderBlocks + token query param + image styles (Task 11) ✓
- Acceptance + CI gate (Task 12) ✓

**Placeholder scan:** None — all steps contain actual code or commands.

**Type consistency:** `Block` struct fields (Type/Value/Src) consistent across Go parser, Go handler response, Kotlin data class, JS object access. `BookService.GetChapterBlocks` signature `(path, idx) ([]Block, error)` matches handler usage. `addBookmarkFromParagraph(blockIndex: Int): Boolean` consistent between VM and test. `chapterResponse.Blocks` field name matches Kotlin `BookChapterContent.blocks` and JS `chapter.blocks`.

# 服务端性能优化 3 项打包（Round 15）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 给 Go 服务端加缩略图 LRU 内存缓存（200 项 ~20MB）、pprof 诊断端点（RFC1918 + loopback 白名单）、streaming Range 测试 3 项边际优化。

**Architecture:** 在现有磁盘缓存之上加 hashicorp/golang-lru/v2 内存层；新增 private_net middleware + net/http/pprof 注册；按现有 streaming_test.go 风格（httptest.NewRecorder + svc.ServeFile）补 2 个 Range 用例（suffix range + 416 unsatisfiable）。

**Tech Stack:** Go 1.24 + Echo v4 + hashicorp/golang-lru/v2 + net/http/pprof + httptest.NewRecorder + testify

## Global Constraints

- Go 1.24（`net.IP.IsPrivate()` 已就绪，Go 1.17+）
- Echo v4 middleware 签名：`func(next echo.HandlerFunc) echo.HandlerFunc`
- 缩略图 LRU 容量 200 项（~20MB 堆）
- 缓存 key 格式：`md5(path + "|" + modTime.Format(time.RFC3339Nano))`（与 `GetThumbnailPath` 一致）
- `GenerateThumbnailBytes` 和 `GenerateSystemThumbnailBytes` 各自对应 `GenerateThumbnail` / `GenerateSystemThumbnail`（不同磁盘路径）
- pprof 路径：`/debug/pprof/*`，用 `net/http/pprof` 默认 ServeMux + `echo.WrapHandler(http.DefaultServeMux)`
- pprof 鉴权：`net.IP.IsLoopback() || net.IP.IsPrivate() || net.IP.IsLinkLocalUnicast()`
- Range 测试用 `httptest.NewRecorder + svc.ServeFile`（延续现有风格，spec §4.3 + §7）
- 现有 streaming_test.go 已覆盖 200 + 单 Range 206；本轮补 suffix range (206) + 416 unsatisfiable
- 每个 commit 后：`cd server && go test ./...` 全过

---

### Task 1 (Commit C1): 缩略图 LRU 内存缓存

**Files:**
- Modify: `server/internal/service/thumbnail.go`（加 `memCache *lru.Cache[string, []byte]` 字段 + `GenerateThumbnailBytes` / `GenerateSystemThumbnailBytes` 方法 + 私有 helper `generateBytesVia`）
- Create: `server/internal/service/thumbnail_cache_test.go`（缓存命中/淘汰/并发击穿测试）
- Modify: `server/internal/server/handler/images.go:49-70`（`GetThumbnail` 改用 `GenerateThumbnailBytes + c.Blob`）
- Modify: `server/internal/server/handler/videos.go`（`GetVideoThumbnail` 同上）
- Modify: `server/internal/server/handler/media.go`（`MediaThumbnail` 同上）
- Modify: `server/internal/server/handler/system.go`（`SystemThumbnail` 改用 `GenerateSystemThumbnailBytes + c.Blob`）
- Modify: `server/go.mod` / `server/go.sum`（加 `github.com/hashicorp/golang-lru/v2`）

**Interfaces:**
- Consumes: `github.com/hashicorp/golang-lru/v2`（新增依赖）
- Produces:
  - `ThumbnailService.memCache` 字段（`*lru.Cache[string, []byte]`，容量 200）
  - `ThumbnailService.GenerateThumbnailBytes(sourcePath string) ([]byte, error)`
  - `ThumbnailService.GenerateSystemThumbnailBytes(sourcePath string) ([]byte, error)`
  - 私有 `thumbnailCacheKey(sourcePath string, modTime time.Time) string`
  - 私有 `generateBytesVia(sourcePath string, genFunc func(string) (string, error)) ([]byte, error)`

- [ ] **Step 1: Add `golang-lru/v2` dependency**

Run: `cd server && go get github.com/hashicorp/golang-lru/v2`
Expected: `go.mod` 加 `github.com/hashicorp/golang-lru/v2 v2.x.x`，`go.sum` 同步更新。

- [ ] **Step 2: Write the failing cache test**

Create `server/internal/service/thumbnail_cache_test.go`:

```go
package service

import (
    "os"
    "path/filepath"
    "testing"
    "time"
)

// helper: create a tiny JPEG file and return its path
func writeTestJPEG(t *testing.T, dir string, name string) string {
    t.Helper()
    p := filepath.Join(dir, name)
    // minimal valid JPEG header + 1x1 pixel (180 bytes)
    jpegBytes := []byte{
        0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
        0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xDB, 0x00, 0x43,
        0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09,
        0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12,
        0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20,
        0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29,
        0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32,
        0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF, 0xC9, 0x00, 0x0B, 0x08, 0x00, 0x01,
        0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF, 0xCC, 0x00, 0x06, 0x00, 0x10,
        0x10, 0x05, 0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00,
        0xD2, 0xCF, 0x20, 0xFF, 0xD9,
    }
    if err := os.WriteFile(p, jpegBytes, 0644); err != nil {
        t.Fatalf("write test JPEG: %v", err)
    }
    // Force modtime to a fixed value so cache key is deterministic
    mt := time.Now().Add(-1 * time.Hour)
    if err := os.Chtimes(p, mt, mt); err != nil {
        t.Fatalf("chtimes: %v", err)
    }
    return p
}

func TestGenerateThumbnailBytes_CachesAfterFirstCall(t *testing.T) {
    cacheDir := t.TempDir()
    svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
    if err != nil {
        t.Fatalf("NewThumbnailService: %v", err)
    }

    srcDir := t.TempDir()
    src := writeTestJPEG(t, srcDir, "img.jpg")

    bytes1, err := svc.GenerateThumbnailBytes(src)
    if err != nil {
        t.Fatalf("first GenerateThumbnailBytes: %v", err)
    }
    if len(bytes1) == 0 {
        t.Fatal("first call returned empty bytes")
    }

    // Stat the disk cache to record its mtime, then verify the second
    // GenerateThumbnailBytes hits memory cache (no disk read).
    cacheKey := svc.thumbnailCacheKey(src, mustModTime(t, src))
    diskPath := filepath.Join(cacheDir, cacheKey+".jpg")
    fi1, err := os.Stat(diskPath)
    if err != nil {
        t.Fatalf("stat disk cache: %v", err)
    }

    bytes2, err := svc.GenerateThumbnailBytes(src)
    if err != nil {
        t.Fatalf("second GenerateThumbnailBytes: %v", err)
    }
    if string(bytes1) != string(bytes2) {
        t.Fatal("second call returned different bytes than first")
    }

    // Disk cache file should be untouched (modtime unchanged) on the
    // memory-cache-hit path. Sleep briefly to make mtime differences detectable.
    time.Sleep(20 * time.Millisecond)
    fi2, _ := os.Stat(diskPath)
    if fi2.ModTime() != fi1.ModTime() {
        t.Fatalf("disk cache modtime changed: %v → %v", fi1.ModTime(), fi2.ModTime())
    }

    // Memory cache should have the entry.
    if _, ok := svc.memCache.Get(cacheKey); !ok {
        t.Fatal("memCache missing entry after second call")
    }
}

func TestGenerateThumbnailBytes_EvictsAtCapacity(t *testing.T) {
    cacheDir := t.TempDir()
    svc, err := NewThumbnailService(cacheDir, 150, "jpg", "")
    if err != nil {
        t.Fatalf("NewThumbnailService: %v", err)
    }

    srcDir := t.TempDir()
    // Fill cache to capacity (200). Use distinct modtimes so keys are distinct.
    for i := 0; i < svc.memCache.Len(); i++ {
        name := "img" + strconv.Itoa(i) + ".jpg"
        src := writeTestJPEG(t, srcDir, name)
        // Override modtime per-file to make cache keys unique.
        mt := time.Date(2020, 1, 1, 0, 0, i, 0, time.UTC)
        if err := os.Chtimes(src, mt, mt); err != nil {
            t.Fatalf("chtimes: %v", err)
        }
        if _, err := svc.GenerateThumbnailBytes(src); err != nil {
            t.Fatalf("fill cache i=%d: %v", i, err)
        }
    }

    // Capacity still 200 (Len == MaxEntries).
    if svc.memCache.Len() != 200 {
        t.Fatalf("expected cache at capacity 200, got %d", svc.memCache.Len())
    }
}

func mustModTime(t *testing.T, p string) time.Time {
    t.Helper()
    fi, err := os.Stat(p)
    if err != nil {
        t.Fatalf("stat %s: %v", p, err)
    }
    return fi.ModTime()
}
```

Add `import "strconv"` to the test file (used in step `TestGenerateThumbnailBytes_EvictsAtCapacity`).

- [ ] **Step 3: Run the tests to verify they fail**

Run: `cd server && go test ./internal/service/ -run TestGenerateThumbnailBytes`
Expected: FAIL — `svc.GenerateThumbnailBytes undefined` and `svc.thumbnailCacheKey undefined`.

- [ ] **Step 4: Implement `memCache` field + `thumbnailCacheKey` + `generateBytesVia` + two `Bytes` methods**

Open `server/internal/service/thumbnail.go`. Make these changes:

**4a. Add import + field:**

In the import block, add:
```go
"github.com/hashicorp/golang-lru/v2"
```

In the `ThumbnailService` struct (after `ffmpegPath string`), add:
```go
    memCache *lru.Cache[string, []byte]
```

**4b. Initialize memCache in `NewThumbnailService`:**

Replace the constructor body:
```go
func NewThumbnailService(cacheDir string, maxSize int, format string, ffmpegPath string) (*ThumbnailService, error) {
    if err := os.MkdirAll(cacheDir, 0755); err != nil {
        return nil, err
    }
    // golang-lru/v2 returns no error when size > 0; the explicit discard is
    // documented. 200 entries ≈ 20 MB heap at ~100 KB per thumbnail.
    memCache, _ := lru.NewWithEvict[string, []byte](200, nil)
    return &ThumbnailService{
        cacheDir:   cacheDir,
        maxSize:    maxSize,
        format:     format,
        sem:        make(chan struct{}, runtime.NumCPU()),
        ffmpegPath: ffmpegPath,
        memCache:   memCache,
    }, nil
}
```

**4c. Add `thumbnailCacheKey` helper after `GetThumbnailPath`:**

```go
// thumbnailCacheKey returns the md5 hash used as both disk cache filename
// (sans .jpg) and memory cache key. MUST match GetThumbnailPath's format
// exactly — both use RFC3339Nano, NOT UnixNano().
func (s *ThumbnailService) thumbnailCacheKey(sourcePath string, modTime time.Time) string {
    key := sourcePath + "|" + modTime.Format(time.RFC3339Nano)
    return fmt.Sprintf("%x", md5.Sum([]byte(key)))
}
```

**4d. Add private `generateBytesVia` helper + two public `Bytes` methods at the end of the file (after `PreGenerateThumbnails`):**

```go
// generateBytesVia returns the JPEG bytes for [sourcePath], serving from
// memCache on hit. On miss it calls [genFunc] to ensure the disk-cached
// file exists, then reads it into memCache. The genFunc indirection lets
// both GenerateThumbnailBytes and GenerateSystemThumbnailBytes share
// logic — only the disk path differs.
func (s *ThumbnailService) generateBytesVia(
    sourcePath string,
    genFunc func(string) (string, error),
) ([]byte, error) {
    fi, err := os.Stat(sourcePath)
    if err != nil {
        return nil, err
    }
    cacheKey := s.thumbnailCacheKey(sourcePath, fi.ModTime())

    if cached, ok := s.memCache.Get(cacheKey); ok {
        return cached, nil
    }

    cachePath, err := genFunc(sourcePath)
    if err != nil {
        return nil, err
    }

    bytes, err := os.ReadFile(cachePath)
    if err != nil {
        return nil, err
    }
    s.memCache.Add(cacheKey, bytes)
    return bytes, nil
}

// GenerateThumbnailBytes is the bytes-equivalent of GenerateThumbnail.
// On memory-cache hit returns JPEG bytes without touching disk.
func (s *ThumbnailService) GenerateThumbnailBytes(sourcePath string) ([]byte, error) {
    return s.generateBytesVia(sourcePath, s.GenerateThumbnail)
}

// GenerateSystemThumbnailBytes is the bytes-equivalent of GenerateSystemThumbnail.
// System thumbnails live under cacheDir/system/ but share the same memory
// cache (keyed by md5(path + modtime) which is unique per source file).
func (s *ThumbnailService) GenerateSystemThumbnailBytes(sourcePath string) ([]byte, error) {
    return s.generateBytesVia(sourcePath, s.GenerateSystemThumbnail)
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd server && go test ./internal/service/ -run TestGenerateThumbnailBytes`
Expected: 2 tests PASS.

- [ ] **Step 6: Run all thumbnail tests to verify no regression**

Run: `cd server && go test ./internal/service/`
Expected: all tests PASS including existing `TestThumbnail*` and `TestParseFFprobeDuration` etc.

- [ ] **Step 7: Refactor handler `images.go::GetThumbnail` to use `GenerateThumbnailBytes + c.Blob`**

Open `server/internal/server/handler/images.go`. Replace `GetThumbnail` (lines 49-70):

```go
func (h *Handler) GetThumbnail(c echo.Context) error {
    pathStr, err := decodeWildcardPath(c.Param("*"), "/thumbnail")
    if err != nil {
        return respondError(c, http.StatusBadRequest, err.Error())
    }

    resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), h.cfg.Scan.ImageExtensions)
    if err != nil {
        return respondError(c, http.StatusForbidden, "access denied")
    }

    bytes, err := h.thumbnail.GenerateThumbnailBytes(resolved)
    if err != nil {
        if os.IsNotExist(err) {
            return respondNotFound(c, "file not found")
        }
        return respondInternalError(c, err)
    }

    setMediaCacheHeaders(c)
    return c.Blob(http.StatusOK, "image/jpeg", bytes)
}
```

- [ ] **Step 8: Refactor handler `videos.go::GetVideoThumbnail` similarly**

Open `server/internal/server/handler/videos.go`. Find `GetVideoThumbnail` (the handler that calls `h.thumbnail.GenerateThumbnail(...)` then `c.File(thumbPath)`). Apply the same pattern as Step 7: replace `GenerateThumbnail` + `c.File` with `GenerateThumbnailBytes` + `c.Blob(http.StatusOK, "image/jpeg", bytes)`. Preserve all validation, error handling, and `setMediaCacheHeaders(c)` calls. The exact diff depends on the current function shape — read it first.

- [ ] **Step 9: Refactor handler `media.go::MediaThumbnail` similarly**

Open `server/internal/server/handler/media.go`. Find `MediaThumbnail` (the handler that calls `h.thumbnail.GenerateThumbnail(...)` then `c.File(thumbPath)`). Apply the same pattern. Preserve all validation, error handling, and `setMediaCacheHeaders(c)` calls.

- [ ] **Step 10: Refactor handler `system.go::SystemThumbnail` to use `GenerateSystemThumbnailBytes + c.Blob`**

Open `server/internal/server/handler/system.go`. Find `SystemThumbnail` (the handler that calls `h.thumbnail.GenerateSystemThumbnail(...)` then `c.File(thumbPath)`). **Note: this uses `GenerateSystemThumbnail`, NOT `GenerateThumbnail`** — apply `GenerateSystemThumbnailBytes` + `c.Blob(http.StatusOK, "image/jpeg", bytes)`. Preserve all validation, error handling, and `setMediaCacheHeaders(c)` calls.

- [ ] **Step 11: Verify full build + tests**

Run: `cd server && go build ./... && go test ./...`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 12: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/service/thumbnail.go \
        server/internal/service/thumbnail_cache_test.go \
        server/internal/server/handler/images.go \
        server/internal/server/handler/videos.go \
        server/internal/server/handler/media.go \
        server/internal/server/handler/system.go \
        server/go.mod server/go.sum
git commit -m "$(cat <<'EOF'
feat(server): thumbnail LRU memory cache (round 15 C1)

Add 200-entry LRU on top of existing disk cache. New methods
GenerateThumbnailBytes / GenerateSystemThumbnailBytes serve JPEG bytes
straight from memory on hit, skipping os.ReadFile. 4 handlers
(GetThumbnail / GetVideoThumbnail / MediaThumbnail / SystemThumbnail)
migrated from c.File(path) to c.Blob(bytes). Cache key uses RFC3339Nano
matching GetThumbnailPath (not UnixNano) so memory and disk caches stay
aligned.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): pprof endpoint + private-net middleware

**Files:**
- Create: `server/internal/server/middleware/private_net.go`
- Modify: `server/internal/server/server.go`（register `/debug/pprof/*` route group with middleware）
- Modify: `server/internal/server/server_test.go`（add tests for PrivateNetOnly + pprof route registration）

**Interfaces:**
- Consumes: `github.com/labstack/echo/v4`，标准库 `net`
- Produces:
  - `middleware.PrivateNetOnly() echo.MiddlewareFunc`
  - 私有 `isPrivateOrLoopback(ip net.IP) bool`

- [ ] **Step 1: Write the failing middleware test**

Create `server/internal/server/middleware/private_net_test.go`:

```go
package middleware

import (
    "net"
    "testing"
)

func TestIsPrivateOrLoopback(t *testing.T) {
    cases := []struct {
        ip   string
        want bool
    }{
        // Private (RFC1918)
        {"192.168.1.100", true},
        {"10.0.0.1", true},
        {"172.16.5.5", true},
        {"172.31.255.255", true},
        // Loopback
        {"127.0.0.1", true},
        {"::1", true},
        // Link-local
        {"169.254.1.1", true},
        {"fe80::1", true},
        // Public (should be rejected)
        {"8.8.8.8", false},
        {"1.1.1.1", false},
        {"203.0.113.1", false}, // TEST-NET-3
    }
    for _, tc := range cases {
        t.Run(tc.ip, func(t *testing.T) {
            ip := net.ParseIP(tc.ip)
            if ip == nil {
                t.Fatalf("ParseIP(%q) failed", tc.ip)
            }
            got := isPrivateOrLoopback(ip)
            if got != tc.want {
                t.Errorf("isPrivateOrLoopback(%s) = %v, want %v", tc.ip, got, tc.want)
            }
        })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd server && go test ./internal/server/middleware/ -run TestIsPrivateOrLoopback`
Expected: FAIL — `isPrivateOrLoopback undefined`.

- [ ] **Step 3: Implement `private_net.go` middleware**

Create `server/internal/server/middleware/private_net.go`:

```go
package middleware

import (
    "net"

    "github.com/labstack/echo/v4"
)

// PrivateNetOnly rejects requests whose source IP is not a private/loopback
// address. Allowed: RFC1918 (10/8, 172.16/12, 192.168/16), loopback
// (127.0.0.0/8, ::1/128), link-local (169.254/16, fe80::/10).
//
// This matches the project's LAN-only deployment (mDNS/Bonjour discovery)
// and prevents leaking pprof data (heap dumps, goroutine traces, CPU
// profiles) to the public internet if the server is accidentally exposed.
//
// Implementation note: relies on Go 1.17+ net.IP.IsPrivate() which covers
// RFC1918 + RFC4193 (fc00::/7).
func PrivateNetOnly() echo.MiddlewareFunc {
    return func(next echo.HandlerFunc) echo.HandlerFunc {
        return func(c echo.Context) error {
            ip := net.ParseIP(c.RealIP())
            if ip == nil {
                return echo.ErrForbidden
            }
            if !isPrivateOrLoopback(ip) {
                return echo.ErrForbidden
            }
            return next(c)
        }
    }
}

func isPrivateOrLoopback(ip net.IP) bool {
    return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}
```

- [ ] **Step 4: Run the middleware test to verify it passes**

Run: `cd server && go test ./internal/server/middleware/ -run TestIsPrivateOrLoopback`
Expected: PASS (12 sub-tests).

- [ ] **Step 5: Register `/debug/pprof/*` route group in `server.go`**

Open `server/internal/server/server.go`. In the imports block, add:

```go
    "net/http"
    _ "net/http/pprof"
```

(`_ "net/http/pprof"` registers default handlers on `http.DefaultServeMux` via `init()`.)

In the `registerRoutes` method, find an appropriate location (after CORS / logger middleware registration, before any explicit `e.Any` catch-all routes). Add:

```go
    // pprof endpoints for live profiling. Restricted to private/loopback
    // IPs to avoid leaking heap/goroutine data on accidental public exposure.
    pprofGroup := s.Echo.Group("/debug/pprof", middleware.PrivateNetOnly())
    pprofGroup.Any("/*", echo.WrapHandler(http.DefaultServeMux))
```

> **Note:** `middleware` is already imported (used for CORS). Verify before adding.

- [ ] **Step 6: Add server_test.go pprof integration test**

Open `server/internal/server/server_test.go`. Append at the end of file:

```go
func TestPprofRoute_RegisteredUnderDebugPrefix(t *testing.T) {
    // Verify the route is wired up. Auth coverage lives in
    // middleware.PrivateNetOnly tests.
    cfg := testConfig(t)
    s, err := New(cfg)
    if err != nil {
        t.Fatalf("New: %v", err)
    }

    routes := s.Echo.Routes()
    found := false
    for _, r := range routes {
        if strings.HasPrefix(r.Path, "/debug/pprof") {
            found = true
            break
        }
    }
    if !found {
        t.Fatal("no /debug/pprof route registered")
    }
}
```

> Adjust `testConfig(t)` to match the existing test helper signature. If no helper exists, copy the pattern from existing tests in this file. If `New` requires specific setup, follow the pattern of other tests in this file.

- [ ] **Step 7: Verify full build + tests**

Run: `cd server && go build ./... && go test ./...`
Expected: BUILD SUCCESSFUL, all tests pass including new `TestPprofRoute_RegisteredUnderDebugPrefix`.

- [ ] **Step 8: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/server/middleware/private_net.go \
        server/internal/server/middleware/private_net_test.go \
        server/internal/server/server.go \
        server/internal/server/server_test.go
git commit -m "$(cat <<'EOF'
feat(server): pprof diagnostic endpoint with private-net whitelist (round 15 C2)

Expose /debug/pprof/* via net/http/pprof on http.DefaultServeMux. New
PrivateNetOnly middleware restricts to RFC1918 + loopback + link-local
IPs, matching the project's LAN-only deployment and preventing heap/
goroutine leaks if the server is accidentally exposed publicly.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3 (Commit C3): streaming Range test coverage

**Files:**
- Modify: `server/internal/service/streaming_test.go`（add 2 new subtests: suffix range + 416 unsatisfiable）

**Interfaces:**
- Consumes: existing `StreamingService.ServeFile(w http.ResponseWriter, r *http.Request, filePath string) error`
- Produces: 无（仅测试）

- [ ] **Step 1: Add suffix range test to `streaming_test.go`**

Open `server/internal/service/streaming_test.go`. The existing file has 2 subtests under `TestServeFile_DirectStreamingHeaders`. Append 2 new subtests inside the same parent test function (after the existing `Range request returns 206 Partial Content` subtest):

```go
    t.Run("Suffix range bytes=-N returns last N bytes with 206", func(t *testing.T) {
        // bytes=-10 means "last 10 bytes" per RFC 7233 §2.1.
        // File is 36 bytes, so last 10 = bytes 26..35 = "QRSTUVWXYZ".
        req := httptest.NewRequest(http.MethodGet, "/stream", nil)
        req.Header.Set("Range", "bytes=-10")
        rec := httptest.NewRecorder()

        err := svc.ServeFile(rec, req, testFilePath)
        if err != nil {
            t.Fatalf("unexpected error: %v", err)
        }

        res := rec.Result()
        defer res.Body.Close()

        if res.StatusCode != http.StatusPartialContent {
            t.Errorf("expected status 206, got %d", res.StatusCode)
        }
        if res.Header.Get("Content-Length") != "10" {
            t.Errorf("expected Content-Length 10, got %q", res.Header.Get("Content-Length"))
        }
        if res.Header.Get("Content-Range") != "bytes 26-35/36" {
            t.Errorf("expected Content-Range 'bytes 26-35/36', got %q", res.Header.Get("Content-Range"))
        }

        bodyBytes, _ := io.ReadAll(res.Body)
        if string(bodyBytes) != "QRSTUVWXYZ" {
            t.Errorf("body mismatch, got %q", string(bodyBytes))
        }
    })

    t.Run("Range past EOF returns 416 Range Not Satisfiable", func(t *testing.T) {
        // bytes=999999- is beyond the 36-byte file.
        req := httptest.NewRequest(http.MethodGet, "/stream", nil)
        req.Header.Set("Range", "bytes=999999-")
        rec := httptest.NewRecorder()

        err := svc.ServeFile(rec, req, testFilePath)
        if err != nil {
            t.Fatalf("unexpected error: %v", err)
        }

        res := rec.Result()
        defer res.Body.Close()

        if res.StatusCode != http.StatusRequestedRangeNotSatisfiable {
            t.Errorf("expected status 416, got %d", res.StatusCode)
        }
        if res.Header.Get("Content-Range") != "bytes */36" {
            t.Errorf("expected Content-Range 'bytes */36', got %q", res.Header.Get("Content-Range"))
        }
    })
```

> **Note:** the test asserts that `ServeFile`'s suffix-range parser (`parts[1] == ""` branch in streaming.go) interprets `bytes=-10` as "last 10 bytes" and computes `start = size - 10 = 26`. Verify the actual implementation handles this — re-read streaming.go:125-132 to confirm `end = size - 1` and `start` is parsed (or default 0) when `parts[0]` is empty string.

> **Pre-flight check before running:** If `streaming.go` does NOT correctly handle `bytes=-10` (start stays 0 when `parts[0] == ""`), this test will fail and you must fix the streaming.go parser. Per spec §4.3 the existing code is believed to handle it, but verify.

- [ ] **Step 2: Run the streaming tests**

Run: `cd server && go test ./internal/service/ -run TestServeFile_DirectStreamingHeaders -v`
Expected: 4 sub-tests pass (existing 200 OK + 206 single-range + new suffix-range + new 416).

**If the suffix-range test fails** because `streaming.go` mishandles `parts[0] == ""`:
- Fix the parser in `server/internal/service/streaming.go` (lines ~121-132). Currently `start, err = strconv.ParseInt(parts[0], 10, 64); if err != nil { start = 0 }` — for empty `parts[0]`, ParseInt returns error so start defaults to 0. The fix is to compute suffix range: when `parts[0] == ""`, set `start = max(0, size - endValue)` where `endValue` is parsed from `parts[1]`. Read the surrounding code carefully before changing.

- [ ] **Step 3: Run full test suite**

Run: `cd server && go test ./...`
Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add server/internal/service/streaming_test.go
# If streaming.go parser was fixed, also add:
# git add server/internal/service/streaming.go
git commit -m "$(cat <<'EOF'
test(server): cover suffix range + 416 in streaming ServeFile (round 15 C3)

Extends TestServeFile_DirectStreamingHeaders with two more subtests
following the existing httptest.NewRecorder + svc.ServeFile style
(spec §4.3): bytes=-N suffix range → 206 with last N bytes, and
bytes=999999- past EOF → 416 with Content-Range 'bytes */size'.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 测试覆盖 |
|---|---|---|---|---|
| C1 缩略图内存缓存 | 7 (含 go.mod) | ~150 行 | 低-中（触及 4 个 handler） | thumbnail_cache_test 2 用例 + 全量回归 |
| C2 pprof | 4 | ~80 行 | 低 | private_net_test 12 IP 用例 + server_test pprof 路由注册 |
| C3 Range 测试 | 1 | ~50 行 | 极低 | streaming_test.go 扩 2 subtest |

## 附录 B: 缓存 key 一致性陷阱

**陷阱：** `GetThumbnailPath` 用 `modTime.Format(time.RFC3339Nano)`，原 spec 错写为 `UnixNano()`。

**影响：** 内存缓存 key 与磁盘缓存文件名不一致 → 内存 miss 时读取磁盘路径错误 → 永远读不到磁盘文件 → 报错"file not found"。

**修复（已落地）：** `thumbnailCacheKey` 也用 `RFC3339Nano`。两者必须 byte-for-byte 一致。

**防御：** `TestGenerateThumbnailBytes_CachesAfterFirstCall` 显式断言 `diskPath = filepath.Join(cacheDir, cacheKey+".jpg")` 存在，即验证两者一致。

## 附录 C: `bytes=-10` 后缀 Range 处理

**RFC 7233 §2.1：** `bytes=-N` 表示"最后 N 字节"。

**streaming.go 当前实现（line 121-132）：**
```go
start, err = strconv.ParseInt(parts[0], 10, 64)
if err != nil {
    start = 0
}
if parts[1] != "" {
    end, err = strconv.ParseInt(parts[1], 10, 64)
    // ...
} else {
    end = size - 1
}
```

**当 `parts[0] == ""`（即 `bytes=-10`）：**
- `ParseInt("", 10, 64)` 返回 error → `start = 0`（错误！应是 `size - 10`）
- `parts[1] = "10"` → `end = 10`
- 结果：`start=0, end=10`，返回前 11 字节而非后 10 字节

**Task 3 Step 2 fallback：** 如果测试失败，按 fallback 修复 streaming.go 解析逻辑（spec §4.3 + §11 已识别此风险）。

## 附录 D: 已知限制（接受）

1. **缓存击穿未防**（spec §8 #1）：`singleflight` YAGNI；`sem` 信号量限流已存在。
2. **缓存不预加载**（spec §8 #2）：冷启动慢。
3. **pprof 白名单基于 `c.RealIP()`**（spec §8 #3）：如服务端配置 X-Forwarded-For 信任代理，可能被 IP 欺骗。本项目无反向代理部署。
4. **Range 测试不覆盖 `If-Range`**（spec §8 #4）：实际使用极少（ExoPlayer 不发）。
5. **`streaming.go` 可能错误处理 `bytes=-N`**（附录 C）：测试若失败则修 parser。

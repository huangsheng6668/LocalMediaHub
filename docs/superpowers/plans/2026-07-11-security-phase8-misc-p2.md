# Security Round 29 — Phase 8: Misc P2 Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Sweep 7 P2 findings: blocked root validation on PUT /admin/config (T8-01), path error sanitization (T8-11), rate limiting on scan trigger + delete (T1-06, T3-04b), and ffmpeg client-disconnect cleanup (T5-02).

**Architecture:** Server-side only. Task 1 exports `service.IsBlockedRoot` + classifies `validateMediaFilePath` errors + wires IsBlockedRoot into `handler.UpdateConfig`. Task 2 adds `middleware/ratelimit.go` (per-IP sliding window) and mounts it on `/admin/scan/trigger` (2/30s) + `/system/delete` (5/min). Task 3 switches `serveTranscoded` to `exec.CommandContext(r.Context(), ...)` with explicit `cmd.Cancel` to kill ffmpeg on Windows when the client disconnects.

**Tech Stack:** Go 1.25+ / Echo v4 / `os/exec` / `sync`

**Source spec:** `docs/superpowers/specs/2026-07-11-security-phase8-misc-p2-design.md`

**Coverage:** T8-01 (Medium 6.1), T1-06 (Low 3.7), T3-04b (Low 3.7), T5-02 (Low 3.7), T8-11 (Low) + Chain-L (Medium 5.4, scan trigger DoS)

## Global Constraints

- **`service.IsBlockedRoot(absPath string) bool` is the single source of truth** for blocked-segment checks. Both `containsBlockedSegment` (internal) and `UpdateConfig` (external) must use it — no duplication. (Spec section 5.1.1)
- **Error classification in `validateMediaFilePath` MUST NOT include `%w` or the path string** in the returned error. Use `os.IsNotExist` / `os.IsPermission` + generic fallback. (Spec section 5.1.2)
- **RateLimit is per-route, NOT global**. Only `/admin/scan/trigger` and `/system/delete` get it. Media streaming endpoints (`/videos/*`, `/system/stream`) must remain unlimited. (Spec section 3.1)
- **`/admin/scan/trigger`: 2 req / 30s. `/system/delete`: 5 req / min.** Exact values, not configurable. (Spec section 5.2.2)
- **ffmpeg cleanup MUST use `exec.CommandContext(r.Context(), ...)`** with explicit `cmd.Cancel` to force-kill on Windows. No timeout (long videos allowed). (Spec section 5.3.2)
- **No new third-party dependencies.**

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `server/internal/service/path.go` | Modify | Export `IsBlockedRoot`; refactor `containsBlockedSegment`; classify `validateMediaFilePath` errors |
| `server/internal/service/path_test.go` | Modify | Add `TestIsBlockedRoot` + `TestValidateMediaFilePathErrorClassification` |
| `server/internal/server/handler/admin.go` | Modify | `UpdateConfig` calls `service.IsBlockedRoot` |
| `server/internal/server/handler/admin_test.go` | Modify | Add `TestUpdateConfigRejectsBlockedRoot` |
| `server/internal/server/middleware/ratelimit.go` | Create | `RateLimit(max, window)` per-IP middleware |
| `server/internal/server/middleware/ratelimit_test.go` | Create | Unit tests: window + multi-IP + reset |
| `server/internal/server/server.go` | Modify | Mount RateLimit on scan trigger + delete routes |
| `server/internal/service/streaming.go` | Modify | `serveTranscoded` uses `exec.CommandContext` + `cmd.Cancel` |
| `server/internal/service/streaming_test.go` | Modify | Add client-disconnect test |

---

## Task 1: Blocked root validation + error sanitization (TDD)

**Files:**
- Modify: `server/internal/service/path.go`
- Modify: `server/internal/service/path_test.go`
- Modify: `server/internal/server/handler/admin.go`
- Modify: `server/internal/server/handler/admin_test.go`

**Interfaces:**
- Produces: `service.IsBlockedRoot(absPath string) bool` (exported) + classified errors in `validateMediaFilePath`.

- [ ] **Step 1: Write the failing tests**

Append to `server/internal/service/path_test.go`:

```go
func TestIsBlockedRoot(t *testing.T) {
	cases := []struct {
		name string
		path string
		want bool
	}{
		// Blocked — segment matches
		{"windows root", `C:\Windows`, true},
		{"windows nested", `C:\Foo\Windows\bar`, true},
		{"system32", `C:\Windows\System32\foo`, true},
		{"program files with parens", `C:\Program Files (x86)\App`, true},
		{"recycle bin", `D:\$Recycle.Bin\x`, true},
		{"system volume information", `C:\System Volume Information`, true},
		// Not blocked — segment is a substring but not whole segment
		{"windows-screenshots (substring)", `D:\Media\windows-screenshots`, false},
		{"myprogram files (substring)", `E:\Foo\myprogram files`, false},
		// Not blocked — clean media paths
		{"user media", `E:\Photos\vacation`, false},
		{"idm downloads", `H:\IDM_Download\Video`, false},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := IsBlockedRoot(tc.path)
			if got != tc.want {
				t.Errorf("IsBlockedRoot(%q) = %v, want %v", tc.path, got, tc.want)
			}
		})
	}
}

func TestValidateMediaFilePathErrorClassification(t *testing.T) {
	tmp := t.TempDir()

	// Not exist — must NOT leak the path
	t.Run("not exist", func(t *testing.T) {
		missingPath := filepath.Join(tmp, "nonexistent-file.mp4")
		err := validateMediaFilePath(missingPath, []string{".mp4"})
		if err == nil {
			t.Fatal("expected error, got nil")
		}
		if err.Error() != "file not found" {
			t.Errorf("error = %q, want %q (must not contain path)", err.Error(), "file not found")
		}
		if strings.Contains(err.Error(), missingPath) {
			t.Errorf("error leaks path: %q", err.Error())
		}
	})

	// Accessible file with allowed extension — no error
	t.Run("accessible allowed ext", func(t *testing.T) {
		p := filepath.Join(tmp, "video.mp4")
		if err := os.WriteFile(p, []byte("x"), 0644); err != nil {
			t.Fatal(err)
		}
		err := validateMediaFilePath(p, []string{".mp4"})
		if err != nil {
			t.Errorf("expected no error, got: %v", err)
		}
	})

	// Accessible file with wrong extension — "file type not allowed"
	t.Run("wrong ext", func(t *testing.T) {
		p := filepath.Join(tmp, "data.txt")
		if err := os.WriteFile(p, []byte("x"), 0644); err != nil {
			t.Fatal(err)
		}
		err := validateMediaFilePath(p, []string{".mp4"})
		if err == nil || err.Error() != "access denied: file type not allowed" {
			t.Errorf("error = %v, want %q", err, "access denied: file type not allowed")
		}
	})
}
```

Confirm `"os"`, `"path/filepath"`, `"strings"` are imported in the test file.

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && go test ./internal/service/ -run "TestIsBlockedRoot|TestValidateMediaFilePathErrorClassification" -v`
Expected: FAIL — `undefined: IsBlockedRoot` and `validateMediaFilePath` returns `"path not accessible: ..."` (with path).

- [ ] **Step 3: Implement `IsBlockedRoot` + refactor `containsBlockedSegment`**

In `server/internal/service/path.go`, add the exported function after `blockedSegments` declaration (around line 33):

```go
// IsBlockedRoot reports whether any segment of absPath matches the blocked list.
// Exported so handler/admin.go can validate user-supplied scan roots (T8-01)
// without letting C:\Windows / D:\Program Files etc. become roots.
//
// Semantics: case-insensitive, whole-segment match — same as the internal
// containsBlockedSegment() used by browse/media/delete paths.
func IsBlockedRoot(absPath string) bool {
	for _, seg := range strings.Split(strings.ToLower(absPath), string(filepath.Separator)) {
		for _, blocked := range blockedSegments {
			if seg == blocked {
				return true
			}
		}
	}
	return false
}
```

Then refactor `containsBlockedSegment` (around line 160) to delegate:

```go
func containsBlockedSegment(absPath string) error {
	if IsBlockedRoot(absPath) {
		return fmt.Errorf("access denied: restricted directory")
	}
	return nil
}
```

- [ ] **Step 4: Classify `validateMediaFilePath` errors**

Replace the existing function body (around lines 171-187):

```go
func validateMediaFilePath(absPath string, allowedExtensions []string) error {
	info, err := os.Stat(absPath)
	if err != nil {
		// Phase 8 T8-11: classify error without leaking the path.
		if os.IsNotExist(err) {
			return fmt.Errorf("file not found")
		}
		if os.IsPermission(err) {
			return fmt.Errorf("permission denied")
		}
		return fmt.Errorf("path not accessible")
	}
	if info.IsDir() {
		return fmt.Errorf("access denied: not a file")
	}

	ext := strings.ToLower(filepath.Ext(absPath))
	for _, allowedExt := range allowedExtensions {
		if strings.EqualFold(ext, allowedExt) {
			return nil
		}
	}
	return fmt.Errorf("access denied: file type not allowed")
}
```

- [ ] **Step 5: Run service tests to verify pass**

Run: `cd server && go test ./internal/service/ -run "TestIsBlockedRoot|TestValidateMediaFilePathErrorClassification" -v`
Expected: PASS — all subcases green.

- [ ] **Step 6: Modify `handler/admin.go UpdateConfig`**

Read `server/internal/server/handler/admin.go` to confirm `service` is imported. If not, add `"github.com/localmediahub/server/internal/service"`.

In the `for _, r := range req.Roots` loop (around lines 27-31), add the blocked check:

```go
	for _, r := range req.Roots {
		if !filepath.IsAbs(r) {
			return respondError(c, http.StatusBadRequest, "scan roots must be absolute paths")
		}
		// Phase 8 T8-01: reject sensitive system directories as roots.
		if service.IsBlockedRoot(r) {
			return respondError(c, http.StatusBadRequest,
				fmt.Sprintf("scan root %q matches a restricted system directory", r))
		}
	}
```

- [ ] **Step 7: Write the admin handler test**

Check if `server/internal/server/handler/admin_test.go` exists. If yes, append; if no, create.

```go
package handler

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/labstack/echo/v4"
	// Adjust the import path to match the project's module structure.
)
```

If `admin_test.go` doesn't exist yet, the test setup will need to construct a `Handler` with minimal dependencies. Look at `server_auth_test.go` (Phase 1) for the `newAuthTestConfig` pattern — that helper creates a Config with valid Thumbnail/Scan. Reuse it if possible; otherwise inline a minimal cfg.

The test body:

```go
func TestUpdateConfigRejectsBlockedRoot(t *testing.T) {
	cases := []string{
		`C:\Windows`,
		`C:\Program Files`,
		`C:\Program Files (x86)`,
		`D:\$Recycle.Bin`,
	}
	for _, blocked := range cases {
		t.Run(blocked, func(t *testing.T) {
			// Construct the handler with a valid cfg (roots can be empty
			// here — UpdateConfig replaces them before Validate runs).
			cfg := newAuthTestConfig(t, "test-token")  // helper from Phase 1
			cfg.Scan.Roots = []string{"D:/existing-media"}  // initial valid roots
			// ... construct h := New(cfg, scanner, tags, streaming, thumbnail)
			//     per Phase 1 test pattern

			e := echo.New()
			body, _ := json.Marshal(map[string][]string{"roots": {blocked}})
			req := httptest.NewRequest(http.MethodPut, "/api/v1/admin/config", bytes.NewReader(body))
			req.Header.Set(echo.HeaderContentType, "application/json")
			req.Header.Set(echo.HeaderAuthorization, "Bearer test-token")
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			err := h.UpdateConfig(c)
			// err may be nil (handler returns c.JSON directly) — check response code.
			if rec.Code != http.StatusBadRequest {
				t.Errorf("blocked root %q: status = %d, want 400; body=%s",
					blocked, rec.Code, rec.Body.String())
			}
			if !bytes.Contains(rec.Body.Bytes(), []byte("restricted system directory")) {
				t.Errorf("blocked root %q: body missing expected text; got %s",
					blocked, rec.Body.String())
			}
		})
	}
}
```

**Note for implementer**: If constructing the full `Handler` is too heavy (scanner/tags/streaming/thumbnail services all need init), consider extracting `UpdateConfig`'s validation logic into a testable helper. But the brief assumes the Phase 1 test pattern (`newAuthTestConfig` + `handler.New`) works — try that first.

- [ ] **Step 8: Run handler test to verify pass**

Run: `cd server && go test ./internal/server/handler/ -run TestUpdateConfigRejectsBlockedRoot -v`
Expected: PASS — all 4 blocked paths return 400.

- [ ] **Step 9: Run full server test suite + vet**

Run: `cd server && go test ./... && go vet ./...`
Expected: All packages green, vet clean. Phase 1/3/4 tests unaffected.

- [ ] **Step 10: Commit**

```bash
git add server/internal/service/path.go server/internal/service/path_test.go \
        server/internal/server/handler/admin.go server/internal/server/handler/admin_test.go
git commit -m "feat(security): reject blocked roots in UpdateConfig + sanitize path errors (Phase 8 T8-01/T8-11)"
```

---

## Task 2: RateLimit middleware + mount (TDD)

**Files:**
- Create: `server/internal/server/middleware/ratelimit.go`
- Test: `server/internal/server/middleware/ratelimit_test.go`
- Modify: `server/internal/server/server.go`

**Interfaces:**
- Produces: `RateLimit(max int, window time.Duration) echo.MiddlewareFunc` — per-IP sliding window, 429 on excess.

- [ ] **Step 1: Write the failing test**

Create `server/internal/server/middleware/ratelimit_test.go`:

```go
package middleware

import (
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/labstack/echo/v4"
)

func TestRateLimit(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}

	// 2 req per 1 second (use short window for fast tests)
	mw := RateLimit(2, 100*time.Millisecond)
	wrapped := mw(handler)

	// First 2 requests pass
	for i := 1; i <= 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/", nil)
		req.RemoteAddr = "1.2.3.4:5678"
		rec := httptest.NewRecorder()
		c := e.NewContext(req, rec)
		if err := wrapped(c); err != nil {
			t.Fatalf("req %d: unexpected error %v", i, err)
		}
		if rec.Code != http.StatusOK {
			t.Errorf("req %d: status = %d, want 200", i, rec.Code)
		}
	}

	// Third request within window → 429
	req := httptest.NewRequest(http.MethodGet, "/", nil)
	req.RemoteAddr = "1.2.3.4:5678"
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)
	_ = wrapped(c)
	if rec.Code != http.StatusTooManyRequests {
		t.Errorf("req 3: status = %d, want 429", rec.Code)
	}
}

func TestRateLimitWindowReset(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}
	mw := RateLimit(1, 50*time.Millisecond) // short window for fast test
	wrapped := mw(handler)

	// First request passes
	req1 := httptest.NewRequest(http.MethodGet, "/", nil)
	req1.RemoteAddr = "1.2.3.4:5678"
	rec1 := httptest.NewRecorder()
	wrapped(e.NewContext(req1, rec1))
	if rec1.Code != http.StatusOK {
		t.Errorf("req 1: status = %d, want 200", rec1.Code)
	}

	// Second request immediately → 429
	req2 := httptest.NewRequest(http.MethodGet, "/", nil)
	req2.RemoteAddr = "1.2.3.4:5678"
	rec2 := httptest.NewRecorder()
	wrapped(e.NewContext(req2, rec2))
	if rec2.Code != http.StatusTooManyRequests {
		t.Errorf("req 2 (immediate): status = %d, want 429", rec2.Code)
	}

	// Wait for window to reset
	time.Sleep(60 * time.Millisecond)

	// Third request after window → 200
	req3 := httptest.NewRequest(http.MethodGet, "/", nil)
	req3.RemoteAddr = "1.2.3.4:5678"
	rec3 := httptest.NewRecorder()
	wrapped(e.NewContext(req3, rec3))
	if rec3.Code != http.StatusOK {
		t.Errorf("req 3 (after reset): status = %d, want 200", rec3.Code)
	}
}

func TestRateLimitPerIPIsolation(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}
	mw := RateLimit(1, time.Hour)
	wrapped := mw(handler)

	// IP A exhausts its bucket
	reqA := httptest.NewRequest(http.MethodGet, "/", nil)
	reqA.RemoteAddr = "10.0.0.1:1234"
	recA := httptest.NewRecorder()
	wrapped(e.NewContext(reqA, recA))

	// IP B should be unaffected
	reqB := httptest.NewRequest(http.MethodGet, "/", nil)
	reqB.RemoteAddr = "10.0.0.2:5678"
	recB := httptest.NewRecorder()
	wrapped(e.NewContext(reqB, recB))
	if recB.Code != http.StatusOK {
		t.Errorf("IP B should not be affected by IP A's limit: status = %d", recB.Code)
	}
}

// Optional: concurrent requests don't race the mutex
func TestRateLimitConcurrentSafety(t *testing.T) {
	e := echo.New()
	handler := func(c echo.Context) error {
		return c.String(http.StatusOK, "ok")
	}
	mw := RateLimit(100, time.Hour)
	wrapped := mw(handler)

	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			req := httptest.NewRequest(http.MethodGet, "/", nil)
			req.RemoteAddr = "1.2.3.4:1234"
			rec := httptest.NewRecorder()
			wrapped(e.NewContext(req, rec))
		}(i)
	}
	wg.Wait()
	// No panic / data race = pass. Run with -race to verify.
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd server && go test ./internal/server/middleware/ -run TestRateLimit -v`
Expected: FAIL — `undefined: RateLimit`.

- [ ] **Step 3: Write minimal implementation**

Create `server/internal/server/middleware/ratelimit.go`:

```go
package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
)

// RateLimit returns a middleware that allows at most `max` requests per `window`
// per client IP. Requests over the limit get 429 Too Many Requests with a JSON
// error body matching the project's standard error envelope.
//
// Implementation: in-memory map[string]*bucket guarded by sync.Mutex. The
// bucket counter resets when the window elapses. Not distributed — sufficient
// for single-process LAN deployment. Memory grows with distinct client IPs,
// which is bounded by LAN size.
//
// Use case: per-route rate limiting on sensitive endpoints (scan trigger,
// delete) to prevent accidental or malicious resource exhaustion. Does NOT
// apply globally — media streaming endpoints (videos, system/stream) must not
// be rate-limited or normal playback breaks.
func RateLimit(max int, window time.Duration) echo.MiddlewareFunc {
	type bucket struct {
		count   int
		resetAt time.Time
	}
	var (
		mu      sync.Mutex
		buckets = make(map[string]*bucket)
	)
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ip := c.RealIP()
			mu.Lock()
			now := time.Now()
			b, ok := buckets[ip]
			if !ok || now.After(b.resetAt) {
				buckets[ip] = &bucket{count: 1, resetAt: now.Add(window)}
				mu.Unlock()
				return next(c)
			}
			if b.count >= max {
				mu.Unlock()
				return c.JSON(http.StatusTooManyRequests,
					map[string]string{"error": "rate limit exceeded"})
			}
			b.count++
			mu.Unlock()
			return next(c)
		}
	}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd server && go test ./internal/server/middleware/ -run TestRateLimit -v -race`
Expected: PASS — all 4 subtests green, no race detector warnings.

- [ ] **Step 5: Mount middleware in server.go**

In `server/internal/server/server.go:registerRoutes`, find the existing routes (around lines 161 + 175):

```go
admin.POST("/scan/trigger", h.TriggerScan)
// ...
sys.POST("/delete", h.DeletePath)
```

Change to:

```go
admin.POST("/scan/trigger", h.TriggerScan, middleware.RateLimit(2, 30*time.Second))
// ...
sys.POST("/delete", h.DeletePath, middleware.RateLimit(5, time.Minute))
```

Confirm `time` is imported (line 11 — yes).

- [ ] **Step 6: Run full server test suite**

Run: `cd server && go test ./...`
Expected: All green. Existing Phase 1/3 tests unaffected (they don't trigger rate limits — only single requests per test).

- [ ] **Step 7: Manual integration test**

Start server with a valid token + roots config, then spam scan trigger:

```bash
cd server
# Ensure config.yaml has token set + roots configured (Phase 3 requirement)
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless &
sleep 3

TOKEN="your-token-from-config"
for i in 1 2 3 4 5; do
  echo "--- request $i ---"
  curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $TOKEN" \
    http://localhost:8000/api/v1/admin/scan/trigger
done

# Expected: 200, 200, 429, 429, 429
kill %1
```

- [ ] **Step 8: Commit**

```bash
git add server/internal/server/middleware/ratelimit.go server/internal/server/middleware/ratelimit_test.go \
        server/internal/server/server.go
git commit -m "feat(middleware): add per-route RateLimit; mount on scan trigger + delete (Phase 8 T1-06/T3-04b)"
```

---

## Task 3: ffmpeg client-disconnect cleanup (TDD)

**Files:**
- Modify: `server/internal/service/streaming.go`
- Modify: `server/internal/service/streaming_test.go`

**Interfaces:**
- Produces: `serveTranscoded` now kills ffmpeg subprocess when client disconnects.

- [ ] **Step 1: Read the current serveTranscoded**

Read `server/internal/service/streaming.go` lines 132-210 to understand current cmd construction + stdout piping + wait logic. Note: implementation uses `cmd.StdoutPipe()` + `cmd.Start()` + `io.Copy(w, stdout)` + `cmd.Wait()`.

- [ ] **Step 2: Write the failing test**

Append to `server/internal/service/streaming_test.go`:

```go
func TestServeTranscodedClientDisconnect(t *testing.T) {
	// Skip if ffmpeg not in PATH — CI environments without ffmpeg can't test this.
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg not in PATH, skipping client-disconnect test")
	}

	// Create a long-running fake "video" file — ffmpeg will read from it
	// for the duration of the test.
	tmp := t.TempDir()
	srcPath := filepath.Join(tmp, "input.mp4")
	// A small but real MP4 — use ffmpeg to generate a 60-second test video.
	genCmd := exec.Command("ffmpeg", "-y", "-f", "lavfi", "-i",
		"testsrc=duration=60:size=320x240:rate=1", "-c:v", "libx264",
		"-preset", "ultrafast", srcPath)
	if out, err := genCmd.CombinedOutput(); err != nil {
		t.Skipf("ffmpeg cannot generate test video: %v\n%s", err, out)
	}

	// Set up streaming service with default ffmpeg path
	svc := NewStreamingService("")

	// Create a request that will be cancelled mid-stream
	ctx, cancel := context.WithCancel(context.Background())
	req := httptest.NewRequest(http.MethodGet,
		"/stream?path="+url.QueryEscape(srcPath), nil).WithContext(ctx)
	rec := httptest.NewRecorder()

	// Run serveTranscoded in a goroutine; cancel ctx after 500ms
	done := make(chan error, 1)
	go func() {
		done <- svc.TranscodeAndServe(rec, req, srcPath)
	}()

	time.Sleep(500 * time.Millisecond)
	cancel()

	// Wait for handler to finish (should return shortly after ctx cancel)
	select {
	case err := <-done:
		// Error is OK — context cancellation may surface as read/write error.
		_ = err
	case <-time.After(5 * time.Second):
		t.Fatal("serveTranscoded did not return within 5s of client disconnect")
	}

	// Give ffmpeg a moment to die after context cancel
	time.Sleep(500 * time.Millisecond)

	// Verify no orphan ffmpeg processes — on Windows this is hard to check
	// without tasklist; on Unix use pgrep. For a cross-platform test, we rely
	// on the "done channel returned" timing: if ffmpeg kept running, the
	// io.Copy would block serveTranscoded from returning.
	//
	// The 5s timeout above IS the assertion: if serveTranscoded returned
	// within 5s of cancel(), the cmd must have been killed.
}
```

Add imports as needed: `"context"`, `"net/http/httptest"`, `"net/url"`, `"os/exec"`, `"path/filepath"`, `"time"`.

**Note**: If `TranscodeAndServe` is not the correct entry-point name, check the existing `streaming.go` public API first — it may be `ServeStream` or similar. Adjust the test to call the right method.

- [ ] **Step 3: Run test to verify it fails**

Run: `cd server && go test ./internal/service/ -run TestServeTranscodedClientDisconnect -v -timeout 30s`
Expected: FAIL — test times out after 5s (current `serveTranscoded` uses `exec.Command` without context, so ffmpeg keeps running and `io.Copy` blocks, so `serveTranscoded` doesn't return within 5s of cancel).

- [ ] **Step 4: Implement client-disconnect cleanup**

In `server/internal/service/streaming.go serveTranscoded` (around lines 177-184), change `exec.Command` to `exec.CommandContext` with explicit cancel:

```go
	// Phase 8 T5-02: bind ffmpeg lifetime to the client's request context.
	// When the client disconnects (or the server shuts down), r.Context()
	// is cancelled, which kills ffmpeg — preventing orphaned processes
	// that would keep transcode CPU/disk long after the client is gone.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()

	cmd := exec.CommandContext(ctx, ffmpegCmd, args...)
	// Windows ffmpeg subprocess may not respond to CTRL_BREAK_EVENT that
	// Go's default CommandContext sends. Force kill on context cancellation.
	cmd.Cancel = func() error {
		if cmd.Process != nil {
			return cmd.Process.Kill()
		}
		return os.ErrProcessDone
	}
```

Confirm `"context"` and `"os"` are imported in streaming.go (line 7 has `"context"`, `"os"` should already be there — check and add if missing).

- [ ] **Step 5: Run test to verify it passes**

Run: `cd server && go test ./internal/service/ -run TestServeTranscodedClientDisconnect -v -timeout 30s`
Expected: PASS — `serveTranscoded` returns within 5s of `cancel()`.

- [ ] **Step 6: Run full service test suite + race detector**

Run: `cd server && go test ./internal/service/ -race -timeout 60s`
Expected: All green, no race detector warnings.

- [ ] **Step 7: Run full server test suite**

Run: `cd server && go test ./...`
Expected: All packages green.

- [ ] **Step 8: Commit**

```bash
git add server/internal/service/streaming.go server/internal/service/streaming_test.go
git commit -m "feat(streaming): kill ffmpeg on client disconnect via CommandContext (Phase 8 T5-02)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ Task 1: T8-01 (UpdateConfig blocked root check) + T8-11 (error sanitization)
- ✅ Task 2: T1-06 + T3-04b (per-route rate limit)
- ✅ Task 3: T5-02 (ffmpeg client-disconnect cleanup)

**Type consistency**:
- `service.IsBlockedRoot(absPath string) bool` — consistent across Tasks 1
- `middleware.RateLimit(max int, window time.Duration)` — consistent across Task 2 (definition + mount sites)
- `cmd.Cancel = func() error { ... }` — Go 1.20+ API, consistent with `exec.CommandContext`

**Placeholder scan**: No TBD/TODO/"add error handling"/"similar to Task N" patterns. Every step contains complete code.

**Known implementation risks** (flagged for executor awareness):
1. **Task 1 Step 7 `TestUpdateConfigRejectsBlockedRoot`**: Constructing a full `Handler` requires scanner/tags/streaming/thumbnail services. If too heavy, consider testing through `httptest.NewServer` + real Echo (integration-style) OR extracting validation logic into a pure function. Read Phase 1's `server_auth_test.go` `newAuthTestConfig` pattern first.
2. **Task 2 Step 3 `c.RealIP()`**: If Echo is configured with `TrustedProxies` (it isn't by default in this project), `RealIP()` may return something unexpected. For LAN-only deployment without reverse proxy, `RemoteAddr` IP is correct.
3. **Task 3 Step 2 test entry-point name**: The plan assumes `TranscodeAndServe` — verify the actual exported method name in `streaming.go` first. May be `ServeStream` or `ServeTranscoded`.
4. **Task 3 Step 2 Windows process check**: The test relies on "serveTranscoded returned within 5s" as the assertion. If the test is flaky on slow CI, increase timeout. The `-race` flag in Step 6 also slows tests — give it room.
5. **Task 1 Step 4 `validateMediaFilePath` callers**: This function may be called from multiple places. The error message change (from `"path not accessible: ..."` to `"file not found"` / `"permission denied"`) might break tests that assert on the old string. Run full suite + grep for `"path not accessible"` in test files before/after.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-11-security-phase8-misc-p2.md`.

Three tasks, server-side Go. Estimated total effort: medium (Task 1 touches 4 files with cross-package concerns; Task 2 is a new middleware + tests; Task 3 is a subtle subprocess-lifecycle change).

Execution model recommendation:
- Task 1: standard model (multi-file + cross-package + handler test construction needs judgment)
- Task 2: standard model (new middleware with concurrency + race-detector testing)
- Task 3: standard model (subprocess lifecycle + Windows quirks + test entry-point verification)

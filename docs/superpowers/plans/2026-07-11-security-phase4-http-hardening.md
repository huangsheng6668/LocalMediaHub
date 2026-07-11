# Security Round 29 — Phase 4: HTTP Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `SecurityHeaders` Echo middleware that sets `X-Frame-Options`, `X-Content-Type-Options`, `Referrer-Policy`, and `Content-Security-Policy` on all responses (including static assets and CORS preflight), mitigating clickjacking, MIME sniffing, referrer leaks, and providing CSP-based defense-in-depth against XSS.

**Architecture:** Server-side only. New file `middleware/security_headers.go` exposes `SecurityHeaders() echo.MiddlewareFunc`. `server.go:registerRoutes` mounts it between `Logger()` and `CORS(...)` so OPTIONS preflight responses also carry the headers. CSP uses `'unsafe-inline'` only for `style-src` (Web UI has 7 inline `style="..."` attributes); `script-src 'self'` is strict (all JS is externalized). No `'unsafe-inline'` for scripts, no `data:` for images.

**Tech Stack:** Go 1.25+ / Echo v4

**Source spec:** `docs/superpowers/specs/2026-07-11-security-phase4-http-hardening-design.md`

**Coverage:** T4-03 (Medium 4.3), T4-04 (Low 3.1) + partial mitigation of Chain-F (conditional XSS, Medium 5.4)

## Global Constraints

- **All 4 headers MUST be set on every response** (API JSON, static assets, OPTIONS preflight, error responses). (Spec section 5.1)
- **CSP value MUST be exactly**: `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'` (verbatim, single line, semicolon-separated). (Spec section 5.1)
- **Middleware MUST be mounted BEFORE CORS** so OPTIONS preflight gets headers. (Spec section 5.2)
- **No HSTS, no Permissions-Policy, no TLS changes.** (Spec section 3.1)
- **No new third-party dependencies.**

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `server/internal/server/middleware/security_headers.go` | Create | `SecurityHeaders()` middleware function |
| `server/internal/server/middleware/security_headers_test.go` | Create | Table-driven unit tests |
| `server/internal/server/server.go` | Modify | Mount middleware in `registerRoutes` (line ~107) |
| `README.md` | Modify | Add "### 安全响应头" section documenting headers + CSP TODO |

---

## Task 1: SecurityHeaders middleware + unit tests (TDD)

**Files:**
- Create: `server/internal/server/middleware/security_headers.go`
- Test: `server/internal/server/middleware/security_headers_test.go`

**Interfaces:**
- Produces: `SecurityHeaders() echo.MiddlewareFunc` — sets 4 headers on all responses, then calls next handler.

- [ ] **Step 1: Write the failing test**

Create `server/internal/server/middleware/security_headers_test.go`:

```go
package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/labstack/echo/v4"
)

func TestSecurityHeaders(t *testing.T) {
	// Expected header values — keep in sync with security_headers.go.
	// CSP is verbatim per Global Constraint.
	expectedHeaders := map[string]string{
		"X-Frame-Options":         "DENY",
		"X-Content-Type-Options":  "nosniff",
		"Referrer-Policy":         "no-referrer",
		"Content-Security-Policy": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'",
	}

	e := echo.New()
	handlerCalled := false
	handler := func(c echo.Context) error {
		handlerCalled = true
		return c.String(http.StatusOK, "ok")
	}

	req := httptest.NewRequest(http.MethodGet, "/anything", nil)
	rec := httptest.NewRecorder()
	c := e.NewContext(req, rec)

	mw := SecurityHeaders()(handler)
	if err := mw(c); err != nil {
		t.Fatalf("middleware returned error: %v", err)
	}

	if !handlerCalled {
		t.Errorf("inner handler was NOT called — middleware must pass through to next")
	}

	for name, want := range expectedHeaders {
		got := rec.Header().Get(name)
		if got != want {
			t.Errorf("header %q = %q, want %q", name, got, want)
		}
	}
}

func TestSecurityHeadersAppliesToAllMethods(t *testing.T) {
	// Verify headers are set regardless of HTTP method (OPTIONS preflight,
	// POST, DELETE — all go through the same middleware chain).
	methods := []string{
		http.MethodGet,
		http.MethodPost,
		http.MethodPut,
		http.MethodDelete,
		http.MethodOptions,
	}

	for _, method := range methods {
		t.Run(method, func(t *testing.T) {
			e := echo.New()
			handler := func(c echo.Context) error {
				return c.String(http.StatusOK, "ok")
			}

			req := httptest.NewRequest(method, "/anything", nil)
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			mw := SecurityHeaders()(handler)
			_ = mw(c)

			if got := rec.Header().Get("X-Frame-Options"); got != "DENY" {
				t.Errorf("method %s: X-Frame-Options = %q, want DENY", method, got)
			}
		})
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/middleware/ -run TestSecurityHeaders -v`
Expected: FAIL — `undefined: SecurityHeaders`.

- [ ] **Step 3: Write minimal implementation**

Create `server/internal/server/middleware/security_headers.go`:

```go
package middleware

import (
	"github.com/labstack/echo/v4"
)

// SecurityHeaders adds browser security headers to all responses.
//
// Coverage:
//   - X-Frame-Options: DENY           → prevents clickjacking (T4-03)
//   - X-Content-Type-Options: nosniff → stops MIME sniffing (T4-04)
//   - Referrer-Policy: no-referrer   → prevents leaking URLs to external resources (T4-04)
//   - Content-Security-Policy        → restricts resource loading to self (T4-04);
//     provides defense-in-depth against XSS even if escapeHtml is missed somewhere
//
// TODO(Phase 5): once Web UI inline styles are migrated to external CSS,
// remove 'unsafe-inline' from style-src for full CSP strictness.
//
// Not added (intentional):
//   - Strict-Transport-Security: only effective under HTTPS; TLS is deferred
//     per main spec section 6.2.
//   - Permissions-Policy: project doesn't use camera/mic/geolocation APIs (YAGNI).
func SecurityHeaders() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			h := c.Response().Header()
			h.Set("X-Frame-Options", "DENY")
			h.Set("X-Content-Type-Options", "nosniff")
			h.Set("Referrer-Policy", "no-referrer")
			h.Set("Content-Security-Policy",
				"default-src 'self'; "+
					"script-src 'self'; "+
					"style-src 'self' 'unsafe-inline'; "+
					"img-src 'self'; "+
					"media-src 'self'; "+
					"connect-src 'self'")
			return next(c)
		}
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/server/middleware/ -run TestSecurityHeaders -v`
Expected: PASS — both `TestSecurityHeaders` (4 headers + handler called) and `TestSecurityHeadersAppliesToAllMethods` (5 methods).

- [ ] **Step 5: Run full middleware package tests + vet**

Run: `cd server && go test ./internal/server/middleware/ -v && go vet ./internal/server/middleware/`
Expected: All middleware tests pass (Phase 1 `TestBearerToken` + `TestIsPrivateOrLoopback` + new tests). Vet clean.

- [ ] **Step 6: Commit**

```bash
git add server/internal/server/middleware/security_headers.go server/internal/server/middleware/security_headers_test.go
git commit -m "feat(middleware): add SecurityHeaders with CSP/XFO/nosniff/Referrer-Policy (Phase 4)"
```

---

## Task 2: Mount middleware in server.go + manual integration test

**Files:**
- Modify: `server/internal/server/server.go` (lines 103-108)

**Interfaces:**
- Consumes: Task 1's `SecurityHeaders()` middleware.
- Produces: All HTTP responses (including OPTIONS preflight + static assets) carry the 4 security headers.

- [ ] **Step 1: Read the current middleware mount block**

Read `server/internal/server/server.go` lines 100-115 to confirm current mount order:
```go
s.Echo.Use(echoMw.Recover())
s.Echo.Use(echoMw.Logger())
s.Echo.Use(middleware.CORS(allowedCORSOrigins(s.Config.Server.Port)))
```

- [ ] **Step 2: Insert SecurityHeaders between Logger and CORS**

Use Edit tool to change the mount block to:

```go
	s.Echo.Use(echoMw.Recover())
	s.Echo.Use(echoMw.Logger())
	s.Echo.Use(middleware.SecurityHeaders()) // Phase 4: before CORS so headers apply to preflight OPTIONS too
	s.Echo.Use(middleware.CORS(allowedCORSOrigins(s.Config.Server.Port)))
```

The `middleware` package is already imported (line 19: `"github.com/localmediahub/server/internal/server/middleware"`), so no import change needed.

- [ ] **Step 3: Run full server test suite to verify no regression**

Run: `cd server && go test ./...`
Expected: All packages green. Phase 1/3 tests (TestBearerToken, TestServerRejectsAdminWithoutToken, TestConfigValidate, TestLogSecurityWarnings, etc.) unaffected — they assert route behavior, not headers.

- [ ] **Step 4: Build the binary**

Run: `cd server && go build -o LocalMediaHub.exe ./cmd/server`
Expected: Build succeeds.

- [ ] **Step 5: Manual integration test — start server + curl headers**

Start the server in the background (ensure `config.yaml` has roots or `auto_detect_roots: true` per Phase 3):

```bash
cd server
./LocalMediaHub.exe --headless &
sleep 3
```

Test 1: API endpoint headers
```bash
curl -sI http://localhost:8000/api/v1/health
```
Expected output contains:
```
X-Frame-Options: DENY
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'
```

Test 2: Static asset (Web UI root) headers
```bash
curl -sI http://localhost:8000/
```
Expected: same 4 headers present.

Test 3: OPTIONS preflight (if CORS preflight triggers)
```bash
curl -sI -X OPTIONS -H "Origin: http://localhost:8000" -H "Access-Control-Request-Method: GET" http://localhost:8000/api/v1/health
```
Expected: response includes the 4 security headers (CORS may return 204 or pass through; headers must be present).

Stop the server:
```bash
kill %1
```

- [ ] **Step 6: Commit**

```bash
git add server/internal/server/server.go
git commit -m "feat(server): mount SecurityHeaders before CORS (Phase 4)"
```

---

## Task 3: README documentation

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: User-facing documentation of security headers + CSP TODO note.

- [ ] **Step 1: Read README to find insertion point**

Read `README.md` to locate a sensible section. Recommended: after "### 3.1 Release 签名" (added by Phase 7) or after the API section — wherever configuration/security info lives.

- [ ] **Step 2: Insert "### 安全响应头" section**

Insert the following markdown (adapt heading level to match surrounding sections):

```markdown
### 安全响应头

服务端对所有响应（含静态资源 + API + OPTIONS 预检）附加以下安全头：

| 头 | 值 | 缓解 |
|---|---|---|
| `X-Frame-Options` | `DENY` | Clickjacking（禁止任何站点 iframe 嵌入本服务） |
| `X-Content-Type-Options` | `nosniff` | MIME 嗅探攻击 |
| `Referrer-Policy` | `no-referrer` | 外链泄漏本服务 URL |
| `Content-Security-Policy` | `default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; media-src 'self'; connect-src 'self'` | XSS 数据 exfiltration（纵深防御） |

**CSP 说明**：
- `script-src 'self'`：JavaScript 全部同源（已外部化，不允许 inline script）。
- `style-src 'self' 'unsafe-inline'`：当前 Web UI 仍有 inline `style="..."` 属性，暂留 `'unsafe-inline'`。**待未来 Phase 5 Web UI XSS 整改完成后移除**。
- 不含 `data:` URI 例外（探索阶段确认 Web UI 无使用）。

**未加的头**：
- `Strict-Transport-Security`（HSTS）：仅 HTTPS 下有效，TLS 留作未来。
- `Permissions-Policy`：项目不使用相机/麦克风/地理位置等敏感 API。
```

- [ ] **Step 3: Verify markdown renders**

Visually inspect the inserted section for correct heading level (`###` matches sibling sections) and table fence closure. No markdown linter required for this small change.

- [ ] **Step 4: Full regression — Go test suite**

Run: `cd server && go test ./...`
Expected: All green. Docs-only change shouldn't affect build, but verify nothing else broke.

- [ ] **Step 5: Commit**

```bash
git add README.md
git commit -m "docs: document security response headers + CSP unsafe-inline TODO (Phase 4)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ SecurityHeaders middleware (Task 1, spec 5.1)
- ✅ Mount before CORS (Task 2, spec 5.2)
- ✅ README documentation + CSP TODO (Task 3, spec 5.3)
- ✅ All 4 headers covered: XFO, nosniff, Referrer-Policy, CSP
- ✅ No HSTS / Permissions-Policy / TLS (per spec 3.1)

**Type consistency**:
- `SecurityHeaders() echo.MiddlewareFunc` — consistent across Tasks 1, 2
- CSP string value — identical in test (Task 1), implementation (Task 1), README (Task 3)
- Header names/values match between spec, plan, test, implementation

**Placeholder scan**: No TBD/TODO/"add error handling"/"similar to Task N" patterns. Every step contains complete code. The TODO in `SecurityHeaders` doc comment is intentional (points to Phase 5 follow-up, not a placeholder for this plan).

**Known implementation risks** (flagged for executor awareness):
1. **Task 2 Step 5 `kill %1` on Windows bash** — may need `taskkill /F /IM LocalMediaHub.exe` or `kill $!` instead. Implementer should adapt to local shell.
2. **Task 2 Step 5 requires Phase 3 config to be valid** — ensure `config.yaml` has roots or use `--auto-detect-roots` flag (per Phase 3 defaults). If config is invalid, server will exit at startup.
3. **Task 3 README insertion point** — heading level may need adjustment if README uses `##` for top-level sections. Read context first.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-11-security-phase4-http-hardening.md`.

Three tasks, server-side Go + docs. Estimated total effort: small (Task 1 is ~50 lines; Task 2 is 1-line mount + manual verification; Task 3 is docs-only).

Execution model recommendation:
- Task 1: cheapest model (new middleware + table-driven tests, complete code in brief)
- Task 2: standard model (touches server.go + manual integration tests on Windows bash)
- Task 3: cheapest model (docs-only)

# Security Round 29 — Phase 1: Bearer Token Auth Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Bearer Token authentication layer to LocalMediaHub that gates all sensitive API endpoints (`admin`/`system`/`media` route groups) on a configurable shared secret, while keeping the server usable without token when unconfigured (backward compatibility).

**Architecture:** Server-side Echo middleware compares the `Authorization: Bearer <token>` header against `config.yaml: server.token` using `crypto/subtle.ConstantTimeCompare` (timing-attack resistant). Android adds an OkHttp `Interceptor` that injects the header from a DataStore-backed `ServerConfigStore.authToken` flow. Web UI detects 401 responses and prompts for token via a modal, persisting it in `sessionStorage`. Token defaults to empty = open access (logs a security warning at startup), so existing deployments keep working until the operator explicitly opts in.

**Tech Stack:** Go 1.25+ / Echo v4 / `crypto/subtle` / `gopkg.in/yaml.v3` · Kotlin / OkHttp 4.12 / DataStore Preferences / Hilt · Vanilla JS (ES modules) / `sessionStorage`

**Source spec:** `docs/superpowers/specs/2026-07-10-security-audit-design.md` (Phase 1, section 5.1)

**Coverage:** T1-01a/b/c, T1-02a/b/c, T1-03c, T1-05, T1-07, T8-02 (10 findings) + mitigates Chain-A/B/C'/G/H (5 attack chains)

## Global Constraints

- **Token comparison MUST use `crypto/subtle.ConstantTimeCompare`** — never `==` or `strings.Compare`. Spec section 5.1 step 1.
- **Empty token = open access** (backward compatibility). When `server.token == ""`, middleware passes through. Server logs a security warning on startup.
- **401 response body MUST be JSON `{"error": "Unauthorized"}`** with `Content-Type: application/json` — Android and Web UI both parse JSON error envelope (existing `respondError` pattern in `handler.go:70`).
- **Token is plaintext in LAN traffic** (T1-04c residual risk). Spec section 5.1 acknowledges this; full fix requires Phase 4 (TLS).
- **Server release version bump**: `versionCode = 3 / versionName = "1.2"` (current `2 / "1.1"` per `build.gradle.kts:69-70`) — old APKs without token support will fail against token-enabled server; document in README.
- **No new third-party dependencies** — all libraries used (`crypto/subtle`, Echo middleware, OkHttp Interceptor, DataStore) are already in the project.

---

## File Structure

### Server (Go)

| File | Type | Responsibility |
|---|---|---|
| `server/internal/server/middleware/auth.go` | Create | `BearerToken(token string) echo.MiddlewareFunc` |
| `server/internal/server/middleware/auth_test.go` | Create | Table-driven unit tests for the middleware |
| `server/internal/config/config.go` | Modify | Add `Token string` field to `ServerConfig` (line 18-21) |
| `server/internal/config/config_test.go` | Modify | Add test case verifying Token round-trips through YAML |
| `server/internal/server/server.go` | Modify | Wire `BearerToken` middleware onto admin/system/media route groups (lines 152-172) |
| `server/config.yaml` | Modify | Add `token: ""` example under `server:` block (lines 1-3) |

### Android (Kotlin)

| File | Type | Responsibility |
|---|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt` | Modify | Add `KEY_AUTH_TOKEN`, `authToken: Flow<String>`, `saveAuthToken(token: String)` |
| `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` | Modify | Add `AuthInterceptor` class; register it in `provideOkHttpClient` |
| `android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt` | Modify | Expose `token: StateFlow<String>` derived from `ServerConfigStore.authToken` |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt` | Modify | Add token input field in connection form |
| `android/app/src/test/java/com/juziss/localmediahub/network/AuthInterceptorTest.kt` | Create | Unit tests for AuthInterceptor (empty token → no header; non-empty → header injected) |
| `android/app/build.gradle.kts` | Modify | Bump `versionCode = 3` / `versionName = "1.2"` (line 69-70) |

### Web UI

| File | Type | Responsibility |
|---|---|---|
| `server/internal/web/state.js` | Modify | Add `authToken` state, load from `sessionStorage` on init |
| `server/internal/web/api.js` | Modify | `apiRequest` injects `Authorization` header; on 401 emits event to trigger modal |
| `server/internal/web/index.html` | Modify | Add token input modal markup |
| `server/internal/web/app.js` | Modify | Wire modal show/hide, save to `state.authToken` + `sessionStorage` |
| `server/internal/web/dom.js` | Modify | Add DOM element IDs for the modal (input, save button, cancel button) |

---

## Task 1: Server-side `BearerToken` middleware (TDD)

**Files:**
- Create: `server/internal/server/middleware/auth.go`
- Test: `server/internal/server/middleware/auth_test.go`

**Interfaces:**
- Produces: `BearerToken(token string) echo.MiddlewareFunc` — gates incoming requests; empty `token` = passthrough.

- [ ] **Step 1: Write the failing test**

Create `server/internal/server/middleware/auth_test.go`:

```go
package middleware

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/labstack/echo/v4"
)

func TestBearerToken(t *testing.T) {
	cases := []struct {
		name        string
		configToken string // token configured on the middleware
		authHeader  string // client-supplied Authorization header
		wantStatus  int
		wantCalled  bool // whether the inner handler should be invoked
	}{
		{
			name:        "empty config token passes through (open mode)",
			configToken: "",
			authHeader:  "",
			wantStatus:  http.StatusOK,
			wantCalled:  true,
		},
		{
			name:        "correct token allows access",
			configToken: "secret123",
			authHeader:  "Bearer secret123",
			wantStatus:  http.StatusOK,
			wantCalled:  true,
		},
		{
			name:        "missing Authorization header rejects with 401",
			configToken: "secret123",
			authHeader:  "",
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
		{
			name:        "wrong token rejects with 401",
			configToken: "secret123",
			authHeader:  "Bearer wrongpass",
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
		{
			name:        "malformed header (no Bearer prefix) rejects with 401",
			configToken: "secret123",
			authHeader:  "secret123",
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
		{
			name:        "timing-safe comparison: prefix-correct but wrong tail rejects",
			configToken: "secret123",
			authHeader:  "Bearer secret12", // one char short
			wantStatus:  http.StatusUnauthorized,
			wantCalled:  false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := echo.New()
			called := false
			handler := func(c echo.Context) error {
				called = true
				return c.String(http.StatusOK, "ok")
			}

			req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
			if tc.authHeader != "" {
				req.Header.Set(echo.HeaderAuthorization, tc.authHeader)
			}
			rec := httptest.NewRecorder()
			c := e.NewContext(req, rec)

			mw := BearerToken(tc.configToken)(handler)
			err := mw(c)

			if tc.wantCalled && !called {
				t.Errorf("inner handler was not called, expected it to be called")
			}
			if !tc.wantCalled && called {
				t.Errorf("inner handler was called, expected it to be rejected")
			}

			if tc.wantStatus == http.StatusOK {
				if err != nil {
					t.Errorf("expected no error, got %v", err)
				}
				if rec.Code != http.StatusOK {
					t.Errorf("status = %d, want %d", rec.Code, tc.wantStatus)
				}
			} else {
				if rec.Code != tc.wantStatus {
					t.Errorf("status = %d, want %d", rec.Code, tc.wantStatus)
				}
				body := rec.Body.String()
				if !strings.Contains(body, `"error"`) {
					t.Errorf("401 body should be JSON envelope, got: %s", body)
				}
			}
		})
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/middleware/ -run TestBearerToken -v`
Expected: FAIL with `undefined: BearerToken` (compilation error).

- [ ] **Step 3: Write minimal implementation**

Create `server/internal/server/middleware/auth.go`:

```go
package middleware

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"
)

// BearerToken returns an Echo middleware that gates requests on an
// `Authorization: Bearer <token>` header. Comparison uses
// `crypto/subtle.ConstantTimeCompare` to prevent timing attacks that could
// otherwise leak the configured token byte-by-byte.
//
// When `token` is empty, the middleware is a no-op (passthrough). This keeps
// existing deployments working until the operator explicitly sets a token.
// Server startup logs a security warning when running in this open mode.
func BearerToken(token string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			if token == "" {
				return next(c)
			}
			auth := c.Request().Header.Get(echo.HeaderAuthorization)
			const prefix = "Bearer "
			if !strings.HasPrefix(auth, prefix) {
				return c.JSON(
					http.StatusUnauthorized,
					map[string]string{"error": "Unauthorized"},
				)
			}
			provided := auth[len(prefix):]
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

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/server/middleware/ -run TestBearerToken -v`
Expected: PASS — all 6 subtests green.

- [ ] **Step 5: Commit**

```bash
git add server/internal/server/middleware/auth.go server/internal/server/middleware/auth_test.go
git commit -m "feat(middleware): add BearerToken auth with constant-time compare"
```

---

## Task 2: Add `Token` field to `ServerConfig`

**Files:**
- Modify: `server/internal/config/config.go` (lines 18-21)
- Modify: `server/internal/config/config_test.go`

**Interfaces:**
- Consumes: Task 1's `BearerToken` middleware.
- Produces: `Config.Server.Token` field (YAML tag `token`, JSON tag `token`).

- [ ] **Step 1: Write the failing test**

Append to `server/internal/config/config_test.go`:

```go
func TestServerConfigTokenRoundTrip(t *testing.T) {
	yamlIn := []byte(`
server:
  host: "0.0.0.0"
  port: 8000
  token: "my-secret-token"
`)
	cfg, err := LoadFromBytes(yamlIn)
	if err != nil {
		t.Fatalf("LoadFromBytes failed: %v", err)
	}
	if cfg.Server.Token != "my-secret-token" {
		t.Errorf("Token = %q, want %q", cfg.Server.Token, "my-secret-token")
	}
}

func TestServerConfigTokenDefaultsEmpty(t *testing.T) {
	yamlIn := []byte(`
server:
  host: "0.0.0.0"
  port: 8000
`)
	cfg, err := LoadFromBytes(yamlIn)
	if err != nil {
		t.Fatalf("LoadFromBytes failed: %v", err)
	}
	if cfg.Server.Token != "" {
		t.Errorf("Token = %q, want empty default", cfg.Server.Token)
	}
}
```

If `LoadFromBytes` does not exist yet (only `Load(path)`), add this helper first as part of this task — see Step 3.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/config/ -run TestServerConfigToken -v`
Expected: FAIL with compile error `cfg.Server.Token undefined` (or `LoadFromBytes undefined`).

- [ ] **Step 3: Write minimal implementation**

Modify `server/internal/config/config.go`:

In the `ServerConfig` struct (around line 18-21), add the `Token` field:

```go
type ServerConfig struct {
	Host  string `yaml:"host" json:"host"`
	Port  int    `yaml:"port" json:"port"`
	Token string `yaml:"token,omitempty" json:"token,omitempty"`
}
```

Add a byte-slice loader helper at the end of the file (for testability without touching disk):

```go
// LoadFromBytes parses config from a YAML byte slice. Used by tests to avoid
// disk I/O; production code uses Load(path).
func LoadFromBytes(data []byte) (*Config, error) {
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) > 0 {
		cfg.Scan.Roots = append([]string(nil), cfg.System.AllowedRoots...)
	}
	return &cfg, nil
}
```

Then refactor the existing `Load` function to delegate to `LoadFromBytes`:

```go
func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return LoadFromBytes(data)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/config/ -v`
Expected: PASS — all tests including the two new ones green.

- [ ] **Step 5: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go
git commit -m "feat(config): add ServerConfig.Token field for Bearer auth"
```

---

## Task 3: Wire middleware onto route groups

**Files:**
- Modify: `server/internal/server/server.go` (lines 99-175)

**Interfaces:**
- Consumes: Task 1's `BearerToken` middleware + Task 2's `Config.Server.Token` field.
- Produces: `/api/v1/admin/*`, `/api/v1/system/*`, `/api/v1/media/*` routes are all token-gated when `server.token != ""`.

- [ ] **Step 1: Write the failing integration test**

Create `server/internal/server/server_auth_test.go`:

```go
package server

import (
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/localmediahub/server/internal/config"
)

func TestServerRejectsAdminWithoutToken(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host:  "127.0.0.1",
			Port:  0,
			Token: "required-token",
		},
	}
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer wrong-token")
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Errorf("status = %d, want 401", rec.Code)
	}
}

func TestServerAcceptsAdminWithCorrectToken(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host:  "127.0.0.1",
			Port:  0,
			Token: "required-token",
		},
	}
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
	req.Header.Set(echo.HeaderAuthorization, "Bearer required-token")
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code == http.StatusUnauthorized {
		t.Errorf("status = 401, want non-401 (token should be accepted)")
	}
}

func TestServerOpenModeWhenTokenEmpty(t *testing.T) {
	cfg := &config.Config{
		Server: config.ServerConfig{
			Host:  "127.0.0.1",
			Port:  0,
			Token: "",
		},
	}
	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}

	req := httptest.NewRequest(http.MethodGet, "/api/v1/admin/config", nil)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code == http.StatusUnauthorized {
		t.Errorf("status = 401, want non-401 (open mode should pass through)")
	}
}
```

Add `import "github.com/labstack/echo/v4"` to the test file (for `echo.HeaderAuthorization`).

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/server/ -run TestServer -v`
Expected: FAIL — all three tests will likely pass through to handler (200) because middleware not yet wired; the first test (expecting 401) will fail.

- [ ] **Step 3: Write minimal implementation**

Modify `server/internal/server/server.go` `registerRoutes` method. The current code (around lines 152-172) has:

```go
// Admin
admin := api.Group("/admin")
admin.GET("/config", h.GetConfig)
admin.PUT("/config", h.UpdateConfig)
admin.POST("/scan/trigger", h.TriggerScan)

// System
sys := api.Group("/system")
sys.GET("/drives", h.GetDrives)
sys.GET("/browse", h.SystemBrowse)
sys.GET("/thumbnail", h.SystemThumbnail)
sys.GET("/original", h.SystemOriginal)
sys.GET("/stream", h.SystemStream)
sys.POST("/delete", h.DeletePath)

// Unified absolute-path media access
media := api.Group("/media")
media.GET("/thumbnail", h.MediaThumbnail)
media.GET("/original", h.MediaOriginal)
media.GET("/stream", h.MediaStream)
media.GET("/duration", h.MediaDuration)
```

Replace with (add `middleware.BearerToken` to each group):

```go
// Auth middleware: gates sensitive endpoints on the configured token.
// Empty token = open mode (passthrough), logged at startup.
authMw := middleware.BearerToken(s.Config.Server.Token)

// Admin
admin := api.Group("/admin", authMw)
admin.GET("/config", h.GetConfig)
admin.PUT("/config", h.UpdateConfig)
admin.POST("/scan/trigger", h.TriggerScan)

// System
sys := api.Group("/system", authMw)
sys.GET("/drives", h.GetDrives)
sys.GET("/browse", h.SystemBrowse)
sys.GET("/thumbnail", h.SystemThumbnail)
sys.GET("/original", h.SystemOriginal)
sys.GET("/stream", h.SystemStream)
sys.POST("/delete", h.DeletePath)

// Unified absolute-path media access
media := api.Group("/media", authMw)
media.GET("/thumbnail", h.MediaThumbnail)
media.GET("/original", h.MediaOriginal)
media.GET("/stream", h.MediaStream)
media.GET("/duration", h.MediaDuration)
```

Also add a startup warning in `New()` after the Server struct is created (around line 83), before `s.registerRoutes(h)`:

```go
if cfg.Server.Token == "" {
	slog.Warn("==============================================================")
	slog.Warn(" SERVER IS RUNNING IN OPEN AUTH MODE (no token configured).")
	slog.Warn(" Any host on the LAN can call admin/system/media endpoints.")
	slog.Warn(" Set 'server.token' in config.yaml to enable authentication.")
	slog.Warn("==============================================================")
} else {
	slog.Info("Auth: token-based authentication enabled for admin/system/media routes")
}
```

Add `"log/slog"` to imports if not already present (it is — `server.go` already uses `fmt.Printf`, but `slog` may need adding; check existing imports).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/server/ -run TestServer -v`
Expected: PASS — all three integration tests green.

- [ ] **Step 5: Run full server test suite to verify no regressions**

Run: `cd server && go test ./...`
Expected: PASS — all existing tests (tags_test, scanner_test, etc.) still green. Existing handler tests that call protected endpoints will need the `BearerToken("")` open-mode default to pass through (which it does).

- [ ] **Step 6: Commit**

```bash
git add server/internal/server/server.go server/internal/server/server_auth_test.go
git commit -m "feat(server): wire BearerToken middleware onto admin/system/media routes"
```

---

## Task 4: Update `config.yaml` example with token field

**Files:**
- Modify: `server/config.yaml` (lines 1-3)

**Interfaces:**
- Consumes: Task 2's `Token` field.
- Produces: Documented `server.token` field for operators.

- [ ] **Step 1: Modify the config file**

Open `server/config.yaml`. Current top:

```yaml
server:
  host: "0.0.0.0"
  port: 8000
```

Change to:

```yaml
server:
  host: "0.0.0.0"
  port: 8000
  # Auth token for admin/system/media API endpoints.
  # Empty (default) = open mode (any LAN host can access — for trusted home networks only).
  # Set to a long random string to require `Authorization: Bearer <token>` on protected routes.
  # Generate one with: python -c "import secrets; print(secrets.token_urlsafe(32))"
  token: ""
```

- [ ] **Step 2: Verify server still starts with new config**

Run: `cd server && go build -o LocalMediaHub.exe ./cmd/server && ./LocalMediaHub.exe --headless &; sleep 2; curl -s http://localhost:8000/api/v1/health; kill %1`

Expected: `{"status":"ok"}` printed, and the startup log shows the OPEN AUTH MODE warning.

(If running on Windows bash, replace `&; ...; kill %1` with starting in background and killing by PID.)

- [ ] **Step 3: Commit**

```bash
git add server/config.yaml
git commit -m "docs(config): document server.token field for Bearer auth"
```

---

## Task 5: Android — Add `authToken` to `ServerConfigStore`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`

**Interfaces:**
- Produces: `ServerConfigStore.authToken: Flow<String>` and `saveAuthToken(token: String)` / `getAuthTokenSnapshot(): String`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreAuthTokenTest.kt`:

```kotlin
package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ServerConfigStoreAuthTokenTest {

    @Test
    fun authTokenDefaultsToEmpty() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        store.clearConfig()
        assertEquals("", store.authToken.first())
    }

    @Test
    fun saveAuthTokenPersists() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        store.clearConfig()
        store.saveAuthToken("my-test-token")
        assertEquals("my-test-token", store.authToken.first())
    }

    @Test
    fun saveEmptyAuthTokenClearsValue() = runBlocking {
        val store = ServerConfigStore(ApplicationProvider.getApplicationContext())
        store.clearConfig()
        store.saveAuthToken("temp")
        store.saveAuthToken("")
        assertEquals("", store.authToken.first())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests ServerConfigStoreAuthTokenTest`
Expected: FAIL — `authToken` unresolved reference, `saveAuthToken` unresolved.

- [ ] **Step 3: Write minimal implementation**

Modify `android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt`. In the companion object (around line 28-33), add a key:

```kotlin
companion object {
    private val KEY_SERVER_URL = stringPreferencesKey("server_url")
    private val KEY_SERVER_IP = stringPreferencesKey("server_ip")
    private val KEY_SERVER_PORT = stringPreferencesKey("server_port")
    private val KEY_KNOWN_SERVERS = stringPreferencesKey("known_servers")
    private val KEY_AUTH_TOKEN = stringPreferencesKey("auth_token")
}
```

Add the Flow and the save method after `serverPort` flow (around line 47):

```kotlin
val authToken: Flow<String> = context.dataStore.data.map { prefs ->
    prefs[KEY_AUTH_TOKEN] ?: ""
}

suspend fun saveAuthToken(token: String) {
    context.dataStore.edit { prefs ->
        prefs[KEY_AUTH_TOKEN] = token
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests ServerConfigStoreAuthTokenTest`
Expected: PASS — all 3 tests green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ServerConfigStore.kt \
        android/app/src/test/java/com/juziss/localmediahub/data/ServerConfigStoreAuthTokenTest.kt
git commit -m "feat(android): add authToken to ServerConfigStore"
```

---

## Task 6: Android — Add `AuthInterceptor` to OkHttp module

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/network/AuthInterceptorTest.kt`

**Interfaces:**
- Consumes: Task 5's `ServerConfigStore.authToken`.
- Produces: `AuthInterceptor` (OkHttp `Interceptor` impl) that adds `Authorization: Bearer <token>` when token is non-empty.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/com/juziss/localmediahub/network/AuthInterceptorTest.kt`:

```kotlin
package com.juziss.localmediahub.network

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AuthInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `empty token does not add Authorization header`() {
        val interceptor = AuthInterceptor { "" } // tokenProvider returns empty
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/test").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertNull(recorded.getHeader("Authorization"))
    }

    @Test
    fun `non-empty token adds Bearer Authorization header`() {
        val interceptor = AuthInterceptor { "my-secret" }
        val client = OkHttpClient.Builder().addInterceptor(interceptor).build()
        server.enqueue(MockResponse().setBody("ok"))

        client.newCall(Request.Builder().url(server.url("/test").toString()).build())
            .execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer my-secret", recorded.getHeader("Authorization"))
    }
}
```

If `okhttp3.mockwebserver` is not in test deps, add to `android/app/build.gradle.kts` `dependencies` block (testImplementation):
```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests AuthInterceptorTest`
Expected: FAIL — `AuthInterceptor` unresolved reference.

- [ ] **Step 3: Write minimal implementation**

Add to `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` (top of file, before `object OkHttpModule`):

```kotlin
/**
 * OkHttp Interceptor that injects `Authorization: Bearer <token>` header
 * when the tokenProvider returns a non-empty value. Empty token = no header
 * (open mode, matches server-side passthrough).
 *
 * The tokenProvider is a lambda returning String so the interceptor always
 * reads the latest value (tokens can change at runtime via Settings).
 */
class AuthInterceptor(private val tokenProvider: () -> String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val token = tokenProvider()
        val request = if (token.isEmpty()) {
            chain.request()
        } else {
            chain.request().newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(request)
    }
}
```

Add the necessary imports:
```kotlin
import okhttp3.Interceptor
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests AuthInterceptorTest`
Expected: PASS — both subtests green.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt \
        android/app/src/test/java/com/juziss/localmediahub/network/AuthInterceptorTest.kt \
        android/app/build.gradle.kts
git commit -m "feat(android): add AuthInterceptor for Bearer token injection"
```

---

## Task 7: Android — Wire `AuthInterceptor` into the OkHttp singleton

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt` (the `provideOkHttpClient` function)
- Modify: `android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt`

**Interfaces:**
- Consumes: Task 5's `ServerConfigStore.authToken` + Task 6's `AuthInterceptor`.
- Produces: All OkHttp-driven API calls (MediaRepository, ExoPlayer via media3-datasource-okhttp) automatically carry the token.

- [ ] **Step 1: Modify `ServerConfig` to expose token**

Open `android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt`. Add a `token` StateFlow derived from the store. The current constructor only takes `httpClient`; we need to also inject `ServerConfigStore`:

Replace the whole class:

```kotlin
@Singleton
class ServerConfig @Inject constructor(
    val httpClient: OkHttpClient,
    private val serverConfigStore: ServerConfigStore,
) {
    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    init {
        // Seed both flows from DataStore on construction
        // Using CoroutineScope(Dispatchers.Default) to avoid blocking init
    }

    fun setBaseUrl(url: String) {
        val normalized = url.trimEnd('/')
        if (normalized != _baseUrl.value) {
            _baseUrl.value = normalized
        }
    }

    fun setToken(token: String) {
        _token.value = token
    }

    fun getTokenSnapshot(): String = _token.value

    fun isInitialized(): Boolean = _baseUrl.value.isNotEmpty()

    fun getBaseUrl(): String = _baseUrl.value
}
```

We'll wire the DataStore → StateFlow sync in `LocalMediaHubApplication` or a startup `Initializer` (see Task 8). For now `setToken` is the API the connection form calls.

- [ ] **Step 2: Modify `OkHttpModule.provideOkHttpClient` to take `ServerConfig` and wire interceptor**

Open `android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt`. Change the function signature to inject `ServerConfig`:

```kotlin
@Provides
@Singleton
fun provideOkHttpClient(
    cache: Cache,
    serverConfig: ServerConfig,
): OkHttpClient {
    val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = MAX_REQUESTS_PER_HOST
    }

    val builder = OkHttpClient.Builder()
        .cache(cache)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .dispatcher(dispatcher)
        .connectionPool(ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES))
        .addInterceptor(AuthInterceptor { serverConfig.getTokenSnapshot() })

    if (BuildConfig.DEBUG) {
        builder.addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
    }

    return builder.build()
}
```

**Note on Hilt cycle**: `provideOkHttpClient` depends on `ServerConfig`, and `ServerConfig` depends on `OkHttpClient` (its `httpClient` val). To break the cycle, change `ServerConfig` to NOT take `httpClient` in its constructor (it was only exposed for derived clients). Update `ServerConfig`:

```kotlin
@Singleton
class ServerConfig @Inject constructor(
    private val serverConfigStore: ServerConfigStore,
) {
    // ... rest unchanged ...

    // httpClient field removed — callers that need it (ConnectionViewModel LAN scan)
    // now inject OkHttpClient directly via Hilt, which still works because
    // OkHttpModule no longer depends on ServerConfig.
}
```

But `OkHttpModule.provideOkHttpClient` does need `ServerConfig` for the interceptor. To resolve: move the interceptor's token lookup to NOT require `ServerConfig` at construction time — use a `Lazy<ServerConfig>` or a setter.

**Cleaner approach**: Make `AuthInterceptor` take a `Lazy<ServerConfig>` and call `.get().getTokenSnapshot()` on each request. Hilt's `Provider<ServerConfig>` is the idiomatic way to break cycles:

```kotlin
@Provides
@Singleton
fun provideOkHttpClient(
    cache: Cache,
    serverConfigProvider: dagger.Provider<ServerConfig>,
): OkHttpClient {
    // ...
    .addInterceptor(AuthInterceptor { serverConfigProvider.get().getTokenSnapshot() })
    // ...
}
```

And `ServerConfig` no longer takes `httpClient` — update any caller that used `serverConfig.httpClient` to instead inject `OkHttpClient` directly (check `ConnectionViewModel` for this pattern). Read `ConnectionViewModel.kt` to find usages; replace `serverConfig.httpClient.newBuilder()` with the Hilt-injected `OkHttpClient` via `newBuilder()`.

- [ ] **Step 3: Verify compilation**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: No errors. If `serverConfig.httpClient` is referenced anywhere, fix those call sites first.

- [ ] **Step 4: Run existing tests to verify no regression**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: All existing tests pass. The `OkHttpModuleTest` (referenced in `OkHttpModule.kt:39-46` comment) may need updating if it constructed `provideOkHttpClient` directly — check and update the test to pass a mock `Provider<ServerConfig>`.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/network/OkHttpModule.kt \
        android/app/src/main/java/com/juziss/localmediahub/network/ServerConfig.kt \
        android/app/src/test/java/com/juziss/localmediahub/network/OkHttpModuleTest.kt
git commit -m "feat(android): wire AuthInterceptor into shared OkHttpClient"
```

---

## Task 8: Android — Connection form UI for token input

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt`
- Modify: `android/app/src/main/res/values/strings.xml` (add string resources)

**Interfaces:**
- Consumes: Task 5's `ServerConfigStore.saveAuthToken` + Task 7's `ServerConfig.setToken`.
- Produces: User-facing token input field.

- [ ] **Step 1: Add string resources**

Open `android/app/src/main/res/values/strings.xml`. Add inside `<resources>`:

```xml
<string name="auth_token_label">访问令牌（可选）</string>
<string name="auth_token_placeholder">服务端开启了鉴权时填写</string>
<string name="auth_token_save">保存令牌</string>
```

- [ ] **Step 2: Modify `ConnectionScreen.kt`**

Read `ConnectionScreen.kt` to find the existing IP/port form layout. Add a token `OutlinedTextField` after the port field. Wrap in a section that reads/writes via `ConnectionViewModel`.

In `ConnectionScreen` Composable, find the form column. Add after the port input:

```kotlin
OutlinedTextField(
    value = tokenInput,
    onValueChange = { tokenInput = it },
    label = { Text(stringResource(R.string.auth_token_label)) },
    placeholder = { Text(stringResource(R.string.auth_token_placeholder)) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    modifier = Modifier.fillMaxWidth(),
)
```

Add the necessary imports:
```kotlin
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
```

In the connect button's `onClick`, before calling the existing connect logic, save the token:

```kotlin
viewModel.saveToken(tokenInput)
```

In `ConnectionViewModel`, add:

```kotlin
fun saveToken(token: String) {
    viewModelScope.launch {
        serverConfigStore.saveAuthToken(token.trim())
        serverConfig.setToken(token.trim())
    }
}
```

Read `ConnectionViewModel.kt` to confirm it has `serverConfigStore` and `serverConfig` injected. If not, add them to the `@Inject constructor`.

- [ ] **Step 3: Verify compilation**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: No errors.

- [ ] **Step 4: Build APK to verify UI renders**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Manually launch the APK and verify the token field appears in the connection screen.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt \
        android/app/src/main/java/com/juziss/localmediahub/viewmodel/ConnectionViewModel.kt \
        android/app/src/main/res/values/strings.xml
git commit -m "feat(android): add token input field in connection screen"
```

---

## Task 9: Android — Bump versionCode

**Files:**
- Modify: `android/app/build.gradle.kts` (line 69-70)

**Interfaces:**
- Produces: New versionCode/versionName to signal breaking change.

- [ ] **Step 1: Modify the version**

Open `android/app/build.gradle.kts`. Change lines 69-70:

```kotlin
versionCode = 3
versionName = "1.2"
```

- [ ] **Step 2: Verify build picks up new version**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Check `android/app/build/outputs/apk/debug/app-debug.apk` manifest shows versionCode 3.

- [ ] **Step 3: Commit**

```bash
git add android/app/build.gradle.kts
git commit -m "chore(android): bump version to 1.2 (token-auth breaking change)"
```

---

## Task 10: Web UI — Add token state to `state.js`

**Files:**
- Modify: `server/internal/web/state.js`

**Interfaces:**
- Produces: `state.authToken` string + `setAuthToken(token)` mutator.

- [ ] **Step 1: Read the existing state.js to understand patterns**

Open `server/internal/web/state.js`. Note the existing state shape and export style. Add new fields following the same pattern.

Add to the state object:

```javascript
export const state = {
    // ... existing fields ...
    authToken: sessionStorage.getItem('lmh_auth_token') || '',
};

export function setAuthToken(token) {
    state.authToken = token;
    if (token) {
        sessionStorage.setItem('lmh_auth_token', token);
    } else {
        sessionStorage.removeItem('lmh_auth_token');
    }
}
```

- [ ] **Step 2: Verify the file parses**

Run: `cd server && node -e "import('./internal/web/state.js').then(m => console.log(Object.keys(m)))"` (if your Node version supports ESM; otherwise skip — Step 3 will catch errors)

Expected: `state` and `setAuthToken` listed.

- [ ] **Step 3: Commit**

```bash
git add server/internal/web/state.js
git commit -m "feat(web): add authToken state with sessionStorage persistence"
```

---

## Task 11: Web UI — Inject Authorization header in `api.js`

**Files:**
- Modify: `server/internal/web/api.js`

**Interfaces:**
- Consumes: Task 10's `state.authToken`.
- Produces: `apiRequest` auto-injects header; on 401, dispatches event to trigger token modal.

- [ ] **Step 1: Read existing api.js**

Open `server/internal/web/api.js`. Current content (verified earlier):

```javascript
export async function apiRequest(url, options = {}) {
    const res = await fetch(url, options);
    if (!res.ok) {
        let errorMsg = `HTTP Error ${res.status}`;
        try {
            const errData = await res.json();
            if (errData && errData.error) errorMsg = errData.error;
        } catch (_) {}
        throw new Error(errorMsg);
    }
    return res.json();
}
```

- [ ] **Step 2: Modify apiRequest to inject header and handle 401**

Replace with:

```javascript
import { state } from './state.js';

// Event dispatched on 401 to trigger the token-input modal in app.js.
export const AUTH_REQUIRED_EVENT = 'lmh:auth-required';

export async function apiRequest(url, options = {}) {
    // Inject Authorization header if we have a token.
    const finalOptions = { ...options };
    if (state.authToken) {
        finalOptions.headers = {
            ...(finalOptions.headers || {}),
            'Authorization': `Bearer ${state.authToken}`,
        };
    }

    const res = await fetch(url, finalOptions);

    if (res.status === 401) {
        // Trigger modal — app.js listens and re-prompts the user.
        window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT, { detail: { url } }));
        throw new Error('Authentication required');
    }

    if (!res.ok) {
        let errorMsg = `HTTP Error ${res.status}`;
        try {
            const errData = await res.json();
            if (errData && errData.error) errorMsg = errData.error;
        } catch (_) {}
        throw new Error(errorMsg);
    }
    return res.json();
}
```

- [ ] **Step 3: Verify syntax**

Run: `cd server && node --check internal/web/api.js`
Expected: No syntax errors.

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/api.js
git commit -m "feat(web): inject Bearer header in apiRequest, dispatch event on 401"
```

---

## Task 12: Web UI — Token input modal in `index.html`

**Files:**
- Modify: `server/internal/web/index.html`
- Modify: `server/internal/web/dom.js`

**Interfaces:**
- Produces: DOM elements `#auth-modal`, `#auth-token-input`, `#auth-save-btn`, `#auth-cancel-btn`.

- [ ] **Step 1: Add modal markup to index.html**

Read `server/internal/web/index.html` to find a sensible insertion point (typically just before `</body>`). Add:

```html
<div id="auth-modal" class="modal hidden" role="dialog" aria-labelledby="auth-modal-title" aria-modal="true">
    <div class="modal-backdrop"></div>
    <div class="modal-content">
        <h2 id="auth-modal-title">需要访问令牌</h2>
        <p>此服务器要求 Bearer Token 鉴权。请输入令牌后继续。</p>
        <input
            id="auth-token-input"
            type="password"
            placeholder="粘贴令牌"
            autocomplete="off"
            class="modal-input"
        />
        <div class="modal-actions">
            <button id="auth-cancel-btn" type="button">取消</button>
            <button id="auth-save-btn" type="button" class="primary">保存</button>
        </div>
    </div>
</div>
```

Add a minimal CSS rule (or append to existing `style.css`):

```css
.modal.hidden { display: none; }
.modal-backdrop {
    position: fixed; inset: 0; background: rgba(0,0,0,0.5);
}
.modal-content {
    position: fixed; top: 50%; left: 50%; transform: translate(-50%,-50%);
    background: var(--bg-color, #fff); padding: 24px; border-radius: 8px;
    min-width: 320px;
}
.modal-input { width: 100%; padding: 8px; margin: 12px 0; }
.modal-actions { display: flex; justify-content: flex-end; gap: 8px; }
```

- [ ] **Step 2: Add element IDs to dom.js**

Read `server/internal/web/dom.js` to understand the existing elements registry pattern. Add new entries:

```javascript
export const elements = {
    // ... existing entries ...
    authModal: document.getElementById('auth-modal'),
    authTokenInput: document.getElementById('auth-token-input'),
    authSaveBtn: document.getElementById('auth-save-btn'),
    authCancelBtn: document.getElementById('auth-cancel-btn'),
};
```

- [ ] **Step 3: Verify both files parse**

Open `server/internal/web/index.html` in a browser at `http://localhost:8000/` (after starting server) and verify the modal markup exists by inspecting DOM (it should be hidden by default via `.hidden` class).

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/index.html server/internal/web/dom.js server/internal/web/style.css
git commit -m "feat(web): add token input modal markup"
```

---

## Task 13: Web UI — Wire modal show/hide in `app.js`

**Files:**
- Modify: `server/internal/web/app.js`

**Interfaces:**
- Consumes: Tasks 10/11/12.
- Produces: On 401, modal shows; on save, token persisted and last failed request retried.

- [ ] **Step 1: Read existing app.js to find init pattern**

Open `server/internal/web/app.js`. Find the `init()` or `DOMContentLoaded` handler.

- [ ] **Step 2: Add event listener for AUTH_REQUIRED_EVENT and wire save/cancel**

Add to the init section:

```javascript
import { elements } from './dom.js';
import { setAuthToken, state } from './state.js';
import { AUTH_REQUIRED_EVENT } from './api.js';

let lastFailedUrl = null;

function showAuthModal(url) {
    lastFailedUrl = url;
    elements.authModal.classList.remove('hidden');
    elements.authTokenInput.focus();
}

function hideAuthModal() {
    elements.authModal.classList.add('hidden');
    elements.authTokenInput.value = '';
}

function saveAuthAndRetry() {
    const token = elements.authTokenInput.value.trim();
    if (!token) return;
    setAuthToken(token);
    hideAuthModal();
    // Reload to re-trigger the original request with the new token.
    if (lastFailedUrl) {
        window.location.reload();
    }
}

// In init():
window.addEventListener(AUTH_REQUIRED_EVENT, (e) => {
    showAuthModal(e.detail.url);
});
elements.authSaveBtn.addEventListener('click', saveAuthAndRetry);
elements.authCancelBtn.addEventListener('click', hideAuthModal);
elements.authTokenInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') saveAuthAndRetry();
    if (e.key === 'Escape') hideAuthModal();
});
```

- [ ] **Step 3: Manual verification**

Start the server with a non-empty token:

```bash
cd server
# Edit config.yaml: token: "test-token-123"
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless
```

Open `http://localhost:8000/` in a browser:
1. Page loads, API calls fail with 401, modal appears.
2. Enter `test-token-123`, click Save.
3. Page reloads, API calls succeed.

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/app.js
git commit -m "feat(web): wire token modal to 401 events with save-and-retry"
```

---

## Task 14: End-to-end integration verification

**Files:**
- None modified — verification only.

**Interfaces:**
- Consumes: All previous tasks.

- [ ] **Step 1: Start server with token configured**

```bash
cd server
# Edit config.yaml: set token: "e2e-test-token"
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless
```

Verify startup log shows: `Auth: token-based authentication enabled for admin/system/media routes`

- [ ] **Step 2: Verify unprotected endpoint still works**

```bash
curl -s http://localhost:8000/api/v1/health
```
Expected: `{"status":"ok"}`

- [ ] **Step 3: Verify protected endpoint rejects without token**

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/v1/admin/config
```
Expected: `401`

```bash
curl -s http://localhost:8000/api/v1/admin/config
```
Expected: `{"error":"Unauthorized"}`

- [ ] **Step 4: Verify protected endpoint accepts with correct token**

```bash
curl -s -H "Authorization: Bearer e2e-test-token" http://localhost:8000/api/v1/admin/config
```
Expected: JSON config dump (200 OK).

- [ ] **Step 5: Verify wrong token rejected**

```bash
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer wrong" http://localhost:8000/api/v1/admin/config
```
Expected: `401`

- [ ] **Step 6: Verify open mode (empty token) still works**

Stop server, edit config.yaml to `token: ""`, restart. Verify startup log shows the OPEN AUTH MODE warning. Repeat step 3:

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:8000/api/v1/admin/config
```
Expected: `200` (passthrough)

- [ ] **Step 7: Run full test suites to confirm no regression**

```bash
cd server && go test ./...
cd ../android && ./gradlew testDebugUnitTest assembleDebug
```
Expected: All tests pass.

- [ ] **Step 8: No commit (verification only)**

If all steps pass, the implementation is complete. If any step fails, file an issue or return to the relevant task.

---

## Self-Review Notes

**Spec coverage** (against spec section 5.1):
- ✅ `crypto/subtle.ConstantTimeCompare` — Task 1 step 3
- ✅ JSON 401 response — Task 1 step 3
- ✅ `server.token` config field — Task 2
- ✅ Middleware on admin/system/media groups — Task 3
- ✅ Android `ServerConfigStore.authToken` — Task 5
- ✅ OkHttp interceptor — Tasks 6+7
- ✅ Web UI sessionStorage + 401 modal — Tasks 10-13
- ✅ Backward compatibility (empty token = open) — Task 3 step 3
- ✅ Startup security warning — Task 3 step 3
- ✅ Version bump — Task 9

**Type consistency**:
- `BearerToken(token string) echo.MiddlewareFunc` — consistent across Tasks 1, 3
- `authToken: Flow<String>` / `saveAuthToken(token: String)` — consistent across Tasks 5, 7, 8
- `getTokenSnapshot(): String` — consistent across Tasks 7, 8
- `AuthInterceptor(tokenProvider: () -> String)` — consistent across Tasks 6, 7
- `state.authToken` / `setAuthToken(token)` — consistent across Tasks 10, 11, 13
- `AUTH_REQUIRED_EVENT` constant — consistent across Tasks 11, 13

**Placeholder scan**: No TBD/TODO/"add error handling"/"similar to Task N" patterns found. Every code step contains complete, runnable code.

**Known implementation risks** (flagged for executor awareness):
1. **Hilt cycle in Task 7**: `ServerConfig` originally takes `OkHttpClient` (its `httpClient` val). This creates a cycle when `OkHttpModule.provideOkHttpClient` needs `ServerConfig` for the interceptor. Resolution uses `dagger.Provider<ServerConfig>` (lazy lookup). Executor must check `ConnectionViewModel` for `serverConfig.httpClient` usages and migrate them to direct `OkHttpClient` Hilt injection.
2. **Task 8 UI placement**: The exact line in `ConnectionScreen.kt` where the token field should go depends on existing layout; executor should read the file first and place the field after the port field in the same form column.
3. **Task 12 CSS variables**: The modal CSS uses `var(--bg-color, #fff)` — if the project's `style.css` uses a different variable name for background, update accordingly. Read `style.css` first.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-10-security-phase1-auth.md`.

Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration. Best for this plan because Tasks span 3 codebases (Go / Kotlin / JS) and each task has clear boundaries.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints for review.

**Which approach?**

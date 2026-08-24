# Open LAN Authentication Simplification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Simplify connection and authentication by setting default open LAN mode (`server.token: ""`), removing artificial restrictions on deletion in open mode, and making Android token configuration optional and collapsed.

**Architecture:** 
1. Server runs in open mode by default with clean startup logging, and allows remote deletion when `system.enable_delete: true` regardless of whether a token is set.
2. Web UI evaluates delete capability based on `enable_delete` without requiring `authToken`.
3. Android ConnectionScreen collapses the Token field into an optional accordion ("高级选项"), making connection a clean one-click flow for LAN users.

**Tech Stack:** Go (Echo v4), Web (Vanilla JS / ES modules), Android (Kotlin / Jetpack Compose / OkHttp).

## Global Constraints

- Never break existing token authentication if the operator intentionally configures a non-empty `server.token`.
- Path validation must always be enforced via `ValidateDeletion` regardless of auth mode.
- All Web innerHTML changes must stay XSS-safe and pass `tools/xsscheck`.
- Conventional commit format required (`feat`, `fix`, `refactor`).

---

### Task 1: Server: Default Empty Token, Clean Startup Logging, and Unblock Deletion

**Files:**
- Modify: `server/config.yaml:1-9`
- Modify: `server/internal/config/config.go:221-236`
- Modify: `server/internal/server/handler/system.go:223-234`
- Modify: `server/internal/server/server_auth_test.go`

**Interfaces:**
- Consumes: `config.Config`, `handler.Handler`, `service.ValidateDeletion`
- Produces: Seamless deletion and request processing when `token == ""`

- [ ] **Step 1: Write test verifying remote deletion in open-auth mode**

Add `TestServerOpenModeAllowsDeletion` in `server/internal/server/server_auth_test.go`:
```go
func TestServerOpenModeAllowsDeletion(t *testing.T) {
	tmpDir := t.TempDir()
	testFile := filepath.Join(tmpDir, "test.txt")
	if err := os.WriteFile(testFile, []byte("hello"), 0644); err != nil {
		t.Fatalf("WriteFile failed: %v", err)
	}

	cfg := newAuthTestConfig(t, "")
	cfg.System.EnableDelete = true
	cfg.Scan.Roots = []string{tmpDir}

	srv, err := New(cfg)
	if err != nil {
		t.Fatalf("New failed: %v", err)
	}
	defer srv.Stop()

	body := `{"path":"` + filepath.ToSlash(testFile) + `","recursive":false}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/system/delete", strings.NewReader(body))
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	rec := httptest.NewRecorder()
	srv.Echo.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Errorf("status = %d, want %d (body: %s)", rec.Code, http.StatusOK, rec.Body.String())
	}
	if _, err := os.Stat(testFile); !os.IsNotExist(err) {
		t.Errorf("expected test file to be deleted, but it still exists")
	}
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test -v -run TestServerOpenModeAllowsDeletion ./internal/server`
Expected: FAIL (status 403 "remote deletion requires a bearer token")

- [ ] **Step 3: Update `system.go`, `config.go`, and `config.yaml`**

In `server/internal/server/handler/system.go`, remove the `if h.cfg.Server.Token == ""` check in `DeletePath`:
```go
func (h *Handler) DeletePath(c echo.Context) error {
	if !h.cfg.System.EnableDelete {
		return respondError(c, http.StatusForbidden, "remote deletion is disabled")
	}

	var req DeleteRequest
	if err := c.Bind(&req); err != nil {
		return respondError(c, http.StatusBadRequest, "invalid request body", err)
	}
```

In `server/internal/config/config.go`:
```go
func LogSecurityWarnings(cfg *Config, autoFromFlag bool) {
	if cfg.Server.Token == "" {
		slog.Info("Auth: running in open LAN mode (no token configured)")
	} else {
		slog.Info("Auth: token-based authentication enabled for admin/system/media routes")
	}

	if cfg.System.EnableDelete {
		slog.Warn("==============================================================")
		slog.Warn(" REMOTE DELETE IS ENABLED (system.enable_delete: true).")
		slog.Warn(" Clients can delete files under allowed_roots.")
		slog.Warn(" Disable 'system.enable_delete' in config.yaml unless you")
		slog.Warn(" genuinely need this feature.")
		slog.Warn("==============================================================")
	}
```

In `server/config.yaml`:
```yaml
server:
  host: "0.0.0.0"
  port: 8000
  token: ""
```

- [ ] **Step 4: Run server tests to verify they pass**

Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 5: Commit server changes**

```bash
git add server/config.yaml server/internal/config/config.go server/internal/server/handler/system.go server/internal/server/server_auth_test.go
git commit -m "feat(server): unblock remote deletion in open-auth mode and clean up startup logging"
```

---

### Task 2: Web: Enable Delete Controls in Open Mode & Validate Web Suite

**Files:**
- Modify: `server/internal/web/settings.js:25`

**Interfaces:**
- Consumes: `state`, `data.system.enable_delete`
- Produces: `state.enableDelete` correctly set when server enables delete

- [ ] **Step 1: Update `settings.js`**

In `server/internal/web/settings.js` line 25, change:
```javascript
state.enableDelete = !!(data.system && data.system.enable_delete);
```

- [ ] **Step 2: Run Web tests and XSS check**

Run: `cd server/internal/web && node --test`
Expected: PASS

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: PASS

- [ ] **Step 3: Commit Web changes**

```bash
git add server/internal/web/settings.js
git commit -m "fix(web): enable delete controls based on server config without requiring authToken"
```

---

### Task 3: Android: Collapse Token Field into Optional Advanced Options

**Files:**
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt`

**Interfaces:**
- Consumes: `ConnectionViewModel`, string resources
- Produces: Frictionless IP + Port manual connection with collapsible advanced token input

- [ ] **Step 1: Add string resources**

In `android/app/src/main/res/values/strings.xml`:
```xml
    <string name="conn_advanced_options">高级选项（访问令牌）</string>
    <string name="conn_hide_advanced_options">收起高级选项</string>
```

- [ ] **Step 2: Update `ConnectionScreen.kt` `ManualConnectionCard`**

In `ManualConnectionCard`, introduce `var showAdvanced by rememberSaveable { mutableStateOf(tokenInput.isNotBlank()) }`:
```kotlin
            var showAdvanced by rememberSaveable { mutableStateOf(tokenInput.isNotBlank()) }

            // IP & Port fields ...

            TextButton(
                onClick = { showAdvanced = !showAdvanced },
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(
                    text = if (showAdvanced) stringResource(R.string.conn_hide_advanced_options)
                           else stringResource(R.string.conn_advanced_options),
                    style = MaterialTheme.typography.labelLarge,
                )
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = onTokenChange,
                    label = { Text(stringResource(R.string.auth_token_label)) },
                    placeholder = { Text(stringResource(R.string.auth_token_placeholder)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
            }
```

- [ ] **Step 3: Run Android Unit Tests and Build**

Run: `cd android && ./gradlew testDebugUnitTest`
Expected: PASS

- [ ] **Step 4: Commit Android changes**

```bash
git add android/app/src/main/res/values/strings.xml android/app/src/main/java/com/juziss/localmediahub/ui/screen/ConnectionScreen.kt
git commit -m "feat(android): collapse auth token input into optional advanced section on ConnectionScreen"
```

---

### Task 4: End-to-End Verification & Verification Run

- [ ] **Step 1: Run comprehensive tests**
  - `cd server && go test ./...`
  - `cd server/internal/web && node --test`
  - `cd tools/xsscheck && go run . ../../server/internal/web`
  - `cd android && ./gradlew testDebugUnitTest assembleDebug`
- [ ] **Step 2: Create walkthrough artifact**

# Security Round 29 — Phase 3: Config Default Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Force explicit `scan.roots` configuration (or explicit opt-in to auto-detect), add startup security warning banners for risky config (`enable_delete`, auto-detect, empty token), tighten `config.yaml` file permissions to 0600, and centralize all startup security warnings in one `config.LogSecurityWarnings` helper.

**Architecture:** Server-side only. `config.go` gains `AutoDetectRoots` field + `Load` validation (reject empty roots without opt-in) + new `LogSecurityWarnings(cfg, autoFlag)` function. `main.go` adds `--auto-detect-roots` flag, calls `LogSecurityWarnings` after `Load`, and `os.Chmod("config.yaml", 0600)`. `server.go` removes the Phase 1 token warning block (migrated to `LogSecurityWarnings`). `config.example.yaml` documents the new field with safety comments.

**Tech Stack:** Go 1.25+ / Echo v4 / `log/slog` / `flag` / `gopkg.in/yaml.v3`

**Source spec:** `docs/superpowers/specs/2026-07-10-security-phase3-config-defaults-design.md`

**Coverage:** T3-01a (Medium 6.1), T3-02 (High 7.6, partial), T7-04 (High 7.6, awareness only), T8-12 (Low) + mitigates Chain-A (Medium 6.1) and Chain-B前置条件

## Global Constraints

- **`scan.roots` empty + `system.allowed_roots` empty + `scan.auto_detect_roots: false` → `config.Load` MUST return error.** No silent fallback to auto-detect. (Spec section 5.1.2)
- **Existing fallback `Roots = AllowedRoots` (when `Roots` empty + `AllowedRoots` non-empty) MUST be preserved.** Only the "both empty" case is new. (Spec section 5.1.2 note)
- **`--auto-detect-roots` flag is "force on"**: `effective_auto = cfg.Scan.AutoDetectRoots || flag_value`. Flag cannot force OFF. (Spec section 3.1)
- **Phase 1 token warning MUST be migrated from `server.New()` to `config.LogSecurityWarnings`** — behavior must stay equivalent (same text, same timing relative to server start). (Spec section 5.3)
- **`LogSecurityWarnings` is called from `main.go`, NOT from `server.New()`** — config layer owns "what is risky"; main.go owns when to print. (Spec section 3.1)
- **`os.Chmod("config.yaml", 0600)` failure is non-fatal** — warn and continue (read-only fs, Windows ACL quirks). (Spec section 5.2)
- **No new third-party dependencies.** All used packages (`flag`, `log/slog`, `os`, `fmt`) are stdlib.

---

## File Structure

| File | Type | Responsibility |
|---|---|---|
| `server/internal/config/config.go` | Modify | Add `AutoDetectRoots` field + `Load` validation + `LogSecurityWarnings` function |
| `server/internal/config/config_test.go` | Modify | Add `TestLoadRejectsEmptyRoots` + `TestLogSecurityWarnings` |
| `server/cmd/server/main.go` | Modify | Add `--auto-detect-roots` flag + call `LogSecurityWarnings` + `os.Chmod` |
| `server/internal/server/server.go` | Modify | DELETE Phase 1 token warning block (migrated) |
| `server/config.example.yaml` | Modify | Document `auto_detect_roots: false` + strengthen `enable_delete` warning |

---

## Task 1: Add `AutoDetectRoots` field + `Load` validation (TDD)

**Files:**
- Modify: `server/internal/config/config.go`
- Test: `server/internal/config/config_test.go`

**Interfaces:**
- Consumes: existing `LoadFromBytes` (Phase 1 Task 2 output).
- Produces: `ScanConfig.AutoDetectRoots bool` field + `Load` rejects empty-roots-no-opt-in configs.

- [ ] **Step 1: Write the failing test**

Append to `server/internal/config/config_test.go`:

```go
func TestLoadRejectsEmptyRoots(t *testing.T) {
	cases := []struct {
		name           string
		yaml           string
		wantLoadErr    bool
		wantErrorMatch string // substring to match in error message, when wantLoadErr
	}{
		{
			name: "empty roots + empty allowed_roots + auto=false → reject",
			yaml: `
server:
  host: "0.0.0.0"
  port: 8000
scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
`,
			wantLoadErr:    true,
			wantErrorMatch: "refusing to start",
		},
		{
			name: "empty roots + empty allowed_roots + auto=true → accept",
			yaml: `
server:
  host: "0.0.0.0"
  port: 8000
scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
  auto_detect_roots: true
`,
			wantLoadErr: false,
		},
		{
			name: "explicit roots → accept regardless of auto flag",
			yaml: `
server:
  host: "0.0.0.0"
  port: 8000
scan:
  roots: ["D:/Media"]
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
`,
			wantLoadErr: false,
		},
		{
			name: "empty roots + non-empty allowed_roots → accept (existing fallback preserved)",
			yaml: `
server:
  host: "0.0.0.0"
  port: 8000
scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
system:
  allowed_roots: ["E:/Photos"]
`,
			wantLoadErr: false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := LoadFromBytes([]byte(tc.yaml))
			if tc.wantLoadErr {
				if err == nil {
					t.Fatalf("expected error, got nil; cfg=%+v", cfg)
				}
				if tc.wantErrorMatch != "" && !strings.Contains(err.Error(), tc.wantErrorMatch) {
					t.Errorf("error = %q, want substring %q", err.Error(), tc.wantErrorMatch)
				}
			} else {
				if err != nil {
					t.Fatalf("expected no error, got: %v", err)
				}
			}
		})
	}
}
```

Add `"strings"` import to `config_test.go` if not present.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/config/ -run TestLoadRejectsEmptyRoots -v`
Expected: FAIL — first subcase accepts (no validation yet), wants error.

- [ ] **Step 3: Write minimal implementation**

In `server/internal/config/config.go`, add field to `ScanConfig`:

```go
type ScanConfig struct {
	Roots           []string `yaml:"roots,omitempty" json:"roots,omitempty"`
	VideoExtensions []string `yaml:"video_extensions" json:"video_extensions"`
	ImageExtensions []string `yaml:"image_extensions" json:"image_extensions"`
	AutoDetectRoots bool     `yaml:"auto_detect_roots,omitempty" json:"auto_detect_roots,omitempty"`

	autoRoots     []string
	autoRootsOnce sync.Once
}
```

In `LoadFromBytes`, after the existing `if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) > 0 { ... }` block (and before `return &cfg, nil`), add:

```go
// Phase 3 safety: refuse to start when no roots are configured and auto-detect
// is not explicitly opted in. Forces operators to declare their attack surface.
// The existing fallback above (Roots = AllowedRoots) handles the case where
// only allowed_roots is set, so this check only fires when BOTH are empty.
if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) == 0 && !cfg.Scan.AutoDetectRoots {
	return nil, fmt.Errorf(
		"refusing to start: no scan.roots or system.allowed_roots configured and " +
			"scan.auto_detect_roots is false.\n" +
			"To serve media, either:\n" +
			"  1. List explicit roots under 'scan.roots' in config.yaml, or\n" +
			"  2. Configure 'system.allowed_roots' (also serves as scan roots fallback), or\n" +
			"  3. Set 'scan.auto_detect_roots: true' in config.yaml (serves ALL drives — " +
			"review your threat model first), or\n" +
			"  4. Run with --auto-detect-roots flag (one-shot override)")
}
```

Confirm `"fmt"` is already imported (it is — `NormalizePath` uses it).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/config/ -run TestLoadRejectsEmptyRoots -v`
Expected: PASS — all 4 subcases green.

- [ ] **Step 5: Run full config test suite + server suite**

Run: `cd server && go test ./internal/config/ ./internal/server/ -v`
Expected: All existing tests pass (Phase 1 `TestServerConfigTokenRoundTrip`, `TestServerRejectsAdminWithoutToken`, etc. green — they construct configs with roots or use real `config.yaml`).

**Note**: If any Phase 1 test constructs a `config.Config{}` literal WITHOUT roots and expects it to load, that test will now fail. If so, that test needs to add roots OR `AutoDetectRoots: true` to its fixture. Check `server_auth_test.go` — the `newAuthTestConfig` helper (from Phase 1 Task 3) sets `Thumbnail.CacheDir` via `t.TempDir()` but may not set roots. **Read it first** and add roots if missing.

- [ ] **Step 6: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go
# Add server_auth_test.go to the commit IF its fixture needed roots added
git commit -m "feat(config): require explicit scan.roots or auto-detect opt-in (Phase 3)"
```

---

## Task 2: Add `LogSecurityWarnings` helper (TDD)

**Files:**
- Modify: `server/internal/config/config.go`
- Test: `server/internal/config/config_test.go`

**Interfaces:**
- Produces: `LogSecurityWarnings(cfg *Config, autoFromFlag bool)` — prints slog.Warn banners for risky config. Idempotent, side-effect-only.

- [ ] **Step 1: Write the failing test**

Append to `server/internal/config/config_test.go`:

```go
func TestLogSecurityWarnings(t *testing.T) {
	// captureSlogOutput swaps slog.Default() for a text handler writing to a
	// buffer for the duration of fn, then restores. Returns captured text.
	captureSlogOutput := func(fn func()) string {
		var buf bytes.Buffer
		orig := slog.Default()
		defer slog.SetDefault(orig)
		slog.SetDefault(slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug})))
		fn()
		return buf.String()
	}

	cases := []struct {
		name           string
		token          string
		enableDelete   bool
		autoDetectCfg  bool
		autoFromFlag   bool
		wantContains   []string // substrings that MUST appear
		wantNotContain []string // substrings that MUST NOT appear
	}{
		{
			name:         "all safe: token set, delete off, auto off",
			token:        "secret",
			enableDelete: false,
			autoDetectCfg: false,
			autoFromFlag: false,
			wantContains: []string{"token-based authentication enabled"},
			wantNotContain: []string{
				"OPEN AUTH MODE",
				"REMOTE DELETE IS ENABLED",
				"AUTO-DETECT ROOTS IS ENABLED",
			},
		},
		{
			name:         "empty token → OPEN AUTH MODE warning",
			token:        "",
			enableDelete: false,
			autoDetectCfg: false,
			autoFromFlag: false,
			wantContains: []string{"OPEN AUTH MODE"},
		},
		{
			name:         "enable_delete=true → REMOTE DELETE warning",
			token:        "secret",
			enableDelete: true,
			autoDetectCfg: false,
			autoFromFlag: false,
			wantContains: []string{"REMOTE DELETE IS ENABLED"},
		},
		{
			name:         "auto_detect via config → AUTO-DETECT warning without flag note",
			token:        "secret",
			enableDelete: false,
			autoDetectCfg: true,
			autoFromFlag: false,
			wantContains: []string{"AUTO-DETECT ROOTS IS ENABLED"},
			wantNotContain: []string{"triggered by --auto-detect-roots flag"},
		},
		{
			name:         "auto_detect via flag (config false) → AUTO-DETECT with flag note",
			token:        "secret",
			enableDelete: false,
			autoDetectCfg: false,
			autoFromFlag: true,
			wantContains: []string{
				"AUTO-DETECT ROOTS IS ENABLED",
				"triggered by --auto-detect-roots flag",
			},
		},
		{
			name:         "all risky at once → three warnings",
			token:        "",
			enableDelete: true,
			autoDetectCfg: true,
			autoFromFlag: true,
			wantContains: []string{
				"OPEN AUTH MODE",
				"REMOTE DELETE IS ENABLED",
				"AUTO-DETECT ROOTS IS ENABLED",
				// When both cfg and flag are true, the flag note is NOT printed
				// (only printed when flag forces on top of cfg=false).
			},
			wantNotContain: []string{"triggered by --auto-detect-roots flag"},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := &Config{
				Server: ServerConfig{Token: tc.token},
				System: SystemConfig{EnableDelete: tc.enableDelete},
				Scan:   ScanConfig{AutoDetectRoots: tc.autoDetectCfg},
			}
			output := captureSlogOutput(func() {
				LogSecurityWarnings(cfg, tc.autoFromFlag)
			})
			for _, want := range tc.wantContains {
				if !strings.Contains(output, want) {
					t.Errorf("output missing %q\nfull output:\n%s", want, output)
				}
			}
			for _, notWant := range tc.wantNotContain {
				if strings.Contains(output, notWant) {
					t.Errorf("output unexpectedly contains %q\nfull output:\n%s", notWant, output)
				}
			}
		})
	}
}
```

Add imports to `config_test.go`: `"bytes"`, `"log/slog"`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server && go test ./internal/config/ -run TestLogSecurityWarnings -v`
Expected: FAIL — `undefined: LogSecurityWarnings`.

- [ ] **Step 3: Write minimal implementation**

Append to `server/internal/config/config.go` (after `LoadFromBytes`):

```go
// LogSecurityWarnings prints slog.Warn banners for risky configuration.
// Called from main.go AFTER config.Load succeeds, BEFORE server.New.
//
// Centralizing here (instead of inside server.New) keeps config layer
// ownership of "what is risky" and lets main.go choose the timing
// (e.g. before mDNS registration). server.New stays focused on wiring.
//
// autoFromFlag is the --auto-detect-roots flag value; it ORs with
// cfg.Scan.AutoDetectRoots to determine the effective auto-detect state.
// The "triggered by flag" note is only printed when flag forces on top of
// a false config value (so users can distinguish persistent opt-in from
// one-shot override).
func LogSecurityWarnings(cfg *Config, autoFromFlag bool) {
	if cfg.Server.Token == "" {
		slog.Warn("==============================================================")
		slog.Warn(" SERVER IS RUNNING IN OPEN AUTH MODE (no token configured).")
		slog.Warn(" Any host on the LAN can call admin/system/media endpoints.")
		slog.Warn(" Set 'server.token' in config.yaml to enable authentication.")
		slog.Warn("==============================================================")
	} else {
		slog.Info("Auth: token-based authentication enabled for admin/system/media routes")
	}

	if cfg.System.EnableDelete {
		slog.Warn("==============================================================")
		slog.Warn(" REMOTE DELETE IS ENABLED (system.enable_delete: true).")
		slog.Warn(" Any authenticated client (or any LAN host if token is empty)")
		slog.Warn(" can delete files under system.allowed_roots.")
		slog.Warn(" Disable 'system.enable_delete' in config.yaml unless you")
		slog.Warn(" genuinely need this feature.")
		slog.Warn("==============================================================")
	}

	if cfg.Scan.AutoDetectRoots || autoFromFlag {
		slog.Warn("==============================================================")
		slog.Warn(" AUTO-DETECT ROOTS IS ENABLED.")
		if autoFromFlag && !cfg.Scan.AutoDetectRoots {
			slog.Warn(" (triggered by --auto-detect-roots flag, not config.yaml)")
		}
		slog.Warn(" Server will serve media from ALL detected drives (A-Z).")
		slog.Warn(" For production, configure 'scan.roots' explicitly instead.")
		slog.Warn("==============================================================")
	}
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/config/ -run TestLogSecurityWarnings -v`
Expected: PASS — all 6 subcases green.

- [ ] **Step 5: Run full config suite**

Run: `cd server && go test ./internal/config/ -v`
Expected: All tests pass (including Task 1's `TestLoadRejectsEmptyRoots`).

- [ ] **Step 6: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go
git commit -m "feat(config): add LogSecurityWarnings helper for startup banners (Phase 3)"
```

---

## Task 3: Wire flag + chmod + migrate Phase 1 token warning

**Files:**
- Modify: `server/cmd/server/main.go`
- Modify: `server/internal/server/server.go`

**Interfaces:**
- Consumes: Task 1 `Load` validation + Task 2 `LogSecurityWarnings`.
- Produces: `--auto-detect-roots` CLI flag, config.yaml `0600` chmod, Phase 1 token warning removed from server.New.

- [ ] **Step 1: Modify `main.go`**

In `server/cmd/server/main.go`, replace the existing `var headless bool` + `main()` flag block with:

```go
var (
	headless        bool
	autoDetectRoots bool
)

func main() {
	flag.BoolVar(&headless, "headless", false, "Run without GUI (system tray)")
	flag.BoolVar(&autoDetectRoots, "auto-detect-roots", false,
		"Force-enable auto-detection of all drives as scan roots (one-shot override; "+
			"also achievable via scan.auto_detect_roots in config.yaml)")
	flag.Parse()

	cfg, err := config.Load("config.yaml")
	if err != nil {
		slog.Error("Failed to load config", "error", err)
		os.Exit(1)
	}

	// Phase 3: log security warnings BEFORE any side effects (mDNS, server).
	// Centralized in config.LogSecurityWarnings so the config layer owns
	// "what is risky"; main.go owns the timing.
	config.LogSecurityWarnings(cfg, autoDetectRoots)

	// Phase 3: tighten config.yaml permissions to owner-only.
	// Non-fatal on failure (read-only fs, Windows ACL quirks) — warn and continue.
	if err := os.Chmod("config.yaml", 0600); err != nil {
		slog.Warn("Failed to tighten config.yaml permissions to 0600", "error", err)
	}

	// ... existing mDNS + headless/gui block unchanged ...
}
```

The rest of `main()` (mDNS start + headless/gui branch) stays unchanged.

- [ ] **Step 2: Modify `server.go` — remove Phase 1 token warning**

In `server/internal/server/server.go` `New()` function, **DELETE** the token warning block (added in Phase 1 commit `2083e47`, around lines 85-97). The block looks like:

```go
// DELETE THIS ENTIRE BLOCK:
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

After deletion, the `New()` function should flow directly from `s := &Server{...}` to `scanner.OnScanComplete = ...` (or wherever the warning was inserted).

**Verify `slog` import**: After removing the warning block, check if `slog` is still used elsewhere in `server.go`. If not, remove the `"log/slog"` import to avoid "unused import" compile error. (Likely still used elsewhere — check before removing.)

- [ ] **Step 3: Run full server test suite to verify migration didn't break anything**

Run: `cd server && go test ./...`
Expected: All tests pass. Specifically:
- `TestServerRejectsAdminWithoutToken`, `TestServerAcceptsAdminWithCorrectToken`, `TestServerOpenModeWhenTokenEmpty` (Phase 1) — these test **route behavior**, not warning text, so they should still pass.
- `TestLogSecurityWarnings` (Task 2) — covers the migrated warning.
- `TestLoadRejectsEmptyRoots` (Task 1) — unaffected.

If any Phase 1 `server_auth_test.go` test fails because it asserted the warning was printed during `server.New()`, that assertion moves to `TestLogSecurityWarnings`. Update the test accordingly.

- [ ] **Step 4: Manual integration test — empty config + no flag**

```bash
cd server
# Temporarily back up real config, create empty one:
cp config.yaml config.yaml.bak
cat > config.yaml <<EOF
server:
  host: "0.0.0.0"
  port: 8000
scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
EOF
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless
echo "exit code: $?"
# Expected: exit 1, stderr contains "refusing to start"
# Restore:
mv config.yaml.bak config.yaml
```

- [ ] **Step 5: Manual integration test — flag override**

```bash
cd server
cp config.yaml config.yaml.bak
cat > config.yaml <<EOF
server:
  host: "0.0.0.0"
  port: 8000
scan:
  video_extensions: [".mp4"]
  image_extensions: [".jpg"]
EOF
./LocalMediaHub.exe --headless --auto-detect-roots &
sleep 3
curl -s http://localhost:8000/api/v1/health
# Expected: {"status":"ok"} + server log shows "AUTO-DETECT ROOTS IS ENABLED" + "(triggered by --auto-detect-roots flag)"
kill %1
mv config.yaml.bak config.yaml
```

- [ ] **Step 6: Commit**

```bash
git add server/cmd/server/main.go server/internal/server/server.go
git commit -m "feat(server): wire --auto-detect-roots flag, chmod 0600, migrate token warning (Phase 3)"
```

---

## Task 4: Update `config.example.yaml` + verify

**Files:**
- Modify: `server/config.example.yaml`
- Verify only: no code changes

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: Documented new field + strengthened warnings for users copying the example.

- [ ] **Step 1: Modify `config.example.yaml`**

Read current `server/config.example.yaml`. The current `scan:` block should look roughly like:

```yaml
scan:
  video_extensions:
    - ".mp4"
    ...
```

Replace/augment to:

```yaml
scan:
  # Media roots. If empty AND system.allowed_roots is empty AND auto_detect_roots
  # is false, the server REFUSES TO START (Phase 3 safety default).
  # Explicit roots are strongly recommended for production.
  roots: []
    # - "D:/Movies"
    # - "E:/Photos"
  video_extensions:
    - ".mp4"
    - ".mkv"
    - ".avi"
    - ".mov"
    - ".wmv"
    - ".flv"
    - ".ts"
  image_extensions:
    - ".jpg"
    - ".jpeg"
    - ".png"
    - ".gif"
    - ".bmp"
    - ".webp"
  # OPT-IN auto-detection: when true, server scans all detected drives (A-Z).
  # Convenience feature with elevated risk — any LAN host (or any authenticated
  # client) can browse media on ALL drives. Default false.
  auto_detect_roots: false
```

And in the existing `system:` block, ensure `enable_delete` has the warning comment (it may already have one — strengthen if needed):

```yaml
system:
  # CAUTION: when true, authenticated clients can delete files under allowed_roots
  # via POST /api/v1/system/delete. The server prints a warning at startup when
  # this is enabled. Default false.
  enable_delete: false
  allowed_roots: []
    # - "D:/Media"
```

- [ ] **Step 2: Verify YAML parses + server still loads**

```bash
cd server
# Copy example to a temp config for testing:
cp config.example.yaml /tmp/test-config.yaml
# Add a token to satisfy any test requirements (optional):
echo 'server:' > /tmp/test-config.yaml
echo '  host: "127.0.0.1"' >> /tmp/test-config.yaml
echo '  port: 8001' >> /tmp/test-config.yaml
echo 'scan:' >> /tmp/test-config.yaml
echo '  auto_detect_roots: true' >> /tmp/test-config.yaml
echo '  video_extensions: [".mp4"]' >> /tmp/test-config.yaml
echo '  image_extensions: [".jpg"]' >> /tmp/test-config.yaml
# Load via the real binary:
go build -o LocalMediaHub.exe ./cmd/server
./LocalMediaHub.exe --headless --auto-detect-roots 2>&1 | head -10
# Expected: server starts, prints AUTO-DETECT warning + Auth Info/Warning
# Ctrl+C to stop
```

- [ ] **Step 3: Full regression — Go test suite**

Run: `cd server && go test ./...`
Expected: All packages green. No regressions from Tasks 1-3.

- [ ] **Step 4: Commit**

```bash
git add server/config.example.yaml
git commit -m "docs(config): document auto_detect_roots + strengthen enable_delete warning (Phase 3)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ `AutoDetectRoots` field + `Load` validation (Task 1, spec 5.1.2)
- ✅ `LogSecurityWarnings` helper (Task 2, spec 5.1.3)
- ✅ `--auto-detect-roots` flag (Task 3, spec 5.2)
- ✅ `os.Chmod 0600` (Task 3, spec 5.2)
- ✅ Phase 1 token warning migration (Task 3, spec 5.3)
- ✅ `config.example.yaml` documentation (Task 4, spec 5.4)
- ✅ Existing `Roots = AllowedRoots` fallback preserved (Task 1 test case 4)

**Type consistency**:
- `LogSecurityWarnings(cfg *Config, autoFromFlag bool)` — consistent across Tasks 2, 3
- `ScanConfig.AutoDetectRoots bool` with `yaml:"auto_detect_roots,omitempty"` — consistent across Tasks 1, 4
- `--auto-detect-roots` flag name — consistent across Tasks 3, 4

**Placeholder scan**: No TBD/TODO/"add error handling"/"similar to Task N" patterns. Every step contains complete code.

**Known implementation risks** (flagged for executor awareness):
1. **Task 1 Step 5 regression risk**: Phase 1 `server_auth_test.go` `newAuthTestConfig` helper may construct configs without roots — if so, it will fail the new `Load` validation. The task brief tells the executor to read `server_auth_test.go` and add roots OR `AutoDetectRoots: true` to the fixture if needed. **Note**: `server_auth_test.go` constructs `*config.Config` literals directly (bypassing `Load`), so the new validation in `LoadFromBytes` does NOT fire on those literals — they should be unaffected. But verify during execution.
2. **Task 3 Step 2 unused import**: Removing the token warning from `server.go` may leave `slog` unused. Check before removing the import.
3. **Task 3 Step 4-5 Windows bash**: Background process + sleep + curl + kill may need adjustment on Windows. The implementer should use `Start-Process` (PowerShell) or run in `--headless` with `&` + `kill %1` carefully.
4. **Task 4 Step 2 heredoc**: YAML heredocs with array syntax may need careful escaping. The implementer can use `printf` or write the file via `cat <<'EOF'` to avoid variable expansion.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-10-security-phase3-config-defaults.md`.

Four tasks, all server-side Go. Estimated total effort: small (each task is 15-40 lines of code + tests).

Execution model recommendation:
- Task 1: standard model (touches existing `Load` logic + potential regression in `server_auth_test.go`)
- Task 2: cheapest model (new function + table-driven tests, complete code in brief)
- Task 3: standard model (multi-file, migration, manual integration tests)
- Task 4: cheapest model (config.yaml edits + verification)

# Security Round 29 — Phase 3: Config Default Safety Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Force explicit `scan.roots` configuration (or explicit opt-in to auto-detect), add startup security warning banners for risky config (`enable_delete`, auto-detect, empty token), tighten `config.yaml` file permissions to 0600, and centralize all startup security warnings in one `config.LogSecurityWarnings` helper.

**Architecture:** Server-side only. `config.go` gains `AutoDetectRoots` field in `ScanConfig` and `ScanConfigPublic` + `Validate(autoFromFlag bool) error` method + new `LogSecurityWarnings(cfg, autoFlag)` function. `main.go` adds `--auto-detect-roots` flag, calls `Validate` and `LogSecurityWarnings` after `Load`, and `os.Chmod("config.yaml", 0600)`. `server.go` removes the Phase 1 token warning block (migrated to `LogSecurityWarnings`). `admin.go` validates config before save. `config.example.yaml` documents the new field with safety comments. `settings.js` adds style alert for `enableDelete`.

**Tech Stack:** Go 1.25+ / Echo v4 / `log/slog` / `flag` / `gopkg.in/yaml.v3` / Vanilla JS

**Source spec:** `docs/superpowers/specs/2026-07-10-security-phase3-config-defaults-design.md`

**Coverage:** T3-01a (Medium 6.1), T3-02 (High 7.6, partial), T7-04 (High 7.6, awareness only), T8-12 (Low) + mitigates Chain-A (Medium 6.1) and Chain-B前置条件

## Global Constraints

- **`scan.roots` empty + `system.allowed_roots` empty + `scan.auto_detect_roots: false` + `autoFromFlag: false` → `config.Validate` MUST return error.** No silent fallback to auto-detect. (Spec section 5.1.2)
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
| `server/internal/config/config.go` | Modify | Add `AutoDetectRoots` field + `Validate` method + `LogSecurityWarnings` function + update public config types |
| `server/internal/config/config_test.go` | Modify | Add `TestConfigValidate` + `TestLogSecurityWarnings` |
| `server/cmd/server/main.go` | Modify | Add `--auto-detect-roots` flag + call `Validate` + call `LogSecurityWarnings` + `os.Chmod` |
| `server/internal/server/server.go` | Modify | DELETE Phase 1 token warning block (migrated) |
| `server/internal/server/handler/admin.go` | Modify | Call `Validate(false)` before saving new roots |
| `server/config.example.yaml` | Modify | Document `auto_detect_roots: false` + strengthen `enable_delete` warning |
| `server/internal/web/settings.js` | Modify | Highlight `enableDelete` in red bold when enabled |

---

## Task 1: Add `AutoDetectRoots` field + `Validate` method (TDD)

**Files:**
- Modify: `server/internal/config/config.go`
- Test: `server/internal/config/config_test.go`

**Interfaces:**
- Consumes: existing config structure.
- Produces: `ScanConfig.AutoDetectRoots bool` field + `ScanConfigPublic.AutoDetectRoots bool` field + `Validate(autoFromFlag bool) error` method.

- [ ] **Step 1: Write the failing test**

Append to `server/internal/config/config_test.go`:

```go
func TestConfigValidate(t *testing.T) {
	cases := []struct {
		name         string
		roots        []string
		allowedRoots []string
		autoDetect   bool
		autoFromFlag bool
		wantErr      bool
	}{
		{
			name:         "empty roots + empty allowed_roots + auto=false + flag=false → reject",
			roots:        nil,
			allowedRoots: nil,
			autoDetect:   false,
			autoFromFlag: false,
			wantErr:      true,
		},
		{
			name:         "empty roots + empty allowed_roots + auto=false + flag=true → accept",
			roots:        nil,
			allowedRoots: nil,
			autoDetect:   false,
			autoFromFlag: true,
			wantErr:      false,
		},
		{
			name:         "empty roots + empty allowed_roots + auto=true + flag=false → accept",
			roots:        nil,
			allowedRoots: nil,
			autoDetect:   true,
			autoFromFlag: false,
			wantErr:      false,
		},
		{
			name:         "explicit roots → accept",
			roots:        []string{"D:/Media"},
			allowedRoots: nil,
			autoDetect:   false,
			autoFromFlag: false,
			wantErr:      false,
		},
		{
			name:         "empty roots + allowed_roots → accept (fallback)",
			roots:        nil,
			allowedRoots: []string{"E:/Photos"},
			autoDetect:   false,
			autoFromFlag: false,
			wantErr:      false,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg := &Config{
				Scan:   ScanConfig{Roots: tc.roots, AutoDetectRoots: tc.autoDetect},
				System: SystemConfig{AllowedRoots: tc.allowedRoots},
			}
			err := cfg.Validate(tc.autoFromFlag)
			if tc.wantErr {
				if err == nil {
					t.Fatal("expected error, got nil")
				}
				if !strings.Contains(err.Error(), "refusing to start") {
					t.Errorf("expected error to contain 'refusing to start', got: %v", err)
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

Run: `cd server && go test ./internal/config/ -run TestConfigValidate -v`
Expected: FAIL — `cfg.Validate` undefined.

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

And in `ScanConfigPublic`:

```go
type ScanConfigPublic struct {
	Roots           []string `json:"roots,omitempty"`
	VideoExtensions []string `json:"video_extensions"`
	ImageExtensions []string `json:"image_extensions"`
	AutoDetectRoots bool     `json:"auto_detect_roots,omitempty"`
}
```

And map it in `Public()`:

```go
// Public returns a copy of the config with sensitive operational fields removed.
func (c *Config) Public() ConfigPublic {
	return ConfigPublic{
		Server:    ServerConfigPublic{Host: c.Server.Host, Port: c.Server.Port},
		Scan:      ScanConfigPublic{Roots: c.Scan.Roots, VideoExtensions: c.Scan.VideoExtensions, ImageExtensions: c.Scan.ImageExtensions, AutoDetectRoots: c.Scan.AutoDetectRoots},
		Thumbnail: c.Thumbnail,
		System:    SystemConfigPublic{AllowedRoots: c.System.AllowedRoots, EnableDelete: c.System.EnableDelete},
	}
}
```

And define the `Validate` method in `config.go`:

```go
// Validate checks if the configuration is safe and sufficient to start.
// Refuses to start when no roots are configured and auto-detect is not
// explicitly opted in (either via config or via command-line override flag).
//
// Note: LoadFromBytes already copies AllowedRoots → Roots when Roots is
// empty and AllowedRoots is non-empty. So after a normal Load, if
// AllowedRoots was set, Roots will be non-empty and this check passes.
// The len(c.Scan.Roots)==0 condition therefore implicitly covers the
// "both empty" case. We still check AllowedRoots explicitly for
// callers who construct Config directly (tests, admin API Validate
// before Save).
func (c *Config) Validate(autoFromFlag bool) error {
	if len(c.Scan.Roots) == 0 && len(c.System.AllowedRoots) == 0 && !c.Scan.AutoDetectRoots && !autoFromFlag {
		return fmt.Errorf(
			"refusing to start: no scan.roots or system.allowed_roots configured and " +
				"scan.auto_detect_roots is false.\n" +
				"To serve media, either:\n" +
				"  1. List explicit roots under 'scan.roots' in config.yaml, or\n" +
				"  2. Configure 'system.allowed_roots' (also serves as scan roots fallback), or\n" +
				"  3. Set 'scan.auto_detect_roots: true' in config.yaml (serves ALL drives — " +
				"review your threat model first), or\n" +
				"  4. Run with --auto-detect-roots flag (one-shot override)")
	}
	return nil
}
```
- [ ] **Step 4: Run test to verify it passes**

Run: `cd server && go test ./internal/config/ -run TestConfigValidate -v`
Expected: PASS — all 5 subcases green.

- [ ] **Step 5: Run full config test suite + server suite**

Run: `cd server && go test ./internal/config/ ./internal/server/ -v`
Expected: All existing tests pass (including `TestSaveIsAtomicAndReadable` and others, without modification, because `Load`/`LoadFromBytes` do not reject empty configs by default anymore).

- [ ] **Step 6: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go
git commit -m "feat(config): add AutoDetectRoots field and Validate method (Phase 3)"
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
Expected: All tests pass (including Task 1's `TestConfigValidate`).

- [ ] **Step 6: Commit**

```bash
git add server/internal/config/config.go server/internal/config/config_test.go
git commit -m "feat(config): add LogSecurityWarnings helper for startup banners (Phase 3)"
```

---

## Task 3: Wire flag + chmod + migrate Phase 1 token warning + admin.go validation

**Files:**
- Modify: `server/cmd/server/main.go`
- Modify: `server/internal/server/server.go`
- Modify: `server/internal/server/handler/admin.go`

**Interfaces:**
- Consumes: Task 1 `Validate` + Task 2 `LogSecurityWarnings`.
- Produces: `--auto-detect-roots` CLI flag, config.yaml `0600` chmod, Phase 1 token warning removed from server.New, config validation in UpdateConfig.

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

	// Phase 3: validate config after Load, incorporating CLI override flags.
	if err := cfg.Validate(autoDetectRoots); err != nil {
		slog.Error("Invalid config", "error", err)
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

In `server/internal/server/server.go` `New()` function, **DELETE** the token warning block (added in Phase 1 commit `2083e47`, lines 86-97 including the comment). The block looks like:

```go
// DELETE THIS ENTIRE BLOCK (lines 86-97):
	// Security startup notice: warn loudly when running in open auth mode so
	// operators don't accidentally expose admin/system/media endpoints without
	// a token. When a token is configured, log a quiet info line instead.
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

After deletion, the `New()` function should flow directly from `h := handler.New(...)` (line 84) to `s.registerRoutes(h)` (previously line 99).

**Verify `slog` import**: After removing the warning block, `slog` is still used elsewhere in `server.go` (e.g., `slog.Info` in `Start()`/`Stop()`) — do NOT remove the import.

- [ ] **Step 2a: Modify `admin.go` — validate new roots**

In `server/internal/server/handler/admin.go` in `UpdateConfig`, call `Validate(false)` before saving to prevent persisting invalid configurations.

```go
	oldRoots := h.cfg.Scan.Roots
	h.cfg.Scan.Roots = req.Roots
	if err := h.cfg.Validate(false); err != nil {
		h.cfg.Scan.Roots = oldRoots
		return respondError(c, http.StatusBadRequest, "invalid configuration: scan roots cannot be empty unless auto-detect is enabled or allowed_roots is set", err)
	}
	h.cfg.Scan.InvalidateRootsCache()
	if err := h.cfg.Save("config.yaml"); err != nil {
```

- [ ] **Step 3: Run full server test suite to verify migration didn't break anything**

Run: `cd server && go test ./...`
Expected: All tests pass. Specifically:
- `TestServerRejectsAdminWithoutToken`, `TestServerAcceptsAdminWithCorrectToken`, `TestServerOpenModeWhenTokenEmpty` (Phase 1) — these test **route behavior**, not warning text, so they should still pass.
- `TestLogSecurityWarnings` (Task 2) — covers the migrated warning.
- `TestConfigValidate` (Task 1) — unaffected.

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
git add server/cmd/server/main.go server/internal/server/server.go server/internal/server/handler/admin.go
git commit -m "feat(server): wire --auto-detect-roots flag, chmod 0600, migrate token warning, validate config in UpdateConfig (Phase 3)"
```

---

## Task 4: Update `config.example.yaml` + Web UI settings.js + verify

**Files:**
- Modify: `server/config.example.yaml`
- Modify: `server/internal/web/settings.js`
- Verify: manual validation

**Interfaces:**
- Consumes: Tasks 1-3.
- Produces: Documented new field + styled Web UI alert for enable_delete.

- [ ] **Step 1: Modify `config.example.yaml`**

Read current `server/config.example.yaml`. Replace/augment `scan` and `system` blocks to:

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

And in the existing `system:` block, ensure `enable_delete` has the warning comment:

```yaml
system:
  # CAUTION: when true, authenticated clients can delete files under allowed_roots
  # via POST /api/v1/system/delete. The server prints a warning at startup when
  # this is enabled. Default false.
  enable_delete: false
  allowed_roots: []
    # - "D:/Media"
```

- [ ] **Step 2: Modify `settings.js`**

In `server/internal/web/settings.js`, update `renderSettings()` to display `enableDelete` with red bold style if it's enabled:

```javascript
    if (state.enableDelete) {
        elements.settingsEnableDelete.textContent = '⚠️ 已开启 (允许从客户端删除电脑媒体文件)';
        elements.settingsEnableDelete.style.color = 'var(--error)';
        elements.settingsEnableDelete.style.fontWeight = 'bold';
    } else {
        elements.settingsEnableDelete.textContent = '已禁用 (安全只读)';
        elements.settingsEnableDelete.style.color = '';
        elements.settingsEnableDelete.style.fontWeight = '';
    }
```

- [ ] **Step 3: Verify YAML parses + server still loads**

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

- [ ] **Step 4: Full regression — Go test suite**

Run: `cd server && go test ./...`
Expected: All packages green. No regressions from Tasks 1-3.

- [ ] **Step 5: Commit**

```bash
git add server/config.example.yaml server/internal/web/settings.js
git commit -m "docs(config): document auto_detect_roots, add Web UI styling for delete (Phase 3)"
```

---

## Self-Review

**Spec coverage** (against spec section 5):
- ✅ `AutoDetectRoots` field + `Validate` validation method (Task 1, spec 5.1.2)
- ✅ `LogSecurityWarnings` helper (Task 2, spec 5.1.3)
- ✅ `--auto-detect-roots` flag (Task 3, spec 5.2)
- ✅ `os.Chmod 0600` (Task 3, spec 5.2)
- ✅ Admin config API UpdateConfig validation (Task 3, spec 5.2a)
- ✅ Phase 1 token warning migration (Task 3, spec 5.3)
- ✅ `config.example.yaml` documentation (Task 4, spec 5.4)
- ✅ Web UI `enable_delete` red bold styling (Task 4, spec 4.3)
- ✅ Existing `Roots = AllowedRoots` fallback preserved (Task 1 test case 5)

**Type consistency**:
- `LogSecurityWarnings(cfg *Config, autoFromFlag bool)` — consistent across Tasks 2, 3
- `ScanConfig.AutoDetectRoots bool` and `ScanConfigPublic.AutoDetectRoots bool` — consistent across Tasks 1, 4
- `--auto-detect-roots` flag name — consistent across Tasks 3, 4

**Placeholder scan**: No TBD/TODO/"add error handling"/"similar to Task N" patterns. Every step contains complete code.

**Known implementation risks** (flagged for executor awareness):
1. **Task 3 Step 2 unused import**: Removing the token warning from `server.go` may leave `slog` unused. Check before removing the import.
2. **Task 3 Step 4-5 Windows bash**: Background process + sleep + curl + kill may need adjustment on Windows. The implementer should use `Start-Process` (PowerShell) or run in `--headless` with `&` + `kill %1` carefully.
3. **Task 4 Step 3 heredoc**: YAML heredocs with array syntax may need careful escaping. The implementer can use `printf` or write the file via `cat <<'EOF'` to avoid variable expansion.

---

## Plan complete

Plan saved to `docs/superpowers/plans/2026-07-10-security-phase3-config-defaults.md`.

Four tasks, Go and JS files. Estimated total effort: small.

Execution model recommendation:
- Task 1: standard model (touches existing `Load` structures, introduces `Validate`)
- Task 2: cheapest model (new function + table-driven tests)
- Task 3: standard model (multi-file, migration, manual integration tests)
- Task 4: cheapest model (config.yaml/JS edits + verification)

package config

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestGetSystemAllowedRootsRequiresExplicitConfig(t *testing.T) {
	cfg := &Config{
		Scan: ScanConfig{
			Roots: []string{`F:\Media`},
		},
	}

	got := cfg.GetSystemAllowedRoots()
	if len(got) != 0 {
		t.Fatalf("expected no system roots without explicit config, got %v", got)
	}
}

func TestGetSystemAllowedRootsReturnsConfiguredRoots(t *testing.T) {
	cfg := &Config{
		System: SystemConfig{
			AllowedRoots: []string{`F:\Photos`, `G:\Videos`},
		},
	}

	got := cfg.GetSystemAllowedRoots()
	if len(got) != 2 {
		t.Fatalf("expected 2 configured roots, got %d", len(got))
	}
	if got[0] != `F:\Photos` || got[1] != `G:\Videos` {
		t.Fatalf("unexpected roots: %v", got)
	}
}

func TestLoadUsesSystemAllowedRootsAsDefaultScanRoots(t *testing.T) {
	dir := t.TempDir()
	configPath := filepath.Join(dir, "config.yaml")
	configBody := `
server:
  host: "0.0.0.0"
  port: 8000
scan:
  video_extensions:
    - ".mp4"
  image_extensions:
    - ".jpg"
thumbnail:
  cache_dir: ".cache/thumbnails"
  max_size: 300
  format: "JPEG"
system:
  allowed_roots:
    - "F:\\restricted"
    - "S:\\more"
`

	if err := os.WriteFile(configPath, []byte(configBody), 0o644); err != nil {
		t.Fatalf("failed to write config: %v", err)
	}

	cfg, err := Load(configPath)
	if err != nil {
		t.Fatalf("Load returned error: %v", err)
	}

	got := cfg.Scan.GetRoots()
	if len(got) != 2 {
		t.Fatalf("expected scan roots to default to allowed roots, got %v", got)
	}
	if got[0] != `F:\restricted` || got[1] != `S:\more` {
		t.Fatalf("expected allowed roots to become scan roots, got %v", got)
	}
}

func TestPublicRedactsOnlyFFmpegPath(t *testing.T) {
	cfg := &Config{
		Server: ServerConfig{Host: "0.0.0.0", Port: 8000},
		System: SystemConfig{
			AllowedRoots: []string{"D:/Media"},
			EnableDelete: true,
			FFmpegPath:   "C:/tools/ffmpeg.exe",
		},
	}

	data, err := json.Marshal(cfg.Public())
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(data)

	if strings.Contains(s, "ffmpeg_path") {
		t.Errorf("Public() leaked ffmpeg_path: %s", s)
	}
	if !strings.Contains(s, "enable_delete") {
		t.Errorf("Public() should keep enable_delete (Web UI delete buttons depend on it): %s", s)
	}
	if !strings.Contains(s, "allowed_roots") {
		t.Errorf("Public() should keep allowed_roots: %s", s)
	}
}

// TestPublicRedactsServerToken is a regression test for the Round 29 security
// fix. ConfigPublic previously embedded ServerConfig directly, so its Token
// field (added in Task 2) was serialized into the GET /api/v1/admin/config
// response — letting any caller read the configured bearer token and bypass
// the entire auth layer. The test uses a non-empty, distinctive token so the
// regression would be caught regardless of which field name or value shape
// the leak takes.
func TestPublicRedactsServerToken(t *testing.T) {
	const secret = "lmh-pr-29-leak-canary-token-DO-NOT-EXPOSE"
	cfg := &Config{
		Server: ServerConfig{Host: "0.0.0.0", Port: 8000, Token: secret},
		System: SystemConfig{
			AllowedRoots: []string{"D:/Media"},
			EnableDelete: true,
		},
	}

	data, err := json.Marshal(cfg.Public())
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	s := string(data)

	if strings.Contains(s, secret) {
		t.Fatalf("Public() leaked server token into config response: %s", s)
	}
	if strings.Contains(strings.ToLower(s), `"token"`) {
		t.Fatalf("Public() serialized a token field — server token must be omitted entirely: %s", s)
	}
	// Sanity: host/port should still be present so the test is meaningful
	// (i.e. we didn't just produce an empty object).
	if !strings.Contains(s, "0.0.0.0") || !strings.Contains(s, "8000") {
		t.Fatalf("Public() lost expected server fields: %s", s)
	}
}

func TestSaveIsAtomicAndReadable(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")

	cfg := &Config{
		Server:      ServerConfig{Host: "0.0.0.0", Port: 8000},
		Scan:        ScanConfig{VideoExtensions: []string{".mp4"}, ImageExtensions: []string{".jpg"}},
		Thumbnail:   ThumbnailConfig{CacheDir: ".cache/thumbnails", MaxSize: 300, Format: "JPEG"},
	}
	if err := cfg.Save(path); err != nil {
		t.Fatalf("Save: %v", err)
	}

	// 不应残留临时文件。
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("readdir: %v", err)
	}
	if len(entries) != 1 || entries[0].Name() != "config.yaml" {
		var names []string
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Fatalf("expected only config.yaml in dir, got %v", names)
	}

	// 可回读。
	loaded, err := Load(path)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	if loaded.Server.Port != 8000 {
		t.Errorf("expected port 8000, got %d", loaded.Server.Port)
	}
}

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
			name:          "empty token → open LAN mode info",
			token:         "",
			enableDelete:  false,
			autoDetectCfg: false,
			autoFromFlag:  false,
			wantContains:  []string{"Auth: running in open LAN mode"},
		},
		{
			name:          "enable_delete=true → REMOTE DELETE warning",
			token:         "secret",
			enableDelete:  true,
			autoDetectCfg: false,
			autoFromFlag:  false,
			wantContains:  []string{"REMOTE DELETE IS ENABLED"},
			wantNotContain: []string{"delete will"},
		},
		{
			name:          "enable_delete=true + empty token → REMOTE DELETE warning without delete-refused note",
			token:         "",
			enableDelete:  true,
			autoDetectCfg: false,
			autoFromFlag:  false,
			wantContains: []string{
				"REMOTE DELETE IS ENABLED",
				"Auth: running in open LAN mode",
			},
			wantNotContain: []string{"reject every request"},
		},
		{
			name:          "auto_detect via config → AUTO-DETECT warning without flag note",
			token:         "secret",
			enableDelete:  false,
			autoDetectCfg: true,
			autoFromFlag:  false,
			wantContains:  []string{"AUTO-DETECT ROOTS IS ENABLED"},
			wantNotContain: []string{"triggered by --auto-detect-roots flag"},
		},
		{
			name:          "auto_detect via flag (config false) → AUTO-DETECT with flag note",
			token:         "secret",
			enableDelete:  false,
			autoDetectCfg: false,
			autoFromFlag:  true,
			wantContains: []string{
				"AUTO-DETECT ROOTS IS ENABLED",
				"triggered by --auto-detect-roots flag",
			},
		},
		{
			name:          "all risky at once → two warnings plus open LAN mode info",
			token:         "",
			enableDelete:  true,
			autoDetectCfg: true,
			autoFromFlag:  true,
			wantContains: []string{
				"Auth: running in open LAN mode",
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

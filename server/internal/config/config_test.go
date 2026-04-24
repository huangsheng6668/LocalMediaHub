package config

import (
	"os"
	"path/filepath"
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

package config

import (
	"encoding/json"
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

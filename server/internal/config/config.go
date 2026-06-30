package config

import (
	"os"
	"path/filepath"
	"sync"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server    ServerConfig    `yaml:"server" json:"server"`
	Scan      ScanConfig      `yaml:"scan" json:"scan"`
	Thumbnail ThumbnailConfig `yaml:"thumbnail" json:"thumbnail"`
	System    SystemConfig    `yaml:"system,omitempty" json:"system,omitempty"`
}

type ServerConfig struct {
	Host string `yaml:"host" json:"host"`
	Port int    `yaml:"port" json:"port"`
}

type ScanConfig struct {
	Roots           []string `yaml:"roots,omitempty" json:"roots,omitempty"`
	VideoExtensions []string `yaml:"video_extensions" json:"video_extensions"`
	ImageExtensions []string `yaml:"image_extensions" json:"image_extensions"`

	// Cached result of auto-detected drives (only used when Roots is empty).
	// Detecting drives probes A-Z: with os.Stat on every call, which is called
	// dozens of times per request across handlers; the drive set rarely changes
	// during a run, so we compute it once and reuse it. Reset via
	// InvalidateRootsCache (e.g. after the user edits config via the admin API).
	autoRoots     []string
	autoRootsOnce sync.Once
}

// GetRoots returns configured roots, or auto-detects all drives if empty.
func (s *ScanConfig) GetRoots() []string {
	if len(s.Roots) > 0 {
		return s.Roots
	}
	s.autoRootsOnce.Do(func() {
		var drives []string
		for _, letter := range "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
			path := string(letter) + ":\\"
			if _, err := os.Stat(path); err == nil {
				drives = append(drives, path)
			}
		}
		s.autoRoots = drives
	})
	return s.autoRoots
}

// InvalidateRootsCache clears the cached auto-detected drive list so the next
// GetRoots call re-probes. Call this whenever Roots may have changed (e.g.
// after applying an admin config update) or when external drive topology
// changes and a refresh is desired.
func (s *ScanConfig) InvalidateRootsCache() {
	s.autoRoots = nil
	s.autoRootsOnce = sync.Once{}
}

type ThumbnailConfig struct {
	CacheDir string `yaml:"cache_dir" json:"cache_dir"`
	MaxSize  int    `yaml:"max_size" json:"max_size"`
	Format   string `yaml:"format" json:"format"`
}

type SystemConfig struct {
	AllowedRoots []string `yaml:"allowed_roots,omitempty" json:"allowed_roots,omitempty"`
	EnableDelete bool     `yaml:"enable_delete,omitempty" json:"enable_delete,omitempty"`
	FFmpegPath   string   `yaml:"ffmpeg_path,omitempty" json:"ffmpeg_path,omitempty"`
}

// ConfigPublic is the redacted view of Config returned by GET/PUT /admin/config.
// It omits only System.FFmpegPath (a local binary path). System.EnableDelete is
// kept because the Web manager's delete buttons gate on it, and System.AllowedRoots
// is kept (already exposed by GET /system/drives and shown in the Web settings UI).
// ScanConfig is projected onto ScanConfigPublic so the internal auto-detected-drive
// cache (sync.Once) is neither copied nor leaked.
type ConfigPublic struct {
	Server    ServerConfig        `json:"server"`
	Scan      ScanConfigPublic    `json:"scan"`
	Thumbnail ThumbnailConfig     `json:"thumbnail"`
	System    SystemConfigPublic  `json:"system"`
}

// ScanConfigPublic mirrors only the user-facing fields of ScanConfig. The internal
// autoRoots/autoRootsOnce cache (the latter is a sync.Once and must not be copied)
// is intentionally omitted.
type ScanConfigPublic struct {
	Roots           []string `json:"roots,omitempty"`
	VideoExtensions []string `json:"video_extensions"`
	ImageExtensions []string `json:"image_extensions"`
}

type SystemConfigPublic struct {
	AllowedRoots []string `json:"allowed_roots,omitempty"`
	EnableDelete bool     `json:"enable_delete,omitempty"`
}

// Public returns a copy of the config with sensitive operational fields removed.
func (c *Config) Public() ConfigPublic {
	return ConfigPublic{
		Server:    c.Server,
		Scan:      ScanConfigPublic{Roots: c.Scan.Roots, VideoExtensions: c.Scan.VideoExtensions, ImageExtensions: c.Scan.ImageExtensions},
		Thumbnail: c.Thumbnail,
		System:    SystemConfigPublic{AllowedRoots: c.System.AllowedRoots, EnableDelete: c.System.EnableDelete},
	}
}

// GetSystemAllowedRoots returns configured system browse roots.
// If empty, system browse is disabled until explicitly configured.
func (c *Config) GetSystemAllowedRoots() []string {
	if len(c.System.AllowedRoots) > 0 {
		return c.System.AllowedRoots
	}
	return []string{}
}

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var cfg Config
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) > 0 {
		cfg.Scan.Roots = append([]string(nil), cfg.System.AllowedRoots...)
	}
	return &cfg, nil
}

// Save writes the config atomically: marshal → temp file in the same dir → fsync
// → rename over the target. A crash mid-write therefore cannot corrupt the
// existing config (on Windows, os.Rename uses MoveFileEx with REPLACE_EXISTING).
func (c *Config) Save(path string) error {
	data, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, ".config-*.yaml.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op once rename succeeds
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}

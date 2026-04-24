package config

import (
	"os"

	"gopkg.in/yaml.v3"
)

type Config struct {
	Server    ServerConfig    `yaml:"server"`
	Scan      ScanConfig      `yaml:"scan"`
	Thumbnail ThumbnailConfig `yaml:"thumbnail"`
	System    SystemConfig    `yaml:"system,omitempty"`
}

type ServerConfig struct {
	Host string `yaml:"host"`
	Port int    `yaml:"port"`
}

type ScanConfig struct {
	Roots           []string `yaml:"roots,omitempty"`
	VideoExtensions []string `yaml:"video_extensions"`
	ImageExtensions []string `yaml:"image_extensions"`
}

// GetRoots returns configured roots, or auto-detects all drives if empty.
func (s *ScanConfig) GetRoots() []string {
	if len(s.Roots) > 0 {
		return s.Roots
	}
	var drives []string
	for _, letter := range "ABCDEFGHIJKLMNOPQRSTUVWXYZ" {
		path := string(letter) + ":\\"
		if _, err := os.Stat(path); err == nil {
			drives = append(drives, path)
		}
	}
	return drives
}

type ThumbnailConfig struct {
	CacheDir string `yaml:"cache_dir"`
	MaxSize  int    `yaml:"max_size"`
	Format   string `yaml:"format"`
}

type SystemConfig struct {
	AllowedRoots []string `yaml:"allowed_roots,omitempty"`
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

func (c *Config) Save(path string) error {
	data, err := yaml.Marshal(c)
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0644)
}

package config

import (
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"

	"gopkg.in/yaml.v3"
)

// DefaultTextExtensions is used when scan.text_extensions is omitted.
var DefaultTextExtensions = []string{".txt", ".epub", ".mobi", ".azw3"}

type Config struct {
	Server    ServerConfig    `yaml:"server" json:"server"`
	Scan      ScanConfig      `yaml:"scan" json:"scan"`
	Thumbnail ThumbnailConfig `yaml:"thumbnail" json:"thumbnail"`
	System    SystemConfig    `yaml:"system,omitempty" json:"system,omitempty"`
	Debug     DebugConfig     `yaml:"debug,omitempty" json:"debug,omitempty"`
}

// DebugConfig holds optional debug-only features. All fields default to off and
// must be explicitly enabled by the operator (config.debug.* in config.yaml or
// the matching --debug-* CLI flag). Round 32 S3 introduced Pprof to gate the
// /debug/pprof/* routes — they were previously registered unconditionally,
// exposing heap/goroutine data to any LAN host that passed PrivateNetOnly.
type DebugConfig struct {
	Pprof bool `yaml:"pprof,omitempty" json:"pprof,omitempty"`
}

type ServerConfig struct {
	Host  string `yaml:"host" json:"host"`
	Port  int    `yaml:"port" json:"port"`
	Token string `yaml:"token,omitempty" json:"token,omitempty"`
	// LanPairing opts into POST /api/v1/pair: an unauthenticated LAN
	// requester may fetch the bearer token once (zero-touch app setup —
	// HTTP auth AND the BLE handshake key both derive from it). This
	// deliberately exposes the token to the LAN; intended for trusted home
	// networks during initial pairing, to be switched off afterwards.
	LanPairing bool `yaml:"lan_pairing" json:"lan_pairing"`
}

type ScanConfig struct {
	Roots           []string `yaml:"roots,omitempty" json:"roots,omitempty"`
	VideoExtensions []string `yaml:"video_extensions" json:"video_extensions"`
	ImageExtensions []string `yaml:"image_extensions" json:"image_extensions"`
	TextExtensions  []string `yaml:"text_extensions,omitempty" json:"text_extensions,omitempty"`
	AutoDetectRoots bool     `yaml:"auto_detect_roots,omitempty" json:"auto_detect_roots,omitempty"`

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
//
// Round 29 fix: Server is projected onto ServerConfigPublic which omits the
// bearer Token. Previously ConfigPublic embedded ServerConfig directly, which
// leaked the token in GET /api/v1/admin/config responses — defeating the auth
// layer. See TestPublicRedactsServerToken.
type ConfigPublic struct {
	Server    ServerConfigPublic   `json:"server"`
	Scan      ScanConfigPublic     `json:"scan"`
	Thumbnail ThumbnailConfig      `json:"thumbnail"`
	System    SystemConfigPublic   `json:"system"`
}

// ServerConfigPublic mirrors only the user-facing fields of ServerConfig. The
// bearer Token is intentionally omitted — it must NEVER be exposed to clients
// via any admin/config endpoint, otherwise the entire auth layer is bypassable
// by anyone who can read the config response.
type ServerConfigPublic struct {
	Host string `json:"host"`
	Port int    `json:"port"`
	// Token intentionally omitted — never expose to clients.
}

// ScanConfigPublic mirrors only the user-facing fields of ScanConfig. The internal
// autoRoots/autoRootsOnce cache (the latter is a sync.Once and must not be copied)
// is intentionally omitted.
type ScanConfigPublic struct {
	Roots           []string `json:"roots,omitempty"`
	VideoExtensions []string `json:"video_extensions"`
	ImageExtensions []string `json:"image_extensions"`
	TextExtensions  []string `json:"text_extensions,omitempty"`
	AutoDetectRoots bool     `json:"auto_detect_roots,omitempty"`
}

type SystemConfigPublic struct {
	AllowedRoots []string `json:"allowed_roots,omitempty"`
	EnableDelete bool     `json:"enable_delete,omitempty"`
}

// Public returns a copy of the config with sensitive operational fields removed.
func (c *Config) Public() ConfigPublic {
	return ConfigPublic{
		Server:    ServerConfigPublic{Host: c.Server.Host, Port: c.Server.Port},
		Scan:      ScanConfigPublic{Roots: c.Scan.Roots, VideoExtensions: c.Scan.VideoExtensions, ImageExtensions: c.Scan.ImageExtensions, TextExtensions: c.Scan.TextExtensions, AutoDetectRoots: c.Scan.AutoDetectRoots},
		Thumbnail: c.Thumbnail,
		System:    SystemConfigPublic{AllowedRoots: c.System.AllowedRoots, EnableDelete: c.System.EnableDelete},
	}
}

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

// GetSystemAllowedRoots returns configured system browse roots.
// If empty, system browse is disabled until explicitly configured.
func (c *Config) GetSystemAllowedRoots() []string {
	if len(c.System.AllowedRoots) > 0 {
		return c.System.AllowedRoots
	}
	return []string{}
}

// LoadFromBytes parses config from a YAML byte slice. Used by tests to avoid
// disk I/O; production code uses Load(path).
func LoadFromBytes(data []byte) (*Config, error) {
	// EnableDelete defaults to false (zero value). It must be opted into
	// explicitly via `system.enable_delete: true` in config.yaml, and the
	// delete endpoint additionally requires a non-empty server.token.
	cfg := Config{}
	if err := yaml.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}
	if len(cfg.Scan.Roots) == 0 && len(cfg.System.AllowedRoots) > 0 {
		cfg.Scan.Roots = append([]string(nil), cfg.System.AllowedRoots...)
	}
	if len(cfg.Scan.TextExtensions) == 0 {
		cfg.Scan.TextExtensions = append([]string(nil), DefaultTextExtensions...)
	}
	return &cfg, nil
}

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
		slog.Warn(" Authenticated clients can delete files under system.allowed_roots.")
		if cfg.Server.Token == "" {
			slog.Warn(" WARNING: server.token is empty — /api/v1/system/delete will")
			slog.Warn(" reject every request (403) until a token is configured.")
		}
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

func Load(path string) (*Config, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	return LoadFromBytes(data)
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

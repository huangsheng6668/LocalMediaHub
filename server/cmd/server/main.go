package main

import (
	"flag"
	"log/slog"
	"os"
	"net/http"

	"github.com/localmediahub/server/internal/ble"
	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/gui"
	localmdns "github.com/localmediahub/server/internal/mdns"
	"github.com/localmediahub/server/internal/server"
)

var (
	headless        bool
	autoDetectRoots bool
	debugPprof      bool
)

func main() {
	flag.BoolVar(&headless, "headless", false, "Run without GUI (system tray)")
	flag.BoolVar(&autoDetectRoots, "auto-detect-roots", false,
		"Force-enable auto-detection of all drives as scan roots (one-shot override; "+
			"also achievable via scan.auto_detect_roots in config.yaml)")
	flag.BoolVar(&debugPprof, "debug-pprof", false,
		"enable /debug/pprof/* routes (overrides config.debug.pprof)")
	flag.Parse()

	cfg, err := config.Load("config.yaml")
	if err != nil {
		slog.Error("Failed to load config", "error", err); os.Exit(1)
	}

	// Round 32 S3: --debug-pprof flag overrides config.debug.pprof. The flag
	// is OR-ed into the config field so server.New sees a single source of
	// truth. Without this, /debug/pprof/* routes are not registered at all
	// (defense-in-depth: off by default, opt-in only).
	if debugPprof {
		cfg.Debug.Pprof = true
	}

	// Phase 3: validate config after Load, incorporating CLI override flags.
	if err := cfg.Validate(autoDetectRoots); err != nil {
		slog.Error("Invalid config", "error", err); os.Exit(1)
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

	// Start mDNS
	mdnsSvc, err := localmdns.NewService()
	if err != nil {
		slog.Warn("Failed to create mDNS service", "error", err)
	} else {
		if err := mdnsSvc.Start(cfg.Server.Host, cfg.Server.Port); err != nil {
			slog.Warn("Failed to start mDNS", "error", err)
		}
	}

	// BLE control channel (experimental, opt-in on client side). Server-side
	// startup failure is non-fatal: if no Bluetooth adapter is present or the
	// build lacks the "bluetooth" tag, BLE is simply unavailable and the
	// server continues with Wi-Fi/HTTP only (zero-regression principle).
	bleAdapter, err := ble.NewTinyGoAdapter()
	if err != nil {
		slog.Info("BLE channel disabled on server", "reason", err)
	} else {
		blePeripheral := ble.NewPeripheral(bleAdapter)
		if err := blePeripheral.Start(); err != nil {
			slog.Warn("BLE Peripheral start failed; continuing without BLE", "error", err)
		} else {
			slog.Info("BLE Peripheral advertising", "service", ble.ServiceUUID)
		}
	}

	if headless {
		runHeadless(cfg)
	} else {
		if !isSystraySupported() {
			slog.Warn("System tray is not supported in this build (requires Windows with CGO enabled). Automatically falling back to headless mode.")
			runHeadless(cfg)
		} else if !isInteractiveSession() {
			slog.Warn("Non-interactive window station or headless environment detected. Automatically falling back to headless mode.")
			runHeadless(cfg)
		} else {
			gui.Run(cfg)
		}
	}
}

func runHeadless(cfg *config.Config) {
	s, err := server.New(cfg)
	if err != nil {
		slog.Error("Failed to create server", "error", err); os.Exit(1)
	}

	slog.Info("LocalMediaHub Server Initialized", "ip", s.IP)
	slog.Info("Starting LocalMediaHub (headless)", "host", cfg.Server.Host, "port", cfg.Server.Port)
	if err := s.Start(); err != nil && err != http.ErrServerClosed {
		slog.Error("Server error", "error", err); os.Exit(1)
	}
}

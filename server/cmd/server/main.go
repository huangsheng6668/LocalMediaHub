package main

import (
	"flag"
	"log/slog"
	"os"
	"net/http"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/gui"
	localmdns "github.com/localmediahub/server/internal/mdns"
	"github.com/localmediahub/server/internal/server"
)

var headless bool

func main() {
	flag.BoolVar(&headless, "headless", false, "Run without GUI (system tray)")
	flag.Parse()

	cfg, err := config.Load("config.yaml")
	if err != nil {
		slog.Error("Failed to load config", "error", err); os.Exit(1)
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

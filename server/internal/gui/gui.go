package gui

import (
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/netutil"
	"github.com/localmediahub/server/internal/server"
	"github.com/localmediahub/server/internal/systray"
)

// Run starts the server + system tray (GUI mode).
func Run(cfg *config.Config) {
	s, err := server.New(cfg)
	if err != nil {
		slog.Error("Failed to create server", "error", err); os.Exit(1)
	}

	ip := netutil.GetLocalIP()
	srvURL := fmt.Sprintf("http://%s:%d", ip, cfg.Server.Port)

	go func() {
		slog.Info("LocalMediaHub running", "url", srvURL)
		if err := s.Start(); err != nil && err != http.ErrServerClosed {
			slog.Error("Server error", "error", err); os.Exit(1)
		}
	}()

	tray := systray.New(srvURL, func() {
		s.Stop()
		os.Exit(0)
	})

	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)

	go func() {
		<-sigCh
		s.Stop()
		os.Exit(0)
	}()

	tray.Run()
}

package gui

import (
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/localmediahub/server/internal/config"
	"github.com/localmediahub/server/internal/server"
	"github.com/localmediahub/server/internal/systray"
)

// Run starts the server + system tray (GUI mode).
func Run(cfg *config.Config) {
	s, err := server.New(cfg)
	if err != nil {
		slog.Error("Failed to create server", "error", err); os.Exit(1)
	}

	ip := getLocalIP()
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

func getLocalIP() string {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "127.0.0.1"
	}
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() && ipNet.IP.To4() != nil {
			ip := ipNet.IP.To4()
			if ip[0] == 169 && ip[1] == 254 {
				continue
			}
			return ip.String()
		}
	}
	return "127.0.0.1"
}

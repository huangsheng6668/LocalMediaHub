package main

import (
	"flag"
	"log"
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
		log.Fatalf("Failed to load config: %v", err)
	}

	// Start mDNS
	mdnsSvc, err := localmdns.NewService(cfg.Server.Host, cfg.Server.Port)
	if err != nil {
		log.Printf("Warning: failed to create mDNS service: %v", err)
	} else {
		if err := mdnsSvc.Start(cfg.Server.Host, cfg.Server.Port); err != nil {
			log.Printf("Warning: failed to start mDNS: %v", err)
		}
	}

	if headless {
		runHeadless(cfg)
	} else {
		gui.Run(cfg)
	}
}

func runHeadless(cfg *config.Config) {
	s, err := server.New(cfg)
	if err != nil {
		log.Fatalf("Failed to create server: %v", err)
	}

	log.Printf("LocalMediaHub Server Initialized. LAN IP: %s", s.IP)
	log.Printf("Starting LocalMediaHub on %s:%d (headless)", cfg.Server.Host, cfg.Server.Port)
	if err := s.Start(); err != nil && err != http.ErrServerClosed {
		log.Fatalf("Server error: %v", err)
	}
}

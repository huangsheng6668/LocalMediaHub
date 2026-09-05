package mdns

import (
	"fmt"
	"log/slog"
	"net"

	"github.com/hashicorp/mdns"

	"github.com/localmediahub/server/internal/netutil"
)

type Service struct {
	server *mdns.Server
}

func NewService() (*Service, error) {
	return &Service{}, nil
}

func (s *Service) Start(host string, port int) error {
	ip := netutil.GetLocalIP()

	parsedIP := net.ParseIP(ip)
	if parsedIP == nil {
		return fmt.Errorf("mDNS: invalid IP: %s", ip)
	}

	service, err := mdns.NewMDNSService(
		"LocalMediaHub",       // instance name
		"_localmediahub._tcp", // service type (matches Android client)
		"",                    // domain
		"",                    // host (auto-detected)
		port,                  // port
		[]net.IP{parsedIP},    // IPs to advertise
		[]string{"path=/"},    // TXT records
	)
	if err != nil {
		return fmt.Errorf("mDNS: failed to create service: %w", err)
	}

	server, err := mdns.NewServer(&mdns.Config{Zone: service})
	if err != nil {
		return fmt.Errorf("mDNS: failed to start server: %w", err)
	}

	s.server = server
	slog.Info("mDNS advertising", "service", "_localmediahub._tcp.local.", "ip", ip, "port", port)
	return nil
}

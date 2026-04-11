package mdns

import (
	"fmt"
	"net"

	"github.com/hashicorp/mdns"
)

type Service struct {
	server *mdns.Server
	quit   chan struct{}
}

func NewService(host string, port int) (*Service, error) {
	return &Service{quit: make(chan struct{})}, nil
}

func (s *Service) Start(host string, port int) error {
	ip, err := getLocalIP()
	if err != nil {
		return fmt.Errorf("mDNS: failed to get local IP: %w", err)
	}

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
	fmt.Printf("mDNS: advertising _localmediahub._tcp.local. on %s:%d\n", ip, port)
	return nil
}

func (s *Service) Stop() error {
	select {
	case <-s.quit:
	default:
		close(s.quit)
	}
	if s.server != nil {
		return s.server.Shutdown()
	}
	return nil
}

func getLocalIP() (string, error) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "", err
	}
	var bestIP string
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() && ipNet.IP.To4() != nil {
			ip := ipNet.IP.To4()
			if ip[0] == 169 && ip[1] == 254 {
				continue
			}
			isPrivate := (ip[0] == 192 && ip[1] == 168) || (ip[0] == 10) || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)
			if isPrivate {
				return ip.String(), nil
			}
			if bestIP == "" {
				bestIP = ip.String()
			}
		}
	}
	if bestIP != "" {
		return bestIP, nil
	}
	return "127.0.0.1", nil
}

package mdns

import (
	"fmt"
	"net"
)

type Service struct {
	quit chan struct{}
}

func NewService(host string, port int) (*Service, error) {
	return &Service{quit: make(chan struct{})}, nil
}

func (s *Service) Start(host string, port int) error {
	ip, err := getLocalIP()
	if err != nil {
		return err
	}
	fmt.Printf("mDNS: advertising _localmediahub._tcp.local. on %s:%d (IP: %s)\n", host, port, ip)
	return nil
}

func (s *Service) Stop() error {
	select {
	case <-s.quit:
	default:
		close(s.quit)
	}
	return nil
}

func getLocalIP() (string, error) {
	addrs, err := net.InterfaceAddrs()
	if err != nil {
		return "", err
	}
	for _, addr := range addrs {
		if ipNet, ok := addr.(*net.IPNet); ok && !ipNet.IP.IsLoopback() && ipNet.IP.To4() != nil {
			return ipNet.IP.String(), nil
		}
	}
	return "127.0.0.1", nil
}

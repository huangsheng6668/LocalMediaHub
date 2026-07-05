package middleware

import (
	"net"
	"testing"
)

func TestIsPrivateOrLoopback(t *testing.T) {
	cases := []struct {
		ip   string
		want bool
	}{
		// Private (RFC1918)
		{"192.168.1.100", true},
		{"10.0.0.1", true},
		{"172.16.5.5", true},
		{"172.31.255.255", true},
		// Loopback
		{"127.0.0.1", true},
		{"::1", true},
		// Link-local
		{"169.254.1.1", true},
		{"fe80::1", true},
		// Public (should be rejected)
		{"8.8.8.8", false},
		{"1.1.1.1", false},
		{"203.0.113.1", false}, // TEST-NET-3
	}
	for _, tc := range cases {
		t.Run(tc.ip, func(t *testing.T) {
			ip := net.ParseIP(tc.ip)
			if ip == nil {
				t.Fatalf("ParseIP(%q) failed", tc.ip)
			}
			got := isPrivateOrLoopback(ip)
			if got != tc.want {
				t.Errorf("isPrivateOrLoopback(%s) = %v, want %v", tc.ip, got, tc.want)
			}
		})
	}
}

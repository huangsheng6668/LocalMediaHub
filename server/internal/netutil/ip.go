// Package netutil centralizes local-IP discovery so the mDNS advertiser, the
// CORS allow-list, and the GUI tray URL agree on which address to advertise.
// Virtual adapters (VMware / VirtualBox / Hyper-V / WSL / Docker / VPN
// tunnels) are skipped because they sit on host-only or NAT subnets that LAN
// clients cannot route to. Previously this filtering existed only in
// server.go's getAllLocalIPs; mdns.go and gui.go picked the first private IP
// from a flat InterfaceAddrs sweep and could advertise a VM-only address.
package netutil

import (
	"net"
	"strings"
)

// virtualAdapterPrefixes lists lower-cased interface-name prefixes that mark a
// virtual machine / container / tunnel adapter. Matches are skipped by
// GetAllLocalIPs so discovery targets the physical LAN only.
var virtualAdapterPrefixes = []string{
	"vmnet",     // VMware
	"vboxnet",   // VirtualBox host-only
	"vethernet", // Hyper-V / WSL
	"docker",    // Docker bridge
	"virtualbox", // alt VirtualBox naming
	"tap-",      // OpenVPN / TAP
	"tun-",      // tunnel adapters
	"isatap",    // ISATAP tunneling
	"teredo",    // Teredo tunneling
}

// IsVirtualAdapter reports whether the interface name marks a VM / container
// / tunnel adapter.
func IsVirtualAdapter(name string) bool {
	lower := strings.ToLower(name)
	for _, p := range virtualAdapterPrefixes {
		if strings.HasPrefix(lower, p) {
			return true
		}
	}
	return false
}

// GetAllLocalIPs returns all usable IPv4 addresses on this machine, with
// private LAN addresses first, then any other non-loopback address, and
// finally 127.0.0.1. APIPA (169.254.x.x) addresses and addresses on virtual
// adapters are skipped so mDNS broadcasts the host's real LAN IP rather than
// a host-only/VM subnet that other devices can't reach. The ordering makes
// the first element a good default for mDNS broadcast, while the full list is
// used to build the CORS allow-list so browsers on the LAN can reach the
// embedded Web UI.
func GetAllLocalIPs() []string {
	var private, others []string

	ifaces, err := net.Interfaces()
	if err != nil {
		return []string{"127.0.0.1"}
	}
	for _, ifc := range ifaces {
		if IsVirtualAdapter(ifc.Name) {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, addr := range addrs {
			ipNet, ok := addr.(*net.IPNet)
			if !ok || ipNet.IP.IsLoopback() {
				continue
			}
			ip := ipNet.IP.To4()
			if ip == nil {
				continue
			}
			// Skip APIPA (link-local) addresses.
			if ip[0] == 169 && ip[1] == 254 {
				continue
			}

			isPrivate := (ip[0] == 192 && ip[1] == 168) ||
				(ip[0] == 10) ||
				(ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31)

			if isPrivate {
				private = append(private, ip.String())
			} else {
				others = append(others, ip.String())
			}
		}
	}

	result := append(private, others...)
	result = append(result, "127.0.0.1")
	return result
}

// GetLocalIP returns the best LAN IPv4 address (private LAN first), falling
// back to 127.0.0.1 when no interface exposes a usable address.
func GetLocalIP() string {
	ips := GetAllLocalIPs()
	if len(ips) == 0 {
		return "127.0.0.1"
	}
	return ips[0]
}

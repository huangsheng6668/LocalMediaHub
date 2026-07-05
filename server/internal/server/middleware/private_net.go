package middleware

import (
	"net"

	"github.com/labstack/echo/v4"
)

// PrivateNetOnly rejects requests whose source IP is not a private/loopback
// address. Allowed: RFC1918 (10/8, 172.16/12, 192.168/16), loopback
// (127.0.0.0/8, ::1/128), link-local (169.254/16, fe80::/10).
//
// This matches the project's LAN-only deployment (mDNS/Bonjour discovery)
// and prevents leaking pprof data (heap dumps, goroutine traces, CPU
// profiles) to the public internet if the server is accidentally exposed.
//
// Implementation note: relies on Go 1.17+ net.IP.IsPrivate() which covers
// RFC1918 + RFC4193 (fc00::/7).
func PrivateNetOnly() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ip := net.ParseIP(c.RealIP())
			if ip == nil {
				return echo.ErrForbidden
			}
			if !isPrivateOrLoopback(ip) {
				return echo.ErrForbidden
			}
			return next(c)
		}
	}
}

func isPrivateOrLoopback(ip net.IP) bool {
	return ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast()
}

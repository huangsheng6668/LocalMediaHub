package middleware

import (
	"github.com/labstack/echo/v4"
	"github.com/labstack/echo/v4/middleware"
)

// CORS returns a CORS middleware restricted to the given origins.
//
// We intentionally do NOT use the wildcard "*" here. For a LAN media server
// that exposes mutating/destructive endpoints (config writes, file/folder
// deletion, scan triggers) an open CORS policy would let any web page on the
// network drive the server. Restricting allowed origins to the host's own LAN
// IPv4 addresses plus localhost keeps the embedded Web UI usable from other
// devices on the same network while blocking arbitrary cross-origin callers.
func CORS(allowedOrigins []string) echo.MiddlewareFunc {
	cfg := middleware.CORSConfig{
		AllowOrigins: allowedOrigins,
		AllowMethods: []string{echo.GET, echo.POST, echo.PUT, echo.DELETE, echo.OPTIONS},
		// Authorization is allowed so a future token-based auth layer can reuse
		// this middleware without changes.
		AllowHeaders:     []string{echo.HeaderOrigin, echo.HeaderContentType, echo.HeaderAccept, echo.HeaderAuthorization},
		AllowCredentials: true,
	}
	return middleware.CORSWithConfig(cfg)
}

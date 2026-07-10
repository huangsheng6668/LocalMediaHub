package middleware

import (
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"
)

// BearerToken returns an Echo middleware that gates requests on an
// `Authorization: Bearer <token>` header. Comparison uses
// `crypto/subtle.ConstantTimeCompare` to prevent timing attacks that could
// otherwise leak the configured token byte-by-byte.
//
// When `token` is empty, the middleware is a no-op (passthrough). This keeps
// existing deployments working until the operator explicitly sets a token.
// Server startup logs a security warning when running in this open mode.
func BearerToken(token string) echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			if token == "" {
				return next(c)
			}
			auth := c.Request().Header.Get(echo.HeaderAuthorization)
			const prefix = "Bearer "
			if !strings.HasPrefix(auth, prefix) {
				return c.JSON(
					http.StatusUnauthorized,
					map[string]string{"error": "Unauthorized"},
				)
			}
			provided := auth[len(prefix):]
			if subtle.ConstantTimeCompare([]byte(provided), []byte(token)) != 1 {
				return c.JSON(
					http.StatusUnauthorized,
					map[string]string{"error": "Unauthorized"},
				)
			}
			return next(c)
		}
	}
}

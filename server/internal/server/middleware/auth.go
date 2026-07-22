package middleware

import (
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
	"strings"

	"github.com/labstack/echo/v4"
)

// BearerToken returns an Echo middleware that gates requests on an
// `Authorization: Bearer <token>` header. Comparison uses
// `crypto/subtle.ConstantTimeCompare` over SHA-256 hashes to prevent
// timing attacks and length leakage.
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
			var provided string
			auth := c.Request().Header.Get(echo.HeaderAuthorization)
			const prefix = "Bearer "
			if strings.HasPrefix(auth, prefix) {
				provided = auth[len(prefix):]
			} else {
				// Fallback for clients that cannot set headers (e.g. <img src>
				// tags loading from /api/v1/books/image). Header takes
				// precedence so this does not change behavior for any
				// existing client.
				provided = c.QueryParam("token")
			}
			providedHash := sha256.Sum256([]byte(provided))
			tokenHash := sha256.Sum256([]byte(token))
			if subtle.ConstantTimeCompare(providedHash[:], tokenHash[:]) != 1 {
				return c.JSON(
					http.StatusUnauthorized,
					map[string]string{"error": "Unauthorized"},
				)
			}
			return next(c)
		}
	}
}

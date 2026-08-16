package middleware

import (
	"github.com/labstack/echo/v4"
)

// SecurityHeaders adds browser security headers to all responses.
//
// Coverage:
//   - X-Frame-Options: DENY           → prevents clickjacking (T4-03)
//   - X-Content-Type-Options: nosniff → stops MIME sniffing (T4-04)
//   - Referrer-Policy: no-referrer   → prevents leaking URLs to external resources (T4-04)
//   - Content-Security-Policy        → restricts resource loading to self (T4-04);
//     provides defense-in-depth against XSS even if escapeHtml is missed somewhere
//
// style-src no longer carries 'unsafe-inline': every inline style="" attribute
// was migrated to CSS classes (see style.css "CSP-safe replacements" section),
// and dynamic styling goes through CSSOM property assignment, which CSP does
// not block.
//
// Not added (intentional):
//   - Strict-Transport-Security: only effective under HTTPS; TLS is deferred
//     per main spec section 6.2.
//   - Permissions-Policy: project doesn't use camera/mic/geolocation APIs (YAGNI).
func SecurityHeaders() echo.MiddlewareFunc {
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			h := c.Response().Header()
			h.Set("X-Frame-Options", "DENY")
			h.Set("X-Content-Type-Options", "nosniff")
			h.Set("Referrer-Policy", "no-referrer")
			h.Set("Content-Security-Policy",
				"default-src 'self'; "+
					"script-src 'self'; "+
					"style-src 'self'; "+
					"img-src 'self' data:; "+
					"media-src 'self'; "+
					"connect-src 'self'")
			return next(c)
		}
	}
}

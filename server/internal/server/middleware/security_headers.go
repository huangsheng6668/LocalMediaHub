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
//     provides defense-in-depth against XSS even if escapeHtml is missed somewhere;
//     Phase 9 (L-12) adds base-uri 'none' (no <base> injection hijacking relative
//     URLs), object-src 'none' (no <object>/<embed> plugins) and form-action
//     'self' (no cross-origin form submission) — the SPA has no <base>/<object>
//     usage and its only <form> is method="dialog" which never navigates.
//
// style-src no longer carries 'unsafe-inline': every inline style="" attribute
// was migrated to CSS classes (see the "CSP-safe replacements" sections in
// the css/ stylesheets), and dynamic styling goes through CSSOM property
// assignment, which CSP does not block.
//
// Not added (intentional):
//   - Strict-Transport-Security: only meaningful under HTTPS, and TLS is a
//     decided NON-goal — LocalMediaHub targets trusted home LANs and is
//     served over plain HTTP by IP address (public CAs cannot cover bare LAN
//     IPs, and self-signed CA import/TOFU machinery outweighs the benefit in
//     this threat model). Bearer-token auth + LAN-only discovery are the
//     access controls instead. Re-evaluate only if a WAN/Internet deployment
//     mode is ever added.
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
					"connect-src 'self'; "+
					"base-uri 'none'; "+
					"object-src 'none'; "+
					"form-action 'self'")
			return next(c)
		}
	}
}

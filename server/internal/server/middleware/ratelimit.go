package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
)

// RateLimit returns a middleware that allows at most `max` requests per `window`
// per client IP. Requests over the limit get 429 Too Many Requests with a JSON
// error body matching the project's standard error envelope.
//
// Implementation: in-memory map[string]*bucket guarded by sync.Mutex. The
// bucket counter resets when the window elapses. Not distributed — sufficient
// for single-process LAN deployment. Memory grows with distinct client IPs,
// which is bounded by LAN size.
//
// Use case: per-route rate limiting on sensitive endpoints (scan trigger,
// delete) to prevent accidental or malicious resource exhaustion. Does NOT
// apply globally — media streaming endpoints (videos, system/stream) must not
// be rate-limited or normal playback breaks.
func RateLimit(max int, window time.Duration) echo.MiddlewareFunc {
	type bucket struct {
		count   int
		resetAt time.Time
	}
	var (
		mu      sync.Mutex
		buckets = make(map[string]*bucket)
	)
	return func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			ip := c.RealIP()
			mu.Lock()
			now := time.Now()
			b, ok := buckets[ip]
			if !ok || now.After(b.resetAt) {
				buckets[ip] = &bucket{count: 1, resetAt: now.Add(window)}
				mu.Unlock()
				return next(c)
			}
			if b.count >= max {
				mu.Unlock()
				return c.JSON(http.StatusTooManyRequests,
					map[string]string{"error": "rate limit exceeded"})
			}
			b.count++
			mu.Unlock()
			return next(c)
		}
	}
}

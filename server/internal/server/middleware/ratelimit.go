package middleware

import (
	"net/http"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
)

// defaultMaxBuckets is the maximum number of distinct client IP buckets the
// in-memory map will hold before the least-recently-used entry is evicted.
// This bounds memory growth when a forged X-Forwarded-For header presents a
// new IP on every request. 4096 is small enough for an O(n) eviction scan
// yet large enough for typical LAN deployments.
const defaultMaxBuckets = 4096

// RateLimit returns a middleware that allows at most `max` requests per `window`
// per client IP. Requests over the limit get 429 Too Many Requests with a JSON
// error body matching the project's standard error envelope.
//
// Implementation: in-memory map[string]*bucket guarded by sync.Mutex. The
// bucket counter resets when the window elapses. Not distributed — sufficient
// for single-process LAN deployment. Memory is bounded by defaultMaxBuckets
// via LRU eviction, mitigating forged X-Forwarded-For memory-exhaustion.
//
// Use case: per-route rate limiting on sensitive endpoints (scan trigger,
// delete) to prevent accidental or malicious resource exhaustion. Does NOT
// apply globally — media streaming endpoints (videos, system/stream) must not
// be rate-limited or normal playback breaks.
func RateLimit(max int, window time.Duration) echo.MiddlewareFunc {
	return RateLimitWithConfig(max, window, defaultMaxBuckets)
}

// RateLimitWithConfig is like RateLimit but allows the caller to configure the
// maximum number of tracked client IP buckets. When the cap is reached, the
// bucket with the oldest lastSeen timestamp is evicted before inserting a new
// entry. If maxBuckets < 1 it is clamped to 1.
func RateLimitWithConfig(max int, window time.Duration, maxBuckets int) echo.MiddlewareFunc {
	if maxBuckets < 1 {
		maxBuckets = 1
	}
	type bucket struct {
		count   int
		resetAt time.Time
		lastSeen time.Time
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
			if b, ok := buckets[ip]; ok && !now.After(b.resetAt) {
				b.lastSeen = now
				if b.count >= max {
					mu.Unlock()
					return c.JSON(http.StatusTooManyRequests,
						map[string]string{"error": "rate limit exceeded"})
				}
				b.count++
				mu.Unlock()
				return next(c)
			}
			// Need to insert a new (or reset) bucket. Enforce capacity first.
			if _, exists := buckets[ip]; !exists && len(buckets) >= maxBuckets {
				// Evict the least-recently-used bucket (oldest lastSeen).
				var oldestKey string
				var oldestSeen time.Time
				first := true
				for k, b := range buckets {
					if first || b.lastSeen.Before(oldestSeen) {
						oldestKey = k
						oldestSeen = b.lastSeen
						first = false
					}
				}
				delete(buckets, oldestKey)
			}
			buckets[ip] = &bucket{count: 1, resetAt: now.Add(window), lastSeen: now}
			mu.Unlock()
			return next(c)
		}
	}
}

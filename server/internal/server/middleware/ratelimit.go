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
// maximum number of tracked client IP buckets. When the cap is reached, a
// deterministic eviction policy picks the victim bucket:
//  1. Expired buckets (window elapsed) are preferred victims — they are useless.
//  2. Among buckets with the same expiry status, the one with the oldest
//     lastSeen is evicted.
//  3. When lastSeen also ties (common under sub-millisecond clustering or
//     concurrent access), a monotonically increasing insertion counter (`seq`)
//     breaks the tie so eviction is stable regardless of Go's randomized map
//     iteration order.
//
// If maxBuckets < 1 it is clamped to 1.
func RateLimitWithConfig(max int, window time.Duration, maxBuckets int) echo.MiddlewareFunc {
	if maxBuckets < 1 {
		maxBuckets = 1
	}
	type bucket struct {
		count    int
		resetAt  time.Time
		lastSeen time.Time
		seq      uint64
	}
	var (
		mu      sync.Mutex
		buckets = make(map[string]*bucket)
		nextSeq uint64
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
				// Deterministic eviction:
				//   1) prefer expired buckets;
				//   2) among same expiry status, oldest lastSeen;
				//   3) on lastSeen tie, oldest seq (insertion order).
				var (
					evictKey   string
					evictReset time.Time
					evictSeen  time.Time
					evictSeq   uint64
				)
				first := true
				for k, b := range buckets {
					if first {
						evictKey, evictReset, evictSeen, evictSeq = k, b.resetAt, b.lastSeen, b.seq
						first = false
						continue
					}
					curExpired := now.After(evictReset)
					candExpired := now.After(b.resetAt)
					switch {
					case candExpired && !curExpired:
						// Candidate is expired, current pick is not — candidate wins.
						evictKey, evictReset, evictSeen, evictSeq = k, b.resetAt, b.lastSeen, b.seq
					case !candExpired && curExpired:
						// Current pick is expired, candidate is not — keep current.
					case b.lastSeen.Before(evictSeen):
						evictKey, evictReset, evictSeen, evictSeq = k, b.resetAt, b.lastSeen, b.seq
					case b.lastSeen.Equal(evictSeen) && b.seq < evictSeq:
						evictKey, evictReset, evictSeen, evictSeq = k, b.resetAt, b.lastSeen, b.seq
					}
				}
				delete(buckets, evictKey)
			}
			nextSeq++
			buckets[ip] = &bucket{count: 1, resetAt: now.Add(window), lastSeen: now, seq: nextSeq}
			mu.Unlock()
			return next(c)
		}
	}
}

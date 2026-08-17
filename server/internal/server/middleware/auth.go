package middleware

import (
	"crypto/sha256"
	"crypto/subtle"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/labstack/echo/v4"
)

// AuthFailureLimiter tracks per-IP 401 bursts for BearerToken. Bounded at
// maxIPs entries with deterministic eviction (expired window first, then
// oldest windowStart, then insertion order) mirroring ratelimit.go policy.
type AuthFailureLimiter struct {
	mu      sync.Mutex
	max     int
	window  time.Duration
	buckets map[string]*authFailBucket
	insert  map[string]int // ip -> insertion seq for deterministic eviction
	nextSeq int
	maxIPs  int
}

type authFailBucket struct {
	count       int
	windowStart time.Time
}

func NewAuthFailureLimiter(max int, window time.Duration) *AuthFailureLimiter {
	return &AuthFailureLimiter{max: max, window: window, buckets: map[string]*authFailBucket{}, insert: map[string]int{}, maxIPs: 4096}
}

func (l *AuthFailureLimiter) Blocked(ip string, now time.Time) bool {
	l.mu.Lock(); defer l.mu.Unlock()
	b := l.buckets[ip]
	return b != nil && now.Sub(b.windowStart) < l.window && b.count >= l.max
}

func (l *AuthFailureLimiter) RecordFailure(ip string, now time.Time) {
	l.mu.Lock(); defer l.mu.Unlock()
	b := l.buckets[ip]
	if b == nil || now.Sub(b.windowStart) >= l.window {
		if b == nil {
			l.insert[ip] = l.nextSeq
			l.nextSeq++
		}
		b = &authFailBucket{windowStart: now}
		l.buckets[ip] = b
		if len(l.buckets) > l.maxIPs {
			l.evictLocked(now)
		}
	}
	b.count++
}

func (l *AuthFailureLimiter) Reset(ip string) {
	l.mu.Lock(); defer l.mu.Unlock()
	delete(l.buckets, ip)
	delete(l.insert, ip)
}

// evictLocked drops one entry when over capacity: expired windows first,
// then oldest windowStart, then lowest insertion seq. Caller holds mu.
func (l *AuthFailureLimiter) evictLocked(now time.Time) {
	var victim string
	for ip, b := range l.buckets {
		if now.Sub(b.windowStart) >= l.window {
			victim = ip
			break
		}
	}
	if victim == "" {
		best := time.Time{}
		for ip, b := range l.buckets {
			if victim == "" || b.windowStart.Before(best) ||
				(b.windowStart.Equal(best) && l.insert[ip] < l.insert[victim]) {
				victim, best = ip, b.windowStart
			}
		}
	}
	if victim != "" {
		delete(l.buckets, victim)
		delete(l.insert, victim)
	}
}

// BearerToken returns an Echo middleware that gates requests on an
// `Authorization: Bearer <token>` header. Comparison uses
// `crypto/subtle.ConstantTimeCompare` over SHA-256 hashes to prevent
// timing attacks and length leakage.
//
// A `?token=` query fallback exists ONLY for GET requests (clients that
// cannot set headers, e.g. <img src> tags); state-changing methods must use
// the header so a leaked token URL cannot drive mutations.
//
// When `token` is empty, the middleware is a no-op (passthrough). This keeps
// existing deployments working until the operator explicitly sets a token.
// Server startup logs a security warning when running in this open mode.
//
// Optional limiters (at most the first is used) add per-IP auth-failure
// backoff: once an IP exceeds `max` 401s inside the window it receives 429
// for the rest of the window — even with the correct token — which thwarts
// online token brute-forcing. A successful authentication resets the IP.
func BearerToken(token string, limiters ...*AuthFailureLimiter) echo.MiddlewareFunc {
	var limiter *AuthFailureLimiter
	if len(limiters) > 0 {
		limiter = limiters[0]
	}
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
			} else if c.Request().Method == http.MethodGet {
				// Query fallback ONLY for GET: <img>/<video> elements cannot
				// send headers. State-changing endpoints (POST/PUT/DELETE)
				// must carry the Authorization header, so a token URL leaked
				// into history or logs cannot drive mutations by itself.
				// Header takes precedence so this does not change behavior
				// for any existing client.
				provided = c.QueryParam("token")
			}
			if limiter != nil && limiter.Blocked(c.RealIP(), time.Now()) {
				return c.JSON(
					http.StatusTooManyRequests,
					map[string]string{"error": "Too many auth failures"},
				)
			}
			providedHash := sha256.Sum256([]byte(provided))
			tokenHash := sha256.Sum256([]byte(token))
			if subtle.ConstantTimeCompare(providedHash[:], tokenHash[:]) != 1 {
				if limiter != nil {
					limiter.RecordFailure(c.RealIP(), time.Now())
				}
				return c.JSON(
					http.StatusUnauthorized,
					map[string]string{"error": "Unauthorized"},
				)
			}
			if limiter != nil {
				limiter.Reset(c.RealIP())
			}
			return next(c)
		}
	}
}

### Task 6: 认证失败限速（M-2）

**Files:**
- Modify: `server/internal/server/middleware/auth.go`
- Test: `server/internal/server/middleware/auth_test.go`

**Interfaces:**
- Produces:
  - `type AuthFailureLimiter struct{...}`；`func NewAuthFailureLimiter(max int, window time.Duration) *AuthFailureLimiter`
  - `func (l *AuthFailureLimiter) RecordFailure(ip string, now time.Time)` / `Blocked(ip string, now time.Time) bool` / `Reset(ip string)`
  - `BearerToken(token string, limiters ...*AuthFailureLimiter)` —— variadic 保持既有调用方编译不变；server.go 传共享单例。
  - 策略：每 IP 每 60s 窗口 10 次 401 → 窗口内后续直接 429；成功认证 `Reset`。桶数上限 4096（确定性淘汰：过期优先 → 最旧窗口 → 插入序）。

- [ ] **Step 1: 写失败测试**

```go
func TestAuthFailureLimiterBlocksAfterBurst(t *testing.T) {
	l := NewAuthFailureLimiter(3, time.Minute)
	now := time.Now()
	for i := 0; i < 3; i++ {
		l.RecordFailure("10.0.0.9", now)
	}
	if !l.Blocked("10.0.0.9", now) {
		t.Fatal("expected blocked after 3 failures")
	}
	if l.Blocked("10.0.0.8", now) {
		t.Fatal("other IP must not be blocked")
	}
	l.Reset("10.0.0.9")
	if l.Blocked("10.0.0.9", now.Add(time.Second)) {
		t.Fatal("reset must clear the window")
	}
	// 窗口过期自动解封
	l.RecordFailure("10.0.0.9", now)
	if l.Blocked("10.0.0.9", now.Add(2*time.Minute)) {
		t.Fatal("window expiry must unblock")
	}
}

func TestBearerTokenReturns429WhenFailureLimited(t *testing.T) {
	e := echo.New()
	limiter := NewAuthFailureLimiter(2, time.Minute)
	e.GET("/p", func(c echo.Context) error { return c.NoContent(200) },
		BearerToken("sekrit", limiter))
	for i := 0; i < 2; i++ {
		req := httptest.NewRequest(http.MethodGet, "/p", nil)
		req.Header.Set("Authorization", "Bearer wrong")
		rec := httptest.NewRecorder()
		e.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("attempt %d = %d, want 401", i, rec.Code)
		}
	}
	req := httptest.NewRequest(http.MethodGet, "/p", nil)
	req.Header.Set("Authorization", "Bearer wrong")
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("third attempt = %d, want 429", rec.Code)
	}
	// 正确 token 在限速期内也被拒（防爆破期间绕过）
	req2 := httptest.NewRequest(http.MethodGet, "/p", nil)
	req2.Header.Set("Authorization", "Bearer sekrit")
	rec2 := httptest.NewRecorder()
	e.ServeHTTP(rec2, req2)
	if rec2.Code != http.StatusTooManyRequests {
		t.Fatalf("valid token during block = %d, want 429", rec2.Code)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/middleware/ -run 'TestAuthFailure|TestBearerTokenReturns429' -v`
Expected: FAIL（类型未定义，编译错误）

- [ ] **Step 3: 实现**

`auth.go` 新增类型（同文件，标准库 only）：

```go
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
```

`BearerToken` 改为 `func BearerToken(token string, limiters ...*AuthFailureLimiter) echo.MiddlewareFunc`，校验分支前置限速检查：

```go
var limiter *AuthFailureLimiter
if len(limiters) > 0 {
	limiter = limiters[0]
}
// ...取 provided 之后：
if limiter != nil && limiter.Blocked(c.RealIP(), time.Now()) {
	return c.JSON(http.StatusTooManyRequests, map[string]string{"error": "Too many auth failures"})
}
// ...比较失败分支：
if subtle.ConstantTimeCompare(...) != 1 {
	if limiter != nil {
		limiter.RecordFailure(c.RealIP(), time.Now())
	}
	return c.JSON(http.StatusUnauthorized, ...)
}
if limiter != nil {
	limiter.Reset(c.RealIP())
}
return next(c)
```

`server/internal/server/server.go` 中 `authMw := middleware.BearerToken(...)` 处改为传共享单例：

```go
authFailLimiter := middleware.NewAuthFailureLimiter(10, time.Minute)
authMw := middleware.BearerToken(cfg.Token, authFailLimiter)
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/server/middleware/ -v && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/middleware/auth.go server/internal/server/middleware/auth_test.go server/internal/server/server.go
git commit -m "feat(security): per-IP auth failure rate limiting (Phase 9)"
```

---


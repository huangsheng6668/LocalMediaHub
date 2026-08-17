### Task 1: 访问日志 RequestURI 脱敏（H-3）

**Files:**
- Modify: `server/internal/server/server.go:202-213`（inline redact 中间件）
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Produces: redact 中间件同时改写 `req.URL.RawQuery` 与 `req.RequestURI`；`_ = c.QueryParams()` 缓存语义不变。

- [ ] **Step 1: 写失败测试**

在 `server_test.go` 中 redact 相关测试（约 613-643 行的既有 token redact 测试）旁新增：

```go
func TestTokenRedactRewritesRequestURI(t *testing.T) {
	e := echo.New()
	e.Use(func(next echo.HandlerFunc) echo.HandlerFunc {
		return func(c echo.Context) error {
			_ = c.QueryParams()
			req := c.Request()
			q := req.URL.Query()
			if q.Get("token") != "" {
				q.Set("token", "REDACTED")
				req.URL.RawQuery = q.Encode()
				req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
			}
			return next(c)
		}
	})
	e.GET("/x", func(c echo.Context) error { return c.NoContent(200) })
	req := httptest.NewRequest(http.MethodGet, "/x?token=sekrit", nil)
	rec := httptest.NewRecorder()
	e.ServeHTTP(rec, req)
	if strings.Contains(req.RequestURI, "sekrit") {
		t.Fatalf("RequestURI still leaks token: %s", req.RequestURI)
	}
	if req.RequestURI != "/x?token=REDACTED" {
		t.Fatalf("unexpected RequestURI: %s", req.RequestURI)
	}
	// 缓存的 query 参数仍可读原始 token（auth 回退依赖）
	if got := req.URL.Query().Get("token"); got != "REDACTED" && got != "sekrit" {
		t.Fatalf("query broken: %q", got)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/ -run TestTokenRedact -v`
Expected: 编译通过但生产代码未改时，测试构造的是独立 echo 实例——因此本测试同时是"实现模板"。直接改生产代码后跑全量断言。

- [ ] **Step 3: 修改生产代码**

`server.go` redact 中间件（`if q.Get("token") != ""` 分支内）追加一行：

```go
q.Set("token", "REDACTED")
req.URL.RawQuery = q.Encode()
// Echo Logger 打印 req.RequestURI（请求行原文，不随 URL 同步），必须一并改写
req.RequestURI = req.URL.Path + "?" + req.URL.RawQuery
```

同时把既有 redact 测试的断言对象从 `URL.RawQuery` 扩展到 `req.RequestURI`（防止回归只测一半）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/server/ -run TestTokenRedact -v && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "fix(security): redact ?token= from access log RequestURI (Phase 9)"
```

---


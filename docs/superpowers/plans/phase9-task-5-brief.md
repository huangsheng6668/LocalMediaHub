### Task 5: 全局请求体大小限制（M-1）

**Files:**
- Modify: `server/internal/server/server.go`（`s.Echo.Use(echoMw.Recover())` 附近的中间件注册区）
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Consumes: `github.com/labstack/echo/v4/middleware` 已有 `BodyLimit`（Echo 标配，无新依赖）。
- Produces: 全局 4M body 上限，超限返回 413。

- [ ] **Step 1: 写失败测试**

```go
func TestBodyLimitRejectsOversizedPayload(t *testing.T) {
	s := newAuthTestServer(t, "") // 开放模式，排除认证干扰
	big := strings.NewReader(`{"roots":["` + strings.Repeat("A", 5<<20) + `"]}`)
	req := httptest.NewRequest(http.MethodPut, "/api/v1/admin/config", big)
	req.Header.Set(echo.HeaderContentType, echo.MIMEApplicationJSON)
	req.Header.Set("Authorization", "Bearer x") // 开放模式下无实际作用，保持形态
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("oversized body = %d, want 413", rec.Code)
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/ -run TestBodyLimit -v`
Expected: FAIL（当前返回非 413）

- [ ] **Step 3: 实现**

在 `s.Echo.Use(echoMw.Logger())` 之后注册：

```go
// Phase 9 (M-1): 全局请求体上限。合法最大 body 是 admin config roots
// 与批量缩略图请求，4MiB 远超需求；防 LAN 内超大 JSON 打内存。
s.Echo.Use(echoMw.BodyLimit("4M"))
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "feat(security): global request body limit (Phase 9)"
```

---


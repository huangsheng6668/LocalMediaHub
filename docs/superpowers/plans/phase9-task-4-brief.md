### Task 4: 媒体读端点并入认证（H-2）

**Files:**
- Modify: `server/internal/server/server.go:274-292`（folders/videos/images/texts/search 路由块）+ 删除 `authZipDownload` 辅助函数
- Test: `server/internal/server/server_test.go`

**Interfaces:**
- Consumes: 既有 `authMw`（`middleware.BearerToken(cfg.Token)` 单实例）。
- Produces: `/api/v1/folders`、`/folders/*`、`/videos`、`/videos/*`、`/images`、`/images/*`、`/texts`、`/search` 全部要求 token；空 token 部署透传不变。

**兼容性事实（已核实，写代码前无需再查）**：Android `AuthInterceptor` 注入 Hilt 单例 OkHttpClient 的所有请求，Coil `SingletonImageLoader` 与 ExoPlayer 共用该 client（`LocalMediaHubApplication.kt:54-68,90-106`）；Web `api.js apiRequest` 统一注入 header。两端无需改动。

- [ ] **Step 1: 写失败测试**

```go
func TestMediaReadEndpointsRequireToken(t *testing.T) {
	// newTestServer 辅助若不存在，参照同文件既有带 token 的测试构造 Server
	s := newAuthTestServer(t, "sekrit-token") // token = "sekrit-token"
	for _, path := range []string{
		"/api/v1/folders", "/api/v1/videos", "/api/v1/images", "/api/v1/texts",
		"/api/v1/search?q=x",
	} {
		req := httptest.NewRequest(http.MethodGet, path, nil)
		rec := httptest.NewRecorder()
		s.Echo.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Fatalf("GET %s without token = %d, want 401", path, rec.Code)
		}
	}
	// 带 header 后不再 401（允许 200/404/400，取决于数据，但不得是 401/403）
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	req.Header.Set("Authorization", "Bearer sekrit-token")
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("GET /folders with token must not 401, got %d", rec.Code)
	}
}

func TestMediaReadEndpointsOpenModePassthrough(t *testing.T) {
	s := newAuthTestServer(t, "") // 开放模式
	req := httptest.NewRequest(http.MethodGet, "/api/v1/folders", nil)
	rec := httptest.NewRecorder()
	s.Echo.ServeHTTP(rec, req)
	if rec.Code == http.StatusUnauthorized {
		t.Fatalf("open mode must stay passthrough, got 401")
	}
}
```

`newAuthTestServer` 若无现成工厂，就在本测试文件内基于既有构造方式封装（只设 Token 与临时 roots，不触发扫描）。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/ -run 'TestMediaReadEndpoints' -v`
Expected: FAIL（无 token 返回 200）

- [ ] **Step 3: 修改路由注册**

```go
// Folders
api.GET("/folders", h.GetFolders, authMw)
api.GET("/folders/*", h.BrowseFolder,
	authMw,
	rateLimitWhen(isFolderZipDownload, middleware.RateLimit(2, 5*time.Minute)))

// Videos
api.GET("/videos", h.GetVideos, authMw)
api.GET("/videos/*", h.GetVideoAsset,
	authMw,
	rateLimitWhen(isTranscodeRequest, middleware.RateLimit(5, time.Minute)))

// Images
api.GET("/images", h.GetImages, authMw)
api.GET("/images/*", h.GetImageAsset, authMw)

// Texts
api.GET("/texts", h.GetTexts, authMw)

// Search
api.GET("/search", h.Search, authMw)
```

同文件删除现在无调用方的 `authZipDownload`（zip download 已被无条件 authMw 覆盖）；`isFolderZipDownload` 保留（限速仍按需）。

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

Run: `cd server && go test ./...`
Expected: PASS。若既有测试因缺 token 失败，给对应测试请求补 `Authorization` header（不改生产代码迁就测试）。

- [ ] **Step 5: 提交**

```bash
git add server/internal/server/server.go server/internal/server/server_test.go
git commit -m "feat(security): require auth on media read endpoints (Phase 9)"
```

---


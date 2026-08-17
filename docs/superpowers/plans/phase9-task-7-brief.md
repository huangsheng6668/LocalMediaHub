### Task 7: 缩略图磁盘缓存上限 + 图片端点限速（M-3）

**Files:**
- Modify: `server/internal/service/thumbnail.go`（`encodeThumbnailToCache:266` 写盘点附近）
- Modify: `server/internal/server/server.go`（`/images/*`、`/videos/*` 路由）
- Test: `server/internal/service/thumbnail_test.go`（若该文件名不同，追加到既有 thumbnail 测试文件）

**Interfaces:**
- Produces:
  - `(s *ThumbnailService) enforceDiskCacheCap()`：遍历 `cacheDir` 与 `cacheDir/system`（`.jpg`），总量超 `diskCacheCapBytes`（const `512 << 20`）按 mtime 升序删除最旧文件；内部节流（距上次清扫 <30s 直接返回）。
  - `/images/*` 与 `/videos/*` 的缩略图请求挂 `middleware.RateLimit(60, time.Minute)`。
  - 说明：spec 提到可配置 `cache_max_mb`，本任务先落常量（YAGNI；如后续需要再提升为 config 字段）。

- [ ] **Step 1: 写失败测试**

```go
func TestEnforceDiskCacheCapEvictsOldest(t *testing.T) {
	dir := t.TempDir()
	s, err := NewThumbnailService(dir, 10, "webp", "ffmpeg")
	if err != nil {
		t.Fatal(err)
	}
	// 直接操作内部上限便于测试：临时下调（生产常量 512MB）
	s.diskCacheCapBytes = 3 << 10
	s.sweepInterval = 0 // 关闭节流
	old := filepath.Join(dir, "old.jpg")
	newer := filepath.Join(dir, "newer.jpg")
	for _, f := range []string{old, newer} {
		if err := os.WriteFile(f, make([]byte, 2<<10), 0644); err != nil {
			t.Fatal(err)
		}
	}
	past := time.Now().Add(-time.Hour)
	os.Chtimes(old, past, past)
	s.enforceDiskCacheCap()
	if _, err := os.Stat(old); !os.IsNotExist(err) {
		t.Fatal("oldest cache file must be evicted")
	}
	if _, err := os.Stat(newer); err != nil {
		t.Fatal("newer cache file must survive")
	}
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/service/ -run TestEnforceDiskCacheCap -v`
Expected: FAIL（字段/方法未定义，编译错误）

- [ ] **Step 3: 实现**

`ThumbnailService` 增加字段与常量：

```go
// Phase 9 (M-3): 磁盘缓存总量上限。默认 512MB，LRU(mtime) 淘汰，清扫节流 30s。
const defaultDiskCacheCapBytes int64 = 512 << 20

// struct 新增：
diskCacheCapBytes int64
lastSweep         int64 // unix nano，atomic
sweepInterval     time.Duration

// NewThumbnailService 内初始化：
// s.diskCacheCapBytes = defaultDiskCacheCapBytes
// s.sweepInterval = 30 * time.Second
```

```go
func (s *ThumbnailService) enforceDiskCacheCap() {
	now := time.Now().UnixNano()
	if now-atomic.LoadInt64(&s.lastSweep) < int64(s.sweepInterval) {
		return
	}
	atomic.StoreInt64(&s.lastSweep, now)
	type entry struct {
		path    string
		size    int64
		modTime time.Time
	}
	var total int64
	var entries []entry
	for _, root := range []string{s.cacheDir, filepath.Join(s.cacheDir, "system")} {
		filepath.Walk(root, func(p string, fi os.FileInfo, err error) error {
			if err != nil || fi == nil || fi.IsDir() || filepath.Ext(p) != ".jpg" {
				return nil
			}
			entries = append(entries, entry{p, fi.Size(), fi.ModTime()})
			total += fi.Size()
			return nil
		})
	}
	if total <= s.diskCacheCapBytes {
		return
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].modTime.Before(entries[j].modTime) })
	for _, e := range entries {
		if total <= s.diskCacheCapBytes {
			break
		}
		if os.Remove(e.path) == nil {
			total -= e.size
		}
	}
}
```

`encodeThumbnailToCache` 成功落盘的返回点前调用 `go s.enforceDiskCacheCap()`（异步，不阻塞生成路径）。

路由侧（server.go）：

```go
api.GET("/images/*", h.GetImageAsset, authMw, middleware.RateLimit(60, time.Minute))
// /videos/* 已挂 authMw 与 transcode 条件限速，追加缩略图条件限速：
rateLimitWhen(isThumbnailRequest, middleware.RateLimit(60, time.Minute))
```

`isThumbnailRequest` 参照同文件 `isTranscodeRequest` 写法（`strings.HasSuffix(c.Path(), "/thumbnail")`）。

- [ ] **Step 4: 跑测试确认通过**

Run: `cd server && go test ./internal/service/ ./internal/server/ -v`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/service/thumbnail.go server/internal/service/thumbnail_test.go server/internal/server/server.go
git commit -m "feat(security): thumbnail disk cache cap and image rate limits (Phase 9)"
```

---


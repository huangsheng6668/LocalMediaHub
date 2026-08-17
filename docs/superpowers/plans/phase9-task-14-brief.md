### Task 14: Web 杂项（L-11 / L-12 + EPUB 外链剥离）

**Files:**
- Modify: `server/internal/web/lightbox.js:56-60` + `server/internal/web/security_headers_test.go`
- Modify: `server/internal/server/middleware/security_headers.go:37-42`
- Modify: `server/internal/service/book.go:130-136` + book 测试
- Test: `cd tools/xsscheck` 通过性

**Interfaces:**
- Produces:
  - lightbox `src="${escapeHtml(url)}"`（与 browserView.js:305 对齐），修正注释（URL 源是用户媒体库路径，不是纯 server-controlled）
  - CSP 追加 `; base-uri 'none'; object-src 'none'; form-action 'self'`
  - `book.go` 图片 block：`http://` / `https://` 前缀的 src 置空（走占位符分支；CSP 本就拦截外联，服务端剥离保证 UI 一致性）；`data:` 保留（epub 规范内合法且 CSP 允许）

- [ ] **Step 1: 写失败/回归测试**

`security_headers_test.go`：既有 CSP 断言更新为包含三个新指令。`book.go` 侧测试：构造 manifest 含 `https://evil.com/x.png` 的 epub 数据，`GetChapterBlocks` 后该 block `Src == ""`；`data:image/png;base64,...` 的 block `Src` 保留原值。

- [ ] **Step 2: 运行确认失败**

Run: `cd server && go test ./internal/server/middleware/ ./internal/service/ -run 'SecurityHeaders|ChapterBlocks' -v`
Expected: FAIL

- [ ] **Step 3: 实现**

```go
// book.go 既有分支改为：
if strings.HasPrefix(src, "http://") || strings.HasPrefix(src, "https://") {
	// Phase 9 (L-11)：外联图片剥离 —— CSP img-src 'self' data: 本就拦截，
	// 服务端置空让客户端渲染占位符而不是静默破图；data: 合法保留。
	block.Src = ""
	continue
}
if strings.HasPrefix(src, "data:") {
	continue
}
```

（保持既有签名与后续逻辑不变。）

- [ ] **Step 4: 跑测试 + XSS lint**

Run: `cd server && go test ./... && cd internal/web && node --test && cd ../../../tools/xsscheck && go run . ../../server/internal/web`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/lightbox.js server/internal/server/middleware/security_headers.go server/internal/server/middleware/security_headers_test.go server/internal/service/book.go server/internal/service/book_test.go
git commit -m "fix(web): lightbox escaping, CSP hardening and epub URL stripping (Phase 9)"
```

---


# Task 14 Report — Web 杂项（L-11 / L-12 + EPUB 外链剥离）

**Commit**: `99a813299d020881bdeafd9bd786186e0b3ee3c5` — `fix(web): lightbox escaping, CSP hardening and epub URL stripping (Phase 9)`
**Branch**: master（按 brief 直接提交）
**Files changed**: 5 files, +57 / -11

## 做了什么

### 1. lightbox 转义补齐（L-11）

`server/internal/web/lightbox.js`（`renderLightboxImage` 拼接模式分支，原 56-60 行）：

- `<img src="${url}" ...>` → `<img src="${escapeHtml(url)}" ...>`，与 `browserView.js:305` 的既有模式对齐。
- 修正 `// XSS-SAFE:` 注释：原注释声称 "URL built from server-controlled relative_path"，实际 URL 由**用户媒体库文件路径**派生（`encodeRoutePath(file.path)` / `encodeURIComponent(file.path)`），不是纯 server-controlled。
- `escapeHtml` 已在文件头部第 4 行从 `./api.js` import，无需新增 import。

实施中踩到一个 lint 约束并已解决：`tools/xsscheck` 要求 `// XSS-SAFE:` 标记必须出现在 sink 同一行**或紧邻上一行**（`xssSafeCommentRe` 只回看 1 行）。最初写成 3 行连续注释导致标记行不在紧邻位置、lint FAIL（`lightbox.js:59 innerHTML sink without // XSS-SAFE: comment`）。调整为"两行解释 + 标记行紧贴 sink"的结构后通过。

### 2. CSP 收紧（L-12）

`server/internal/server/middleware/security_headers.go`：

- CSP 追加 `; base-uri 'none'; object-src 'none'; form-action 'self'`（与既有 6 条指令以分号拼接）。
- 中间件 doc comment 同步记录三个新指令的动机与前端兼容性确认。
- `security_headers_test.go` 的 `TestSecurityHeaders` CSP 期望值同步更新为完整 9 指令字符串（verbatim 断言）。

### 3. EPUB 外链图片剥离（L-11 后半）

`server/internal/service/book.go`（`GetChapterBlocks`，原 130-136 行）：

- 原：`data:` / `http://` / `https://` 前缀统一 `continue`（原样保留 src）。
- 现：`http://` 或 `https://` 前缀 → `out[i].Src = ""` + `continue`（走占位符分支）；`data:` 单独保留 `continue`（原语义）。分支结构按 brief Step 3 代码，变量名适配既有循环体（`out[i]` 而非伪码 `block`），签名与后续 manifest 重写逻辑不变。
- 函数 doc comment 同步更新（原"absolute http(s):// URL — these are passed through unchanged"已不准确）。
- 新增测试 `TestGetChapterBlocksStripsExternalHttpUrls`（`book_test.go`）：用既有 `buildEpubWithImage` helper 构造含 `https://evil.com/x.png` 与 `http://evil.com/y.jpg` 的 epub，断言两者 `Src == ""`。`data:` 保留语义由既有 `TestGetChapterBlocksPreservesDataUri` 覆盖（继续通过）。

## grep form/base/object 确认结果

CSP 收紧前的前端兼容性确认（`server/internal/web/*.js *.html`）：

| 检查项 | grep 模式 | 结果 |
|---|---|---|
| `<form` | 全量 | 仅 1 处：`reader-settings.js:56` `<form method="dialog">` |
| `action=` / `.submit(` | 排除 `data-action` | 0 处 |
| `<base` / `document.base` / `baseURI` | 全量 | 0 处 |
| `<object` / `<embed` | 全量 | 0 处 |

唯一 `<form method="dialog">` 的兼容性分析：`method="dialog"` 的表单提交只关闭宿主 `<dialog>`，**不产生任何导航/网络请求**，`form-action` 只约束表单提交的目标 URL，对 dialog 方法无影响——已确认 `form-action 'self'` 不破坏阅读设置弹窗的关闭按钮。`base-uri 'none'` 与 `object-src 'none'` 在零 `<base>` / `<object>` 用法下无影响。

## 测试结果

| 命令 | 结果 |
|---|---|
| `go test ./internal/server/middleware/ ./internal/service/ -run 'SecurityHeaders|Chapter|Book' -v` | 全部 PASS（含新增 `TestGetChapterBlocksStripsExternalHttpUrls` 2 子用例） |
| `go test ./...`（server） | 除 `bookparser TestParseUserNovel` 外全部 PASS；该失败为任务书声明的基线例外（txt 章节标题启发式，与本改动无关，改动前即存在） |
| `node --test`（server/internal/web） | 88 tests / 88 pass / 0 fail（lightbox 无 jsdom 直接测试，无适配需要；lightbox.js 二次修改后复跑仍 88/88） |
| `tools/xsscheck` | `OK: no unescaped innerHTML variables in 30 file(s)`（首次 FAIL 系注释行位问题，见上文） |

TDD 顺序证据：Step 2 先行确认两个新断言 FAIL（`TestSecurityHeaders` CSP 不匹配；`TestGetChapterBlocksStripsExternalHttpUrls` 得到原值 `https://evil.com/x.png` 而非 `""`），实施后转 PASS。

## Self-review

1. **占位符链路双向确认**：空 `Src` 的渲染分支在两个客户端均已存在且未改动——web `textReader.js:548-549`（`if (block.src) img.src = block.src; else img.alt = '[本图片无法显示]'`）、Android `TextReaderScreen.kt:1176-1179`（`if (block.src.isNullOrEmpty()) → "[本图片无法显示]"`）。服务端剥离后 UI 一致性成立，无需客户端改动（符合"不动 textReader.js 与阅读器渲染链路"约束）。
2. **单图模式无需改动**：`lightbox.js:89` 的 `elements.lightboxImg.src = url` 是 DOM 属性赋值而非 HTML 解析，无注入面；brief 也只要求 innerHTML 拼接处。
3. **`data:` 保留的合理性复核**：CSP `img-src 'self' data:` 明确允许 data:；epub 规范内 data: URI 合法。符合 brief 消歧。
4. **行为变化面**：`GetChapterBlocks` 对外链 src 从"透传（客户端 CSP 拦截 → 静默破图）"变为"置空（客户端渲染占位符）"。Android 端此前对外链 URL 会走图片加载（Coil），现在也统一走占位符——这正是预期的一致性改善，无回归风险（外链图片在收紧的 CSP 下本就不可见）。
5. **未纳入 commit 的文件**：工作区存在无关 untracked（`docs/superpowers/reviews/`、`tools/reformat_novels.py`），未加入本次提交；`git add` 严格按 brief Step 5 清单。
6. **风险点（低）**：`form-action 'self'` 若未来新增会提交到外域的表单（如第三方登录）需同步调整 CSP 与本报告结论；当前纯 SPA 无此场景。

## 结论

三项改动全部按 brief 完成，验证命令全绿（唯一失败为声明过的基线例外）。状态：DONE。

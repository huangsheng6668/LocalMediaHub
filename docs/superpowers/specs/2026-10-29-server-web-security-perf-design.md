# Server + Web SPA 安全加固与性能优化（批次 1）设计

- 日期：2026-10-29
- 范围：`server/`（Go / Echo）与 `server/internal/web/`（SPA）
- 后续：批次 2（Android + Rust Native）另起 spec
- 状态：设计已逐节确认，待实施计划

## 背景

全栈审查发现 Server 与 Web 端存在若干安全边界缺口（链接逃逸、鉴权限流绕过、配置并发竞争）与性能/生命周期问题（EPUB 全量解压、HLS 槽位排队不可取消、Gzip 压缩二进制、阅读器心跳泄漏等）。本 spec 汇总批次 1 的修复设计。

### 非目标

- 不改变 Bearer Token 架构（header + GET `?token=` 双通道仍为既定约定，见 AGENTS.md）。
- 不将媒体 URL 全量改为 HMAC 签名（方案 C 已否决）。
- 不处理 Android / Rust（批次 2）。
- 不调整现有缩略图 `RateLimit(60/min)` 阈值本身。

---

## 第 1 节：安全与并发边界

### 1.1 遍历中的符号链接 / Windows Junction 跳过

**问题**：`ResolveBrowsePath` / `ResolveWithinRoots` 只校验请求的**顶层路径**（逐段 `os.Readlink` 拒绝链接）。之后的遍历不再检查子项：

| 位置 | 实际风险 |
|---|---|
| `handler/folders.go` `DownloadFolderZip`（`filepath.Walk` + `os.Open`） | **高**：目录内软链接文件（如 `a.mp4 -> C:\secret.mp4`）被 `os.Open` 跟随并打包下载，越界读取 |
| `service/scanner.go` `Scan`（`filepath.WalkDir`） | 低：链接文件进入扫描缓存（暴露名称/Lstat 元数据），实际读取时会被 `ResolveWithinRoots` 拒绝 |
| `handler/folders.go` `BrowseFolder`、`handler/system.go` `SystemBrowse`（`os.ReadDir`） | 低：列出链接条目名称，进入时会被拒绝 |

`Walk` / `WalkDir` 基于 Lstat，不会进入链接目录，因此不存在目录级递归逃逸。

**设计**（与 `path.go` 中 `resolveWithin` 既有的"拒绝、不跟随"策略保持一致——`EvalSymlinks` 无法解析 Windows Junction，故不采用解析放行）：

- `service/path.go` 新增：
  ```go
  // IsLinkEntry reports whether a directory entry is a symlink or Windows
  // reparse point (junction / mount point surface as ModeIrregular on Go 1.23+).
  func IsLinkEntry(mode fs.FileMode) bool {
      return mode&(fs.ModeSymlink|fs.ModeIrregular) != 0
  }
  ```
  仅基于 `DirEntry.Type()` / `FileInfo.Mode()` 的位判断，**零额外系统调用**。
- `DownloadFolderZip`：改用 `filepath.WalkDir`；`IsLinkEntry(d.Type())` 为真时，若是目录返回 `filepath.SkipDir`，否则跳过该条目。并且在 `os.Open` 后用 `f.Stat()` 与 `os.Lstat` 结果交叉核对（`os.SameFile`），防止遍历与打开之间被替换成链接（TOCTOU）。
- `Scanner.Scan`：`IsLinkEntry(d.Type())` 为真时直接跳过（不入缓存）。
- `BrowseFolder` / `SystemBrowse`：`IsLinkEntry(entry.Type())` 为真时不出现在返回列表。

**测试**：`folders_test.go` 新增"目录内软链接文件不进入 zip"；`scanner_test.go` 新增"链接文件不进缓存"；Windows 用 `mklink /J` 或复用 `path_test.go` 中既有的 junction 构造辅助函数（无权限时 `t.Skip`）。

### 1.2 `/api/v1/books/image` 鉴权前置与失败限流

**问题**（`handler/books.go` `GetBookImage`，路由挂在 `authMw` 外，见 `server.go:448`）：
1. `?token=` 回退分支直接比较 token，**未接入 `AuthFailureLimiter`**，可被无限暴力枚举 `server.token`。
2. 先调用 `ValidateAccessibleMediaPath`（内部 `os.Stat`），再校验 sig/token：未认证请求能根据 `403`（不存在）与 `401`（存在）的差异探测文件是否存在。

**设计**：
- `Handler` 注入与 `server.go` 中同一个 `*middleware.AuthFailureLimiter` 实例（构造参数传入，不用全局变量）。
- `GetBookImage` 执行顺序：
  1. `limiter.Blocked(c.RealIP(), now)` → `429`。
  2. `service.NormalizePath(pathStr)`（纯词法处理，不访问磁盘）。
  3. 校验 `signer.VerifyImage(clientIP, normalized, manifestID, sig)`；不通过则在 `server.token != ""` 时做常量时间 token 比较（保留 `[DEPRECATED]` warn）。开放模式（token 为空）保持现有行为。
  4. 失败 → `limiter.RecordFailure` + 统一返回 `401`（与文件是否存在无关）。成功 → `limiter.Reset`。
  5. 通过后才调用 `ValidateAccessibleMediaPath` 并读取图片。
- 前置条件：确认 `SignImage` 签名时用的 path 和 `NormalizePath` 的输出一致（同为归一化绝对路径）；不一致则在签名侧统一。

**测试**：sig 错误 / token 错误对存在与不存在的文件返回相同的 `401`；连续失败达到阈值后返回 `429`；有效 sig 正常返回图片。

### 1.3 `ScanConfig` 并发安全与 watcher 热更新

**问题**（`handler/admin.go` `UpdateConfig`）：直接改写 `h.cfg.Scan.Roots`、重置 `sync.Once`，与并发的 `GetRoots()` 产生 data race；校验失败回滚前，并发请求可能读到无效 roots；更新后 `fsnotify` 仍监听旧根目录。

**设计**：
- `config.ScanConfig` 增加 `mu sync.RWMutex`：`GetRoots()` 加读锁并返回切片副本；新增 `SetRoots([]string)`（写锁，去重，上限 64 个）。auto-detect 的 `sync.Once` 重置也移入写锁内。
- `UpdateConfig`：先在副本 `Config` 上完成 `Validate(false)` 并 `Save`，成功后再 `SetRoots`；失败时不触碰运行时配置。
- `Scanner.StartWatching` 支持重入：已有 watcher 时先关闭并等待旧 goroutine 退出，再监听新 roots。`UpdateConfig` 在 `InvalidateCache()` 后调用。
- 快照身份键（roots）不匹配时，按既有逻辑自然弃用。

**测试**：`go test -race` 下并发执行 `UpdateConfig` 与 `GetRoots`；校验失败不改动运行时 roots；切换根目录后，新目录的文件变化能触发失效。

### 1.4 标签系统路径与边界

**问题**（`handler/tags.go`、`service/tags.go`）：
- `AssociateTag` / `DisassociateTag` 直接使用原始的 `c.Param("*")`（未 unescape、未归一化），导致含非 ASCII 字符或正斜杠的路径与扫描缓存键对不上。
- `isPathWithinConfiguredRoots` 缺少 `IsBlockedRoot` 检查；`buildTaggedMediaFallback` 对历史标签路径直接 `os.Stat`，根目录收窄后仍会泄露目录外文件的元数据。
- `CleanDeletedPath` 在 `RLock` 下执行 `DELETE`。
- `CreateTag` 不校验名称与颜色；500 错误直接返回 `err.Error()`。

**设计**：
- 路径统一经过 `decodeWildcardPath` → `service.NormalizePath`。
- `isPathWithinConfiguredRoots` 增加 `service.IsBlockedRoot`；`buildTaggedMediaFallback` 在 `os.Stat` 前先做边界复核，不通过直接丢弃。
- `CleanDeletedPath` 改用 `s.mu.Lock()`。
- `CreateTag`：名称去首尾空白后非空且 ≤ 64 个 rune；颜色可选，格式为 `^#[0-9a-fA-F]{3,8}$`。所有 500 改用 `respondInternalError`。
- **兼容性**：历史数据库中未归一化的路径——在 `TagsService` 初始化时做一次性的幂等归一化迁移（遇冲突时合并关联）。

**测试**：URL 编码的中文路径能正确关联并被 `GetTaggedMedia` 命中；收窄 roots 后旧标签不再返回目录外文件；名称与颜色校验覆盖边界值。

---

## 第 2 节：Server 性能与资源生命周期

### 2.1 EPUB 目录定向解析与 BookService 缓存

**问题**：`bookparser/epub.go` `readToc` 遍历 `for _, href := range manifest`（Go map 随机顺序，包含所有章节、图片、字体），逐个解压（每个最多 16 MiB）并 `html.Parse`。插图多的 EPUB 打开时 CPU 和内存消耗极大；`parseNavToc` 匹配任意 `<a href>`，一旦随机先命中带脚注的正文，就会把它误当成全书目录（目录内容不确定）。

**设计**：
- `parseOpf`：记录 `properties` 中包含 `nav` 这个词的 manifest item → `navID`（EPUB 3）。
- `readToc(zr, navID, ncxID, manifest, opfDir)`：优先只读 `navID` 对应的文件；为空或解析结果为空时回退到 `ncxID`（EPUB 2）；**删除全量遍历**。两者都没有时返回空目录，由上层沿用 spine 生成章节标题的既有逻辑。
- `parseNavToc`：优先选 `<nav epub:type="toc">`，其次第一个 `<nav>`，只在该子树内收集 `<a href>`；找不到任何 `<nav>` 时，保持现有对整个文档收集的行为（兼容非标准的 nav）。
- `BookService.cache` 增加容量上限 64（按插入/访问顺序淘汰）。
- `GetBookChapter`：同一请求内复用已获取的 `*Book`，消除第二次 `GetBook` / `os.Stat`。

**测试**：构造含 nav、多个带链接正文、大图资源的 EPUB fixture，断言目录稳定且等于 nav 内容、只读取了 nav 条目；仅含 NCX 的 EPUB 能回退；缓存超过上限会淘汰。

### 2.2 HLS 槽位可取消、ffprobe 超时、限流补齐

**问题**：
- `transcode_hls.go` `startHlsFFmpeg` 用 `context.Background()` 调用 `acquireTranscodeSlot`，槽位满时会永久阻塞；`sess.cancel` 在拿到槽位之后才赋值，所以 `cancelSiblingSessions` / `reapHlsSessions` / `CloseHLS` 都取消不了正在排队的会话。
- `streaming.go` `GetVideoDuration` 用的是无超时的 `exec.Command("ffprobe")`。
- `/api/v1/system/thumbnail`、`/api/v1/media/thumbnail`、`/api/v1/media/duration` 没有 `RateLimit`（而 `/videos|images/*/thumbnail` 有）。

**设计**：
- 会话创建时立即 `ctx, cancel := context.WithCancel(...)` 并赋值给 `sess.cancel`（在锁内完成）；`startHlsFFmpeg` 用 `waitCtx, _ := context.WithTimeout(ctx, 30*time.Second)` 获取槽位。超时或取消时把会话标记为失败并清理（playlist 请求返回 `503` + `Retry-After`）。
- `GetVideoDuration` 改用 `exec.CommandContext`，超时 10s。
- `server.go` 为上述三个端点挂载 `middleware.RateLimit(60, time.Minute)`。

**测试**：`max_sessions=1` 时，第二个会话排队期间调用 `cancelSiblingSessions` / `CloseHLS` 能立即返回；排队超时后会话被清理；ffprobe 超时路径用桩命令覆盖。

### 2.3 Gzip 跳过二进制、静态资源预压缩、脱敏快路径

- `server.go` Gzip `Skipper` 新增跳过：`/hls/segment`、`/books/image`、`/media/thumbnails`、`/favicon.ico`、以 `.woff2` 结尾的路径。
- `web/static.go`：`buildStatic()` 时为 JS / CSS / HTML / SVG / JSON 预先计算 `gzipBody`（只在体积变小时保留）。请求的 `Accept-Encoding` 含 `gzip` 时直接写出，并设置 `Content-Encoding: gzip` + `Vary: Accept-Encoding`；静态路由同时加入 Gzip 中间件的 skipper，避免二次压缩。ETag / 304 逻辑保持不变（ETag 加 `-gz` 后缀以区分不同表示）。
- token 脱敏中间件：`RawQuery` 中不包含 `token=` 时直接放行，不解析 query。

**测试**：HLS 分片和书籍图片响应不带 `Content-Encoding`；静态 JS 在 gzip 请求下返回预压缩字节且能正确解压；304 路径不受影响。

### 2.4 Windows 路径大小写不敏感索引

- 新增 `pathKey(p string) string`：Windows 下返回 `strings.ToLower(filepath.Clean(p))`，其他平台返回 `filepath.Clean(p)`。
- `Scanner.cacheByDir` / `byPath` 的读写键统一使用 `pathKey`（值中保留原始大小写）。
- `handler/search.go` `searchFiles` 的 scope 前缀匹配在 Windows 下大小写不敏感（与 `searchFoldersCached` 一致）。
- `handler/library.go` `PostDecorations` 用 `pathKey` 构建 O(1) 映射，删除 O(N²) 的 `EqualFold` 回退扫描。

**测试**：Windows 下用不同大小写的路径能命中 `GetCachedByDir` / 搜索 / decorations；非 Windows 平台行为不变（build tag 分别覆盖）。

---

## 第 3 节：Web SPA

### 3.1 媒体 `<img>` / `<video>` 统一 token 注入（修复 429 自锁）

**问题**：token 模式下，`dashboard.js:76`、`browserView.js:373-379`、`lightbox.js:72-74,109-111` 构造的 `<img src>` 不带凭证。每张图都会 401 并计入 `AuthFailureLimiter`（10 次/60s，且 `Blocked` 检查在 header 校验之前，见 `auth.go:131`），一屏超过 10 张缩略图就会把整个 IP 锁 60 秒，连带合法 header 的 API 请求也返回 429。

**设计**：
- `api.js` 导出 `withMediaAuthToken(url)`：token 非空、URL 指向本站 `/api/v1/`、且未带 `token=` / `sig=` 时，追加 `token=`（自动处理 `?` / `&`）。纯函数，便于测试。
- `dashboard.js`、`browserView.js`、`lightbox.js` 所有缩略图和原图 URL 都经它处理；`videoPlayer.js` 的局部函数 `withVideoAuthToken` 改为调用它，覆盖 `/videos/*/stream`、`/system/stream`、`/media/stream`、HLS playlist。
- 只用于 GET 媒体资源；服务端已有 `?token=` 的访问日志脱敏。

**测试**（`node --test`）：`withMediaAuthToken` 覆盖无 token、已有 query、已带 sig、跨域 URL 等情况；视图渲染出的缩略图 `src` 带有 token。

### 3.2 阅读器生命周期清理

**问题**：`textReader.js` 中的 `container._cleanupReader` 只在打开下一本书时调用。离开 `#/read` 后，30s 心跳 `readingHeartbeatTimer`、`readTimer`、自动滚动 rAF、`immersiveEntryTimer` 以及 document/window 监听器都会残留，持续上报 `read_seconds_delta` 并篡改 `lastReadAt`。

**设计**：
- `router.js` 路由处理入口：目标不是 `#/read` 且 `elements.viewReader?._cleanupReader` 存在时，调用它并置为 `null`。
- `bookshelf.js` `render(container)`：清空容器前做同样的守卫（书架全页复用 `viewReader` 容器）。
- `_cleanupReader` 本身保证幂等。

**测试**：jsdom 模拟进入阅读页再切换路由，断言心跳定时器已清除、不再触发 progress 上报。

### 3.3 渲染与监听器性能

- `textReader.js` `renderBlocks`：把 `readerPrefs.getSettings()` 提到循环外（每章从 2N 次 `localStorage` 读取 + `JSON.parse` 降为 1 次）；段落书签按钮改为在 `els.content` 上做单一事件委托（`closest('.para-bookmark-btn')` + 段落 `dataset`）。`collectVisibleParagraphs` 的 `Math.max(...spread)` 改为直接取末元素。
- `scrollNav.js`：`document.body` 上的 MutationObserver 改为 `subtree: false, attributeFilter: ['data-active-tab']`；路由切换和章节加载后由调用方显式触发 `update()`。
- `browserView.js`：用模块级的单例 `Intl.Collator(undefined, { numeric: true, sensitivity: 'base' })` 替代比较器内的 `localeCompare`；删除未被引用的 `file-card-${safeBtoa(...)}` id 计算。

**测试**：现有阅读器与浏览页测试全部通过；补充事件委托触发书签切换的测试；补充排序结果与原实现一致（自然排序）的测试。

---

## 验证清单

- `cd server && go build ./... && go test -race ./...`
- `cd server/internal/web && node --test`
- `cd tools/xsscheck && go run . ../../server/internal/web`（新增的 DOM 写入遵守 `XSS-SAFE` 规则）
- 手工验证：token 模式下 Web 首页和浏览页缩略图正常，不出现 429；HLS 转码播放与拖动正常；EPUB 目录正确。
- AGENTS.md：同步更新模块地图中 scanner / tags / book / web 相关描述（链接跳过策略、`withMediaAuthToken`、阅读器清理）。

## 风险与回滚

- 1.1 跳过链接后，原本通过软链接组织的媒体不再出现在扫描、浏览和打包结果中（这些文件此前直接访问就已被拒绝，属于行为对齐，不算功能退化）。在 AGENTS.md 安全约定中写明。
- 1.4 标签路径迁移遇冲突时合并关联，迁移前自动备份 `tags.db`。
- 各节相互独立，可分别提交、分别回滚。

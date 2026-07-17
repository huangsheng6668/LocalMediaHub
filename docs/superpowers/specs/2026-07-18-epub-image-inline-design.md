# epub 图片内联设计

- **日期**：2026-07-18
- **作者**：brainstorming session
- **状态**：spec（待用户审阅 → 转入 writing-plans）
- **依赖**：基于 B + C 阶段已合并实现（spec `2026-07-17-text-reader-design.md` + `2026-07-17-text-reader-c-phase-design.md`，master HEAD `fca182c`）

## 背景与目标

B 阶段 epub parser 在 `extractXhtmlText` 里遇到 `<img>` / SVG `<image>` 时只输出 `[图片]` 占位，丢失了 src。漫画、儿童绘本、技术书插图等场景在当前阅读器里完全不可用。

本设计把图片真正显示出来：
- 服务端解析章节时把图片作为独立 block 抽出，保留 src
- 新增 `/api/v1/books/image` 端点提供图片字节
- 章节端点响应结构从 `{title, content}` 升级为 `{title, blocks}`
- 客户端按 blocks 顺序渲染：text block 渲染文本、image block 渲染 `<img>`/`AsyncImage`

## 范围

### 首期包含

- 新增 `Block` 类型（text/image 二分），所有格式（txt + epub）的章节统一返回 blocks
- 服务端 `extractBlocks` 替换 `extractXhtmlText`，按文档顺序产出 blocks
- image src 改写：相对路径 → `/api/v1/books/image?path=<epub>&manifest=<id>` 端点 URL
- data: URI 原样保留（客户端原生支持）
- 新增 `/api/v1/books/image` 端点（zip 内字节提取 + MIME 推断 + 1 天缓存）
- `authMw` 支持 query param token fallback（`<img src>` 走浏览器原生加载，无法注入 Authorization 头）
- Android: `AsyncImage`（Coil 3 + OkHttp 已集成）+ Block 数据类
- Web: 原生 `<img loading="lazy">` + CSS max-width 约束
- txt 路径：服务端按 `\n\n` split 成多个 text blocks，与 epub 路径对称

### 首期不包含（YAGNI，留作后续）

- CSS `background-image: url(...)` 解析（装饰性，小说场景罕见）
- 图片点击放大/lightbox
- 图片长按保存到本地
- 图片预加载（lazy load 已够用）
- WebP/AVIF 转码（直接透传原格式）

## 服务端架构

### 新增 `Block` 类型（`bookparser/parser.go`）

```go
// Block 是章节正文的一个有序单元。一个章节 = 一串 Block。
type Block struct {
    Type  string `json:"type"`            // "text" | "image"
    Value string `json:"value,omitempty"` // text block 用
    Src   string `json:"src,omitempty"`   // image block 用（已改写为端点 URL 或 data: URI）
}
```

### Book 暴露 manifest 给 BookService

新增 getter（只读，不复制 map）：
```go
func (b *Book) EpubManifest() map[string]string { return b.epubManifest }
func (b *Book) EpubOpfDir() string               { return b.epubOpfDir }
```

### 导出 bookparser 工具函数

供 BookService 跨包调用：
- `NormalizeHref(s string) string`（原 `normalizeHref`）
- `JoinZipPath(dir, href string) string`（原 `joinZipPath`）
- `ReadCapped(r io.Reader, max int64) ([]byte, error)`（原 `readCapped`）

`MaxEpubEntrySize` 已是 exported 大写常量。

### 删除的旧 API

- `(*Book).ChapterText(idx int)` — 替换为 `ChapterBlocks(idx)`
- `(*Book).epubChapterText` / `(*Book).txtChapterText` — 替换为 blocks 版本
- `extractXhtmlText` — 替换为 `extractBlocks`
- "imageOnly 返回值"逻辑（被 blocks 结构吸收）

### 新增 API

- `(*Book).ChapterBlocks(idx int) ([]Block, error)` — 入口
- `(*Book).epubChapterBlocks(idx int) ([]Block, error)` — epub 实现
- `(*Book).txtChapterBlocks(idx int) ([]Block, error)` — txt 实现
- `extractBlocks(data []byte) []Block` — XHTML walk
- `extractImgSrc(n *html.Node) string` — 处理 `<img src>` + SVG `<image xlink:href>` + `<image href>`

### `extractBlocks` walk 算法

```go
func extractBlocks(data []byte) []Block {
    doc, err := html.Parse(bytes.NewReader(data))
    if err != nil {
        return []Block{{Type: "text", Value: "[本章节解析失败]"}}
    }
    var blocks []Block
    var textBuf strings.Builder
    flush := func() {
        if s := strings.TrimSpace(textBuf.String()); s != "" {
            blocks = append(blocks, Block{Type: "text", Value: s})
        }
        textBuf.Reset()
    }
    var walk func(*html.Node)
    walk = func(n *html.Node) {
        if n.Type == html.ElementNode {
            switch n.Data {
            case "img", "image":
                flush()
                if src := extractImgSrc(n); src != "" {
                    blocks = append(blocks, Block{Type: "image", Src: src})
                }
                return
            case "p", "div", "br", "h1", "h2", "h3":
                if textBuf.Len() > 0 {
                    textBuf.WriteString("\n\n")
                }
            }
        }
        if n.Type == html.TextNode {
            textBuf.WriteString(n.Data)
        }
        for c := n.FirstChild; c != nil; c = c.NextSibling {
            walk(c)
        }
    }
    walk(doc)
    flush()
    if len(blocks) == 0 {
        return []Block{{Type: "text", Value: "[本章节为空]"}}
    }
    return blocks
}
```

### `extractImgSrc`

```go
func extractImgSrc(n *html.Node) string {
    for _, a := range n.Attr {
        if a.Key == "src" && a.Val != "" {
            return a.Val
        }
        if (a.Key == "xlink:href" || a.Key == "href") && a.Val != "" {
            return a.Val
        }
    }
    return ""
}
```

### `BookService.GetChapterBlocks`

```go
func (s *BookService) GetChapterBlocks(path string, idx int) ([]bookparser.Block, error) {
    b, err := s.GetBook(path)
    if err != nil {
        return nil, err
    }
    blocks, err := b.ChapterBlocks(idx)
    if err != nil {
        return nil, err
    }
    for i := range blocks {
        if blocks[i].Type != "image" {
            continue
        }
        src := blocks[i].Src
        if src == "" || strings.HasPrefix(src, "data:") {
            continue
        }
        manifestID := reverseLookupManifest(b.EpubManifest(), b.EpubOpfDir(), src)
        if manifestID == "" {
            blocks[i].Src = ""
            continue
        }
        blocks[i].Src = fmt.Sprintf("/api/v1/books/image?path=%s&manifest=%s",
            url.QueryEscape(path), url.QueryEscape(manifestID))
    }
    return blocks, nil
}
```

### `reverseLookupManifest`

```go
func reverseLookupManifest(manifest map[string]string, opfDir, src string) string {
    normalized := bookparser.NormalizeHref(bookparser.JoinZipPath(opfDir, src))
    for id, href := range manifest {
        if bookparser.NormalizeHref(href) == normalized {
            return id
        }
    }
    return ""
}
```

### `BookService.ReadImageBytes`

```go
func (s *BookService) ReadImageBytes(path, manifestID string) ([]byte, string, error) {
    b, err := s.GetBook(path)
    if err != nil {
        return nil, "", err
    }
    if b.Format != "epub" {
        return nil, "", fmt.Errorf("%w: image fetch only for epub", ErrUnsupported)
    }
    href, ok := b.EpubManifest()[manifestID]
    if !ok {
        return nil, "", fmt.Errorf("%w: manifest id not found: %s", ErrInvalidEpub, manifestID)
    }
    // 防御性：拒绝 .. 路径段（manifest 来自 XML 解析，理论安全，但加一层保险）
    if strings.Contains(href, "..") {
        return nil, "", fmt.Errorf("%w: invalid manifest href", ErrInvalidEpub)
    }
    fullPath := bookparser.JoinZipPath(b.EpubOpfDir(), href)
    zr, err := zip.OpenReader(path)
    if err != nil {
        return nil, "", fmt.Errorf("%w: %v", ErrIoFailure, err)
    }
    defer zr.Close()
    rc, err := zr.Open(fullPath)
    if err != nil {
        return nil, "", fmt.Errorf("%w: image not found in epub: %s", ErrInvalidEpub, fullPath)
    }
    defer rc.Close()
    data, err := bookparser.ReadCapped(rc, bookparser.MaxEpubEntrySize)
    if err != nil {
        return nil, "", err
    }
    return data, mimeByExtension(filepath.Ext(fullPath)), nil
}

func mimeByExtension(ext string) string {
    switch strings.ToLower(ext) {
    case ".jpg", ".jpeg":
        return "image/jpeg"
    case ".png":
        return "image/png"
    case ".gif":
        return "image/gif"
    case ".webp":
        return "image/webp"
    case ".svg":
        return "image/svg+xml"
    case ".bmp":
        return "image/bmp"
    default:
        return "application/octet-stream"
    }
}
```

### REST 端点改造

#### `/api/v1/books/chapter` 响应结构变更

旧：`{title, content: string}`
新：`{title, blocks: [{type, value?, src?}]}`

```go
type chapterResponse struct {
    Title  string             `json:"title"`
    Blocks []bookparser.Block `json:"blocks"`
}
```

#### 新增 `/api/v1/books/image`

```
GET /api/v1/books/image?path=<epubPath>&manifest=<manifestID>&token=<token>
```

行为：
- 路径校验：`ValidateAccessibleMediaPath(path, scanRoots, systemAllowedRoots, cfg.Scan.TextExtensions)`
- manifest 白名单：必须存在于 `Book.EpubManifest()`
- 防 `..` 路径段（防御性）
- 读取：`zip.OpenReader` + `bookparser.ReadCapped(rc, MaxEpubEntrySize)` (16MB)
- Content-Type：根据扩展名推断
- 缓存：`Cache-Control: public, max-age=86400`
- 错误：path 越界 403、manifest 不存在 404（`ErrInvalidEpub` → 422）、IO 失败 500、超 16MB 413

路由注册（`server.go`）：
```go
books := api.Group("/books", authMw)
books.GET("/info", h.GetBookInfo)
books.GET("/chapter", h.GetBookChapter)
books.GET("/image", h.GetBookImage)  // 新增
```

### `authMw` 扩展（`middleware/auth.go`）

```go
func AuthMiddleware(token string) echo.MiddlewareFunc {
    return func(next echo.HandlerFunc) echo.HandlerFunc {
        return func(c echo.Context) error {
            if token == "" {
                return next(c)
            }
            provided := strings.TrimPrefix(c.Request().Header.Get("Authorization"), "Bearer ")
            if provided == "" {
                provided = c.QueryParam("token")  // fallback for <img src>
            }
            if provided != token {
                return c.JSON(http.StatusUnauthorized, map[string]string{"error": "unauthorized"})
            }
            return next(c)
        }
    }
}
```

Header 优先于 query param——非图片端点行为零变化。

## Android 客户端

### 数据模型（`data/Models.kt`）

```kotlin
@Parcelize
data class Block(
    val type: String,            // "text" | "image"
    @SerializedName("value") val value: String? = null,
    @SerializedName("src") val src: String? = null,
) : Parcelable

@Parcelize
data class BookChapterContent(
    val title: String,
    val blocks: List<Block> = emptyList(),
) : Parcelable
```

### MediaRepository

`getBookChapter` 签名不变，返回类型 `BookChapterContent` 字段变了。

### ViewModel（`viewmodel/TextReaderViewModel.kt`）

- `_chapterText: MutableStateFlow<String>` → `_chapterBlocks: MutableStateFlow<List<Block>>`
- `loadChapter` 成功分支：`_chapterBlocks.value = r.data.blocks`
- `addBookmarkFromParagraph(blockIndex: Int)`：从 `_chapterBlocks.value[blockIndex]` 取 block，仅允许 type=="text"，preview = `block.value?.take(30)`

### UI（`ui/screen/TextReaderScreen.kt`）

```kotlin
val blocks by viewModel.chapterBlocks.collectAsState()

LazyColumn(state = listState, ...) {
    itemsIndexed(blocks) { blockIdx, block ->
        when (block.type) {
            "text" -> Text(
                text = block.value ?: "",
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                    .combinedClickable(onClick = {}, onLongClick = { showMenuAt = blockIdx }),
                style = LocalTextStyle.current.copy(
                    fontSize = settings.fontSize.sp.sp,
                    lineHeight = (settings.fontSize.sp * settings.lineHeight.multiplier).sp,
                ),
            )
            "image" -> {
                if (block.src.isNullOrEmpty()) {
                    Text("[本图片无法显示]", ...)
                } else {
                    AsyncImage(
                        model = block.src,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}
```

### Coil token 注入验证

`OkHttpModule` 必须确认 `ImageLoader` 使用的 OkHttpClient 包含 auth interceptor（自动注入 `Authorization: Bearer <token>`）。如果当前不带，本次需要加上：

```kotlin
// OkHttpModule.kt 内 ImageLoader 用的 OkHttpClient
val imageHttpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val req = chain.request().newBuilder()
            .header("Authorization", "Bearer ${serverConfig.token}")
            .build()
        chain.proceed(req)
    }
    .build()

val imageLoader = ImageLoader.Builder(context)
    .components { add(NativeDecoderFactory()) }
    .okHttpClient(imageHttpClient)
    .build()
```

### 不改动的部分

- TextReaderActivity / manifest 不变
- Bookmark 数据类字段名 `paragraphIndex` 保留（语义变为 block index，向后兼容 C-phase 书签数据）
- 自动滚动 / 主题 / 字体 / 行距逻辑不变（操作 LazyColumn 容器）
- TOC 抽屉 Tab 化逻辑不变
- ReaderSettingsSheet 不变

## Web 客户端

### `textReader.js` 改造

`loadChapter` 调 `renderBlocks` 替换 `renderParagraphs`：

```javascript
function renderBlocks(blocks) {
    els.content.innerHTML = '';
    blocks.forEach((block, idx) => {
        if (block.type === 'text') {
            const p = document.createElement('p');
            p.textContent = block.value || '';
            p.dataset.blockIndex = idx;
            // hover 书签按钮（与 C-phase 一致）
            const btn = document.createElement('button');
            btn.className = 'text-reader__para-bookmark';
            btn.type = 'button';
            btn.textContent = '+';
            btn.title = '添加书签';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const ok = readerPrefs.addBookmark({
                    bookPath: path,
                    chapterIndex: currentIdx,
                    paragraphIndex: idx,
                    preview: (block.value || '').slice(0, 30),
                    createdAt: Date.now(),
                });
                showToast(ok ? '已添加书签' : '已存在书签', ok ? 'success' : 'info');
            });
            p.appendChild(btn);
            els.content.appendChild(p);
        } else if (block.type === 'image') {
            const img = document.createElement('img');
            img.className = 'text-reader__image';
            img.loading = 'lazy';
            if (block.src) {
                img.src = appendTokenQueryParam(block.src);
            } else {
                img.alt = '[本图片无法显示]';
            }
            els.content.appendChild(img);
        }
    });
}

// appendTokenQueryParam: 给 image URL 加上 token query param
// （因为 <img> 不走 fetch，无法注入 Authorization 头）
function appendTokenQueryParam(url) {
    const token = getAuthToken();  // 从现有 auth 模块拿
    if (!token) return url;
    const sep = url.includes('?') ? '&' : '?';
    return url + sep + 'token=' + encodeURIComponent(token);
}
```

### `style.css` 新增

```css
.text-reader__image {
    display: block;
    max-width: 100%;
    height: auto;
    margin: 12px auto;
    border-radius: 4px;
}

.text-reader__content img[alt="[本图片无法显示]"] {
    padding: 16px;
    color: var(--text-muted);
    font-style: italic;
    text-align: center;
    background: var(--bg-elevated);
    border-radius: 4px;
}
```

### 不改动的部分

- `router.js` / `api.js` 不变
- `bookshelf.js` / `dashboard.js` 不变
- 设置面板、主题、自动滚动逻辑不变
- `Bookmark.paragraphIndex` 字段名保留（语义为 block index）

## 错误处理与边界

| 场景 | 行为 |
|------|------|
| manifest id 不存在 | 422 `{"error":"manifest id not found"}` |
| epub zip 打不开 | 500（不暴露细节） |
| 图片 entry > 16MB | 413 |
| path 越界 | 403（中间件已拦截） |
| 非 epub 文件请求 image | 400 `image fetch only for epub` |
| 章节 blocks 为空 | 单 text block `[本章节为空]`，HTTP 200 |
| image src 找不到 manifest | Block.Src 设为 ""，客户端显示 `[本图片无法显示]` |
| image src 是 data: URI | 原样保留 |
| manifest href 含 `..` | 422（防御性拒绝） |

## 测试策略

### 服务端单元测试

**`bookparser/epub_test.go` 扩展**：
- `TestEpubChapterBlocksExtract`：含 1 张图的 XHTML → blocks 顺序正确
- `TestExtractBlocksDataUriPreserved`：data: URI 不被处理
- `TestExtractBlocksImageOnlyChapter`：纯图章节不再返回 `[本章节为图片版]` 占位

**`bookparser/txt_test.go` 扩展**：
- `TestTxtChapterBlocksSplit`：多段落 txt → 多 text block

**`service/book_test.go` 新增**：
- `TestGetChapterBlocksRewritesImageSrc`：epub image block 被改写为端点 URL
- `TestGetChapterBlocksPreservesDataUri`：data: URI 不被改写
- `TestReverseLookupManifest`：相对/绝对路径、带 fragment、找不到 4 case
- `TestReadImageBytes`：成功 + manifest 不存在 + 非 epub 3 case

**`handler/books_test.go` 扩展**：
- `TestGetBookChapterReturnsBlocks`：替换原 `TestGetBookChapterReturnsJSON`
- `TestGetBookImageReturnsBlob`：合法 path + manifest → 200 + image bytes
- `TestGetBookImagePathOutsideRoots403`
- `TestGetBookImageManifestNotFound404`

**`middleware/auth_test.go` 扩展**：
- `TestAuthMiddlewareAcceptsTokenInQueryParam`
- `TestAuthMiddlewareHeaderTakesPrecedenceOverQueryParam`
- `TestAuthMiddlewareRejectsInvalidQueryParamToken`

### Android 单元测试

**`TextReaderViewModelReaderTest` 扩展**：
- 现有 5 个测试改为断言 `chapterBlocks` 而非 `chapterText`
- 新增 `addBookmarkFromParagraph_returns_false_for_image_block`

### Web 测试

继续不引入测试框架。手动验收覆盖。

### 验收清单

- [ ] 服务端启动，打开含图的 epub
- [ ] 章节正文文本与图片交替渲染（不是 `[图片]` 占位）
- [ ] 图片加载失败时显示 `[本图片无法显示]`
- [ ] 切章节 → 新章节图片正常加载
- [ ] 主题/字体切换 → 图片正常显示（不受主题影响，原图原色）
- [ ] 杀进程重开 → 上次章节恢复 + 图片重新加载（Coil/HTTP 缓存命中）
- [ ] Web 端同样流程跑通
- [ ] 跨端：Android 看的图片在 Web 也能看到（同 epub 同 manifest）
- [ ] data: URI 图片正常显示
- [ ] txt 文件不受影响（仍按 `\n\n` 段落渲染）
- [ ] C-phase 全部功能（书签/自动滚动/主题/字体/行距）不受影响

CI 闸门：
- `cd server && go test ./...` PASS
- `cd android && ./gradlew testDebugUnitTest assembleDebug` PASS

## 后续扩展路径

- CSS `background-image: url(...)` 解析（装饰性 epub）
- 图片点击放大 / lightbox
- 图片长按保存到本地
- 图片预加载（lazy load 已够用）
- WebP/AVIF 转码（直接透传原格式）

## 风险

- **Coil token 注入**：如果当前 `ImageLoader` 用的 OkHttpClient 不带 auth interceptor，所有 `AsyncImage` 加载 `/api/v1/books/image` 会 401。Plan 必须包含验证步骤。
- **query param token 暴露**：token 出现在 URL 里，浏览器历史/日志会留痕。单用户局域网场景风险可接受；未来公网部署需要换成 cookie-based auth 或 signed URL。
- **manifest 反查漏匹配**：如果 epub 用了 `#fragment` 或 URL encoding 的 src，`NormalizeHref` 必须正确处理。测试覆盖。
- **响应体膨胀**：章节 JSON 略大于纯文本（每个 block 一个对象），但与原 content 字符串差异不大（10~20%），可接受。
- **txt 路径行为变化**：原 txt 直接返回整段字符串，客户端按 `\n\n` split；新 txt 服务端 split 后客户端不 split。LazyColumn item 数量变化（从 1 个变成 N 个），但不影响功能——书签 `paragraphIndex` 语义变为 block index，向后兼容 C-phase 已存数据。

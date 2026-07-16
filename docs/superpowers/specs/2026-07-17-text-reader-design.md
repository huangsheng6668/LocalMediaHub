# 小说阅读器设计（txt + epub）

- **日期**：2026-07-17
- **作者**：brainstorming session
- **状态**：spec（已审阅修订 → 待用户确认 → 转入 writing-plans）
- **审阅**：2026-07-17 代码审核通过，已修正 10+ 处与现有代码不符的问题

## 背景与目标

LocalMediaHub 当前只识别 `video` / `image` 两种媒体类型，无法处理文本类资源。本设计为项目新增"小说阅读"能力，首期支持：

- `.txt`（含 GBK/GB18030 中文编码自动检测）
- `.epub`（EPUB 2 + EPUB 3）

`.mobi` / `.azw3` 在首期**扫描收集但灰显**，留作后续扩展。

两端均要支持：
- Android 客户端（独立 `TextReaderActivity`）
- Web 管理界面（新增 `#/read` / `#/bookshelf` 路由）

首期只做 B 阶段（章节切分 + 翻页 + 进度记录）。字体/行距/主题/自动滚动/章节书签等阅读体验增强（C 阶段）全部留作后续独立 spec。

## 范围

### 首期包含

- 服务端：新增 `MediaType = "text"` 分类、`bookparser` 子包（txt/epub 解析）、`BookService` 缓存层、两个 REST 端点
- Android：`TextReaderActivity` + `TextReaderScreen` + `TextReaderViewModel` + `RecentActivityStore` 扩展 + HomeScreen 书架卡片 + Browse 混排
- Web：`textReader.js` + `bookshelf.js` + `router.js` 路由 + `browserView.js` 文档卡片 + `dashboard.js` 书架聚合
- 进度持久化：客户端各自存（Android DataStore / Web localStorage）

### 首期不包含（YAGNI，留作后续）

- mobi/azw3 真正解析（首期灰显）
- 字体大小/行距/主题/自动滚动/章节书签
- txt 章节规则集 profile 切换 UI（首期固定 `common`）
- epub 图片内联（首期 `[图片]` 占位）
- 跨设备进度同步
- 多标签页 localStorage 同步
- Web 端测试框架
- `chardet` 第三方库（GB18030 兜底已足够）

## 服务端架构

### 配置扩展（`server/internal/config/config.go`）

`ScanConfig` 增加 `TextExtensions []string` 字段，默认值由代码常量给出（与现有 video/image extensions 处理方式一致）：

```yaml
scan:
  text_extensions: [".txt", ".epub", ".mobi", ".azw3"]
```

`ScanConfigPublic` 同步增加 `TextExtensions` 字段；`Config.Public()` 完整透出。

默认 `text_extensions` 常量：
```go
DefaultTextExtensions = []string{".txt", ".epub", ".mobi", ".azw3"}
```

**联动更新**：`handler.go` 的 `isMediaExt()` 和 `mediaExtensions()` 需要同步纳入 `TextExtensions`，否则 `SystemBrowse` handler 不会返回文本文件（`system.go:94` 调用 `isMediaExt` 过滤非媒体文件）。

### Scanner 扩展（`server/internal/service/scanner.go`）

- `Scanner` 增加 `textExts map[string]bool` 字段
- `NewScanner` 签名扩展：`NewScanner(videoExts, imageExts, textExts []string)`（当前签名只有 `videoExts, imageExts`，调用方 `server.go:47` 需同步修改）
- `Scan()` 的 mediaType 判定增加 `text` 分支（当前 `scanner.go:159-164` 只有 video/image 二分支）：
  ```go
  if isVideo { mediaType = "video" } else if isImage { mediaType = "image" } else if isText { mediaType = "text" } else { return nil }
  ```
- **缓存合并**（`scanner.go:230-269`）：当前 `switch f.MediaType` 只处理 `"video"` 和 `"image"` 两个分支，text 文件会落入 `allFiles` 但不会进入类型缓存。需增加 `textFiles` slice 和 `cache["text"]` 分组：
  ```go
  textFiles := make([]models.MediaFile, 0)
  // switch 内增加：
  case "text":
      textFiles = append(textFiles, f)
  // 锁内增加：
  s.cache["text"] = textFiles
  ```
- **OnScanComplete 过滤**：当前回调 `callback(allFiles)` 会把所有文件发给 `ThumbnailService.PreGenerateThumbnails`，文本文件没有缩略图。需在回调前过滤掉 `MediaType == "text"` 的文件，或在 `PreGenerateThumbnails` 内跳过非 video/image 文件。推荐后者（防御性更强）。
- 新增 `TextExts() map[string]bool` getter（对齐 `VideoExts` / `ImageExts`）

### 新增子包 `server/internal/service/bookparser/`

```
bookparser/
  parser.go      # Book/Chapter 类型 + Parse(path) 入口 + 按 ext 路由
  txt.go         # 编码检测 + 章节规则集 + 字符偏移切片
  epub.go        # ZIP 解压 + OPF spine + NCX/nav TOC
  unsupported.go # mobi/azw3 → 返回 ErrUnsupported
```

核心类型（`parser.go`）：

```go
type Chapter struct {
    Title      string `json:"title"`
    Index      int    `json:"index"`
    CharStart  int    `json:"char_start,omitempty"`  // txt: rune 偏移
    CharEnd    int    `json:"char_end,omitempty"`    // txt: rune 偏移
    ManifestID string `json:"manifest_id,omitempty"` // epub: OPF spine idref
}

type Book struct {
    Path     string    `json:"path"`
    Format   string    `json:"format"`             // "txt" | "epub" | "unsupported"
    Title    string    `json:"title"`              // epub: metadata; txt: filename
    Charset  string    `json:"charset,omitempty"`  // txt: "UTF-8" / "GB18030"
    Chapters []Chapter `json:"chapters"`
    ModTime  time.Time `json:"mod_time"`
}

// Parse 按 ext 路由到子解析器
func Parse(path string) (*Book, error)

// ChapterText 现场读文件并切片返回第 idx 章正文
func (b *Book) ChapterText(idx int) (string, error)
```

`Book` **不持有全文**——`ChapterText` 现场重读文件并切片。内存缓存只存章节索引，单本几 KB，上百本无压力。

### txt 解析（`bookparser/txt.go`）

**编码检测**：
1. 读前 3 字节匹配 UTF-8 BOM / UTF-16 LE/BE BOM → 直接确定
2. 无 BOM → `utf8.Valid` 整段验证通过 → UTF-8
3. 失败 → `golang.org/x/text/encoding/simplifiedchinese.GB18030` 解码
4. 解码失败率高 → 回退 UTF-8 容错（`utf8.DecodeRune` 替换无效字节）

不引入 `chardet`，GB18030 兜底已覆盖中文场景 99%+。

**章节规则集**：预定义 4 个 profile，每个是一组编译好的 `[]*regexp.Regexp`：

| Profile | 适用 | 示例 |
|---------|------|------|
| `common`（首期固定使用） | 通用网文/实体 | `^第[一二三四五六七八九十百千零0-9]+[章节回卷集部篇]`、`^Chapter\s+\d+`、`^楔子|^序章|^尾声|^前言|^后记` |
| `webnovel` | 起点/纵横网文 | `common` + `^[第卷][0-9一二三四五六七八九十百千]+[章卷]` + `^\d{1,4}、` |
| `classic` | 实体/古典 | `common` + `^【.+】` + `^[（(]\s*[一二三四五六七八九十\d]+\s*[)）]` |
| `numeric` | 纯数字标题 | `^\s*\d{1,4}\s*$` + `^\s*\d+\.\s+` |

**匹配规则**：
- 逐行扫描，任一正则命中即认定为章节标题行
- 章节正文范围 = 下一行到下一个标题行前一行（rune 偏移记录到 `Chapter.CharStart/CharEnd`）
- 文件开头到第一章之间若有内容 → 归为"序言"章
- 无任何命中 → 整篇作为单章，`Chapter.Title = filename`

**性能与边界**：
- 百万字 txt ≈ 100 万行扫描，编译好的正则约 200~500ms 完成
- 文件 > 50MB → `ErrTooLarge`

### epub 解析（`bookparser/epub.go`）

**结构**：epub 是 ZIP，三件套 `META-INF/container.xml` → OPF（manifest + spine + metadata）→ NCX（EPUB 2）或 nav.xhtml（EPUB 3）。

**解析流程**：
1. `zip.OpenReader(path)` 打开
2. 读 `container.xml` → OPF 路径
3. 读 OPF：
   - `metadata` → `<dc:title>` 作为 `Book.Title`
   - `manifest` → 建 `map[id]href` 资源表
   - `spine` → 按 `itemref idref` 顺序生成 `Chapter[]`，每章 `ManifestID = idref`
4. TOC 解析（用于章节显示标题）：
   - 优先 EPUB 3 `nav.xhtml`（`golang.org/x/net/html` 解析 `<nav epub:type="toc"]` 下 `<a>`）
   - 否则 EPUB 2 `.ncx`（`encoding/xml` 解析 `<navPoint>/<navLabel>/<text>`）
   - 用 href 反查 `ManifestID`，填入 `Chapter.Title`
5. 章节文本提取：`ChapterText(idx)` 打开对应 manifest XHTML → `golang.org/x/net/html` 解析 → 按顺序收集文本节点 → `\n\n` 连接段落
6. 图片处理：`<img>` 用 `[图片]` 占位；纯图片章节 → 返回 `[本章节为图片版，暂不支持]`

**边界**：
- 无 spine → `ErrInvalidEpub`
- 无 TOC → `Chapter.Title` 用 `"第 N 章"` 兜底
- DRM 加密 → `ErrEncrypted`
- 文件 > 100MB → `ErrTooLarge`

**依赖**：
- `archive/zip`（标准库）
- `encoding/xml`（标准库）
- `golang.org/x/net/html` — ⚠️ 当前仅为 **indirect** 依赖（`go.mod:42`），项目 Go 代码中未直接 import。本次使用将提升为 **direct** 依赖。
- `golang.org/x/text/encoding/simplifiedchinese`（txt 编码检测用）— ⚠️ 同样当前为 **indirect** 依赖（`go.mod:44`），将提升为 direct。

两个 `x/` 包均已在 `go.mod` 的 indirect 列表中（被其他依赖传递引入），`go mod tidy` 只需将 `// indirect` 注释移除，不会引入全新 module。但需注意这**不是零新依赖**——是零新 module、两个新 direct import。

### mobi/azw3 处理（`bookparser/unsupported.go`）

```go
func parseUnsupported(path string) (*Book, error) {
    return &Book{
        Path:   path,
        Format: "unsupported",
        Title:  filepath.Base(path),
    }, ErrUnsupported
}
```

`ErrUnsupported` 仅用于日志，调用方根据 `Book.Format` 字段决定 UI 行为。

### BookService 缓存层（`server/internal/service/book.go`）

```go
type BookService struct {
    mu    sync.RWMutex
    cache map[string]*bookparser.Book
    sf    singleflight.Group
}

func (s *BookService) GetBook(path string) (*Book, error)
```

- 查 mtime（`os.Stat`）→ 与 `Book.ModTime` 一致则命中
- miss → `singleflight.Do` 防击穿 → `bookparser.Parse`
- **不做 TTL**——mtime 校验精准，无需时间窗口
- 不订阅 scanner fsnotify 事件，避免耦合；mtime 校验成本极低

### REST 端点（`server/internal/server/handler/books.go`）

⚠️ **路径设计修正**：原方案 `/api/v1/media/text/*` 与现有 `/api/v1/media/*` 路由组冲突。现有 `/media/*` 提供统一的 `thumbnail`/`original`/`stream`/`duration` 端点，且整个 group 挂载了 `authMw`（`server.go:207`）。文本阅读是独立的资源语义，不适合塞入 media 组。改用独立 `/api/v1/books/*` 路由组：

| 方法 | 路径 | 行为 |
|------|------|------|
| GET | `/api/v1/books/info?path=...` | 返回 `Book` JSON（不含正文） |
| GET | `/api/v1/books/chapter?path=...&index=N` | 返回 `{"title": "...", "content": "..."}` JSON |

章节端点改为返回 JSON 而非纯文本，理由：
1. 与项目所有其他端点的 JSON 响应格式一致
2. 可以附带 `title` 等元数据，前端不需要额外请求
3. 错误响应已经是 JSON `{"error": "..."}` 格式，混合纯文本响应会让客户端解析变复杂

路由注册（`server.go` `registerRoutes` 内）：
```go
books := api.Group("/books", authMw)
books.GET("/info", h.GetBookInfo)
books.GET("/chapter", h.GetBookChapter)
```

两个端点都走现有 `ValidateAccessibleMediaPath` + token 鉴权中间件，`allowedExtensions` 传入 `cfg.Scan.TextExtensions`，不引入新安全模型。

#### ⚠️ 其他现有 Go 处理器联调细节修改：
1. **`media.go` (MediaOriginal)**：原先仅允许 `ImageExtensions`，需修改以支持小说文件下载：
   ```go
   allowedExts := append(h.cfg.Scan.ImageExtensions, h.cfg.Scan.TextExtensions...)
   resolved, err := service.ValidateAccessibleMediaPath(pathStr, h.cfg.Scan.GetRoots(), h.cfg.GetSystemAllowedRoots(), allowedExts)
   ```
2. **`system.go` (SystemBrowse)**：遍历盘符或目录时，原逻辑对非图片媒体默认标记为 `"video"`，需要增加 text 分支：
   ```go
   // 如果 isMediaExt，但不在 ImageExtensions 中，需额外检查 TextExtensions：
   mediaType := "video"
   isImg := false
   for _, imgExt := range h.cfg.Scan.ImageExtensions {
       if strings.EqualFold(ext, imgExt) {
           mediaType = "image"
           isImg = true
           break
       }
   }
   if !isImg {
       for _, txtExt := range h.cfg.Scan.TextExtensions {
           if strings.EqualFold(ext, txtExt) {
               mediaType = "text"
               break
           }
       }
   }
   ```
3. **`folders.go` (BrowseFolder / DownloadFolderZip)**：
   - `BrowseFolder` 中动态扫描磁盘返回子文件时，需包含 `h.scanner.TextExts()`，并将匹配项标记为 `"text"` 类型。
   - `DownloadFolderZip` 同样需将 text 扩展名加入扫描，允许目录 ZIP 打包中包含书籍。
4. **`tags.go` (buildTaggedMediaFallback)**：fallback 查询时，`switch` 必须增加对 `h.scanner.TextExts()[ext]` 的支持并返回 `"text"` 媒体类型，否则被标记的标签书籍会丢失。

### Server struct 扩展

`server/internal/server/server.go` 的 `Server` 增加 `BookService *service.BookService` 字段。

`Handler` struct（`handler.go:15`）增加 `books *service.BookService` 字段，`New()` 构造函数（`handler.go:24`）增加对应参数。调用方 `server.go:90` 同步修改。

## 错误处理与边界

### 服务端错误分类

| 错误 | 触发场景 | HTTP | 前端展示 |
|------|---------|------|---------|
| `ErrUnsupported` | mobi/azw3 | 200 + `format:"unsupported"` | 灰显 + Toast |
| `ErrTooLarge` | txt > 50MB 或 epub > 100MB | 413 | "文件过大，暂不支持" |
| `ErrInvalidEpub` | 损坏 ZIP / 缺 spine | 422 | "文件已损坏" |
| `ErrEncrypted` | DRM 加密 | 422 | "DRM 加密，无法阅读" |
| `ErrIoFailure` | 读文件失败 | 500 | "读取失败，请重试" |
| `ErrPathInvalid` | 路径越界（中间件拦截） | 403 | 不应到达 |

错误统一映射为 `{"error": "<code>"}` JSON。

### 客户端网络错误

复用现有 `NetworkResult`：
- 超时 / 无连接 → "无法连接服务器"
- 401 → 走现有重连流程
- 4xx/5xx → 错误消息 + 重试按钮
- 章节加载失败 → 保持当前章节不切，Toast 提示

### 进度持久化边界

- Android DataStore 原子写入；Web localStorage 同步写
- 越界（章节索引超过当前书章节数）→ 加载时 clamp 到 `[0, chapters.size - 1]`

### 空文件与极端格式

- 空 txt → `Book.Chapters = []`，前端"空文档"
- epub spine 为空 → `ErrInvalidEpub`
- epub 某 XHTML 损坏 → 该章返回 `[本章节解析失败]`，不影响其他章节

### 路径安全

所有端点的 `path` 参数经 `ValidateAccessibleMediaPath`（已有），客户端无法通过参数访问任意文件区域。

## Android 客户端

### 数据层

**`data/Models.kt`** 新增：
```kotlin
@Parcelize
data class BookChapter(
    val index: Int,
    val title: String,
    @SerializedName("char_start") val charStart: Int = 0,
    @SerializedName("char_end") val charEnd: Int = 0,
    @SerializedName("manifest_id") val manifestId: String? = null,
) : Parcelable

@Parcelize
data class Book(
    val path: String,
    val format: String,
    val title: String,
    val charset: String? = null,
    val chapters: List<BookChapter>,
    @SerializedName("mod_time") val modTime: String,
) : Parcelable

// 章节内容响应（对应 /api/v1/books/chapter 的 JSON 返回）
data class BookChapterContent(
    val title: String,
    val content: String,
)
```

⚠️ 注意：JSON 字段命名需要 `@SerializedName` 注解，与服务端 `json:"char_start"` 等 tag 对齐。现有 `MediaFile` 已使用此模式（见 `Models.kt:18-22`）。

**`data/MediaRepository.kt`** 新增（⚠️ 项目使用 **OkHttp + Gson** 而非 Retrofit，需对齐现有 `httpGet<T>` 模式）：
```kotlin
suspend fun getBookInfo(path: String): NetworkResult<Book> =
    httpGet("${baseUrl}/api/v1/books/info?path=${encode(path)}", Book::class.java)

suspend fun getBookChapter(path: String, index: Int): NetworkResult<BookChapterContent> =
    httpGet("${baseUrl}/api/v1/books/chapter?path=${encode(path)}&index=$index", BookChapterContent::class.java)
```

**`data/RecentActivityStore.kt`** 扩展：
- 新增 `book_progress` 持久化键，结构 `Map<String, BookProgress>`
- `BookProgress(path, chapterIndex, scrollOffsetPx, lastReadAt)`
- `getBookProgress(path)` / `saveBookProgress(...)` / `clearBookProgress(path)`
- API 风格对齐 `PlaybackProgressEntry`

### ViewModel 层

**新增 `viewmodel/TextReaderViewModel.kt`**：
- `@HiltViewModel`，注入 `MediaRepository` + `RecentActivityStore`
- 持有 `Book`、当前章节索引、章节正文 `StateFlow<String>`
- `loadBook(path)` / `loadChapter(index)` / `nextChapter()` / `prevChapter()`
- 加载章节时同步存进度

**`HomeViewModel` 扩展**：
- 新增 `recentBooks: StateFlow<List<RecentBookEntry>>`
- 从 `RecentActivityStore` 读 `book_progress`，按 `lastReadAt` 排序，最多 10 本
- 过滤 `format == "unsupported"`

**`BrowseViewModel`**：
- 新增 `TextOpenDelegate` 或并入 `BrowseNavigator`
- 点击 text 文件触发 `openText(path)` 回调到 Activity

### UI 层

**新增 `TextReaderActivity.kt`**（独立 Activity，对齐 `VideoPlayerActivity`）：
- singleTop + 独立回退栈
- 接收 `path` Extra
- 承载 `TextReaderScreen`
- `onStop` 存进度

**新增 `ui/screen/TextReaderScreen.kt`**：
- 顶部：书名 + 章节标题 + 翻页按钮
- 中部：`LazyColumn` 渲染章节正文（按段落分行，纯文本）
- 底部：章节进度指示器（"第 12 / 87 章"）
- 点击中部呼出章节抽屉（`ModalDrawerSheet`）
- mobi/azw3 → "暂不支持该格式"占位页

**`HomeScreen` 扩展** + `HomeComponents.kt`：
- 新增 "我的书架" 卡片：`LazyRow` 横向滑动
- 卡片元素：书名 + 章节进度 + 上次阅读时间
- 空状态 → 隐藏卡片

**`BrowseScreen` 扩展**：
- text 文件用统一文档图标卡片
- txt/epub 点击 → `openText(path)` → `TextReaderActivity`
- mobi/azw3 点击 → Toast
- 复用 `MediaItems.kt` 加 `TextCard` composable

**⚠️ 关键：`when(file.mediaType)` 全量排查**

当前代码库中有 **20+ 处** 使用 `when(file.mediaType)` 或 `if (file.mediaType == "video")` 的二分支判断，均无 `else` 分支。新增 `mediaType = "text"` 后，文本文件会**静默跳过**（不渲染、不处理）。此外，部分地方如果走到了 `else` 分支（即假设所有非 video 都是 image），会导致严重错误。需逐一排查并添加 `"text"` 分支或 `else` 兜底：

| 文件 | 行为 | 修改方式 |
|------|------|----------|
| `BrowseContent.kt` (4 处 `when`) | 网格渲染 | 增加 `"text" -> TextCard(...)` |
| `BrowseStateContent.kt` (3 处) | 状态内容渲染 | 同上 |
| `BrowseSearchView.kt` | 搜索结果过滤 | 增加 text 分支 |
| `HomeComponents.kt` (4 处) | 首页卡片 | 增加书籍卡片或过滤 |
| `MainActivity.kt` (3 处 `if mediaType == "video"`) | 点击/下载点击路由 | **严重**：原逻辑 `if (mediaType == "video") ... else ... (imagePreview)`，点击下载的 text 文件会误入 `imagePreview`。需重写为 `when` 分支，并在 text 分支下显式使用 `Intent` 启动 `TextReaderActivity`。 |
| `DownloadsScreen.kt` (3 处) | 下载列表 | **严重**：1. 点击事件原逻辑若非 video 则默认进入 image 列表预览（会导致崩溃）；需改为 `when` 并在 text 分支调用本地文本阅读器。 2. 缩略图区域原逻辑仅在 `"image"` 时加载，否则直接显示视频胶卷/播放图标；需为 text 增加文档图标。 3. 类型标签原先 `if video "视频" else "图片"`；需为 text 渲染 "小说" 标签。 |
| `DownloadManager.kt` | URL 选择 | 当前 `if video then streamUrl else imageUrl`。由于我们扩展了 `MediaOriginal` 对 `TextExtensions` 的支持，此处的 `imageUrl` (即 `/api/v1/media/original?path=...`) 可以正确作为书籍的下载链接，但建议增加注释说明避免后续误解。 |
| `GridContainers.kt` | 瀑布流 | 仅影响 image，text 不参与，无需改 |

#### 📖 离线阅读与解析架构设计
由于章节切分和 EPUB/TXT 解析逻辑（`bookparser`）位于 Go 服务端，当客户端离线时无法直接发起 HTTP 请求获取章节数据。为此，首期设计支持以下离线缓存方案（方案 A）：
1. **元数据伴随下载**：在 `DownloadWorker` 下载书籍（txt/epub）时，额外拉取服务端的 `/api/v1/books/info?path=...`，将其序列化并保存为本地的同名 `.json` 文件（例如 `book.txt.json`）。
2. **正文离线提取**：
   - 对于 **txt**，客户端 `TextReaderViewModel` 探测到 `is_local == true` 时，从本地 JSON 读取 `BookChapter` 偏移列表。当跳转章节时，直接使用 `RandomAccessFile`（或 `BufferedInputStream`）配合 `charStart`/`charEnd` 直接截取并解码本地的文本片段。
   - 对于 **epub**，使用 Java/Kotlin 内置的 `java.util.zip.ZipFile`，在本地读取 OPF/NCX 对应的 XHTML 路径，直接解析本地的 HTML 段落，无需连接服务器。

### 导航

独立 Activity，不走 NavHost。`Intent(this, TextReaderActivity::class.java)` + Extra，与 `VideoPlayerActivity` 一致。启动时需携带 `path` 与 `is_local` 标志，以便在离线和在线状态间无缝切换。

### Hilt

`TextReaderViewModel` 通过 `@HiltViewModel` 注入，无需新增 `@Module`。

## Web 客户端

### 新增模块

- `server/internal/web/textReader.js`：阅读器主逻辑
- `server/internal/web/bookshelf.js`：书架聚合

### `router.js` 扩展

```js
'#/read'      // ?path=xxx
'#/bookshelf'
```

返回键复用 router history 栈回到浏览位置。

### `api.js` 扩展

```js
getBookInfo(path)          // GET /api/v1/books/info
getBookChapter(path, idx)  // GET /api/v1/books/chapter
```

### `browserView.js` 扩展

text 文件渲染为"文档卡片"：文件名 + 格式图标；mobi/azw3 加灰色蒙层 + 角标"暂不支持"；点击 txt/epub → `#/read?path=...`。

### `textReader.js`

DOM 结构（沿用 `dom.js` 模板风格）：header（返回 / 标题）+ 正文区 + footer（上一章 / 进度 / 下一章 / 目录）+ TOC 抽屉。

行为：
- 进入时 `getBookInfo` → 渲染 TOC
- 读 `localStorage['book_progress:' + path]` → 跳到上次章节
- `getBookChapter` → 返回 `{title, content}` JSON → `element.textContent = content`（避免 XSS）
- 翻页 / 选章节 → 存进度（章节索引 + 滚动像素）
- mobi/azw3 → 占位页

### `bookshelf.js`

- 扫 `localStorage` 所有 `book_progress:*` 键 → 按 `lastReadAt` 排序
- 复用 `browserView` grid 样式
- 空状态 → 隐藏首页书架区块

### `dashboard.js` 扩展

在首页"最近活动""继续播放"之间新增"我的书架"区块，调用 `bookshelf.js` 渲染。

### 样式

- 阅读器主题：跟随系统 `prefers-color-scheme`（首期不做应用内切换）
- 排版：1.8 行距、16px 字体、最大宽度 800px 居中
- 文档卡片图标用 inline SVG

### 安全

- 正文一律 `textContent`，不用 `innerHTML`（防 XSS）
- 路径参数 `encodeURIComponent` + 服务端 `ValidateAccessibleMediaPath`

## 测试策略

### 服务端单元测试（Go `_test.go`）

对齐现有 `scanner_test.go` / `tags_test.go` / `streaming_test.go` 风格。

**`bookparser/txt_test.go`**：
- 编码检测：UTF-8 BOM / UTF-8 无 BOM / GB18030 / 损坏字节回退
- `common` profile：覆盖「第X章」「Chapter X」「楔子」+ 无命中兜底
- 偏移切片正确性
- 边界：空文件、50MB+、纯英文、纯数字章节

**`bookparser/epub_test.go`**：
- 现场构造最小合法 epub（OPF + spine + 1 XHTML）→ 验证 `Book.Chapters`
- EPUB 3 `nav.xhtml` + EPUB 2 `.ncx` TOC 解析
- 边界：无 TOC、损坏 ZIP、加密、`<img>` 占位

**`bookparser/unsupported_test.go`**：
- `.mobi` / `.azw3` 返回 `Format: "unsupported"`

**`service/book_test.go`**：
- mtime 命中（计数器验证 `Parse` 次数）
- mtime 变化重新解析
- `singleflight` 并发（10 goroutine → 1 次 `Parse`）
- 文件不存在 → `ErrIoFailure`

**`handler/books_test.go`**：
- `GET /books/info`：合法 / 越界 403 / 不存在 500 / mobi 200+unsupported
- `GET /books/chapter`：合法 JSON 响应（含 title + content） / 越界 index 400 / 路径越界 403
- token 鉴权 401

**`scanner_test.go` 扩展**：
- text 文件被正确扫描并归入 `cache["text"]`
- `cacheByDir` 包含 text 文件
- `GetCachedByType(ctx, roots, "text")` 返回正确结果

**`handler.go` 扩展测试**：
- `isMediaExt` 识别 `.txt` / `.epub` / `.mobi` / `.azw3`
- `mediaExtensions` 包含 text 扩展名

### Android 单元测试（Kotlin）

**`TextReaderViewModelTest`**：
- `loadBook` / `loadChapter` 成功 + 进度持久化
- 网络失败不崩溃
- 翻页边界

**`RecentActivityStoreTest`** 扩展：
- `saveBookProgress` → `getBookProgress` 往返
- `clearBookProgress`
- 越界 clamp

**`HomeViewModelTest`** 扩展：
- `recentBooks` 过滤 `unsupported`
- 空进度 → 空列表

### Web 测试

项目 Web 端无测试框架。首期不引入 vitest/jest——YAGNI。靠服务端 handler 测试 + 手动验收覆盖。

### 验收清单

- [ ] 服务端启动后 txt/epub 出现在 Browse 网格
- [ ] 打开 txt（GBK 编码）→ 章节列表正确识别"第X章"
- [ ] 打开 epub（EPUB 3）→ TOC 正确显示
- [ ] 翻页、章节跳转、返回浏览页
- [ ] 杀进程重开 → 上次章节与滚动位置恢复
- [ ] mobi/azw3 灰显 + Toast
- [ ] Web 端同样流程通过
- [ ] HomeScreen / 首页"我的书架"聚合卡片正确展示

CI 必须通过：`go test ./...` + `./gradlew testDebugUnitTest assembleDebug`。

## 后续扩展路径

C 阶段（独立 spec）：
- 字体大小 / 行距 / 主题（日间/夜间/护眼）/ 自动滚动 / 章节书签
- txt 规则集 profile 切换 UI
- epub 图片内联（base64 或单独端点）

更后：
- mobi/azw3 真正解析（新增 `bookparser/mobi.go`，零 API/DB/配置变更）
- 跨设备进度同步（引入 device_id + 服务端持久化）

## 风险

- **纯 Go mobi/azw3 库现状不佳**：首期选择灰显规避，风险已通过降级策略消化。
- **txt 编码误判**：GB18030 兜底已覆盖 99%+，剩余极端场景留作 C 阶段加"手动切换编码"兜底。
- **epub 格式多样性**：EPUB 2/3 + 各种 metadata 变体，靠测试集覆盖典型场景，损坏文件走 `ErrInvalidEpub`。
- **章节正则误命中**：`common` profile 经过实战筛选，但稀有章节样式仍可能漏检；C 阶段 profile 切换可解决。
- **mediaType 散布范围广**：Android 客户端 20+ 处硬编码 `"video"` / `"image"` 二分支判断（无 `else`），遗漏任何一处会导致文本文件静默不可见或崩溃。需编写集成测试验证 Browse 页面可正确显示和点击 `mediaType == "text"` 的文件。

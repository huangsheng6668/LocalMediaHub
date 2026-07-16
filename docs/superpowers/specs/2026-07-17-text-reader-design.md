# 小说阅读器设计（txt + epub）

- **日期**：2026-07-17
- **作者**：brainstorming session
- **状态**：spec（待用户审阅 → 转入 writing-plans）

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

### Scanner 扩展（`server/internal/service/scanner.go`）

- `Scanner` 增加 `textExts map[string]bool` 字段
- `NewScanner` 签名扩展：`NewScanner(videoExts, imageExts, textExts []string)`
- `Scan()` 的 mediaType 判定增加 `text` 分支：
  ```go
  if isVideo { mediaType = "video" } else if isImage { mediaType = "image" } else if isText { mediaType = "text" } else { return nil }
  ```
- 新增 `cache["text"]` 分组、`Scan()` 合并阶段把 text 文件归入 `textFiles` slice
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

**依赖**：`archive/zip`、`golang.org/x/net/html`（已在用）、`encoding/xml`——**零新依赖**。

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

| 方法 | 路径 | 行为 |
|------|------|------|
| GET | `/api/v1/media/text/info?path=...` | 返回 `Book`（不含正文） |
| GET | `/api/v1/media/text/chapter?path=...&index=N` | 返回第 N 章正文（纯文本） |

两个端点都走现有 `ValidateAccessibleMediaPath` + token 鉴权中间件，不引入新安全模型。

### Server struct 扩展

`server/internal/server/server.go` 的 `Server` 增加 `BookService *service.BookService` 字段，handler 接收引用。

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
    val charStart: Int = 0,
    val charEnd: Int = 0,
    val manifestId: String? = null,
) : Parcelable

@Parcelize
data class Book(
    val path: String,
    val format: String,
    val title: String,
    val charset: String? = null,
    val chapters: List<BookChapter>,
    val modTime: String,
) : Parcelable
```

**`data/MediaRepository.kt`** 新增：
```kotlin
suspend fun getBookInfo(path: String): NetworkResult<Book>
suspend fun getBookChapter(path: String, index: Int): NetworkResult<String>
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

### 导航

独立 Activity，不走 NavHost。`Intent(this, TextReaderActivity::class.java)` + Extra，与 `VideoPlayerActivity` 一致。

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
getBookInfo(path)          // GET /api/v1/media/text/info
getBookChapter(path, idx)  // GET /api/v1/media/text/chapter
```

### `browserView.js` 扩展

text 文件渲染为"文档卡片"：文件名 + 格式图标；mobi/azw3 加灰色蒙层 + 角标"暂不支持"；点击 txt/epub → `#/read?path=...`。

### `textReader.js`

DOM 结构（沿用 `dom.js` 模板风格）：header（返回 / 标题）+ 正文区 + footer（上一章 / 进度 / 下一章 / 目录）+ TOC 抽屉。

行为：
- 进入时 `getBookInfo` → 渲染 TOC
- 读 `localStorage['book_progress:' + path]` → 跳到上次章节
- `getBookChapter` → `element.textContent = chapterText`（避免 XSS）
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
- `GET /text/info`：合法 / 越界 403 / 不存在 500 / mobi 200+unsupported
- `GET /text/chapter`：合法 / 越界 index 400 / 路径越界 403
- token 鉴权 401

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

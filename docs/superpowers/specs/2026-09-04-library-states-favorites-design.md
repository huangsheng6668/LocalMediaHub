# 列表页阅读状态 + 滚动恢复 + 收藏（服务端统一存储）设计（2026-09-04）

## 背景与问题

用户对媒体库的小说/漫画列表页（Web 管理界面 + Android 客户端）提出三点产品优化，调查确认的缺口：

| # | 缺口 | 现状 |
|---|------|------|
| 1 | **无阅读状态**：列表页看不出哪本小说读过、读到哪、读完没有 | Web 进度存 localStorage（`book_progress:<path>`，payload `{chapterIndex, paraIndex, lastReadAt}`）、Android 存 DataStore（`BookProgress`），均只在阅读器内用于续读，列表页零展示；服务端零参与 |
| 2 | **返回列表丢滚动位置**：点开小说/漫画再返回，列表回到顶部 | Web：`#/browser` 路由重入即重渲染，`.view-container` 滚动归零，无任何恢复逻辑。Android：看小说走独立 `TextReaderActivity`（列表在下层保留，正常）；但看漫画/图片走 NavHost 内 `imagePreview` 目标，前进时 Browse 组合被销毁，`rememberLazyGridState()` 是普通 `remember`，返回后回到顶部 |
| 3 | **无收藏（Web）/ 目录不可收藏（Android）** | Web 完全没有收藏功能（`bookmarksView.js` 是阅读器内段落书签，不是收藏）。Android 有完整的文件级收藏（`FavoritesStore` + 卡片心形 + 收藏视图），但 `FolderCard` 无心形按钮、`FavoriteMediaEntry` 只存 `MediaFile`，**目录收不了**；且两端收藏/进度互不相通 |

**用户决策**（2026-09-04）：

- 两端都做；数据**服务端统一存储**（SQLite），多设备共享同一份（服务端无多用户体系，全局单用户，符合个人局域网工具定位）。
- 阅读状态仅小说（**漫画不做状态**）；三态 未读/读过/已读完；**自动判定 + 手动覆盖**。
- 收藏对象：文件 + 目录（含漫画目录）。
- 列表页筛选：**只看收藏 + 按状态筛选**双筛选；"只看收藏"= **过滤当前目录**（与 Android 现有 `filterFilesByFavorites` 语义一致），不做全局收藏列表页。
- 未读状态不显示徽章（列表保持干净），未读只在筛选中体现。

## 目标

- **G1（服务端）**：新增 `LibraryService`（SQLite），统一存储阅读状态（进度 + 完结标记 + 手动覆盖）与收藏；REST API 供两端读写。
- **G2（状态徽章）**：两端列表页小说卡片显示状态徽章——读过 → `读到 N%`，已读完 → `已读完`，未读无徽章。
- **G3（自动判定）**：读到最后一章且接近末尾自动标为已读完；重读不降级；手动覆盖可改任意状态并可清除（恢复自动）。
- **G4（滚动恢复）**：Web 从阅读器返回列表恢复到离开时的位置；Android 从图片预览返回恢复列表位置。
- **G5（收藏）**：两端所有卡片（含文件夹）可收藏/取消；收藏数据服务端同步，多设备一致。
- **G6（筛选）**：两端列表页提供"只看收藏"开关 + 未读/读过/已读完状态筛选。

## 非目标

- 漫画/图片的阅读状态（用户明确排除）。
- 服务端多用户/账户体系（现有 token 认证模型不变）。
- 已删除/改名文件的残留状态行自动清理（徽章自然不显示；清理入口留作后续）。
- 视频播放进度同步（视频进度仍纯本地，本次只同步小说文本进度与收藏）。
- Web 书架页（`#/bookshelf`）、仪表盘、Android 首页书架卡片的改造。

## 关键现状事实（实现时依赖）

- **书籍身份 = 服务端文件 path**。注意两种形态：扫描器输出的列表 `file.path` 保留 config.yaml 原始写法；`/api/v1/books/info` 返回的 `Book.Path` 经 `ValidateAccessibleMediaPath` → `NormalizePath` 规范化（盘符大写 + `filepath.Clean`）。**服务端所有写入先规范化再入库，即可同时兼容两种客户端 key**（Web 用列表 path，Android 阅读器用 `Book.path`）。
- Web 文本卡片结构（`browserView.js:275-307`）：`.media-card` → `.card-preview` + `.card-actions-overlay`（现有 delete 按钮）+ `.card-details > .card-meta`（已有 `card-badge` / `card-badge--unsupported` 徽章样式可复用）；**注意：当前文本卡片缺少 `id` 与 `data-path` 属性**（仅图片/视频卡片有 `id="file-card-..."`，文件夹卡片有 `data-path`），本次需为文本卡片补齐 `id="file-card-${safeBtoa(file.path).replace(/=/g, '')}"` 与 `data-path="${safePath}"`，统一卡片寻址与锚点。
- Android `BrowseContent.kt:256-257`：`rememberLazyGridState()` / `rememberLazyStaggeredGridState()` 底层虽已调用 `rememberSaveable`，但因 `BrowseNavigator` 的 `_restoreScrollTo` 仅在 `navigateBack()`（目录层级回退）时置位，从 NavHost 的 `imagePreview` 目标返回时 `restorePath` 为 null，导致未触发基于 `_scrollPositions` 的恢复；本次需在 `BrowseContent` 中使初始 index 绑定 `getScrollPosition(currentPath)` 或在返回时补全恢复链路。
- Android `FolderCard`（`MediaItems.kt:60`）无 `isFavorite`/`onToggleFavorite` 参数；`FavoritesStore` 的 `FavoriteMediaEntry {file: MediaFile, isSystemBrowse}` 无法表达目录。
- 长按文件已有 `QuickActionsDialog`（browse 组件），是手动标记的天然入口。
- `tags.go` 是新 service 的模板：WAL + busy_timeout + `SetMaxOpenConns(max(4,NumCPU))` + 索引 + 批量 IN 查询 + `s.mu.RLock` 读路径。
- Web 端列表卡片使用 `content-visibility:auto` + `contain-intrinsic-size`（`browser.css:130-131`），卡片自身高度稳定，锚点式滚动恢复可行。

---

## 服务端设计

### 1. 数据模型与 service

新文件 `server/internal/service/library.go`：`LibraryService`（`mu sync.RWMutex` + `db *sql.DB`），数据库 `.data/library.db`，建库/PRAGMA/连接参数照抄 `tags.go`。两张表：

```sql
CREATE TABLE IF NOT EXISTS reading_states (
    path TEXT PRIMARY KEY COLLATE NOCASE,        -- NormalizePath 规范化后的绝对路径（Windows 路径大小写不敏感）
    chapter_index INTEGER NOT NULL DEFAULT 0,
    para_index INTEGER NOT NULL DEFAULT 0,
    percent REAL NOT NULL DEFAULT 0,             -- 客户端算好的全书百分比 [0,100]
    finished INTEGER NOT NULL DEFAULT 0 CHECK (finished IN (0, 1)), -- 客户端判定"读到末尾"，置位后粘滞
    manual_status TEXT CHECK (manual_status IS NULL OR manual_status IN ('unread', 'reading', 'finished')), -- NULL=自动; 'unread'|'reading'|'finished'
    last_read_at INTEGER NOT NULL DEFAULT 0,     -- Unix 毫秒，行新鲜度依据
    updated_at INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS favorites (
    path TEXT PRIMARY KEY COLLATE NOCASE,
    is_dir INTEGER NOT NULL DEFAULT 0 CHECK (is_dir IN (0, 1)),
    is_system INTEGER NOT NULL DEFAULT 0 CHECK (is_system IN (0, 1)), -- 1=系统磁盘浏览(/system)，0=常规扫描库
    title TEXT NOT NULL DEFAULT '',
    media_type TEXT NOT NULL DEFAULT '',         -- video|image|text|folder，收藏视图渲染用
    added_at INTEGER NOT NULL DEFAULT 0
);
-- favorites 表另含 snapshot TEXT NOT NULL DEFAULT '{}'：客户端 JSON 快照（Android 传完整 FavoriteEntry、Web 传轻量对象，拉取方按自己形态解析、无法解析则跳过该条），上限 8KB，随 added_at 胜者整体覆盖
```

索引：`idx_reading_states_last_read_at`、`idx_favorites_added_at`。`LibraryService.Close()` 关 DB，接线进 `Server.Stop`（与 tags DB 并列）。

### 2. 合并规则（upsert 语义）

进度 upsert（`POST /api/v1/library/states`，单条原子 SQL `INSERT ... ON CONFLICT(path) DO UPDATE SET`）：

- 行不存在 → 插入新行。
- 行存在且 `incoming.lastReadAt >= stored.lastReadAt` → 更新进度字段（`chapter_index`, `para_index`, `percent`, `last_read_at`, `updated_at`）；**`finished` 粘滞**：`new.finished = stored.finished || incoming.finished`（重读不降级）；**自动解除手动未读**：若 `stored.manual_status == 'unread'`，由于产生了更新的实际阅读行为，将其**自动重置为 NULL**（恢复自动派生为 reading），避免"用户重读数章但列表永远卡在未读"的严重逻辑缺陷；若为 `'reading'` 或 `'finished'` 则保留。
- `incoming.lastReadAt < stored.lastReadAt` → no-op（200 返回当前状态）。此规则使"客户端把全部本地记录无脑 POST 一遍"的迁移天然幂等且安全。

手动覆盖（`PUT /api/v1/library/states/status`）：
- `status == 'unread'`：置 `manual_status = 'unread'`，**并重置 `finished = 0, percent = 0`**（防止用户欲重新全书重读时，一旦清除覆盖或有新阅读就立即因历史 `finished==1` 弹回已读完）。
- `status == 'finished'`：置 `manual_status = 'finished', finished = 1, percent = 100.0`。
- `status == 'reading'`：置 `manual_status = 'reading'`。
- `status == null`：清除覆盖，置 `manual_status = NULL`，完全回到自动派生。

收藏 upsert（`POST /api/v1/library/favorites`）：按 `added_at` 择新（`snapshot` 跟随胜者整体覆盖）；重复收藏幂等（保留原 `added_at`）。

### 3. 状态派生（单行内，无跨表）

```
status = manual_status                          // 非 NULL 时完全接管
       : (finished == 1)      → 'finished'
       : (行存在)             → 'reading'
       : (无行)               → 'unread'（默认，响应中省略）
```

手动覆盖 `status: null` 表示清除覆盖、回到自动派生。`manual_status='unread'` 语义：徽章隐藏、筛选"未读"命中，但进度字段保留（续读不受影响）。

### 4. API 与路径校验设计

新 handler `server/internal/server/handler/library.go`（薄解析，业务在 service），挂 Bearer auth 路由组（与 media 组同姿态：空 token 开放透传）。不做 rate limit（进度上报高频且非破坏性，与 scan/delete 的限流定位不同）。

**路径校验规则（针对文件/目录双形态的重要修正）**：
- `ValidateAccessibleMediaPath` 内部强制校验 `!info.IsDir()` 且校验媒体扩展名。若直接用于收藏目录或批量包含目录的请求，将因 `access denied: not a file` 全部报错。
- 因此实行**分层精准校验**：
  1. **阅读状态端点**（`/library/states*`）：对象必为小说文本文件，使用 `ValidateAccessibleMediaPath(path, scanRoots, systemAllowedRoots, textExts)` 严格校验文本文件合法性。
  2. **收藏与批量端点**（`/library/favorites*`, `/library/decorations`）：对象可能为文件或目录。校验逻辑复用 `path.go` 的边界规则——通过 `IsPathWithinRoots(path, allRoots)` 保证落在 `scan.roots` 或 `system.allowed_roots` 内，且通过 `IsBlockedRoot` 阻断系统敏感段（`windows`, `system32` 等）。无效路径写端点报 400，批量端点静默跳过。
- **批量 decorations 响应 key 保真**：客户端请求的 `paths` 数组可能存在反斜杠/正斜杠或盘符大小写差异。服务端先在内存建立 `normalizedPath -> originalRequestedPath` 映射，入库查完后以**客户端原始请求传入的 path 字符串作为 response JSON map 的 key** 返回，确保前端 `decorations.states[file.path]` 字典索引 100% 命中，无跨平台分隔符错配风险。

| 端点 | 请求 JSON（统一标准 snake_case） | 响应 JSON |
|---|---|---|
| `POST /api/v1/library/states` | `{"path": "...", "chapter_index": 0, "para_index": 0, "percent": 0.0, "finished": 0, "last_read_at": 1725400000000}` | `{"status": "reading", "updated_at": 1725400000000}`（no-op 也 200） |
| `GET /api/v1/library/states?path=` | — | `{"state": {...}}` 或 `{"state": null}` |
| `PUT /api/v1/library/states/status` | `{"path": "...", "status": "finished"}`（status ∈ `unread` \| `reading` \| `finished` \| null） | `{"status": "finished"}` |
| `POST /api/v1/library/decorations` | `{"paths": ["..."]}`（上限 500，超出 400） | `{"states": {"<path>": {"status": "...", "percent": 45.2, "last_read_at": 1725400000000}}, "favorites": ["<path>"]}` |
| `GET /api/v1/library/favorites` | — | `[{"path": "...", "is_dir": 1, "is_system": 0, "title": "...", "media_type": "folder", "snapshot": {...}, "added_at": 1725400000000}]` |
| `POST /api/v1/library/favorites` | `{"path": "...", "is_dir": 1, "is_system": 0, "title": "...", "media_type": "folder", "snapshot": {...}}` | `{"ok": true}` |
| `DELETE /api/v1/library/favorites?path=` | — | `{"ok": true}` |

`decorations.states` 中：无行的路径省略（客户端按未读处理）；`manual_status='unread'` 的行**包含**且 status 为 `unread`（筛选需要区分"有行但标未读"）。`favorites` 为请求路径中已收藏的子集列表。

### 5. 服务端测试

`service/library_test.go`（临时目录建库）：upsert 三分支（新行/更新/陈旧 no-op）、finished 粘滞、manual_status 覆盖与清除、decorations 批量含 unread 行与越界 path 跳过、favorites upsert 幂等与删除。`handler/library_test.go`：参数校验 400、路径校验失败 400。

---

## 已读完判定（客户端计算，服务端只存结果）

服务端不解析书籍、不知道总章数，判定在阅读器内完成：

- **finished 条件**：当前处于最后一章 **且** 到达该章末尾——Web 分章模式以末尾 50px 触底或最后段落可见为达标，滚动模式当前活动章为末章且滚动近尾部；Android 分章模式末块/❖标志可见，滚动模式活动章为末章且滚动到章尾（阈值 ≥ 90% 章高）。
- **percent 进度公式**：统一公式 `percent = clamp(((chapterIndex + intraChapter) / max(1, totalChapters)) * 100, 0.0, 100.0)`，其中 `intraChapter = paraIndex / max(1, 该章段落数)`（无段落数时取 0），结果四舍五入保留 1 位小数存 REAL。Web 用 `paraIndex`、Android 分章模式用 `blockIndex`、滚动模式用映射后的 (章, 段)，两端公式一致，可独立单测。
- 上报与进度保存同点触发（复用两端现有防抖保存链路），字段 `finished` 自动判定只做 false→true 单向置位。
- 手动覆盖与自动判定独立：手动覆盖写 `manual_status`，仅在手动设为 `'unread'` 时同步重置 `finished = 0, percent = 0`。

---

## Web 端设计

### 1. 新模块 `library.js`（API + 纯函数）

- `fetchDecorations(paths)`（分块 ≤500，自动携带 Bearer Token）、`fetchState(path)`、`reportState(...)`、`setStatus(path, status)`、收藏增删查。
- 纯函数 `applyListFilters(folders, files, decorations, {favoritesOnly, statusFilter})` → 过滤后的列表：
  - **收藏过滤**：`favoritesOnly === true` 时，文件夹通过 `folder.path` 匹配 `decorations.favorites`，文件通过 `file.path` 匹配。
  - **状态过滤**：阅读状态仅针对小说。当 `statusFilter != null` 时，**隐藏所有文件夹及非文本文件**（音视频/图片无阅读状态，不应在已读完等状态下展示干扰），仅保留 `media_type === 'text'` 中符合目标状态的卡片（`unread` 对应不在 `states` 中或 `status === 'unread'`）。两者同时开启取交集。
- 状态徽章 HTML 构造函数（复用 `card-badge`，`已读完` 用 `card-badge--finished` 绿色徽章、`读到 N%` 用 `card-badge--reading` 蓝色徽章）。

### 2. browserView 集成

- 渲染与装饰分步走：
  1. `browsePath` 拿到数据后立即执行首屏快速渲染 `renderBrowserList`（展示基础卡片与骨架，保证即时响应）。
  2. 异步发起 `fetchDecorations`（传入当前视图内所有文件夹与文件的 `path`）。
  3. **DOM 就地更新（Patch）防跳动与闪烁**：装饰数据返回后，**严禁直接用 innerHTML 重绘整个列表**（否则会打断正在进行的用户滚动并导致 `<img loading="lazy">` 重新加载闪烁）。优先通过 `data-path` 或卡片 ID 选择器直接更新卡片内 `.card-meta` 徽章与 `.card-actions-overlay` 收藏按钮状态；仅在当前存在激活的筛选条件时，才调用 `applyListFilters` 执行列表级重排。
- **卡片 ID 与属性补齐**：修改文本卡片生成模板（`browserView.js`），补上 `id="file-card-${safeBtoa(file.path).replace(/=/g, '')}"` 与 `data-path="${safePath}"`，统一所有类型卡片的 DOM 标识。
- **徽章**：仅 `media_type==='text'` 卡片在 `.card-meta` 注入；`已读完`（绿）+ ✓；`读过` 态文案 = `percent > 0 ? '读到 ' + percent + '%' : '读过'`；未读无徽章。
- **收藏心形**：所有卡片 `.card-actions-overlay` 增加心形按钮（红心实心为收藏态，空心为未收藏）。点击乐观更新 UI 并异步调用 API，失败时回滚并弹出 toast。
- **手动标记菜单**：文本卡片 overlay 增加 "⋮" 按钮，点击弹出下拉菜单（标为已读完 / 标为读过 / 标为未读 / 清除手动标记）。采用原生 CSS 类与 CSSOM 动态控制位置，无 inline style，完全符合 CSP。
- **筛选工具栏**：在浏览器工具栏新增 `只看收藏` 开关与 `全部 / 未读 / 读过 / 已读完` 状态单选 chips。

### 3. 滚动恢复（新模块 `scrollMemory.js`）

- 内存 `Map<dirPath, {anchorPath, offset}>`（SPA 会话期有效）。捕获：监听 `.view-container` 滚动（节流 200ms），遍历已渲染卡片找到第一个底边越过视口顶部的卡片，记录其 `anchorPath`（读取 `data-path` 属性，统一文件与目录）以及 `offset = 卡片顶边 - 容器视口顶`。
- 恢复机制：当从小说阅读器返回 `#/browser`，在列表渲染或 DOM 就地更新完成后，按 `anchorPath` 查找对应卡片元素，恢复 `container.scrollTop = el.offsetTop - offset`。找不到（如过滤/排序变更后锚点消失）则安全退回无操作。
- 目录切换、排序变更、筛选条件变更时主动清除该目录记忆。

### 4. 进度同步与平滑迁移

- `textReader.js` 保存进度时保留本地 localStorage 写入（离线容灾），在同一 2s 防抖周期中追加异步调用 `reportState`。打开书籍时并行发起 `fetchState(path)`，与本地 `lastReadAt` 比对择新续读并同步更新本地缓存。
- **平滑分批迁移**：`app.js` 启动时若未发现 `library_migrated_v1` 标记，收集所有 `book_progress:*` 本地记录。为防大批量并发打满连接池，按并发度 6 的轻量 Promise 池逐批上报，迁移完成后写入 `library_migrated_v1` 标记。

### 5. Web 测试

`node --test`：`library.test.mjs`（双筛选矩阵、徽章与按钮 HTML 纯函数、状态显示映射）、`scrollMemory.test.mjs`；`cd tools/xsscheck && go run . ../../server/internal/web` 全绿。

---

## Android 端设计

### 1. 滚动修复（G4）

- **现象与成因诊断**：Compose Foundation 的 `rememberLazyGridState()` 与 `rememberLazyStaggeredGridState()` 底层本身已有 `rememberSaveable` 支持。然而现有架构中：
  1. `_scrollPositions` 记忆恢复仅在 `BrowseNavigator.navigateBack()`（文件夹层级返回）时通过 `restoreScrollTo` 触发。
  2. 当点击图片跳转至 NavHost 内的独立目标 `imagePreview` 时，返回时并未触发目录级 `navigateBack()`，`restoreScrollTo` 恒为 null。
  3. 若 `BrowseContent` 因 NavHost 进出或重组发生重建，默认 `initialFirstVisibleItemIndex = 0` 会覆盖已有滚动状态。
- **修复方案**：
  在 `BrowseContent.kt` 中，`rememberLazyGridState` 与 `rememberLazyStaggeredGridState` 的 `initialFirstVisibleItemIndex` 均绑定 `getScrollPosition(currentPath)`；或者在 `restorePath == null` 但本地存在 `_scrollPositions[currentPath]` 且当前状态处于顶部时，安全补偿滚动至目标索引，彻底修复图片预览返回后回到顶部的体验问题。

### 2. 数据层重构

- `Models.kt`：新增 `ReadingStatus` 枚举（`UNREAD`, `READING`, `FINISHED`）；新增 `LibraryDecoration(val path: String, val status: ReadingStatus, val percent: Double, val lastReadAt: Long)`。
- `FavoritesStore.kt`：重构为兼容模型 `data class FavoriteEntry(val file: MediaFile? = null, val folder: Folder? = null, val isSystemBrowse: Boolean = false)`。
  - **Gson 向后兼容**：自定义解码逻辑兼容三代数据：
    1. 最老代裸 `MediaFile` JSON → `FavoriteEntry(file = mediaFile)`.
    2. 上代 `FavoriteMediaEntry { file, isSystemBrowse }` → `FavoriteEntry(file = file, isSystemBrowse = isSystemBrowse)`.
    3. 新代含 `folder` 字段的 JSON.
  - 派生属性：`val FavoriteEntry.path: String get() = file?.path ?: folder?.path ?: ""`，`val FavoriteEntry.isDir: Boolean get() = folder != null`。
- `MediaRepository.kt`：新增 Retrofit 端点调用，所有装饰与状态读取失败默认捕获降级，不破坏主浏览业务。

### 3. Browse 层集成（`LibraryController`）

- 目录进入/刷新后，批量调用 `fetchDecorations` 注入 `BrowseSharedState.libraryStates: StateFlow<Map<String, LibraryDecoration>>`。
- `TextCard` 接收 `readingStatus: ReadingStatus?` 与 `percent: Double`，分别渲染 `✓ 已读完`（绿）与 `读到 N%`（蓝）胶囊徽章。
- `FolderCard` 增加 `isFavorite` 与 `onToggleFavorite` 回调，卡片右上角集成 `FavoriteToggleIcon`。
- `BrowseFavoritesView`：重构支持渲染收藏的文件夹，点击目录条目可直接进入该目录（根据 `isSystemBrowse` 分流 `browseFolder` 或 `browseSystemPath`）。
- 列表筛选扩展：在 `BrowseTopBar` 附近增加 `未读 / 读过 / 已读完` 筛选 Chips；串联过滤逻辑：当选择小说阅读状态时，过滤掉非文本文件与目录。
- `QuickActionsDialog`：文本卡片长按菜单增加"标为已读完 / 标为读过 / 标为未读 / 清除标记"，点击后调用 `setStatus` 并即时刷新 `libraryStates`。

### 4. 阅读器同步（`TextReaderViewModel`）

- `saveBookProgress` 防抖链路中同时触发 `reportState`，传入客户端计算的 `percent` 与 `finished`。
- `loadBook` 时异步调用 `fetchState(b.path)`，与本机 DataStore 记录根据 `last_read_at` 择新应用并写回本地。
- 网络异常时静默降级为本地阅读，不阻断书籍打开。

### 5. 收藏双向同步（`FavoritesController`）

- 握手成功后执行初次双向同步：将本地 DataStore 收藏逐条 POST 上报（服务端幂等），随后全量 GET `/api/v1/library/favorites`，以 `added_at` 择新合并写入本地 DataStore，本地 UI 继续只监听本地热流。
- 用户点按收藏心形时：本地 DataStore 乐观更新 + 异步推送服务端。

### 6. Android 测试

`./gradlew testDebugUnitTest`：
- `FavoriteEntry` 三代 JSON 反序列化向后兼容性测试。
- `applyListFilters` 纯逻辑矩阵测试（收藏+状态单选组合）。
- 进度 `percent` 计算与 `finished` 边界判定纯函数测试。

---

## 迁移与兼容汇总

| 数据 | 迁移方式 | 幂等性 |
|---|---|---|
| Web localStorage `book_progress:*` | 启动一次性逐条 `reportState`，`library_migrated_v1` 标记 | 服务端 lastReadAt 守卫 |
| Android DataStore `BookProgress` | 首次同步批量 `reportState`（无需先读服务端） | 同上 |
| Android DataStore 收藏 | 逐条 POST + 全量 GET 并集合并 | upsert 幂等 |
| 服务端 | SQLite 落盘，重启不丢 | — |

## 验证

```bash
cd server && go test ./...
cd server/internal/web && node --test
cd tools/xsscheck && go run . ../../server/internal/web
cd android && ./gradlew testDebugUnitTest assembleDebug
```

手工冒烟：

1. **状态**：PC 浏览器读一本小说至最后一章末尾 → 手机刷新列表 → 该书显示 `已读完`；长按/⋮ 手动改状态 → 另一端刷新可见；重读该书第一章 → 状态保持已读完。
2. **滚动**：Web 列表滚到中部点开小说 → 返回 → 落回原卡片附近；Android 列表滚到中部点开一张图 → 返回 → 落回原位置；改排序后返回顶部（符合预期）。
3. **收藏**：Web 收藏一个漫画目录 + 一本小说 → Android 收藏视图出现该目录并可点入；Android 取消收藏 → Web 心形变空心。
4. **筛选**：只看收藏 = 当前目录内过滤；状态筛选"未读"只留未读/手动未读的书。

## 风险与对策

| 风险 | 对策 |
|---|---|
| `ValidateAccessibleMediaPath` 误杀目录收藏与批量查询 | 分层校验：小说阅读状态走媒体文本文件严格校验；收藏与批量 decorations 走根目录范围与敏感段阻断校验，放行有效目录 |
| 手动标记未读后重新阅读，状态卡死在未读 | 上报更新时，若 `stored.manual_status == 'unread'` 则自动清除为 NULL；手动置未读时同步重置 `finished = 0` |
| Web 异步获取 decorations 后重写 innerHTML 导致懒加载闪烁与滚动位置丢失 | 装饰到达后执行 DOM 就地 Patch（仅修改 badge 与收藏按钮），无筛选时不触发全局重绘 |
| Android 从 `imagePreview` 返回时 `restorePath` 为 null 导致滚回顶部 | 在 `BrowseContent` 中使 Grid 初始索引绑定 `getScrollPosition(currentPath)`，跨目标导航回退时无缝恢复 |
| Web 迁移把大量 localStorage 记录并发打满连接池 | 前端采用并发限制为 6 的 Promise 批处理队列，平滑在后台同步 |
| 文件改名/移动后 path 身份失效，状态与收藏残留 | 已列入非目标（后续清理入口）；徽章/收藏只是不显示，无功能性破坏 |
| 多设备同时读同一本，进度 last-write-wins 来回跳 | 个人使用场景低频；按 lastReadAt 择新是确定性行为 |
| `content-visibility` 卡片测 rect 时未渲染导致锚点计算偏差 | 锚点取"已布局卡片"的 rect（滚动中必然已布局），恢复失败退回无恢复，无崩溃路径 |
| Gson 旧收藏 JSON 无 `folder` 字段 | 自定义解码分派，缺省 null → 文件条目；单测锁定三代兼容性 |
| 阅读器上报失败静默导致两端进度短暂不一致 | 本地缓存兜底续读不受影响；下次保存自动重试，最终一致 |


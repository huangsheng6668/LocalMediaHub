# Android 浏览与阅读性能（Phase B）

日期：2026-10-30
范围：`viewmodel/BrowseListFilters.kt` / `TagController.kt` / `ui/component/browse/BrowseStateContent.kt` /
`ui/screen/BrowseScreen.kt` / `ui/screen/TextReaderScreen.kt` / `data/LocalBookRepository.kt` /
`viewmodel/TextReaderViewModel.kt` / `data/RecentActivityStore.kt`
状态：已实施

## 问题与基线

无 Android 设备连接（`adb devices` 为空），基线用 JVM 单元测试探针测得（desktop JVM，
warmup 2 + 5 次取中位；Android 真机通常更慢，此处只用于比较相对量级），探针测完即删除。

| 路径 | 基线（JVM） | 问题 |
|---|---|---|
| `applyBrowseFilters` 10k 文件 | 0–1 ms/次 | 在 `BrowseStateContent` 组合期每分支内联执行，每次重组都产生新 List 实例 |
| `filterFilesByTag` 10k（1430 命中） | 1 ms/次 | 同上；先建 1430 项 Set 再 filter，结果无缓存 |
| `TxtChapterParser.splitChapters` 3.89M 字符 / 600 章 | **540 ms** | 在 Main 线程解析（本地书打开时） |
| `LocalBookRepository.getLocalBookInfo` 同上 | 558–575 ms | `readBytes` + 全文解码 + 分章，全在 Main |
| `getLocalBookChapter` 同一 3.89M 字符文件 | **18 ms/章** | 每章重新 `readBytes` + 全文解码；滚动模式连续拉多章时叠加在 Main |
| Gson 解码 `book_progress` 200 条 | <1 ms | DataStore `.map {}` 在收集者（Main）执行 |
| `overallPercent` 300 章循环 | <1 ms | `remember` 块在组合期读 `listState.layoutInfo`，滚动时每帧使整个阅读器组合失效 |

关键结论：浏览过滤单次 CPU 不高，但「组合期执行 + 新列表实例」使每次重组都重跑并让
`LazyVerticalGrid` 无法跳过；阅读器的真正代价在本地书解析/逐章全文重读（数百 ms 级）
与组合期读布局状态（滚动帧失效）。

## 方案

### B1 浏览筛选下沉 + remember 缓存

- `BrowseListFilters.kt` 新增纯函数 `applyTagFilter(files, fileTags, activeTag)`；
  `TagController.filterFilesByTag` 与 `BrowseViewModel` 的同名委托删除（改后无调用方，
  行为与顺序不变）。
- `BrowseStateContent` 删除 `filterFilesByTag: (List<MediaFile>) -> List<MediaFile>`
  回调参数，改为接收 `fileTags: Map<String, List<Tag>>`（默认 `emptyMap()`）。
- `RootFolders` / `SystemBrowsed` / `Browsed` 三个分支用
  `remember(result.folders, result.files, favorites, showFavoritesOnly, statusFilter, libraryStates, fileTags, activeTagFilter)`
  缓存「标签过滤 + 收藏/状态过滤」结果；无激活标签时 `applyTagFilter` 原样返回，
  无过滤时列表引用与 `browseState` 内一致。
- `BrowseScreen` 收集 `viewModel.fileTags` 传入。

### B2 阅读器整体进度派生化

- 新增纯函数 `data/ReaderScrollProgress.kt#computeOverallPercent`，1:1 复刻原循环
  （首个可见 item 索引 → 章节 + 章内亚 item 比例 → 全书百分比），便于单测。
- `TextReaderScreen` 的 `overallPercent` 从
  `remember(idx, chapterPercent, …) { 组合期读 layoutInfo }` 改为
  `remember(idx, totalChaptersCount, scrollChapters, isScrollMode) { derivedStateOf { … } }`；
  布局状态只在派生计算内读取，百分比整数不变时不触发重组。

### B3 本地书籍解析移出主线程 + 全文解码缓存

- `LocalBookRepository.getLocalBookInfo` / `getLocalBookChapter` 改 `suspend`，
  内部 `withContext(Dispatchers.IO)`（重活全在 IO：`readBytes`/全文解码/分章/ZipFile）。
  `TextReaderViewModel` 的 `File.exists()` 保持同步——仅元数据 stat，包 `withContext`
  反而会让依赖测试调度器的既有用例失去确定性。
- 新增单条解码缓存（`absolutePath + lastModified + length` 为键，`@Volatile`），
  `getTxtChapterContent` 不再逐章 `readBytes` + 全文解码；文件被覆盖/下载更新时键失效。
- TXT 分章与章节切片规则不变，`LocalBookRepositoryTest` 改 `runTest` 继续覆盖
  CRLF 偏移与 sidecar 逻辑。

### B4 DataStore 解码 flowOn(IO)

- `RecentActivityStore` 六个解码 Flow 追加 `.flowOn(Dispatchers.IO)`，Gson 解码不再
  占用收集者线程（Main）；`getAllBookProgressFlow` 的排序同样。

## 非目标（留待后续 Phase）

- HomeViewModel 首屏三次串行网络刷新、LibrarySyncManager 串行全量同步、
  下载并发度与通知节流 → 归入「Phase D：启动、同步与下载链路」。
- 浏览排序（`BrowseSorter`）已在 `Dispatchers.Default` 执行，不在本次范围。

## 验证

- 新增单测：`BrowseLibraryFiltersTest`（applyTagFilter 三例）、`ReaderScrollProgressTest`
  （首章/章中/末章/空输入/无匹配 item）、`LocalBookRepositoryTest` 改 `runTest`
  并新增「二次读取命中缓存但内容不变」用例。
- `cd android && ./gradlew testDebugUnitTest assembleDebug`：408 个用例全绿，
  APK 1.5.2（versionCode 9）。
- 真机（Pixel 6，1080×2400 / 420dpi，debug 构建，服务端同网段 `:8000` 开放模式）
  用 `adb shell dumpsys gfxinfo` 实测：

| 场景 | 帧数 | 卡顿帧 | 分位数 |
|---|---|---|---|
| 浏览根目录滚动 | 247 | 1（0.40%） | p50 5ms / p95 9ms |
| 状态筛选 chips 连续切换 | 216 | 3（1.39%） | p90 7ms |
| 服务器书籍滚动模式滚动 | 155 | 0 | p95 7ms |
| 已下载 TXT（846KB / 35 章）打开后 TOC 跳章 | 77 | 2（2.60%） | p50 6ms / p95 8ms |
| 已下载 TXT 滚动模式滚动 | 199 | 2（1.01%） | p50 5ms / p95 8ms |

- 功能确认：滚动模式页脚「全书进度」随滚动实时变化（服务器书 99%/16 章；
  本地书 31% → 29%），证明 `derivedStateOf` 派生值在真机生效。
- 本地路径确认：从浏览页直接点开已下载书籍后，logcat 中无任何 `/api/v1/books/*`
  请求（只有 library/states 同步），书籍 35 章来自本地解析；`TextReaderActivity`
  `Displayed +94ms`。
- 局限：改动前未在真机采集 A/B 基线（当时无设备），设备数据用于确认功能正确与
  无卡顿回归；JVM 探针数据仅作相对量级参考。

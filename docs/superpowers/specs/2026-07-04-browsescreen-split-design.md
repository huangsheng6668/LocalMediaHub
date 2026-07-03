# BrowseScreen.kt 拆分设计 (Behavior-Preserving, Approach B)

- 日期: 2026-07-04
- 范围: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/BrowseScreen.kt`
- 分支: `master`（项目约定；与第 1–7 轮一致，用户已在历史 plan 的 Global Constraints 中同意自动同步至 GitHub master，不使用 worktree）
- 风险姿态: **严格保持行为**（纯机械抽取，零逻辑改动）
- 关联前序工作: web 端 `app.js` 模块化（round 7）、`BrowseViewModel` 重构（`2026-07-01-browseviewmodel-refactor`）

---

## 1. 背景与问题

`BrowseScreen.kt` 当前为 **834 行的单个 `@Composable fun BrowseScreen(...)`**（全文件仅 1 处 `@Composable` 注解）。它是一个典型的「上帝 Composable」，混聚了：

- 10 个 `collectAsState` 状态订阅 + 2 个本地 UI 状态（`isSearchMode`、`showTagMenuForFile`）
- 3 个 `LaunchedEffect`（加载 roots/tags、toast 上浮、搜索 500ms 防抖）
- `BackHandler`（跨 4 种模式的返回逻辑）
- ~150 行的 `TopAppBar`（搜索态 + 常态势），其中 actions 内嵌 ~55 行的排序 `DropdownMenu`
- 三个对话框：Quick Actions（长按动作表，~120 行）、Delete Confirm（~75 行）、Delete Loading（~18 行）
- 两处 `return@Scaffold` 早退分支（搜索 / 收藏）
- 一个 8 分支的 `when(browseState)` 内容分发器（~200 行）

代价：难以阅读与维护、重组（recomposition）作用域过大、回归风险高。`final_optimization_plan.md` 曾计划拆分此文件，组件层（`MediaItems`/`GridContainers`/`TagComponents`/`BrowseContent`）已抽出，但 Screen 本身始终未拆。web 端 `app.js` 已在 round 7 完成同等模块化，Android 侧尚未对齐。

## 2. 目标与非目标

### 目标
- 将 `BrowseScreen.kt` 从 834 行的单一 Composable 拆为 **1 个外壳 + 8 个聚焦子组件**，外壳降至 ~220 行。
- 每个抽取严格保持行为：渲染结果、交互、错误路径、副作用时序完全一致。
- 每个抽取步骤独立可编译、可由现有单测守护。

### 非目标（显式排除）
- **不改任何业务逻辑**（包括不修硬编码中文字符串、不消除 `!!` 强制解包、不重排副作用）。这些留作后续独立小轮。
- **不解耦 `BrowseViewModel`**：`BrowseContent` / `TagMenuDialog` / `BrowseStateContent` 仍按现状直接接收 `viewModel`（解耦属 Approach C，另行立项）。
- **不动 `BrowseViewModel.kt`**（已有专属重构轮与测试）。
- **不引入 Compose UI 测试基础设施**（项目当前无；本行为保持轮不扩张范围）。

## 3. 设计原则

1. **纯移动（Pure move）**：每个新文件都是对现有代码段的逐行搬迁，签名通过「数据 + lambda 下传」表达。与 web round 7 一致。
2. **单一职责**：每个新文件只做一件事（排序菜单 / 顶部栏 / 某对话框 / 某内容视图 / 状态分发器）。
3. **叶子优先**：按依赖顺序抽取（无依赖的叶子先抽），使每步评审最小化。
4. **现状契约不变**：对 `BrowseViewModel`、`BrowseContent`、`TagMenuDialog` 等既有公共签名零改动。

## 4. 目标架构

所有新文件落入既有包 `com.juziss.localmediahub.ui.component/browse/`（`BrowseSummaryCard`/`BrowseStateCard`/`BrowseLoadingCard` 已在此）。

| 新文件 | 搬自（行） | 职责 |
|------|-----------|------|
| `BrowseSortMenu.kt` | 229–283 | 文件夹 + 文件排序 `DropdownMenu`（内含 Sort IconButton + 展开状态） |
| `BrowseTopBar.kt` | 146–297 | `if (isSearchMode)` 双态 `TopAppBar`（标题 / 导航 / actions），内部调用 `BrowseSortMenu` |
| `QuickActionsDialog.kt` | 334–457 | 长按动作表，`MediaFile` / `Folder` 分支 |
| `DeleteConfirmDialog.kt` | 460–534 | 删除确认（含 recursive Checkbox） |
| `DeleteLoadingDialog.kt` | 537–554 | 删除进行中遮罩 |
| `BrowseSearchView.kt` | 566–591 | 搜索分支（包装 `SearchContent`） |
| `BrowseFavoritesView.kt` | 593–624 | 收藏分支（SummaryCard + `FavoritesContent`） |
| `BrowseStateContent.kt` | 626–831 | 8 分支 `when(browseState)` 内容分发器 |

**`BrowseScreen.kt`（~220 行）保留：**
- 10 个 `collectAsState` + 2 个本地状态
- 3 个 `LaunchedEffect` + `BackHandler`
- `Scaffold` → `BrowseTopBar`，以及精简后的内容 lambda：本地动作状态 + `deleteState` 副作用 + 4 个对话框委托 + 一个 `when` 分发（搜索 / 收藏 / `BrowseStateContent`）。

**关于 5 个状态共享骨架**：`RootFolders`/`SystemDrives`/`SystemBrowsed`/`Browsed`/`TagCollection` 均为 `Column { BrowseSummaryCard(...); <content>.weight(1f) }` 形态。本设计（Approach B）将这 8 个分支保持在**同一个** `BrowseStateContent` 内，骨架不重复、也不引入额外 helper —— 这是选择 B 而非 C（按状态拆 8 文件）的关键理由。

## 5. 组件接口

> 仅示结构；精确 lambda 装配属实现计划范畴。

```kotlin
// 无状态叶子
@Composable
fun BrowseSortMenu(folderSort: SortOrder, fileSort: SortOrder,
                   onFolderSortChange: (SortOrder) -> Unit,
                   onFileSortChange: (SortOrder) -> Unit)

@Composable
fun DeleteLoadingDialog()

// 数据 + 类型化动作 lambda；item 非空（调用方保留既有 `if (x != null) { val item = x!!; ... }`
// 守卫与 `!!`，忠实原文、对齐「不消除 !!」非目标）
@Composable
fun QuickActionsDialog(item: Any, onEditTags: (MediaFile) -> Unit,
                      onDownloadFile: (MediaFile) -> Unit, onDeleteFile: (MediaFile) -> Unit,
                      onDownloadFolder: (Folder) -> Unit, onDeleteFolder: (Folder) -> Unit,
                      onDismiss: () -> Unit)

@Composable
fun DeleteConfirmDialog(item: Any, deleteRecursive: Boolean,
                       onRecursiveChange: (Boolean) -> Unit,
                       onConfirm: (path: String, recursive: Boolean) -> Unit,
                       onDismiss: () -> Unit)

// 轻量包装：分支相关 glue（路径计算、allImages 过滤、isFavoriteSystemBrowse）迁入此处
@Composable
fun BrowseSearchView(searchState: SearchState, searchQuery: String,
                    onClearSearch: () -> Unit, onBrowseFolder: (path: String, name: String) -> Unit,
                    onVideoClick: (MediaFile) -> Unit, onImageClick: (MediaFile, List<MediaFile>) -> Unit,
                    onToggleFavorite: (MediaFile) -> Unit, isFavorite: (String) -> Boolean,
                    getThumbnailUrl: (MediaFile) -> String, onFileLongClick: (MediaFile) -> Unit,
                    modifier: Modifier = Modifier)

@Composable
fun BrowseFavoritesView(favoriteFiles: List<MediaFile>,
                       onVideoClick: (MediaFile) -> Unit, onImageClick: (MediaFile, List<MediaFile>) -> Unit,
                       onToggleFavorite: (MediaFile) -> Unit, isFavorite: (String) -> Boolean,
                       getFavoriteThumbnailUrl: (MediaFile) -> String,
                       onFileLongClick: (MediaFile) -> Unit, modifier: Modifier = Modifier)
```

**两个有意「宽」签名**（如实反映当前行为，非过度设计）：

- `BrowseTopBar`（~16 参：双态、标题、可空 `onBack: (() -> Unit)?`、库操作布尔、system/favorites 切换、进入搜索，以及转发给 `BrowseSortMenu` 的 4 个排序参数）。保持单文件（已批准的 8 个之一），其 `if (isSearchMode)` 与原文一致。*备选：若 review 认为过宽，可将搜索态拆为第 9 个文件，行为不变。*
- `BrowseStateContent`（8 分支 `when`）接收 `browseState` + 路径/标签/收藏数据 + 内容回调 + `viewModel`（保留耦合）。5 个共享 `Column { SummaryCard; content }` 骨架的状态在内部复用同一结构，不重复、不引入 helper。

## 6. 数据流

`BrowseScreen` 仍是唯一的状态采集点（10 流 + 2 本地状态）。数据 + lambda **下传**给 8 个展示型 Composable。无任何状态上提变更。`viewModel` 仍按现状穿透至 `BrowseContent` / `TagMenuDialog` / `BrowseStateContent`（解耦属 Approach C，本轮显式不做）。

## 7. 两个忠实的语义变换

1. **`return@Scaffold` 早退 → `when` 分发**：搜索 / 收藏两处 `return@Scaffold` 改为内容 lambda 末尾的 `when { isSearchMode -> ...; showFavoritesOnly -> ...; else -> BrowseStateContent(...) }`。对话框先于内容渲染（对话框是覆盖层，视觉顺序无关）。渲染结果与副作用时序一致，且消除该 smell。
2. **`@OptIn(ExperimentalMaterial3Api::class)` 迁移**：每个使用实验性 API（`TopAppBar`、`DropdownMenu` 等）的新文件按需添加自身 `@OptIn`；`BrowseScreen` 保留其仍用到的部分。

## 8. 错误处理（不变）

- `BrowseState.Error` 经 `BrowseStateContent` 渲染（retry 动作不变）。
- 删除成功/失败仍由 `BrowseScreen` 内的 `deleteState` `LaunchedEffect` 上浮 toast，并复位 `itemToDelete` / `showDeleteConfirm`。
- 下载 toast 仍由其独立 effect 上浮。
- 严格保持行为 ⇒ 无任何错误路径改动。

## 9. 验证策略（对齐 web round 7）

- **每步**：`cd android && ./gradlew assembleDebug`（编译，含 `@OptIn` 正确性）+ `./gradlew testDebugUnitTest`（既有 `BrowseViewModelTest` / `BrowseSorterTest` 须保持通过）。
- **手工真机回归**（滚动 / 搜索 / 收藏 / 长按 → Quick Actions 与删除 / 排序菜单 / 返回导航）**逐任务推迟给用户执行** —— 与 round 7 推迟浏览器回归同构。
- 不新增 Compose UI 测试（见非目标）。

## 10. 任务拆分与顺序

| # | 任务 | 依赖 | 备注 |
|---|------|------|------|
| 1 | `BrowseSortMenu` | 无 | 最深叶子，~55 行 |
| 2 | `DeleteLoadingDialog` | 无 | 琐碎叶子，~18 行 |
| 3 | `DeleteConfirmDialog` | Dialog | ~75 行 |
| 4 | `QuickActionsDialog` | Dialog | ~120 行，最大对话框 |
| 5 | `BrowseTopBar` | 任务 1 | 含 `BrowseSortMenu` 调用 |
| 6 | `BrowseSearchView` + `BrowseFavoritesView` | 无 | 消除两处 `return@Scaffold`（唯一略非机械步） |
| 7 | `BrowseStateContent` + 将 `BrowseScreen` 收缩为 ~220 行外壳 | 任务 1–6 | 8 分支 `when` 最后搬迁 |
| 8 | 全分支终审（opus） | 全部 | 对齐 round 7 评审关 |

每任务 = 一次搬迁、一次编译、既有单测跑通。任务 6 的 `return@Scaffold → when` 变换将在实现计划中单独写明。

## 11. 风险与回滚

- **主要风险**：抽取时签名装配错误导致编译失败或行为偏移（尤其 `BrowseTopBar` 与 `BrowseStateContent` 的宽签名）。
- **缓解**：叶子优先、每步独立编译 + 单测、任务 6 的非机械变换显式标注。
- **回滚**：每任务独立提交于 `master`；任意一步可 `git revert` 单个提交回到上一稳定态。

## 12. 范围外 / 未来

- 硬编码中文字符串迁入 `strings.xml`（与 `R.string.*` 现有约定对齐）。
- 消除 `!!` 强制解包。
- `BrowseViewModel` 与子组件解耦（Approach C）。
- 引入 Compose UI 测试基础设施并覆盖新抽取的纯展示型对话框。

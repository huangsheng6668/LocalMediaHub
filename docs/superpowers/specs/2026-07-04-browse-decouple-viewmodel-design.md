# BrowseViewModel 解耦设计 (Round 10, Approach C)

- 日期: 2026-07-04
- 范围: `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt`、`TagComponents.kt`、`ui/component/browse/BrowseStateContent.kt`、`ui/screen/BrowseScreen.kt`，以及新增测试
- 分支: `master`（项目约定；与 round 7–9 一致，自动同步至 GitHub master；不使用 worktree）
- 风险姿态: **UI 行为保持**（同 round 9）；签名变更 + 新增测试
- 关联: round 9 spec §2 显式推迟的「解耦 BrowseViewModel（Approach C），另行立项」

---

## 1. 背景与问题

Round 9 把 834 行的 `BrowseScreen` 拆成外壳 + 8 个子 Composable，但**显式保留**了三处对 `BrowseViewModel` 的直接依赖（解耦留到本轮）：

- `BrowseContent.kt`：接收 `viewModel: BrowseViewModel`，内部 `collectAsState` 读取 `folderSortOrder`/`fileSortOrder`/`restoreScrollTo`/`currentPath`，并在 3 个 `LaunchedEffect` 中调用 `saveScrollPosition`/`getScrollPosition`/`consumeRestoreScroll`，以及 `getThumbnailUrl`。
- `browse/BrowseStateContent.kt`：接收 `viewModel`，用于 `filterFilesByTag`/`browseFolder`/`browseSystemPath`/`loadRoots`/`loadSystemDrives`/`setActiveTagFilter`，并把 `viewModel` 继续下传给 `BrowseContent`。
- `TagComponents.kt` 的 `TagMenuDialog`：接收 `viewModel`，调用 `getTagsForFile`/`tagFile`/`untagFile`。

代价：这三个组件无法脱离 `BrowseViewModel` 实例化（god-object 耦合），不可独立做 UI 测试；`BrowseStateContent` 还把整个 `viewModel` 转发给 `BrowseContent`，耦合面进一步放大。

`BrowseSearchView`、`BrowseFavoritesView` 在 round 9 已是 `viewModel`-free，本轮不动。

## 2. 目标与非目标

### 目标
- 从 `BrowseContent` / `BrowseStateContent` / `TagMenuDialog` 移除 `viewModel: BrowseViewModel` 依赖，改为显式状态 + 回调 + 纯函数。
- 引入一个聚焦的 `BrowseContentState` 数据类承载内容网格所需的响应式状态。
- 写**首批** Compose UI 测试（Robolectric JVM）证明上述组件可独立测试。
- UI 行为保持不变（渲染、滚动保存/恢复、排序置顶、标签切换、错误重试全部一致）。

### 非目标（显式排除）
- 不改 `BrowseViewModel` 的任何行为或公共 API（它只是不再被下传）。
- 不动 `BrowseSearchView` / `BrowseFavoritesView`（已解耦）。
- 不引入功能、不改错误路径、不重构滚动算法本身（仅改其数据来源）。
- 不追求 100% UI 覆盖率；首批测试以「证明可测」为目标，YAGNI。

## 3. 架构

### 3.1 状态数据类

```kotlin
data class BrowseContentState(
    val folderSort: SortOrder,
    val fileSort: SortOrder,
    val currentPath: String,
    val restoreScrollTo: String?,
)
```

仅承载「内容网格」需要的响应式状态（排序 + 当前路径 + 滚动恢复目标）。回调与纯函数保持为独立参数（不混入数据类）。由 `BrowseScreen` 在已收集的 flow（`folderSort`/`fileSort`/`currentPath` + 本轮新增的 `restoreScrollTo`）上构造，一次构建、向下传递。

### 3.2 解耦后的组件签名

```kotlin
@Composable internal fun BrowseContent(
    folders: List<Folder>, files: List<MediaFile>,
    onFolderClick: (Folder) -> Unit, onVideoClick: (MediaFile) -> Unit, onImageClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit, isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit = {}, onFolderLongClick: (Folder) -> Unit = {},
    state: BrowseContentState,
    onSaveScrollPosition: (path: String, index: Int) -> Unit,
    onConsumeRestoreScroll: () -> Unit,
    getScrollPosition: (path: String) -> Int,
    getThumbnailUrl: (file: MediaFile) -> String,
    modifier: Modifier = Modifier,
)
// 3 个 LaunchedEffect 改读 state.* / 回调，函数体其余不变。

@Composable internal fun BrowseStateContent(
    browseState: BrowseState,
    state: BrowseContentState,            // state.currentPath 取代旧的 currentPath 参数
    isSystemBrowse: Boolean,
    tags: List<Tag>, activeTagFilter: Tag?,
    onVideoClick: (MediaFile) -> Unit, onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit, isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit, onFolderLongClick: (Folder) -> Unit,
    onRetry: () -> Unit,
    filterFilesByTag: (List<MediaFile>) -> List<MediaFile>,
    onSaveScrollPosition: (String, Int) -> Unit,
    onConsumeRestoreScroll: () -> Unit,
    getScrollPosition: (String) -> Int,
    getThumbnailUrl: (MediaFile) -> String,
    modifier: Modifier = Modifier,
)
// 不再接收 viewModel；内部把 state + 滚动/缩略图回调转发给 BrowseContent。

@Composable internal fun TagMenuDialog(
    file: MediaFile, tags: List<Tag>, fileTags: List<Tag>,   // fileTags 由调用方计算
    onTagFile: (tagId: String) -> Unit,                       // Tag.id 为 String
    onUntagFile: (tagId: String) -> Unit,
    onDismiss: () -> Unit,
)
```

### 3.3 `BrowseScreen` 作为唯一接线点

```kotlin
val restoreScrollTo by viewModel.restoreScrollTo.collectAsState()                 // 本轮新增收集
val contentState = BrowseContentState(folderSort, fileSort, currentPath, restoreScrollTo)
…
TagMenuDialog(
    file = file, tags = tags,
    fileTags = viewModel.getTagsForFile(file.relativePath),
    onTagFile = { id -> viewModel.tagFile(id, file.relativePath) },
    onUntagFile = { id -> viewModel.untagFile(id, file.relativePath) },
    onDismiss = { showTagMenuForFile = null },
)
BrowseStateContent(
    browseState = browseState, state = contentState, isSystemBrowse = isSystemBrowse,
    tags = tags, activeTagFilter = activeTagFilter,
    onVideoClick = onVideoClick, onImageClick = onImageClick,
    onToggleFavorite = onToggleFavoriteCb, isFavorite = isFavoriteCb,
    onFileLongClick = onFileLongClickCb, onFolderLongClick = { folder -> itemForActions = folder },
    onRetry = { if (isSystemBrowse) viewModel.loadSystemDrives() else viewModel.loadRoots() },
    filterFilesByTag = viewModel::filterFilesByTag,
    onSaveScrollPosition = viewModel::saveScrollPosition,
    onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
    getScrollPosition = viewModel::getScrollPosition,
    getThumbnailUrl = viewModel::getThumbnailUrl,
    modifier = Modifier.padding(innerPadding),
)
```

`viewModel` 仅在 `BrowseScreen` 出现；其下全部为纯数据/回调。`BrowseViewModel` 行为不变。

## 4. 数据流

`BrowseScreen` 仍是唯一状态采集点（含新增的 `restoreScrollTo`），构造 `BrowseContentState` 并提供所有回调。数据 + 回调单向**下传**给 3 个展示型 Composable；`BrowseStateContent` 把 `state` 与滚动/缩略图回调**转发**给 `BrowseContent`。无状态上提变更。`BrowseContent` 的 3 个 `LaunchedEffect`（保存滚动 / 恢复滚动 / 排序变化置顶）逻辑不变，仅把 `viewModel.*` 替换为 `state.*`/回调。

## 5. 测试策略（首批 Compose UI 测试）

- **运行器：** Robolectric JVM，置于 `android/app/src/test/java/com/juziss/localmediahub/ui/browse/`。用 `@RunWith(RobolectricTestRunner::class)` + `@GraphicsMode(NATIVE_ROBOLECTRIC)` + `createAndroidComposeRule<ComponentActivity>()`。
- **依赖：** 在 `android/app/build.gradle.kts` 新增 `testImplementation("androidx.compose.ui:ui-test-junit4")`（Robolectric 4.13、`androidx.activity:activity-compose:1.8.2` 已就绪；`ui-test-manifest` 已在 `debugImplementation`）。
- **首批用例（~5 个，YAGNI）：**
  - `TagMenuDialogTest`：(1) 已应用标签渲染为勾选、未应用为未勾选；(2) 点击未应用 → `onTagFile(id)`；点击已应用 → `onUntagFile(id)`。
  - `BrowseContentTest`：(1) 用构造状态渲染 folders + files，无 viewModel；(2) `onSaveScrollPosition` 在组合/滚动时触发。
  - `BrowseStateContentTest`：(1) `Error` 态展示错误卡片 + 重试按钮；点击重试 → `onRetry()`。
- **回退：** 任一用例在 Robolectric 跑不通 → 该用例单独迁至 instrumented `androidTest`。

## 6. 验收

- **每任务门禁：** `cd android && ./gradlew testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`，且**包含新增的 Robolectric Compose 测试**全部通过，既有单元测试不回归。
- **行为保持：** UI 渲染、滚动保存/恢复、排序置顶、标签切换、错误重试与 round 9 末态一致。
- **手工真机回归**（滚动记忆、排序切换、长按打标签勾选态、错误重试）逐任务推迟给用户，与 round 7–9 同构。

## 7. 任务拆分（预览）

| # | 任务 | 备注 |
|---|------|------|
| 1 | 测试基础设施：加 `testImplementation(ui-test-junit4)` + 一个 Robolectric-Compose 冒烟测试 | 先验证 harness |
| 2 | 引入 `BrowseContentState`；解耦 `BrowseContent`（签名 + 3 个 LaunchedEffect 改读参数）；更新唯一调用方 `BrowseStateContent` 内部调用（过渡态，仍持 viewModel） + `BrowseContentTest` | 最大块；滚动机制须精确保持 |
| 3 | 解耦 `TagMenuDialog` + `BrowseScreen` 调用点（算 `fileTags`、提供 `onTagFile/onUntagFile`） + `TagMenuDialogTest` | 最简单组件 |
| 4 | 解耦 `BrowseStateContent`（去 viewModel，加 `onRetry` + `state` + 各回调）；更新 `BrowseScreen` 接线（构造 `contentState`、提供全部回调） + `BrowseStateContentTest` | 收尾接线 |
| 5 | 全分支终审 | |

每任务一次提交、一次编译、既有 + 新增测试通过。任务 4 是唯一略复杂的接线步，计划中将单独写明 `BrowseScreen` 的全部新增/改动调用。

## 8. 风险与回滚

- **主要风险：** (a) `BrowseContent` 滚动恢复 `LaunchedEffect` 精确性——任务 2 必须逐行保持行为，门禁会捕获回归；(b) Robolectric + Compose 配置坑——任务 1 先用冒烟测试验证 harness 规避。
- **回滚：** 每任务独立提交于 `master`，任意一步可 `git revert`。

## 9. 范围外 / 未来

- 进一步收窄 `BrowseContent` 签名（如把滚动回调也打包）。
- 更全面的 UI 覆盖（排序置顶、瀑布流分支等）。
- 把 `BrowseContract` 接口模式推广到其他屏幕。

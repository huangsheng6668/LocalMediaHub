# Android 端快速置顶与置底悬浮导航（FAB）设计

- **日期**：2026-09-03
- **状态**：Draft (Pending Approval)
- **作者**：Antigravity & User

---

## 1. 背景与目标

### 1.1 背景
在 LocalMediaHub Android 客户端中：
1. **媒体共享库浏览（`BrowseContent`）**：此前存在简单的两个置顶/置底悬浮按钮，但它们只要目录非空就始终全部显示，缺乏顶部/底部的智能显隐状态感知。
2. **小说文本阅读器（`TextReaderScreen`）**：在分章或连续多章滚动长文本阅读时，目前仅有右侧全书进度条，缺乏一键快速置顶（回段首/章首）和置底（到段尾/缓冲末尾）的快速操作。

### 1.2 目标
1. **统一的智能显隐体验**：
   - 与 Web 端体验对齐，提供智能的显隐控制：
     - 当内容无需滚动时，置顶与置底按钮均自动隐藏；
     - 位于顶部时，仅显示“置底”按钮；
     - 位于中间时，同时显示“置顶”与“置底”按钮；
     - 位于底部时，仅显示“置顶”按钮。
2. **通用可复用组件 `ScrollFabGroup`**：
   - 抽取独立的 Composable 组件 `ui/component/ScrollFabGroup.kt`；
   - 纯逻辑计算函数 `calculateScrollFabVisibility` 剥离并提供 100% 覆盖率的 JVM 单元测试。
3. **沉浸式阅读无感避让**：
   - 在 `TextReaderScreen` 中，放置于右下角但留出边距避让右侧的 `ReaderScrollbar`（全书进度拖动条）；
   - 与阅读器上下控制栏联动：沉浸全屏阅读模式下自动淡出隐藏，绝不遮挡小说正文，点击呼出菜单时同步淡入。

### 1.3 非目标
- 不重写或破坏已有 `ReaderScrollbar`（全书进度条拖动与松手跳章机制）；
- 不修改现有的 `PageTurnSimulator` 卷曲动画与手势翻页逻辑；
- 不引入外部第三方库，纯使用 Jetpack Compose Material 3 基础组件。

---

## 2. 详细设计

### 2.1 纯逻辑计算函数（`calculateScrollFabVisibility`）
```kotlin
data class ScrollFabVisibility(
    val canScrollToTop: Boolean,
    val canScrollToBottom: Boolean,
)

fun calculateScrollFabVisibility(
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    lastVisibleIndex: Int,
    totalItems: Int,
    visibleCount: Int,
    offsetThreshold: Int = 100,
): ScrollFabVisibility {
    if (totalItems <= 1 || visibleCount >= totalItems) {
        return ScrollFabVisibility(canScrollToTop = false, canScrollToBottom = false)
    }
    val atTop = firstVisibleIndex == 0 && firstVisibleOffset <= offsetThreshold
    val atBottom = lastVisibleIndex >= totalItems - 1
    return ScrollFabVisibility(
        canScrollToTop = !atTop,
        canScrollToBottom = !atBottom,
    )
}
```

### 2.2 `ScrollFabGroup` 组件接口与视觉规范
```kotlin
@Composable
fun ScrollFabGroup(
    canScrollToTop: Boolean,
    canScrollToBottom: Boolean,
    onScrollToTop: () -> Unit,
    onScrollToBottom: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
)
```
- **结构与动画**：
  - 采用垂直 `Column`，按钮间距 `Arrangement.spacedBy(8.dp)`；
  - 置顶与置底各自包裹在 `AnimatedVisibility(visible = canScrollTo..., enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut())` 中；
  - 采用 `SmallFloatingActionButton` 或 `FloatingActionButton(modifier = Modifier.size(40.dp))`，图标为 `Icons.Filled.KeyboardArrowUp` 和 `Icons.Filled.KeyboardArrowDown`（大小 `24.dp`）。

### 2.3 接入端点

#### 1. 媒体共享库（`BrowseContent.kt`）
- 监听 `gridState`（或 `staggeredState`）的可视项范围和总数：
  - `totalItems = if (useStaggeredGrid) images.size else folders.size + files.size`
  - 首项与尾项索引接入 `calculateScrollFabVisibility`；
- 点击置顶：`scope.launch { gridState.animateScrollToItem(0) }`；
- 点击置底：`scope.launch { gridState.animateScrollToItem(lastIndex) }`；
- 定位：`Alignment.BottomEnd`，`padding(end = 16.dp, bottom = 16.dp)`。

#### 2. 小说文本阅读器（`TextReaderScreen.kt`）
- 绑定正文 `listState`（分章模式 `ChapterModeContent` 或滚动模式 `ScrollModeContent`）：
  - `totalItems = listState.layoutInfo.totalItemsCount`
  - 首项与尾项索引接入 `calculateScrollFabVisibility`；
- 避让与联动：
  - 与 `isChromeVisible` 联动：仅在 `isChromeVisible == true` 时展示，全屏沉浸阅读时随 `AnimatedVisibility` 自动淡出；
  - 定位：`Alignment.BottomEnd`，`padding(end = 36.dp, bottom = 80.dp)`，避让右侧 `ReaderScrollbar`（宽 `28.dp`）以及底部工具栏（`BottomAppBar`）。
- 点击置顶：`scope.launch { listState.animateScrollToItem(0) }`；
- 点击置底：`scope.launch { listState.animateScrollToItem((totalItems - 1).coerceAtLeast(0)) }`。

---

## 3. 文件变更清单

| 文件 | 类型 | 职责说明 |
|---|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/ScrollFabGroup.kt` | 新建 | 悬浮导航按钮组与纯算法 `calculateScrollFabVisibility` |
| `android/app/src/test/java/com/juziss/localmediahub/ui/component/ScrollFabVisibilityTest.kt` | 新建 | JVM 单元测试：覆盖无滚动、置顶、置底、中间等边界 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/BrowseContent.kt` | 修改 | 将原有常驻 FAB 替换为 `ScrollFabGroup` 并接入智能显隐 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt` | 修改 | 阅读器接入 `ScrollFabGroup`，支持工具栏沉浸联动与进度条避让 |

---

## 4. 测试与验证方案

1. **单元测试**：
   - 运行：`cd android && ./gradlew testDebugUnitTest`
   - 验证：`ScrollFabVisibilityTest` 全部通过，既有单元测试无回归。
2. **编译验证**：
   - 运行：`cd android && ./gradlew assembleDebug`
   - 验证：APK 顺利打包构建成功。

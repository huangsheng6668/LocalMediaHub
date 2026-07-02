# Android 状态持久化（Round 8）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 旋转屏不丢图片 zoom/pan 状态、视频播放位置；进程杀时进度已存 DataStore（已有 dispose flush，补 seek 目标）。

**Architecture:** 仅 Android，2 个任务。Task 1 把 `ImagePreviewScreen` 的 `scale`/`offset` 从 `remember` 改为 `rememberSaveable`（offset 拆两个 Float）；Task 2 给 `VideoPlayerScreen` 加 `rememberSaveable` 位置跟踪、wrap `onProgress` 更新它、`LaunchedEffect` seek 用它。

**Tech Stack:** Kotlin / Jetpack Compose / ExoPlayer Media3 / Hilt。

## Global Constraints

- **提交策略**（`AGENTS.md`）：本地改动自动同步推送至 GitHub `master`。所有提交直接在 `master`，conventional + `Co-Authored-By: Claude <noreply@anthropic.com>`。
- **Kotlin 规则**：Jetpack Compose MVVM；异步 Coroutines。
- **构建**：`cd android && ./gradlew assembleDebug`（编译验证）。中国大陆网络配 gradle 代理。
- **行为约束**：`rememberSaveable` 行为（旋转保持、进程杀不保持）+ dispose flush（进程杀保持）需**真机**验证；程序崩溃/意外 kill 时 dispose 可能不执行——这是系统限制，非本 round 修。
- **范围外**：OkHttp/Coil 网络缓存、ExoPlayer 入 ViewModel、ImagePreview 的 `visibleIndex`/`showTopBar`。

## File Structure

- 修改 `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt` — `ZoomableImageItem` 的 `scale`/`offset` → `rememberSaveable`。
- 修改 `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt` — 加 `savedPositionMs` + wrap `onProgress` + seek。

---

## Task 1: ImagePreviewScreen `remember` → `rememberSaveable`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt`（`ZoomableImageItem` 约 `:162-212`；import 块加 `rememberSaveable`）

**Interfaces:** 无外部接口变化（`ZoomableImageItem` 内部改造）。

> 无单测（Compose 状态是运行时）；靠 `assembleDebug` 编译 + 真机旋转回归。

- [ ] **Step 1: 加 import**

在 `ImagePreviewScreen.kt` 的 `androidx.compose.runtime.*` import 行追加 `rememberSaveable`（若该行只有 `getValue`/`remember` 等，把 `remember` 改成 `remember, rememberSaveable`，或另起一行）。

- [ ] **Step 2: 改状态声明**

将 `ZoomableImageItem` 内（`:162-164`）：

```kotlin
    var scale by remember(file.relativePath) { mutableFloatStateOf(1f) }
    var offset by remember(file.relativePath) { mutableStateOf(Offset.Zero) }
    var hasMoved by remember { mutableStateOf(false) }
```

替换为：

```kotlin
    var scale by rememberSaveable(file.relativePath) { mutableFloatStateOf(1f) }
    var offsetX by rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }
    var offsetY by rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }
    var hasMoved by remember { mutableStateOf(false) } // UI 辅助，reset 可接受
```

- [ ] **Step 3: 改 offset 写入（pointerInput 内）**

将 `pointerInput` 块里的 offset 更新（约 `:187-189`、`:198`）：

```kotlin
                            offset = Offset(
                                x = offset.x + panChange.x,
                                y = offset.y + panChange.y,
                            )
```

改为：

```kotlin
                            offsetX += panChange.x
                            offsetY += panChange.y
```

以及 `:198` `offset = Offset.Zero` 改为：

```kotlin
                            offsetX = 0f
                            offsetY = 0f
```

- [ ] **Step 4: 改 offset 读取（graphicsLayer）**

将 `graphicsLayer` 里的 `:211-212`：

```kotlin
                translationX = if (scale <= 1f) 0f else offset.x
                translationY = if (scale <= 1f) 0f else offset.y
```

改为：

```kotlin
                translationX = if (scale <= 1f) 0f else offsetX
                translationY = if (scale <= 1f) 0f else offsetY
```

- [ ] **Step 5: 构建 + 提交**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/ImagePreviewScreen.kt
git commit -m "fix(android): save image zoom/pan state across rotation

Scale and offset in ZoomableImageItem now use rememberSaveable instead of
remember, so zoom/pan survives configuration change. Offset split into
offsetX/offsetY (two Floats) to avoid a custom Saver.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 2: VideoPlayerScreen 位置跟踪 + seek

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`（`:96-190` 区域；import 加 `rememberSaveable`）

**Interfaces:** 无外部接口变化（`VideoPlayerScreen` 内部改造）。

> 注：`DisposableEffect(Unit)`（`:143-150`）**已在 dispose 时调 `onProgress(exoPlayer.currentPosition, ...)`**——进程杀时的 DataStore flush 已存在。本任务补的是：旋转后新 ExoPlayer **seek 到保存的位置**（而非 `initialPositionMs` 参数值）。

- [ ] **Step 1: 加 import**

在 `VideoPlayerScreen.kt` 的 `androidx.compose.runtime.*` 行追加 `rememberSaveable`。

- [ ] **Step 2: 加 savedPositionMs 状态 + wrap onProgress**

在 `showDeleteConfirm`（`:109`）之后插入：

```kotlin
    var savedPositionMs by rememberSaveable { mutableLongStateOf(initialPositionMs) }

    // Wrap the caller's onProgress so this screen also tracks the position
    // for rememberSaveable (rotation survival). Both the periodic 5s timer
    // and the dispose-time flush update this.
    val wrappedOnProgress: (Long, Long) -> Unit = { positionMs, durationMs ->
        savedPositionMs = positionMs
        onProgress(positionMs, durationMs)
    }
```

- [ ] **Step 3: 替换 onProgress 调用为 wrappedOnProgress**

把 composable 内 3 处 `onProgress(` 调用改为 `wrappedOnProgress(`：
- `DisposableEffect(Unit)` 的 dispose 块内 `:145`（完成时 flush）
- `DisposableEffect(exoPlayer)` 的 `onPlaybackStateChanged` 内 `:157`（播放结束时保存）
- `LaunchedEffect(exoPlayer)` 的 5s 定时器内 `:186`（定期保存）

（搜索 `onProgress(` 确保全部替换——除了 wrappedOnProgress 定义本身。）

- [ ] **Step 4: seek 目标改用 savedPositionMs**

将 `LaunchedEffect(exoPlayer, initialPositionMs)`（`:176-180`）：

```kotlin
    LaunchedEffect(exoPlayer, initialPositionMs) {
        if (initialPositionMs > 0L) {
            exoPlayer.seekTo(initialPositionMs)
        }
    }
```

替换为：

```kotlin
    LaunchedEffect(exoPlayer, savedPositionMs) {
        if (savedPositionMs > 0L) {
            exoPlayer.seekTo(savedPositionMs)
        }
    }
```

`initialPositionMs` 参数保留（`wrappedOnProgress` 的初始值用它），但新增的 `savedPositionMs` 是运行时值。若 `initialPositionMs` 现在仅用于初始化 `savedPositionMs`，它仍被 `mutableLongStateOf(initialPositionMs)` 使用；没问题。

- [ ] **Step 5: 构建 + 提交**

Run: `cd android && ./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL。

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "fix(android): track and seek to saved video position across rotation

Add rememberSaveable savedPositionMs that wraps the existing onProgress,
so the tracked position survives rotation. The new ExoPlayer now seeks to
savedPositionMs instead of initialPositionMs. The existing dispose-time
onProgress call already covers process-kill DataStore persistence.

Co-Authored-By: Claude <noreply@anthropic.com>"
```

---

## Task 3: 真机回归（无代码改动）

**Files:** 无改动（纯验证）。> `rememberSaveable` 是运行时行为，必须真机/模拟器。

- [ ] **Step 1: 装包**

Run: `cd android && ./gradlew assembleDebug` → 装到真机/模拟器。

- [ ] **Step 2: 图片预览旋转**

打开一张图片 → 双指放大 + 平移 → 旋转设备 → 确认 **zoom/pan 位置保持**（不重置为默认 1.0f/Zoom.zero）。

- [ ] **Step 3: 视频播放旋转**

播放一段视频（拉到中间位置）→ 旋转设备 → 确认 **播放位置保持**（不从头开始）。

- [ ] **Step 4: 进程杀恢复**

播放一段视频 → 切后台 → 杀进程（adb shell am kill 或最近任务滑掉）→ 从 Home 重开 → "继续播放"**应显示杀死前的位置**（dispose flush 已在 round-4 前实现，这次验证它仍生效）。

- [ ] **Step 5: 既有功能无回归**

浏览/搜索/收藏/标签/删除/排序等交互正常。

---

## Self-Review（作者已执行）

**1. Spec 覆盖**：
- §3 ImagePreviewScreen scale/offset → rememberSaveable（offset 拆两个 Float）→ Task 1。✅
- §4.1 savedPositionMs by rememberSaveable → Task 2 Step 2。✅
- §4.2 onProgress 更新 → Task 2 Step 2（wrappedOnProgress 实时跟踪）。✅
- §4.3 seek 用 savedPositionMs → Task 2 Step 4。✅
- §4.4 dispose flush → 已存在（`:143-150`，Task 2 只是用 `wrappedOnProgress` 替换，flush 行为不变）。✅
- §5 真机回归 → Task 3。✅
- §6 决策（offset 拆 Float、仅 VideoPlayer dispose、key 用 file.relativePath）→ 各任务落地。✅

**2. 占位符扫描**：无 TBD/TODO；每个代码步骤含完整代码；每条命令含期望输出。✅

**3. 类型/签名一致性**：
- `rememberSaveable(file.relativePath) { mutableFloatStateOf(1f) }` → scale 类型 Float saveable。✅
- `rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }` → offsetX/offsetY Float saveable。✅
- `rememberSaveable { mutableLongStateOf(initialPositionMs) }` → savedPositionMs Long saveable。✅
- 3 处 `onProgress` → `wrappedOnProgress`：dispose、onPlaybackStateChanged、5s 定时器。✅
- `initialPositionMs` 参数保留，仅用于 `mutableLongStateOf` 初始值。✅

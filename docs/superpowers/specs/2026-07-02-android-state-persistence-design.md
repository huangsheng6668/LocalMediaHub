# Android 状态持久化设计（State Persistence · Round 8）

- **日期**: 2026-07-02
- **范围**: Android 客户端（`ImagePreviewScreen.kt`、`VideoPlayerScreen.kt`）
- **策略**: A — rememberSaveable + dispose 时存 DataStore
- **状态**: 待评审

---

## 1. 背景与动机

Android 客户端存在两个状态丢失痛点：

- **旋转屏丢 zoom/pan**：`ImagePreviewScreen` 的 `ZoomableImageItem`（`:162-164`）用 `remember(file.relativePath)` 存 `scale`/`offset`，旋转后 composable 重建 → zoom/pan 重置为默认值。
- **视频进度丢失**：`VideoPlayerScreen`（`:111`）用 `remember` 创建 ExoPlayer，旋转后重建；`initialPositionMs` 作为 seek 目标（`:176`），但 ExoPlayer 位置仅在 `onProgress` 回调（`:99`）定期（~5s）存 DataStore。进程被杀在两次存之间 → 丢最多 5s 进度。

---

## 2. 目标与非目标

### 目标
1. **ImagePreviewScreen 旋转保持**：`scale`/`offset` 改 `rememberSaveable`，旋转不丢 zoom/pan。
2. **VideoPlayerScreen 旋转保持**：加 `rememberSaveable` 位置跟踪，旋转后新 ExoPlayer seek 到正确位置。
3. **进程杀进度覆盖**：ExoPlayer dispose 时 flush 当前位置到 DataStore。

### 非目标（留待后续轮次）
- OkHttp/Coil 网络缓存。
- ExoPlayer 入 ViewModel（方案 C，旋转不重建）。
- ImagePreview 的 `visibleIndex`/`showTopBar` 等 UI 状态 saveable（reset 到合理默认，非关键）。

---

## 3. ImagePreviewScreen 旋转保持

`ImagePreviewScreen.kt` 的 `ZoomableImageItem`（`:162-164`）：

```kotlin
// 改前（remember → 旋转丢）
var scale by remember(file.relativePath) { mutableFloatStateOf(1f) }
var offset by remember(file.relativePath) { mutableStateOf(Offset.Zero) }
var hasMoved by remember { mutableStateOf(false) }
```

改为（`rememberSaveable`；`offset` 拆两个 Float 避免 Saver）：

```kotlin
var scale by rememberSaveable(file.relativePath) { mutableFloatStateOf(1f) }
var offsetX by rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }
var offsetY by rememberSaveable(file.relativePath) { mutableFloatStateOf(0f) }
var hasMoved by remember { mutableStateOf(false) }

// offset 作为一个计算属性使用（或直接用 Offset(offsetX, offsetY) 替换原 offset 引用）
```

更新处（pointerInput 里的 `offset = ...`）改为 `offsetX = newValue.x; offsetY = newValue.y`。读取处（graphicsLayer 的 `translationX/Y`）改为 `offsetX.value` / `offsetY.value`（或 `Offset(offsetX, offsetY)`）。

> `visibleIndex`/`showTopBar`/`hideJob`/`initialized` 保持 `remember`（旋转 reset 到合理默认，非关键）。`listState`（`rememberLazyListState`）本身已 saveable，滚动位置不丢。

---

## 4. VideoPlayerScreen 旋转 + 进程杀进度

`VideoPlayerScreen.kt`：

### 4.1 rememberSaveable 位置跟踪

在 composable 顶部（与 `exoPlayer = remember{...}` 同区）加：

```kotlin
var savedPositionMs by rememberSaveable { mutableLongStateOf(initialPositionMs) }
```

### 4.2 onProgress 更新

`onProgress` 回调（`:99`，被调用方定期传入 positionMs/durationMs）里追加：

```kotlin
onProgress = { positionMs, durationMs ->
    savedPositionMs = positionMs
    // 原有逻辑（存 DataStore 等）不动
}
```

### 4.3 ExoPlayer seek 用 savedPositionMs

将 `LaunchedEffect(exoPlayer, initialPositionMs)`（`:176`，seek 到 initialPositionMs）改为 seek 到 `savedPositionMs`：

```kotlin
LaunchedEffect(exoPlayer, savedPositionMs) {
    if (savedPositionMs > 0) {
        exoPlayer.seekTo(savedPositionMs)
    }
}
```

旋转时 `savedPositionMs`（Bundle）survives → 新 ExoPlayer seek 到正确位置。

### 4.4 dispose 时 flush 到 DataStore

在 ExoPlayer 的 `DisposableEffect`（`:153`）的 `onDispose` 块里，释放前 flush 当前位置：

```kotlin
DisposableEffect(exoPlayer) {
    onDispose {
        onProgress(exoPlayer.currentPosition, exoPlayer.duration) // flush 到 DataStore
        exoPlayer.release()
    }
}
```

进程杀时 compose 执行 dispose → onProgress → DataStore 存最新进度。旋转时也执行（双保险——savedPositionMs 已在 Bundle，dispose flush 确保 DataStore 也最新）。

---

## 5. 测试与验证

- **`assembleDebug`** 编译验证。
- **JVM 单测**（可选）：dispose flush 逻辑可提取为可测函数（传入 currentPosition/duration → 调 savePlaybackProgress），用 Fake repository 验证。
- **真机回归**（必须）：
  - 图片预览：双指 zoom → 旋转 → zoom 保持。
  - 视频播放中 → 旋转 → 位置保持（不从头开始）。
  - 视频播放中 → 杀进程 → 从 Home 重开 → "继续播放"位置准确（dispose flush 验证）。

---

## 6. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 方案 | A（rememberSaveable + dispose-save） | 旋转+进程杀双覆盖，侵入最小 |
| Offset 存储 | 拆两个 Float（offsetX/offsetY） | 无需自定义 Saver，简单直接 |
| dispose-save 范围 | 仅 VideoPlayer | 图片预览无"进度"概念 |
| rememberSaveable key | `file.relativePath` | 保持每文件独立（同原 `remember` key） |
| visibleIndex/showTopBar | 保持 `remember` | 旋转 reset 到合理默认，非关键 |
| ExoPlayer 入 ViewModel | 不做（YAGNI） | 方案 C 过重，rememberSaveable+dispose 已覆盖 |

---

## 7. 后续轮次（不在本 spec，仅备忘）

- **网络缓存**：OkHttp 响应缓存 + Coil `respectCacheHeaders=true`（接 round-3 服务端缓存头）。
- **ExoPlayer 入 ViewModel**：旋转不重建（方案 C）。
- **Web**：`style.css` 响应式 `@media`。
- **测试**：`streaming` Range / media handler / Android 纯函数。
- **架构**：RetrofitClient Hilt 可注入。

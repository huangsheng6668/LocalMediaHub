# ExoPlayer 状态保留设计（Round 20）

- **日期**: 2026-07-07
- **范围**: Android 客户端 — `VideoPlayerScreen.kt` + `MainActivity.kt`
- **策略**: 2 commits — C1 旋转屏 seekTo 修复 + C2 进程被杀恢复（rememberSaveable）
- **状态**: 待评审
- **前置**: Round 4（ImagePreviewScreen OOM 修复 + Coil 缓存调优）；Round 17（OkHttp Hilt 单例 + VideoPlayerViewModel）

---

## 1. 背景与动机

当前 ExoPlayer 播放状态有两个问题：

### 1.1 旋转屏后 seek 恢复不完美与转码视频失效

虽然 `VideoPlayerScreen.kt` 已有 `rememberSaveable { mutableLongStateOf(initialPositionMs) }` 保存 `savedPositionMs`，并尝试在 `LaunchedEffect(exoPlayer)` 中进行 `seekTo`，但这存在以下主要问题：

1. **异步 Seek 导致画面闪烁**：`LaunchedEffect` 在 Composable 首次组合并渲染后才在协程中异步执行。此时 ExoPlayer 已经开始调用 `prepare()` 并播放（`playWhenReady = true`），因此会先从 0 秒播放几帧再跳转到 `savedPositionMs`，导致画面闪烁或短暂的“声音/画面回弹”。
2. **转码视频 seekTo 失效**：若当前流为转码流（URL 中带有 `transcode=true`），由于服务端不支持 Range 分块请求，对其直接调用 `exoPlayer.seekTo(savedPositionMs)` 会失效或导致卡顿。必须通过 `buildStreamUrl(streamUrl, true, savedPositionMs / 1000.0)` 重新构造带 `start` 参数的播放 URL 让服务端从指定位置开始转码。
3. **未绑定 Key 导致状态污染**：`rememberSaveable` 未绑定 `streamUrl` 作为 Key，当在同一个 Screen 实例中切换不同视频时，上一个视频的播放位置可能会残留并污染新视频。
4. **Seek 动作不同步**：当用户在界面上手势拖拽或使用系统控制条 Seek 时，`savedPositionMs` 只能等 5 秒定时器触发后才更新。在此期间若发生旋转或进程被杀，会丢失最近一次 Seek 的进度。

### 1.2 进程被杀后无法恢复

`MainActivity.kt:82-85` 用 `remember { mutableStateOf }`（非 Saveable）持有 4 个视频状态：

```kotlin
var currentVideoFile by remember { mutableStateOf<MediaFile?>(null) }
var currentVideoUrl by remember { mutableStateOf("") }
var currentVideoUsesSystemUrl by remember { mutableStateOf(false) }
var currentVideoStartPositionMs by remember { mutableLongStateOf(0L) }
```

进程被杀后 Activity 重建时这些全部丢失——用户无法恢复到视频播放页面。

### 1.3 范围明确

- ✅ C1: 旋转屏 seekTo 修复
- ✅ C2: 进程被杀恢复（rememberSaveable）
- ❌ 暂停状态保留（playWhenReady 硬编码 true — YAGNI）
- ❌ 播放列表 / queue
- ❌ 字幕 / 音轨选择保留
- ❌ 服务端 / Web 改动

---

## 2. 目标与非目标

### 目标
1. **C1 旋转屏与进程恢复时精准 Seek**：
   - **普通视频（直链）**：在 `remember(streamUrl)` 块内、ExoPlayer 调用 `prepare()` 之前同步执行 `seekTo(savedPositionMs)`，避免异步 seek 的画面闪烁。
   - **转码视频（`transcode=true`）**：若 `savedPositionMs > 0`，在 `remember(streamUrl)` 块内使用 `buildStreamUrl` 重新构造带 `start` 参数的媒体源 URL，代替直接 `seekTo`，实现对转码视频的精准进度恢复。
   - **绑定 Key**：`rememberSaveable` 绑定 `streamUrl` 作为输入 Key，确保切换视频时状态能正确重置。
   - **即时更新**：监听 `Player.Listener.onPositionDiscontinuity`，在发生 Seek 动作时立即同步更新 `savedPositionMs`。
2. **C2 进程被杀恢复**：`currentVideoFile` / `currentVideoUrl` / `currentVideoUsesSystemUrl` / `currentVideoStartPositionMs` 改为 `rememberSaveable`，进程被杀后 Activity 重建时自动恢复。
3. **零行为变化（首次播放）**：首次播放时 `savedPositionMs = 0`，不 seekTo，从头播放。
4. **现有测试不回归**：57 JVM tests 全过。

### 非目标
- ❌ 暂停状态保留（playWhenReady）
- ❌ 播放列表 / queue
- ❌ 字幕 / 音轨
- ❌ 服务端 / Web 改动

---

## 3. 架构与文件清单

### 3.1 文件改动矩阵（2 个 commit）

| Commit | 文件 | 改动类型 | 说明 |
|---|---|---|---|
| C1 | `ui/screen/VideoPlayerScreen.kt` | 改 | `remember(streamUrl)` 块内加 `seekTo(savedPositionMs)` |
| C2 | `MainActivity.kt` | 改 | 4 处 `remember { mutableStateOf }` → `rememberSaveable` |

### 3.2 关键约束

- `MediaFile` 已 `@Parcelize : Parcelable`（`Models.kt:10-22`，已验证）——可直接存入 `rememberSaveable`
- `seekTo` / 转码 URL 重构必须在 `prepare()` 之前（ExoPlayer 文档推荐，规避异步 seek 闪烁）
- `savedPositionMs > 0` 时才 seekTo 或重构转码 URL（避免首次播放无意义 seek/重构）
- `rememberSaveable(streamUrl)` 必须使用 `streamUrl` 作为 key，防止切换视频时数据残留
- `Player.Listener` 需增加 `onPositionDiscontinuity` 监听，保证拖动进度条或手势 seek 时立刻更新 `savedPositionMs`
- `playWhenReady = true` 保持不变（不保留暂停状态 — YAGNI）
- Compose Navigation 已配置 `navController.navigate("videoPlayer")`，进程恢复时 back stack 自动重建

---

## 4. 实现细节

### 4.1 C1: 旋转屏 seekTo 优化

**1. 状态定义优化（增加 key）：**

当前：
```kotlin
var savedPositionMs by rememberSaveable { mutableLongStateOf(initialPositionMs) }
```

改为：
```kotlin
var savedPositionMs by rememberSaveable(streamUrl) { mutableLongStateOf(initialPositionMs) }
```

**2. `remember(streamUrl)` 块内逻辑优化（支持转码流 seek）：**

当前（约 line 154-163）：
```kotlin
ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .setMediaSourceFactory(mediaSource)
    .build().apply {
        val mediaItem = MediaItem.fromUri(streamUrl)
        setMediaItem(mediaItem)
        prepare()
        playWhenReady = true
    }
```

改为：
```kotlin
val isTranscoding = streamUrl.contains("transcode=true")
val finalUrl = if (isTranscoding && savedPositionMs > 0L) {
    buildStreamUrl(streamUrl, true, savedPositionMs / 1000.0)
} else {
    streamUrl
}

val exoPlayer = remember(streamUrl) {
    // ... loadControl, dataSourceFactory, mediaSource 定义保持不变 ...

    ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setMediaSourceFactory(mediaSource)
        .build().apply {
            val mediaItem = MediaItem.fromUri(finalUrl)
            setMediaItem(mediaItem)
            // Round 20: seek to saved position before prepare (only for non-transcoded streams).
            // Transcoded streams are seeked via url parameters reconstruction.
            if (!isTranscoding && savedPositionMs > 0L) {
                seekTo(savedPositionMs)
            }
            prepare()
            playWhenReady = true
        }
}
```

**3. 移除原有的 LaunchedEffect 初始化 seek：**

原有代码：
```kotlin
    // Initial seek on player creation only — NOT keyed on savedPositionMs,
    // otherwise every 5s progress update re-seeks and fights the user's scrubber.
    LaunchedEffect(exoPlayer) {
        if (savedPositionMs > 0L) {
            exoPlayer.seekTo(savedPositionMs)
        }
    }
```
**直接将其全部删除**。因为在 ExoPlayer 构建阶段已完成了同步 seek 或转码 URL 的重构，此处的异步 seek 已是冗余且可能引发竞态或直接 seek 转码视频报错。

**4. 增加 `Player.Listener` 监听以实现即时进度同步：**

在 `DisposableEffect(exoPlayer)` 注册 of `Player.Listener` 中（约 line 188-208）新增 `onPositionDiscontinuity` 回调，以实时捕捉进度变化：
```kotlin
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    wrappedOnProgress(exoPlayer.duration, exoPlayer.duration)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val activity = context as? Activity ?: return
                    activity.requestedOrientation = if (videoSize.width >= videoSize.height) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
            }

            // Round 20: Update savedPositionMs immediately on any seek/discontinuity.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                savedPositionMs = exoPlayer.currentPosition
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }
```

> **为什么在 `remember(streamUrl)` 块内：** ExoPlayer 创建和 seekTo 必须同步执行——如果 seekTo 在 LaunchedEffect 里，可能竞态（LaunchedEffect 在 prepare 之后才执行）。`remember` 块在 Composable 首次组合时执行一次，`savedPositionMs` 此时已从 `rememberSaveable` 恢复。

### 4.2 C2: 进程被杀恢复

**`MainActivity.kt:82-85` — 4 处 `remember` → `rememberSaveable`：**

当前：
```kotlin
var currentVideoFile by remember { mutableStateOf<MediaFile?>(null) }
var currentVideoUrl by remember { mutableStateOf("") }
var currentVideoUsesSystemUrl by remember { mutableStateOf(false) }
var currentVideoStartPositionMs by remember { mutableLongStateOf(0L) }
```

改为：
```kotlin
var currentVideoFile by rememberSaveable { mutableStateOf<MediaFile?>(null) }
var currentVideoUrl by rememberSaveable { mutableStateOf("") }
var currentVideoUsesSystemUrl by rememberSaveable { mutableStateOf(false) }
var currentVideoStartPositionMs by rememberSaveable { mutableLongStateOf(0L) }
```

> **`MediaFile` 已 `@Parcelize`**（`Models.kt:10-22`）——可直接序列化到 `SavedStateHandle`。

> **导入**：`import androidx.compose.runtime.saveable.rememberSaveable`——检查是否已导入（`VideoPlayerScreen.kt:40` 有同 import，`MainActivity.kt` 可能需要添加）。

> **行为变化**：
> - **旋转屏**：原 `remember` 会丢失（旋转后 Activity 重建），改为 `rememberSaveable` 后旋转保留。但 `currentVideoFile` 等在 MainActivity 层级，不在 `VideoPlayerScreen` 内——旋转时 MainActivity 可能不重建（Compose 默认不重建 Activity，只重组）。需确认 Android 配置变更行为。
> - **进程被杀**：`rememberSaveable` 自动序列化到 `SavedStateHandle`，进程恢复时 Compose 从 saved state 重建。

---

## 5. 测试

### 5.1 手工验证

| 场景 | 期望 |
|---|---|
| 首次播放视频 | 从头播放（savedPositionMs=0，不 seekTo） |
| 播放 30s → 旋转屏 | 从 ~30s 继续（savedPositionMs 已由 5s timer 更新） |
| 播放 30s → 按 Home → 杀进程 → 重新打开 app | 导航到 videoPlayer 路由，恢复到 savedPositionMs |
| 播放 30s → 按 Back → 重新点同一视频 | 从 RecentActivityStore 的 playbackProgress 恢复（现有行为不变） |

### 5.2 自动化测试

- 现有 57 JVM tests 全过（无新测试）
- Compose UI 测试可选但本轮不做（进程被杀场景需 `ActivityScenario` 集成测试，超范围）

---

## 6. 实现顺序与提交策略

2 个 commit：

1. **C1 旋转屏 seekTo**：VideoPlayerScreen.kt 单文件改动（~3 行）
2. **C2 进程被杀恢复**：MainActivity.kt 单文件改动（4 处 `remember` → `rememberSaveable` + 1 import）

每个 commit 后：`cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过。

---

## 7. 已决策点

| 决策 | 选择 | 理由 |
|---|---|---|
| 范围 | 旋转 + 进程恢复 | 用户明确选 |
| seekTo 位置 | `remember(streamUrl)` 块内（prepare 之前） | 同步执行无竞态 |
| seekTo 条件 | `savedPositionMs > 0` | 避免首次播放无意义 seek |
| 进程恢复机制 | `rememberSaveable` | MediaFile 已 @Parcelize；Compose 自动桥接 SavedStateHandle |
| `playWhenReady` | 硬编码 `true`（不保留暂停状态） | YAGNI |
| 提交粒度 | 2 个 commit | C1 极小、C2 极小 |

---

## 8. 已知限制（接受）

1. **`playWhenReady` 不保留**：旋转/进程恢复后自动播放。如果用户暂停后旋转，会自动恢复播放。YAGNI 当前轮。
2. **进程被杀恢复依赖 Compose Navigation back stack**：如果用户在 videoPlayer 页面被杀，恢复时直接进 videoPlayer。如果用户在 Browse 页面被杀，恢复后不会自动跳到 videoPlayer——这是正确行为（用户不在视频页）。
3. **`savedPositionMs` 进度保存时效**：对于常规播放进度，每 5 秒更新一次，可能会在非正常进程被杀时丢失最多 5 秒进度。但对于主动 Seek 动作（包括手势拖动与控制栏拖动），已通过 `onPositionDiscontinuity` 实现了毫秒级即时同步保存，不会丢失 Seek 后的进度。
4. **无 `ActivityScenario` 集成测试**：进程被杀场景难以 JVM 单测。手工验证为主。

---

## 9. 非目标（再次明确）

- ❌ 暂停状态保留
- ❌ 播放列表 / queue
- ❌ 字幕 / 音轨
- ❌ 服务端 / Web 改动
- ❌ Compose UI 测试（进程被杀场景）
- ❌ ConnectionViewModel 拆分（Round 21 候选）

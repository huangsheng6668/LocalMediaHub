# ExoPlayer 状态保留设计（Round 20）

- **日期**: 2026-07-07
- **范围**: Android 客户端 — `VideoPlayerScreen.kt` + `MainActivity.kt`
- **策略**: 2 commits — C1 旋转屏 seekTo 修复 + C2 进程被杀恢复（rememberSaveable）
- **状态**: 待评审
- **前置**: Round 4（ImagePreviewScreen OOM 修复 + Coil 缓存调优）；Round 17（OkHttp Hilt 单例 + VideoPlayerViewModel）

---

## 1. 背景与动机

当前 ExoPlayer 播放状态有两个问题：

### 1.1 旋转屏后不 seekTo

`VideoPlayerScreen.kt` 已有 `rememberSaveable { mutableLongStateOf(initialPositionMs) }` 保存 `savedPositionMs`，`wrappedOnProgress` 每 5 秒更新该值。但 ExoPlayer 在 `remember(streamUrl)` 块内创建时：

```kotlin
ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .setMediaSourceFactory(mediaSource)
    .build().apply {
        setMediaItem(mediaItem)
        prepare()       // ← 没有 seekTo(savedPositionMs)
        playWhenReady = true
    }
```

旋转后 ExoPlayer 重建，从头开始播放——`savedPositionMs` 保存了但从未使用。

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
1. **C1 旋转屏 seekTo**：ExoPlayer 创建时 `seekTo(savedPositionMs)`（`prepare()` 之前），`savedPositionMs > 0` 时才 seek。
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
- `seekTo` 必须在 `prepare()` 之前（ExoPlayer 文档推荐）
- `savedPositionMs > 0` 时才 seekTo（避免首次播放无意义 seek）
- `rememberSaveable` 替换后，Compose Navigation back stack 自动持久化到 `SavedStateHandle`
- `playWhenReady = true` 保持不变（不保留暂停状态 — YAGNI）
- Compose Navigation 已配置 `navController.navigate("videoPlayer")`，进程恢复时 back stack 自动重建

---

## 4. 实现细节

### 4.1 C1: 旋转屏 seekTo

**`ui/screen/VideoPlayerScreen.kt` — `remember(streamUrl)` 块：**

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
ExoPlayer.Builder(context)
    .setLoadControl(loadControl)
    .setMediaSourceFactory(mediaSource)
    .build().apply {
        val mediaItem = MediaItem.fromUri(streamUrl)
        setMediaItem(mediaItem)
        // Round 20: seek to saved position before prepare.
        // savedPositionMs is rememberSaveable so it survives rotation.
        // Only seek if > 0 to avoid no-op on first play.
        if (savedPositionMs > 0) {
            seekTo(savedPositionMs)
        }
        prepare()
        playWhenReady = true
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
3. **`savedPositionMs` 每 5 秒更新一次**：旋转或杀进程时可能丢失最多 5 秒进度。可接受（视频通常长得多）。
4. **无 `ActivityScenario` 集成测试**：进程被杀场景难以 JVM 单测。手工验证为主。

---

## 9. 非目标（再次明确）

- ❌ 暂停状态保留
- ❌ 播放列表 / queue
- ❌ 字幕 / 音轨
- ❌ 服务端 / Web 改动
- ❌ Compose UI 测试（进程被杀场景）
- ❌ ConnectionViewModel 拆分（Round 21 候选）

# ExoPlayer 状态保留（Round 20）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 ExoPlayer 旋转屏 seek 闪烁 + 转码视频 seek 失效 + 进程被杀无法恢复三个问题。

**Architecture:** 2 个 commit 顺序执行——C1 修复 VideoPlayerScreen 的 ExoPlayer 初始化（同步 seekTo + 转码 URL 重构 + onPositionDiscontinuity 实时同步 + 删除冗余 LaunchedEffect 异步 seek）；C2 MainActivity 视频状态从 `remember` 改为 `rememberSaveable` 支持进程被杀恢复。

**Tech Stack:** Kotlin + Compose + ExoPlayer/Media3 1.2.0 + rememberSaveable

## Global Constraints

- minSdk=26, targetSdk=34, Kotlin jvmTarget=1.8
- `MediaFile` 已 `@Parcelize : Parcelable`（`Models.kt:10-22`）——可直接存入 `rememberSaveable`
- `buildStreamUrl(baseUrl, transcode, startSec)` 是 VideoPlayerScreen.kt 内的 private helper（line 78），已存在
- 转码流检测：`streamUrl.contains("transcode=true")`
- ExoPlayer `seekTo` 必须在 `prepare()` 之前调用
- `savedPositionMs > 0` 时才 seek（避免首次播放无意义 seek）
- `rememberSaveable(streamUrl)` 必须用 `streamUrl` 作为 key（避免切换视频时位置残留）
- `onPositionDiscontinuity(oldPosition, newPosition, reason)` 是 Media3 1.x 三参数版本
- `playWhenReady = true` 保持不变（不保留暂停状态 — YAGNI）
- MainActivity 使用 `import androidx.compose.runtime.*`（line 6），需加 `import androidx.compose.runtime.saveable.rememberSaveable`
- 每个改动后：`cd android && ./gradlew assembleDebug :app:testDebugUnitTest` 全过
- 2 commit 顺序：C1 → C2

---

### Task 1 (Commit C1): VideoPlayerScreen — 同步 seekTo + 转码 URL 重构 + 实时进度同步

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`

**Interfaces:**
- Consumes: `buildStreamUrl(baseUrl: String, transcode: Boolean, startSec: Double): String`（line 78，private helper）
- Produces: 无（内部修复）

**Extract these from VideoPlayerScreen.kt — 4 changes in 1 commit:**

| Change | Location | Description |
|---|---|---|
| 1. savedPositionMs 加 streamUrl key | line 120 | `rememberSaveable` → `rememberSaveable(streamUrl)` |
| 2. ExoPlayer 初始化加 seekTo + 转码 URL | lines 154-162 | 同步 seekTo（普通流）或 buildStreamUrl 重构（转码流） |
| 3. 删除 LaunchedEffect 异步 seek | lines 210-216 | 移除整个 LaunchedEffect(exoPlayer) 块 |
| 4. Player.Listener 加 onPositionDiscontinuity | lines 187-208 | 新增回调实时更新 savedPositionMs |

- [ ] **Step 1: Add `streamUrl` key to `rememberSaveable`**

Open `android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`. Find line 120:

```kotlin
    var savedPositionMs by rememberSaveable { mutableLongStateOf(initialPositionMs) }
```

Replace with:

```kotlin
    // Round 20: key on streamUrl so switching videos resets position.
    var savedPositionMs by rememberSaveable(streamUrl) { mutableLongStateOf(initialPositionMs) }
```

- [ ] **Step 2: Modify ExoPlayer initialization — seekTo + transcode URL**

Find the `remember(streamUrl)` block (lines 130-163). The current ExoPlayer builder at lines 154-162:

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

Replace with:

```kotlin
        // Round 20: For transcoded streams, seekTo doesn't work because the
        // server can't Range-slice a transcode. Instead, rebuild the URL with
        // a `start` param so ffmpeg begins transcoding from savedPositionMs.
        // For direct streams, seekTo before prepare is the correct sync path.
        val isTranscoding = streamUrl.contains("transcode=true")
        val finalUrl = if (isTranscoding && savedPositionMs > 0L) {
            buildStreamUrl(streamUrl, true, savedPositionMs / 1000.0)
        } else {
            streamUrl
        }

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSource)
            .build().apply {
                val mediaItem = MediaItem.fromUri(finalUrl)
                setMediaItem(mediaItem)
                // Round 20: sync seek before prepare (only for non-transcoded).
                // Transcoded streams are seeked via URL `start` param above.
                if (!isTranscoding && savedPositionMs > 0L) {
                    seekTo(savedPositionMs)
                }
                prepare()
                playWhenReady = true
            }
```

- [ ] **Step 3: Delete the LaunchedEffect async seek block**

Find lines 210-216:

```kotlin
    // Initial seek on player creation only — NOT keyed on savedPositionMs,
    // otherwise every 5s progress update re-seeks and fights the user's scrubber.
    LaunchedEffect(exoPlayer) {
        if (savedPositionMs > 0L) {
            exoPlayer.seekTo(savedPositionMs)
        }
    }
```

Delete the entire block (including the comment). This async seek is now redundant — Step 2 handles it synchronously in the `remember` block before `prepare()`.

- [ ] **Step 4: Add `onPositionDiscontinuity` to Player.Listener**

Find the `DisposableEffect(exoPlayer)` block (lines 187-208). The current listener object:

```kotlin
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
        }
```

Replace with:

```kotlin
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

            // Round 20: update savedPositionMs immediately on any user-initiated
            // seek (scrubber drag, gesture). Without this, a rotation or process
            // kill within 5s of a seek would lose the new position.
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                savedPositionMs = exoPlayer.currentPosition
            }
        }
```

- [ ] **Step 5: Verify build + tests**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 tests pass.

- [ ] **Step 6: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt
git commit -m "$(cat <<'EOF'
fix(android): sync ExoPlayer seekTo + transcode URL + instant progress (round 20 C1)

- savedPositionMs keyed on streamUrl (prevent cross-video position leak)
- ExoPlayer init: sync seekTo before prepare (fixes rotation flicker)
- Transcoded streams: buildStreamUrl with start param instead of seekTo
- Delete redundant LaunchedEffect async seek (was racing with prepare)
- Player.Listener: onPositionDiscontinuity for instant seek save (no 5s gap)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2 (Commit C2): MainActivity — rememberSaveable for process death recovery

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt`

**Interfaces:**
- Consumes: `MediaFile : Parcelable`（@Parcelize）
- Produces: 无

- [ ] **Step 1: Add `rememberSaveable` import**

Open `android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt`. After line 6 (`import androidx.compose.runtime.*`), add:

```kotlin
import androidx.compose.runtime.saveable.rememberSaveable
```

- [ ] **Step 2: Replace 4 `remember` with `rememberSaveable`**

Find lines 82-85:

```kotlin
    var currentVideoFile by remember { mutableStateOf<MediaFile?>(null) }
    var currentVideoUrl by remember { mutableStateOf("") }
    var currentVideoUsesSystemUrl by remember { mutableStateOf(false) }
    var currentVideoStartPositionMs by remember { mutableLongStateOf(0L) }
```

Replace with:

```kotlin
    // Round 20: rememberSaveable for process-death recovery.
    // MediaFile is @Parcelize so it serializes to SavedStateHandle automatically.
    var currentVideoFile by rememberSaveable { mutableStateOf<MediaFile?>(null) }
    var currentVideoUrl by rememberSaveable { mutableStateOf("") }
    var currentVideoUsesSystemUrl by rememberSaveable { mutableStateOf(false) }
    var currentVideoStartPositionMs by rememberSaveable { mutableLongStateOf(0L) }
```

> Do NOT change `currentImageFile` (line 87) or other non-video state — only the 4 video-related fields. Image preview state has its own lifecycle and `ImagePreviewScreen` uses `rememberSaveable` internally for scroll position.

- [ ] **Step 3: Verify build + tests**

Run: `cd android && ./gradlew assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all 57 tests pass.

- [ ] **Step 4: Commit**

```bash
cd "E:\github_project\LocalMediaHub"
git add android/app/src/main/java/com/juziss/localmediahub/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat(android): rememberSaveable for video state recovery (round 20 C2)

4 video navigation fields (currentVideoFile/Url/UsesSystemUrl/
StartPositionMs) upgraded from remember to rememberSaveable.
Process-death recovery now restores the video player screen with
correct file + URL + position. MediaFile @Parcelize makes this
zero-config.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## 附录 A: 实现速查

| Commit | 文件数 | 改动量 | 风险 | 测试覆盖 |
|---|---|---|---|---|
| C1 VideoPlayerScreen seek fix | 1 modify | ~30 行净改 | 低-中（转码 URL 逻辑） | 57 tests + 手工旋转验证 |
| C2 MainActivity rememberSaveable | 1 modify | ~8 行净改 | 低 | 57 tests + 手工进程恢复验证 |

## 附录 B: `buildStreamUrl` 函数参考

`VideoPlayerScreen.kt:78` 已有 private helper：

```kotlin
private fun buildStreamUrl(baseUrl: String, transcode: Boolean, startSec: Double): String {
    var clean = baseUrl
        .replace(Regex("[?&]transcode=true"), "")
        .replace(Regex("[?&]start=[^&]*"), "")
    clean = clean.replace("?&", "?").removeSuffix("?").removeSuffix("&")
    val params = mutableListOf<String>()
    if (transcode) params.add("transcode=true")
    if (transcode && startSec > 0) params.add("start=%.3f".format(startSec))
    return if (params.isEmpty()) clean else {
        val sep = if (clean.contains("?")) "&" else "?"
        "$clean$sep${params.joinToString("&")}"
    }
}
```

Task 1 Step 2 调用 `buildStreamUrl(streamUrl, true, savedPositionMs / 1000.0)`。此函数已处理 URL 清理（去旧 transcode/start params + 补 separator），无需额外处理。

## 附录 C: 已知限制（接受）

1. **`playWhenReady` 不保留**：旋转/进程恢复后自动播放。YAGNI。
2. **进程被杀恢复依赖 Navigation back stack**：仅在 videoPlayer 路由被杀时恢复。Browse 页面被杀不会跳转。
3. **`onPositionDiscontinuity` 可能频繁触发**：每次 UI seekbar 拖动都会更新 `savedPositionMs`（写入 SavedStateHandle binder）。实际开销极低（一个 Long 写入），可接受。
4. **转码流首次播放 `savedPositionMs = 0` 时不用 buildStreamUrl**：直接用原 `streamUrl`（不含 start param），从 0 开始转码。正确行为。
5. **无 ActivityScenario 集成测试**：进程被杀场景难以 JVM 单测。手工验证为主。

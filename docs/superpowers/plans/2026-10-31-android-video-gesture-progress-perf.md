# Android 视频播放 Phase 2 实施计划

对应设计：`docs/superpowers/specs/2026-10-31-android-video-gesture-progress-perf-design.md`

## 任务 1：手势百分比门控测试（RED）

- 文件：`android/app/src/test/java/com/juziss/localmediahub/ui/component/GesturePercentGateTest.kt`（新建）
- 内容：

  ```kotlin
  package com.juziss.localmediahub.ui.component

  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class GesturePercentGateTest {
      @Test fun firstValueIsEmitted() { assertTrue(GesturePercentGate().shouldEmit(42)) }
      @Test fun repeatedValueIsSuppressed() {
          val gate = GesturePercentGate()
          assertTrue(gate.shouldEmit(42))
          assertFalse(gate.shouldEmit(42))
      }
      @Test fun changedValueIsEmitted() {
          val gate = GesturePercentGate()
          gate.shouldEmit(42)
          assertTrue(gate.shouldEmit(43))
          assertFalse(gate.shouldEmit(43))
      }
      @Test fun resetAllowsSameValueAgain() {
          val gate = GesturePercentGate()
          gate.shouldEmit(42)
          gate.reset()
          assertTrue(gate.shouldEmit(42))
      }
      @Test fun quantizeTruncatesLikeHudText() {
          assertEquals(45, quantizeGesturePercent(0.456f))
          assertEquals(100, quantizeGesturePercent(1f))
          assertEquals(0, quantizeGesturePercent(0f))
      }
      @Test fun quantizeClampsOutOfRange() {
          assertEquals(100, quantizeGesturePercent(1.4f))
          assertEquals(0, quantizeGesturePercent(-0.3f))
      }
  }
  ```

- 验证：`cd android && ./gradlew testDebugUnitTest --tests "*GesturePercentGateTest"`，
  预期编译失败（`quantizeGesturePercent` / `GesturePercentGate` 未定义）。

## 任务 2：手势百分比门控实现（GREEN）

- 文件：`android/app/src/main/java/com/juziss/localmediahub/ui/component/PlayerGestureDetector.kt`
- 新增顶层声明：

  ```kotlin
  /** 0–100 的整数百分比，与 HUD 文案同一精度。 */
  internal fun quantizeGesturePercent(fraction: Float): Int =
      (fraction * 100f).toInt().coerceIn(0, 100)

  /**
   * 整数百分比不变时抑制重复的系统调用与指示器发射。
   * ACTION_MOVE 触发频率远高于百分比变化频率。
   */
  internal class GesturePercentGate {
      private var lastPercent = Int.MIN_VALUE

      fun shouldEmit(percent: Int): Boolean {
          if (percent == lastPercent) return false
          lastPercent = percent
          return true
      }

      fun reset() {
          lastPercent = Int.MIN_VALUE
      }
  }
  ```

- 验证：任务 1 全绿。

## 任务 3：手势监听器接入门控（GREEN）

- 文件：`PlayerGestureDetector.kt`
- `remember(exoPlayer)` 块内：
  - 删除 `getMaxVolume(context)` / `setVolume(context, vol)`（二者每次调用都重取
    AudioManager）；
  - 新增 `val brightnessGate = GesturePercentGate()`、`val volumeGate = GesturePercentGate()`、
    `var audioManager: AudioManager? = null`、`var maxVolume = 0`；
  - ACTION_DOWN（`gestureActive = true` 之后）：
    ```kotlin
    audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    volumeStart = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    brightnessStart = getBrightness(ctx)
    brightnessGate.reset()
    volumeGate.reset()
    ```
  - 亮度分支：
    ```kotlin
    val newBrightness = (brightnessStart + progress).coerceIn(0f, 1f)
    val percent = quantizeGesturePercent(newBrightness)
    if (brightnessGate.shouldEmit(percent)) {
        setBrightness(ctx, newBrightness)
        currentOnBrightnessIndicatorChange.value(
            GestureIndicator(
                visible = true,
                iconResId = R.drawable.ic_brightness_6,
                text = "$percent%",
                progress = newBrightness,
            )
        )
    }
    ```
  - 音量分支：
    ```kotlin
    val delta = (progress * maxVolume).toInt()
    val newVol = (volumeStart + delta).coerceIn(0, maxVolume)
    val volProgress = if (maxVolume > 0) newVol.toFloat() / maxVolume else 0f
    val percent = quantizeGesturePercent(volProgress)
    if (volumeGate.shouldEmit(percent)) {
        audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
        currentOnVolumeIndicatorChange.value(
            GestureIndicator(
                visible = true,
                iconResId = R.drawable.ic_volume_up,
                text = "$percent%",
                progress = volProgress,
            )
        )
    }
    ```
- 验证：`assembleDebug` 通过；行为上 HUD 百分比与系统音量/亮度同步。

## 任务 4：进度策略测试（RED）

- 文件：`android/app/src/test/java/com/juziss/localmediahub/ui/screen/PlaybackProgressPolicyTest.kt`（新建）
- 内容：

  ```kotlin
  package com.juziss.localmediahub.ui.screen

  import androidx.compose.runtime.saveable.SaverScope
  import org.junit.Assert.assertEquals
  import org.junit.Assert.assertFalse
  import org.junit.Assert.assertTrue
  import org.junit.Test

  class PlaybackProgressPolicyTest {
      private val scope = object : SaverScope {
          override fun canBeSaved(value: Any): Boolean = true
      }

      @Test fun unknownDurationNeverPersists() {
          assertFalse(shouldPersistPlaybackProgress(60_000L, 0L, 0L))
          assertFalse(shouldPersistPlaybackProgress(60_000L, -1L, 0L))
      }
      @Test fun zeroPositionNeverPersists() {
          assertFalse(shouldPersistPlaybackProgress(0L, 600_000L, 120_000L))
      }
      @Test fun unchangedPositionDoesNotRewrite() {
          assertFalse(shouldPersistPlaybackProgress(120_000L, 600_000L, 120_000L))
      }
      @Test fun smallDriftDoesNotRewrite() {
          assertFalse(shouldPersistPlaybackProgress(123_000L, 600_000L, 120_000L))
      }
      @Test fun forwardProgressPersistsAfterMinDelta() {
          assertTrue(shouldPersistPlaybackProgress(125_000L, 600_000L, 120_000L))
      }
      @Test fun backwardSeekPersistsAfterMinDelta() {
          assertTrue(shouldPersistPlaybackProgress(15_000L, 600_000L, 120_000L))
      }
      @Test fun holderRoundTripsThroughSaver() {
          val holder = PlaybackPositionHolder(93_000L)
          val saved = with(PlaybackPositionHolderSaver) { scope.save(holder) }
          assertEquals(93_000L, saved)
          assertEquals(93_000L, PlaybackPositionHolderSaver.restore(saved!!)?.value)
      }
  }
  ```

- 验证：预期编译失败（`shouldPersistPlaybackProgress` / `PlaybackPositionHolder` /
  `PlaybackPositionHolderSaver` 未定义）。

## 任务 5：进度策略实现（GREEN）

- 文件：`android/app/src/main/java/com/juziss/localmediahub/ui/screen/PlaybackProgressPolicy.kt`（新建）

  ```kotlin
  package com.juziss.localmediahub.ui.screen

  import androidx.compose.runtime.saveable.Saver
  import kotlin.math.abs

  /**
   * 播放位置的跨配置/进程重建载体。刻意不是快照状态：进度循环每 10s
   * 写一次，可观察值会让整个播放器重组，而它只在重建播放器时被读一次。
   */
  internal class PlaybackPositionHolder(var value: Long)

  internal val PlaybackPositionHolderSaver: Saver<PlaybackPositionHolder, Long> = Saver(
      save = { it.value },
      restore = { PlaybackPositionHolder(it) },
  )

  /** 两次持久化之间视为「无有效进度」的最小位置差。 */
  internal const val PROGRESS_PERSIST_MIN_DELTA_MS: Long = 5_000L

  /** 进度刷新 tick（刷新 holder 与门控持久化共享）。 */
  internal const val PROGRESS_PERSIST_INTERVAL_MS: Long = 10_000L

  /**
   * 是否值得写入 RecentActivityStore。该 store 在 isValidProgress 拒绝时
   * （position < 10s 或 duration <= 0）是**删除条目**而非忽略，所以播放器
   * 未就绪（duration 未知）或续播 seek 尚未生效（position 0）时绝不能写。
   */
  internal fun shouldPersistPlaybackProgress(
      positionMs: Long,
      durationMs: Long,
      lastPersistedPositionMs: Long,
  ): Boolean {
      if (durationMs <= 0L) return false
      if (positionMs <= 0L) return false
      return abs(positionMs - lastPersistedPositionMs) >= PROGRESS_PERSIST_MIN_DELTA_MS
  }
  ```

- 验证：任务 4 全绿。

## 任务 6：播放器浮层拆分（重组隔离）

- 文件：`android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
- 新增文件级 `private class PlayerOverlayState(initialShowRestartChip: Boolean = false)`：
  `seekState` / `playPauseIndicator` / `brightnessIndicator` / `volumeIndicator` /
  `rewindSeekSeconds` / `isRewindRippleVisible` / `forwardSeekSeconds` /
  `isForwardRippleVisible` / `showRestartChip`，全部 `mutableStateOf` 委托。
- `VideoPlayerScreen` 内：
  - `val overlay = remember { PlayerOverlayState(initialShowRestartChip = initialPositionMs > 0L) }`；
  - 删除原 `seekState` 等 9 个 `remember`，删除五个自动隐藏 effect、两个涟漪
    effect、seek 应用 effect、`showRestartChip` 的 remember/effect；
  - 手势回调改为写 `overlay.*`；
  - `Box` 内容中删除六个浮层的原实现（保留 PiP / 返回按钮），改为：

    ```kotlin
    if (!isInPipMode) {
        PlayerOverlays(
            exoPlayer = exoPlayer,
            overlay = overlay,
            isBuffering = isBufferingState,
            onRestart = {
                exoPlayer.seekTo(0L)
                positionHolder.value = 0L
            },
        )
        // PiP 按钮 + 返回按钮保持原位
    }
    ```

- 文件末尾新增叶子 composable（均为 `BoxScope` 扩展，读取各自切片并携带原 effect）：
  `PlayerOverlays` / `SeekIndicator` / `PlayPauseIndicator` / `VolumeBrightnessPill` /
  `DoubleTapRipples` / `RestartChip` / `BufferingIndicator`。
- 删除 `val isBuffering: Boolean by isBufferingState`（父级不再读取）。
- 验证：`assembleDebug` 通过；浮层视觉与交互不变。

## 任务 7：进度保存门控接入

- 文件：`VideoPlayerScreen.kt`
- `savedPositionMs` → `val positionHolder = rememberSaveable(streamUrl, saver = PlaybackPositionHolderSaver) { PlaybackPositionHolder(initialPositionMs) }`；
  4 处写入点（`wrappedOnProgress`、`onPositionDiscontinuity`、`onPlayerError`、
  重启 chip）改为 `positionHolder.value = ...`；`remember(streamUrl)` 内读取
  `positionHolder.value`。
- 新增 `val lastPersistedPositionMs = remember(streamUrl) { AtomicLong(initialPositionMs) }`。
- 进度循环改为：

  ```kotlin
  LaunchedEffect(exoPlayer) {
      while (true) {
          delay(PROGRESS_PERSIST_INTERVAL_MS)
          val duration = exoPlayer.duration
          if (duration <= 0L) continue
          val position = exoPlayer.currentPosition
          positionHolder.value = position
          if (shouldPersistPlaybackProgress(position, duration, lastPersistedPositionMs.get())) {
              lastPersistedPositionMs.set(position)
              onProgress(position, duration)
          }
      }
  }
  ```

- `Player.Listener` 新增 `onIsPlayingChanged`：`false` 时跳过 `STATE_ENDED`、
  `duration <= 0`、`position <= 0`，否则 `lastPersistedPositionMs.set(position)` +
  `wrappedOnProgress(position, duration)`。
- `STATE_ENDED` 分支与 `onDispose` 的 `wrappedOnProgress` 增加 `duration > 0` 守卫。
- 新增 import：`java.util.concurrent.atomic.AtomicLong`。

## 任务 8：退出保存可靠性

- 文件：`android/app/src/main/java/com/juziss/localmediahub/VideoPlayerActivity.kt`
- 注入 `@ApplicationScope lateinit var appScope: CoroutineScope`，`onProgress` 中
  `appScope.launch { savePlaybackProgress(...) }` 取代 `rememberCoroutineScope()`；
  删除不再使用的 `rememberCoroutineScope` import。
- 验证：`assembleDebug` 通过。

## 任务 9：版本号

- 文件：`android/app/build.gradle.kts`
- `versionCode 11 → 12`，`versionName "1.5.4" → "1.5.5"`。

## 任务 10：全量验证与提交

- `cd android && ./gradlew testDebugUnitTest assembleDebug`
- 期望：全部单测通过（含新增两个测试类），Debug 构建成功。
- 提交：`perf(android): isolate player overlays and gate progress saves (Phase 2)`
- 只提交本 Phase 相关文件（含 spec/plan）。

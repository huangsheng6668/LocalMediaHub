# Android 视频播放性能设计（Phase 2：手势重组隔离与进度保存门控）

日期：2026-10-31
范围：Android 客户端（服务端 / Web 不动）
上游：`docs/superpowers/specs/2026-10-30-android-video-playback-perf-design.md`（Phase 1 已完成：
HLS 转码回退修复、屏幕常亮、解码器兜底、缓冲调优）。Phase 3（media3 升级 / AFR / 音频
offload）仍未授权，不在本设计内。

## 背景与证据

Phase 1 之后视频播放本身已可用，剩下的开销集中在**手势期间的重组**和**进度保存的频率**。
以下依据来自对 `VideoPlayerScreen.kt` / `PlayerGestureDetector.kt` / `VideoPlayerActivity.kt`
的逐行审计（本轮不改动服务端与 Web）。

### 1. 手势状态全部读在父级函数体里

`VideoPlayerScreen` 的 `seekState` / `playPauseIndicator` / `brightnessIndicator` /
`volumeIndicator` / 双击涟漪 / `showRestartChip` 全部是父级函数体里的
`remember { mutableStateOf(...) }`，并且父级函数体直接读它们：

- `LaunchedEffect(seekState.isSeeking)`（应用 seek）
- `LaunchedEffect(playPauseIndicator.visible)` 等五个自动隐藏 effect
- 六个浮层的 `AnimatedVisibility(...)` 与内容

Compose 的订阅粒度是「读取发生所在的 restartable 函数」。这些读全部发生在
`VideoPlayerScreen` 本体，因此**每一次状态写入都会让整个屏幕重组**，包括：

- `AndroidView(update = ...)` 的 `update` lambda（会比较 player 引用、重设
  `useController`、重设 `OnTouchListener`）；
- `rememberPlayerGestureListener(...)` 及其 7 个 `rememberUpdatedState`；
- `remember(exoPlayer)` 之外的所有参数求值（`pausedText` / `playingText` 等）。

一次音量拖动中 `ACTION_MOVE` 每秒可达 60–120 次；去掉重复 seek 值后仍有数十次
音量/亮度指示器写入。即：**一次拖动 = 数十次全屏重组**。

### 2. 每次 ACTION_MOVE 都做系统 IPC 与字符串分配

`PlayerGestureDetector` 垂直拖动分支：

- 音量：`getMaxVolume(ctx)`（binder）+ `setVolume(ctx, ...)`（内部再
  `getMaxVolume` + `setStreamVolume`，共 3 次 binder）+ `"${(volProgress * 100).toInt()}%"`
  字符串分配 + `GestureIndicator` 分配，**每个 MOVE 都执行**；
- 亮度：`getBrightness`（ACTION_DOWN 一次）+ 每次 MOVE
  `activity.window.attributes = ...`（WindowManager 事务）+ 字符串 + 数据类分配。

实际可见的精度只有整数百分比（HUD 文案就是 `"45%"`），但实现按每个触摸事件的
浮点值全量下发，绝大多数写入与上一帧完全相同。

### 3. 进度保存：每 5 秒无条件写 DataStore，且写的是组合状态

- `savedPositionMs` 是 `mutableLongStateOf`，被父级函数体在
  `remember(streamUrl) { ... }` 中读取；**每 5 秒进度回写都会让整屏重组一次**，
  哪怕画面与浮层毫无变化。
- 进度循环 `while (true) { delay(5_000); onProgress(...) }` 不看播放状态：
  暂停状态下也每 5 秒写一次 DataStore（`savePlaybackProgress` → `edit {}`），
  每次还会刷新 `updatedAt`，把没看过的视频顶到「继续观看」列表前面。
- `savePlaybackProgress` 在 `isValidProgress` 为 false（position < 10s 或
  **duration <= 0**）时是**删除条目**而不是忽略：
  `RecentActivityStore.kt:266`。而退出时的 flush 无条件调用
  `wrappedOnProgress(exoPlayer.currentPosition, exoPlayer.duration)`——播放器尚未
  就绪（duration 未知）时退出会把已有续播点删掉。
- `VideoPlayerActivity` 的 `onProgress` 用 `rememberCoroutineScope()` 启动保存：
  组合被销毁时该 scope 立即取消，**退出时最后一笔保存可能根本不会执行**。

## 方案

### P2-1 手势浮层重组隔离

新增 `PlayerOverlayState`（`@Stable` 普通持有者，字段仍是 `mutableStateOf`）与
`BoxScope.PlayerOverlays` 组合函数，把六个浮层拆成各自读取自己切片的叶子
composable：

| 叶子 | 读取 |
| --- | --- |
| `SeekIndicator` | `seekState`（含手势结束应用 seek 的 effect） |
| `PlayPauseIndicator` | `playPauseIndicator` |
| `VolumeBrightnessPill` | `brightnessIndicator` / `volumeIndicator`（含两个自动隐藏 effect） |
| `DoubleTapRipples` | 两个涟漪的秒数与可见性（含两个 600ms effect） |
| `RestartChip` | `showRestartChip`（含 3s effect） |
| `BufferingIndicator` | `isBufferingState` |

父级 `VideoPlayerScreen` 函数体只**写**状态（通过手势回调），不读任何浮层状态，
因此：

- 拖动 seek 只重组 `SeekIndicator`；
- 拖音量只重组 `VolumeBrightnessPill`；
- `AndroidView(update = ...)` 不再参与手势重组；
- 进度 holder 改为非观察对象后，5s/10s 回写不再触发任何重组。

行为不变：浮层外观、动画、自动隐藏时长、seek 应用时机、重启 chip 初始可见条件
（`initialPositionMs > 0`）全部保持原值。

### P2-2 手势 IPC 与分配节流

`PlayerGestureDetector.kt` 新增两个纯逻辑单元：

```kotlin
internal fun quantizeGesturePercent(fraction: Float): Int =
    (fraction * 100f).toInt().coerceIn(0, 100)

internal class GesturePercentGate {
    private var lastPercent = Int.MIN_VALUE
    fun shouldEmit(percent: Int): Boolean { ... }   // 只在新百分比时返回 true
    fun reset() { lastPercent = Int.MIN_VALUE }     // 新手势开始
}
```

- ACTION_DOWN 时缓存 `AudioManager` 与 `maxVolume`（每次手势 1 次 binder，
  取代每个 MOVE 的 2–3 次），并 `reset()` 两个门控；
- 亮度/音量：先算整数百分比，只有 `shouldEmit(percent)` 为 true 才执行
  `setStreamVolume` / `window.attributes =` 并构造 `"$percent%"` 文案 + 发射
  `GestureIndicator`。

即：一次全屏拖动最多产生 ~100 次系统调用与发射（HUD 精度上限），取代此前的
「每个 MOVE 一次」。撤销 `getMaxVolume` / `setVolume` 两个每调用都重新取
`AudioManager` 的旧辅助函数。

### P2-3 进度保存门控与退出可靠性

新增 `ui/screen/PlaybackProgressPolicy.kt`：

- `PlaybackPositionHolder`：普通 `var value: Long`，**不是**快照状态；
  配 `Saver<PlaybackPositionHolder, Long>` 交给 `rememberSaveable` 保留
  （旋转 / 进程重建）。
- `shouldPersistPlaybackProgress(positionMs, durationMs, lastPersistedPositionMs)`：

  ```kotlin
  if (durationMs <= 0L) return false   // 会删除条目
  if (positionMs <= 0L) return false   // 播放器尚未应用续播 seek
  return abs(positionMs - lastPersistedPositionMs) >= 5_000L
  ```

`VideoPlayerScreen`：

- `savedPositionMs` 状态 → `positionHolder`（`rememberSaveable(streamUrl, saver=...)`），
  所有写入点（进度回写、seek discontinuity、转码回退、重启 chip）改为写 holder；
- 进度循环：`delay(10_000)`，每 tick 刷新 holder（廉价、无重组）；仅当
  `shouldPersistPlaybackProgress` 通过时写 `onProgress` 并更新
  `lastPersistedPositionMs`（`AtomicLong`，初值 `initialPositionMs`）；
- 新增 `Player.Listener.onIsPlayingChanged(false)` 精确 flush：跳过
  `STATE_ENDED`（`onPlaybackStateChanged` 已保存时长）与 `duration <= 0` /
  `position <= 0`，暂停时刻的精确位置立即持久化；
- `onDispose` 与 `STATE_ENDED` 的 flush 增加 `duration > 0` 守卫，避免
  「未就绪就退出」把续播点删掉。

`VideoPlayerActivity`：

- `onProgress` 的保存改由 Hilt 提供的 `@ApplicationScope CoroutineScope` 执行
  （`di/CoroutineScopesModule.kt` 已有单例 `SupervisorJob + Dispatchers.Default`），
  取代 `rememberCoroutineScope()`——后者在组合销毁时被取消，退出时的最后一笔
  保存会丢。

## 非目标

- media3 1.2.0 升级、AFR、音频 offload（Phase 3，未授权）。
- 手势灵敏度、双击步长（10s）、涟漪动画时长、控制栏显示逻辑等交互语义变更。
- 续播弹窗判定（95%/98%）与 `RecentActivityStore` 数据模型。
- 「暂停后拖动、然后从最近任务强杀」这类不经过任何生命周期回调的场景
  （最多丢 10s，与本期 tick 一致）。

## 验证

- 新增 `GesturePercentGateTest`（纯 JVM）：重复百分比抑制、变化发射、reset、
  百分比截断与 0–100 钳制。
- 新增 `PlaybackProgressPolicyTest`（纯 JVM）：duration/position 守卫（防删条目）、
  5s 最小增量、后退 seek、holder 经 `Saver` 往返。
- 既有 `VideoPlayerStreamUrlTest` / `VideoPlayerFallbackTest` /
  `VideoPlaybackMediaItemTest` 保持通过。
- `cd android && ./gradlew testDebugUnitTest assembleDebug`。
- 真机验收（用户）：音量/亮度拖动 HUD 仍连续跟随且无卡顿；暂停/退出后重新打开，
  续播位置与退出瞬间一致（不再出现被清零）。

## 版本

按仓库约定递增：1.5.4 → 1.5.5，versionCode 11 → 12。

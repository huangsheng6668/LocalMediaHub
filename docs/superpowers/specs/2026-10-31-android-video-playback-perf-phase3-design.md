# Android 视频播放性能设计（Phase 3：Media3 升级、静默解码失败兜底、AFR 核对）

日期：2026-10-31
范围：Android 客户端（服务端 / Web 不动）
上游：`docs/superpowers/specs/2026-10-30-android-video-playback-perf-design.md`（Phase 1：
HLS 回退修复、屏幕常亮、解码器兜底、缓冲调优）与
`docs/superpowers/specs/2026-10-31-android-video-gesture-progress-perf-design.md`（Phase 2：
手势重组隔离、进度保存门控）均已完成并通过真机验证。

## 背景与证据

### 1. Media3 落后 9 个 minor（1.2.0 → 1.11.1）

当前依赖 `androidx.media3:*:1.2.0`（2023-12），最新稳定版 1.11.1（2026-09-10；
1.12.0-alpha02 为 alpha，不采用）。五个模块在 Google Maven 均有 1.11.1 制品，
且本仓使用的 API 在 1.11.1 源码中逐一确认仍存在：

| 本仓 API | 1.11.1 状态 |
| --- | --- |
| `DefaultRenderersFactory.setEnableDecoderFallback` | 保留 |
| `DefaultLoadControl.Builder.setBufferDurationsMs / setBackBuffer / setPrioritizeTimeOverSizeThresholds` | 保留 |
| `DefaultMediaSourceFactory.setDataSourceFactory` | 保留 |
| `OkHttpDataSource.Factory(Call.Factory)` | 保留 |
| `MediaSession.Builder(Context, Player)` | 保留 |
| `ExoPlayer.setSeekParameters` | 保留 |

1.11.1 的 `media3-common` 依赖 `kotlin-stdlib:2.2.10`（本仓 Kotlin 编译器 2.2.0，
patch 级差异；已有 `-Xskip-metadata-version-check`），`minSdk` 23（本仓 26），
`compileSdk` 不高于本仓 36。

与本次相关的上游修复/改进（Release Notes）：

- **1.11.0**：把「profile/level 格式正确但无法识别」的视频轨标记为
  `NO_EXCEEDS_CAPABILITIES` 而不是 `supported=YES`。Phase 1 真机验证中的
  H.264 High 4:4:4 静默黑屏曾被期望由此修复；Phase 3 实测该样片解码器仍会
  交付损坏帧（见 §2），说明设备解码器将该 profile 列为支持，改动不会触发。
- **1.11.0**：动态调度（dynamic scheduling）默认开启，播放工作循环不再固定间隔
  轮询，减少主线程唤醒。
- **1.11.0**：音频渲染器增加 100ms ready↔not-ready 宽限期，抑制瞬时欠载导致的
  缓冲状态抖动（本仓 `isBufferingState` 直接跟 `STATE_BUFFERING`）。
- **1.11.0**：修复 `VideoFrameReleaseHelper` 在显示模式变化时重复的 Choreographer
  回调与主线程 CPU 唤醒。
- **1.6–1.10**：AVI/Matroska/MPEG-TS 解复用修复、HLS 播放列表刷新与音轨时间戳
  修复、`DefaultLoadControl` 默认值重调（本仓显式配置，不受影响）。

### 2. 自动回退的错误码白名单覆盖不全（真机实测）

`shouldAutoFallbackToTranscode` 当前只接受 4004 / 4005 / 3003。Phase 1 真机验证
（Pixel 6）发现两类漏网：

- **H.264 High 10**：`format_supported=NO_EXCEEDS_CAPABILITIES`，但 ExoPlayer
  进入 ERROR 后**没有触发回退**——实际错误码是解码器初始化失败（4001），不在白名单。
- **H.264 High 4:4:4**：硬件解码器报错但 ExoPlayer 保持 `STATE_PLAYING`，
  画面全黑——没有任何错误，自然没有回退。

Phase 3 真机复核（Pixel 6 + 1.11.1）修正了 4:4:4 的结论：解码器**确实向
Surface 交付了帧**（`onVideoSizeChanged` 报 1280×720、播放正常走到片尾、
表面有破碎色块），只是输出内容错误。首帧看门狗因此**无法**识别该情况
（`onRenderedFirstFrame` 已触发）；这是解码输出正确性问题，任何「有没有首帧」
的启发式都检测不到，需像素级校验才能发现，明确列为本期不解决的残余。

服务端转码固定输出 H.264（NVENC/QSV/AMF/libx264）+ AAC，因此视频/音频解码器
初始化失败或解码失败都可以由转码修复。

### 3. AFR 已默认开启（无需改代码）

`ExoPlayer.Builder` 的 `C.VideoChangeFrameRateStrategy` 默认值是
`VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS`（1.2.0 与 1.11.1 相同），
`VideoFrameReleaseHelper` 在 API 30+ 会调用
`Surface.setFrameRate(frameRate, FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)`
（两参 API 即「仅在无缝切换时改变刷新率」）。即 AFR 从 1.2.0 起就是开启状态，
本 Phase 只做真机核对，不改实现。

不引入强制 `CHANGE_FRAME_RATE_ALWAYS`：Media3 未暴露该策略常量，需要关闭
Media3 的帧率管理并手写 `Surface.setFrameRate`，非无缝模式下会造成可见的
显示模式切换闪烁。实测 Pixel 6 只有 60/90Hz 两种模式，23.976fps 对两者都
不是整数倍，强制切换没有收益。

### 4. 音频 offload 对纯视频播放不生效（本 Phase 不引入）

`TrackSelectionParameters.AudioOffloadPreferences` 默认
`AUDIO_OFFLOAD_MODE_DISABLED`。初版方案计划改为 `ENABLED`；随后在 1.11.1 源码
`DefaultTrackSelector.maybeConfigureRendererForOffload()` 中确认了它的前置条件：
**只要任一非音频渲染器有选中轨道就直接跳过 offload 配置**——即 offload 只服务于
纯音频播放。本仓是视频播放器，视频轨永远存在，`ENABLED` 是纯 no-op。

真机复核（Pixel 6，48kHz 立体声 AAC 样片）：`dumpsys media.audio_flinger` 中
应用 `AudioTrack` 为 `0x00000001`（PCM 16-bit）+ MIXER 线程，从未出现
compressed-offload 输出线程，与源码结论一致。因此本 Phase **撤销** P3-3，
不保留无效配置。

## 方案

### P3-1 静默解码失败兜底

**a. 扩展错误码白名单**（`VideoPlayerScreen.kt`）：

```kotlin
PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,      // 4001
PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,     // 4002
PlaybackException.ERROR_CODE_DECODING_FAILED,          // 4003
```

现有守卫不变：转码中 / 已回退过一次 / 本地文件一律不再回退；网络类错误码
不进入白名单。4006（资源回收）是 Media3 可自恢复的状态，不加入。

**b. 首帧看门狗**（新增纯函数 + 播放器接线）：

```kotlin
internal const val FIRST_FRAME_FALLBACK_TIMEOUT_MS: Long = 6_000L
internal const val FIRST_FRAME_WATCHDOG_TICK_MS: Long = 500L

internal fun shouldFallbackForMissingFirstFrame(
    isTranscoding: Boolean,
    alreadyAttempted: Boolean,
    isLocalUri: Boolean,
    hasVideoTrack: Boolean,
    hasRenderedFirstFrame: Boolean,
    isPlaying: Boolean,
    playbackState: Int,
    unrenderedPlayingMs: Long,
): Boolean
```

判定：未转码、未回退、非本地、存在视频轨、从未渲染首帧、`isPlaying` 且
`STATE_READY`，并且累计「播放中无首帧」时长 ≥ 6s。缓冲中/暂停中不累计，
因此慢网络的首屏缓冲不会误判；`onRenderedFirstFrame` 一次后永久解除。

接线：

- `Player.Listener.onRenderedFirstFrame` → `hasRenderedFirstFrameState = true`；
- `LaunchedEffect(exoPlayer, streamUrl)` 每 500ms 读 `currentTracks` 是否有
  `C.TRACK_TYPE_VIDEO` 组；满足条件累计，达到阈值调用回退；
- 把 `onPlayerError` 里的回退动作抽成 `triggerTranscodeFallback(posMs, reason)`，
  错误路径与看门狗共用（`setMediaItem(HLS)` → `prepare` → `seekTo(posMs)` →
  `play` + Toast），并打一条 `Log.w("VideoPlayback", ...)` 便于真机核对。

### P3-2 Media3 1.2.0 → 1.11.1

`android/app/build.gradle.kts` 五个模块统一升至 1.11.1：

```kotlin
implementation("androidx.media3:media3-exoplayer:1.11.1")
implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
implementation("androidx.media3:media3-ui:1.11.1")
implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
implementation("androidx.media3:media3-session:1.11.1")
```

升级后先跑目标测试（`VideoPlaybackMediaItemTest` 等）与全量单测；若 1.11.1 的
Robolectric 兼容性出现问题（测试用的 SDK 34 android-all 缺新 API），优先升级
Robolectric 测试依赖，而不是降级 Media3。

### P3-3 音频 offload —— 撤销（见「背景与证据 §4」）

初版实现曾设置 `AUDIO_OFFLOAD_MODE_ENABLED`，真机与源码均证明对视频播放无效，
已从 `VideoPlayerScreen.kt` 移除，不保留死配置。

### P3-4 AFR 真机核对（无代码变更）

真机结果（Pixel 6，1080×2400，仅 60/90Hz 两种模式）：播放 60fps 样片时
`dumpsys SurfaceFlinger` 的 explicit frame rate 表出现
`SurfaceView[VideoPlayerActivity](BLAST) … 60.00 ExactOrMultiple OnlySeamless`，
证明 Media3 已按内容帧率发起无缝 AFR 请求；面板保持 90Hz（Pixel Smooth Display
策略，系统未做模式切换）。23.976fps 样片对 60/90 都不是整数倍，系统不切换属预期。
结论：AFR 由 Media3 默认策略承担，本期不改实现。

## 非目标

- 强制 `CHANGE_FRAME_RATE_ALWAYS` 或手写 `VideoFrameMetadataListener` 帧率控制。
- 服务端转码参数、HLS 分片策略、Web 播放器。已发现的 `-pix_fmt yuv420p`
  缺口（10-bit 源无法 NVENC 编码）留待单独授权处理。
- 「解码器交付损坏帧」的检测（4:4:4 黑屏/花屏）：需像素级校验，无法用首帧启发式解决。
- 手势、PiP、进度保存语义（Phase 2 已完成）。
- Media3 `1.12.0-alpha`（alpha 不采用）。
- 下载/离线播放链路改动。

## 验证

- `VideoPlayerFallbackTest` 扩展：4001/4002/4003 触发回退；5001/5002、
  4006、网络类不触发；转码中/已回退/本地文件守卫不变。
- 新增 `FirstFrameFallbackTest`（纯 JVM）：无视频轨/已渲染首帧/暂停/
  非 READY/本地/转码中均不触发；阈值前后边界；守卫优先级。
- `cd android && ./gradlew testDebugUnitTest assembleDebug`。
- 临时样片放在媒体库临时目录，验证后删除。

### 真机验证结果（2026-10-31，Pixel 6 / 无线 ADB / Android 1.5.6+13）

1. **H.264 High 10**：扩展白名单生效——`W VideoPlayback: transcode fallback
   (error=4003) at 0ms`（Phase 1 时无任何回退）。但服务端 HLS 转码 503
   `transcode session unavailable`：`buildHlsArgs` 未指定 `-pix_fmt yuv420p`，
   NVENC 对 10-bit 输入直接报 `10 bit encode not supported`（用同一 argv 手动
   ffmpeg 复现）。属**服务端**残余，本 Phase 不动（需单独授权）。
2. **H.264 High 4:4:4**：解码器交付了帧（画面破碎/黑、播放到 00:20/00:20、
   `onVideoSizeChanged` 报 1280×720），`onRenderedFirstFrame` 已触发，看门狗
   按设计不误判。该「输出损坏」场景超出首帧检测能力，记录为残余。
3. **ASF/WMV 改名 .mp4**：`error=3003` 回退 → HLS 播放画面正常（Phase 1 回归通过）。
4. **回归**：直连 MP4 正常渲染；双击 seek（+10s 钳制到片尾）；
   PiP 进入 `mode=pinned`；窗口 `fl=KEEP_SCREEN_ON`；退出后重开出现
   「上次看到 0:12」续播弹窗（进度保存正常）。
5. **音频 offload**：见 §4，`ENABLED` 对视频播放无效，已撤销。
6. **AFR**：见 P3-4，Media3 发起 `60.00 ExactOrMultiple OnlySeamless` 请求。

## 版本

按仓库约定递增：1.5.5 → 1.5.6，versionCode 12 → 13。

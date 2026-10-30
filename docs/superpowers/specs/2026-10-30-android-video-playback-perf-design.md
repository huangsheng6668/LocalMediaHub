# Android 视频播放性能设计（Phase 1：修复 + 低风险调优）

日期：2026-10-30
范围：Android 客户端（服务端 / Web 不动）
上游：视频链路审计（本次会话），Phase 2（手势/进度主线程）与 Phase 3（media3 升级）未授权，不在本设计内。

## 背景与证据

对 Android 视频链路的只读审计发现四个可直接落地的问题。

### 1. 转码回退路径不可用（缺陷）

`VideoPlayerScreen` 的 `shouldAutoFallbackToTranscode()` 在设备无法解码源文件时
切换到服务端 HLS 转码，但该路径当前必然失败：

- `android/app/build.gradle.kts` 只依赖 `media3-exoplayer` / `media3-ui` /
  `media3-datasource-okhttp` / `media3-session`，**没有 `media3-exoplayer-hls`**。
- `VideoPlayerScreen.kt` 用 `MediaItem.fromUri("/api/v1/media/hls/playlist?path=...")`
  构造媒体项；该 URL 无 `.m3u8` 后缀，media3 1.2.0 的
  `Util.inferContentType(Uri)` 按最后路径段扩展名推断为 `CONTENT_TYPE_OTHER`。

临时 Robolectric 探针（已删除）实测：

```
PLAIN_URL_SOURCE=androidx.media3.exoplayer.source.ProgressiveMediaSource
MIME_TYPED_RESULT=Failure(IllegalStateException: No suitable media source factory found for content type: 2)
```

即：无 MIME 时把 m3u8 当普通媒体解析（必然失败）；即使补
`MimeTypes.APPLICATION_M3U8`，缺少 HLS 模块也会在工厂处直接抛异常。

### 2. 播放期间不保持屏幕常亮（缺陷）

全仓只有阅读器设置 `FLAG_KEEP_SCREEN_ON`；`VideoPlayerActivity` / `VideoPlayerScreen` /
Manifest 均未设置，系统息屏超时后会走 `ON_STOP` → `exoPlayer.pause()`。

### 3. 解码器无兜底

`ExoPlayer.Builder(context)` 使用默认 `DefaultRenderersFactory`，
`enableDecoderFallback` 默认为 false：硬件解码器初始化失败即报错，不会退软解。

### 4. 缓冲策略未针对局域网调优

当前 `DefaultLoadControl`：min 5s / max 30s / start 250ms / after-rebuffer 300ms，
无 back buffer。局域网带宽充足，前向缓冲可以更大以吸收 Wi-Fi 抖动；没有 back buffer
时，倒退 10 秒也要重新发 Range 请求。

## 方案

### P1 HLS 转码回退修复

- `android/app/build.gradle.kts` 增加 `androidx.media3:media3-exoplayer-hls:1.2.0`
  （与其余 media3 模块版本对齐）。
- `VideoPlayerScreen.kt` 新增纯函数：

  ```kotlin
  internal fun buildPlaybackMediaItem(streamUrl: String): MediaItem {
      val isTranscode = streamUrl.contains("transcode=true")
      if (!isTranscode) return MediaItem.fromUri(streamUrl)
      return MediaItem.Builder()
          .setUri(buildStreamUrl(streamUrl, true))
          .setMimeType(MimeTypes.APPLICATION_M3U8)
          .build()
  }
  ```

- 初次建播放器（`ExoPlayer` remember 块）与 `onPlayerError` 回退分支都改用该函数，
  避免两处构造逻辑漂移。
- 顺带删除未使用的 `isTranscodingEnabled` 局部变量。

### P2 屏幕常亮

`PlayerView` 工厂里设置 `keepScreenOn = true`（视图随 Activity 分离后自动失效）。

### P3 解码器兜底

```kotlin
val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
ExoPlayer.Builder(context).setRenderersFactory(renderersFactory)
```

### P4 缓冲调优

`DefaultLoadControl.Builder`：

- `setBufferDurationsMs(10_000, 45_000, 250, 500)`
  —— 前向缓冲 30s → 45s，最小缓冲 5s → 10s，重缓冲起步阈值 300ms → 500ms，
  起播阈值保持 250ms（局域网首帧不被拖慢）。
- `setBackBuffer(15_000, /* retainFromKeyframe = */ true)`
  —— 倒退/双击回退 15s 内不重新拉流。
- `setPrioritizeTimeOverSizeThresholds(true)` 保持不变。

## 非目标

- 手势期间的整屏重组与音量/亮度 IPC 节流（Phase 2）。
- 进度保存门控与 DataStore 写入频率（Phase 2）。
- media3 1.2.0 → 最新 1.x 升级、AFR、音频 offload（Phase 3）。
- 转码会话的远端 seek 重锚（HLS 已转码区间外的拖动限制，功能议题，不在本期）。

## 验证

- 新增 `VideoPlaybackMediaItemTest`（Robolectric）：
  1. 直连 URL 的 `MediaItem` 不带 mime；
  2. 转码 URL 的 `MediaItem` mime 为 `APPLICATION_M3U8`；
  3. `DefaultMediaSourceFactory` 对转码 URL 产出 HLS 媒体源（守护 HLS 模块依赖）。
- 既有 `VideoPlayerStreamUrlTest` / `VideoPlayerFallbackTest` 保持通过。
- `cd android && ./gradlew testDebugUnitTest assembleDebug`。
- 真机验收（用户）：HEVC/AV1 等无法直连解码的视频应自动切到转码并播放；
  播放期间不熄屏。

## 版本

Android 版本号按约定递增：1.5.3 → 1.5.4，versionCode 10 → 11。

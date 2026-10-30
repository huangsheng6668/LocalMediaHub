# Android 视频播放 Phase 1 实施计划

对应设计：`docs/superpowers/specs/2026-10-30-android-video-playback-perf-design.md`

## 任务 1：写 HLS 媒体源回归测试（RED）

- 文件：`android/app/src/test/java/com/juziss/localmediahub/ui/screen/VideoPlaybackMediaItemTest.kt`（新建）
- 内容：Robolectric 测试 `hlsMimeItemProducesHlsMediaSource`——用
  `MediaItem.Builder().setUri(<hls url>).setMimeType(MimeTypes.APPLICATION_M3U8)` 构造，
  交给 `DefaultMediaSourceFactory(DefaultHttpDataSource.Factory())`，断言结果是 `HlsMediaSource`。
- 验证：`cd android && ./gradlew testDebugUnitTest --tests "...VideoPlaybackMediaItemTest"`，
  预期失败：`IllegalStateException: No suitable media source factory found for content type: 2`
  （缺 `media3-exoplayer-hls`）。

## 任务 2：补 HLS 依赖（GREEN）

- 文件：`android/app/build.gradle.kts`
- 在 Video player 依赖块加：`implementation("androidx.media3:media3-exoplayer-hls:1.2.0")`
- 验证：任务 1 的测试通过。

## 任务 3：转码 URL 带 HLS MIME（RED → GREEN）

- 文件：`android/app/src/test/java/com/juziss/localmediahub/ui/screen/VideoPlaybackMediaItemTest.kt`
- 新增测试：
  - `transcodeUrlGetsHlsMime`：`buildPlaybackMediaItem(direct, transcode = true)` 的 uri 等于
    `/hls/playlist` 版本且 `mimeType == MimeTypes.APPLICATION_M3U8`；
  - `directUrlKeepsNoMime`：直连 URL 的 `mimeType` 为 null、uri 原样。
- 先运行确认失败（`buildPlaybackMediaItem` 未定义）。
- 文件：`android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
- 新增：

  ```kotlin
  internal fun buildPlaybackMediaItem(
      streamUrl: String,
      transcode: Boolean = streamUrl.contains("transcode=true"),
  ): MediaItem {
      if (!transcode) return MediaItem.fromUri(streamUrl)
      return MediaItem.Builder()
          .setUri(buildStreamUrl(streamUrl, true))
          .setMimeType(MimeTypes.APPLICATION_M3U8)
          .build()
  }
  ```

- 初次建播放器：`val mediaItem = buildPlaybackMediaItem(streamUrl)`，删掉旧的
  `finalUrl` 分支。
- `onPlayerError` 回退：`exoPlayer.setMediaItem(buildPlaybackMediaItem(streamUrl, transcode = true))`。
- 删除未使用的 `var isTranscodingEnabled by transcodeEnabledState`。
- 新增 import：`androidx.media3.common.MimeTypes`、`androidx.media3.exoplayer.DefaultRenderersFactory`。
- 验证：两个新测试通过。

## 任务 4：屏幕常亮

- 文件：`VideoPlayerScreen.kt`（`AndroidView` 的 `factory` 内）
- `PlayerView(ctx).apply { ... keepScreenOn = true }`
- 验证：`assembleDebug` 通过；真机播放中不熄屏。

## 任务 5：解码器兜底

- 文件：`VideoPlayerScreen.kt`（`ExoPlayer.Builder` 处）

  ```kotlin
  val renderersFactory = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
  ExoPlayer.Builder(context)
      .setRenderersFactory(renderersFactory)
      // .setLoadControl(...) 等保持不变
  ```

- 验证：`assembleDebug` 通过。

## 任务 6：缓冲调优

- 文件：`VideoPlayerScreen.kt`（`loadControl` 构建处）
- 改为：

  ```kotlin
  val loadControl = DefaultLoadControl.Builder()
      .setBufferDurationsMs(10_000, 45_000, 250, 500)
      .setBackBuffer(15_000, /* retainFromKeyframe = */ true)
      .setPrioritizeTimeOverSizeThresholds(true)
      .build()
  ```

- 验证：`assembleDebug` 通过；真机拖动/回退无明显重新缓冲。

## 任务 7：版本号

- 文件：`android/app/build.gradle.kts`
- `versionCode 10 → 11`，`versionName "1.5.3" → "1.5.4"`。

## 任务 8：全量验证

- `cd android && ./gradlew testDebugUnitTest assembleDebug`
- 期望：全部单测通过，Debug 构建成功。

## 任务 9：提交

- 按仓库约定：`fix(android): restore HLS transcode fallback and tune video playback (Phase 1)`
- 只提交本 Phase 相关文件（含 spec/plan）。

# Android 视频播放 Phase 3 实施计划

对应设计：`docs/superpowers/specs/2026-10-31-android-video-playback-perf-phase3-design.md`

顺序说明：先做低风险的静默失败兜底（TDD），再做依赖升级，最后是真机验证
（offload 任务经源码与真机证据撤销，见任务 6）。

## 任务 1：扩展错误码白名单测试（RED）

- 文件：`android/app/src/test/java/com/juziss/localmediahub/ui/screen/VideoPlayerFallbackTest.kt`
- 新增测试：
  - `decoderInitFailureTriggersFallback`：4001 → true；
  - `decoderQueryFailureTriggersFallback`：4002 → true；
  - `decodingFailureTriggersFallback`：4003 → true；
  - `audioTrackAndResourceErrorsDoNotRetry`：5001 / 5002 / 4006 → false；
  - 既有守卫（转码中 / 已回退 / 本地）对新错误码同样生效（补一个循环断言）。
- 验证：`cd android && ./gradlew testDebugUnitTest --tests "*VideoPlayerFallbackTest"`，
  预期三个新用例失败。

## 任务 2：扩展白名单（GREEN）

- 文件：`android/app/src/main/java/com/juziss/localmediahub/ui/screen/VideoPlayerScreen.kt`
- `shouldAutoFallbackToTranscode` 的 `when` 增加：

  ```kotlin
  PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
  PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
  PlaybackException.ERROR_CODE_DECODING_FAILED,
  ```

- 验证：任务 1 测试全部通过。

## 任务 3：首帧看门狗策略测试（RED）

- 文件：`android/app/src/test/java/com/juziss/localmediahub/ui/screen/FirstFrameFallbackTest.kt`（新建）
- 内容（纯 JVM，不依赖 Robolectric）：

  ```kotlin
  private fun shouldFallback(
      isTranscoding: Boolean = false,
      alreadyAttempted: Boolean = false,
      isLocalUri: Boolean = false,
      hasVideoTrack: Boolean = true,
      hasRenderedFirstFrame: Boolean = false,
      isPlaying: Boolean = true,
      playbackState: Int = Player.STATE_READY,
      unrenderedPlayingMs: Long = FIRST_FRAME_FALLBACK_TIMEOUT_MS,
  ) = shouldFallbackForMissingFirstFrame(
      isTranscoding, alreadyAttempted, isLocalUri, hasVideoTrack,
      hasRenderedFirstFrame, isPlaying, playbackState, unrenderedPlayingMs,
  )
  ```

  用例：达到阈值触发；低于阈值不触发；已渲染首帧不触发；无视频轨不触发；
  暂停不触发；非 READY 不触发；本地文件不触发；转码中/已回退不触发；
  守卫优先于阈值。
- 验证：运行后失败（函数未定义）。

## 任务 4：看门狗策略与接线（GREEN）

- 文件：`VideoPlayerScreen.kt`

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
  ): Boolean {
      if (isTranscoding || alreadyAttempted || isLocalUri) return false
      if (!hasVideoTrack || hasRenderedFirstFrame) return false
      if (!isPlaying || playbackState != Player.STATE_READY) return false
      return unrenderedPlayingMs >= FIRST_FRAME_FALLBACK_TIMEOUT_MS
  }
  ```

- 在 `exoPlayer` 之后新增：

  ```kotlin
  val hasRenderedFirstFrameState = remember(streamUrl) { mutableStateOf(false) }

  val triggerTranscodeFallback: (Long, String) -> Unit = { posMs, reason ->
      val at = posMs.coerceAtLeast(0L)
      autoFallbackAttemptedState.value = true
      positionHolder.value = at
      transcodeEnabledState.value = true
      android.util.Log.w("VideoPlayback", "transcode fallback ($reason) at ${at}ms")
      exoPlayer.setMediaItem(buildPlaybackMediaItem(streamUrl, transcode = true))
      exoPlayer.prepare()
      exoPlayer.seekTo(at)
      exoPlayer.play()
      android.widget.Toast.makeText(
          context,
          context.getString(R.string.video_fallback_transcode),
          android.widget.Toast.LENGTH_LONG,
      ).show()
  }
  ```

- `onPlayerError` 改为：

  ```kotlin
  override fun onPlayerError(error: PlaybackException) {
      if (!shouldAutoFallbackToTranscode(...)) return
      triggerTranscodeFallback(
          exoPlayer.currentPosition,
          "error=${error.errorCode}",
      )
  }
  ```

  并新增：

  ```kotlin
  override fun onRenderedFirstFrame() {
      hasRenderedFirstFrameState.value = true
  }
  ```

- 在进度循环之后新增看门狗：

  ```kotlin
  LaunchedEffect(exoPlayer, streamUrl) {
      var unrenderedPlayingMs = 0L
      while (true) {
          delay(FIRST_FRAME_WATCHDOG_TICK_MS)
          val hasVideoTrack =
              exoPlayer.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO }
          val counting = exoPlayer.isPlaying &&
              exoPlayer.playbackState == Player.STATE_READY &&
              hasVideoTrack && !hasRenderedFirstFrameState.value
          unrenderedPlayingMs = if (counting) {
              unrenderedPlayingMs + FIRST_FRAME_WATCHDOG_TICK_MS
          } else {
              0L
          }
          if (shouldFallbackForMissingFirstFrame(
                  isTranscoding = transcodeEnabledState.value,
                  alreadyAttempted = autoFallbackAttemptedState.value,
                  isLocalUri = isLocalStreamUri(streamUrl),
                  hasVideoTrack = hasVideoTrack,
                  hasRenderedFirstFrame = hasRenderedFirstFrameState.value,
                  isPlaying = exoPlayer.isPlaying,
                  playbackState = exoPlayer.playbackState,
                  unrenderedPlayingMs = unrenderedPlayingMs,
              )
          ) {
              triggerTranscodeFallback(exoPlayer.currentPosition, "no-first-frame")
              break
          }
      }
  }
  ```

- 新增 import：`androidx.media3.common.C`。
- 验证：任务 3 测试通过；`assembleDebug` 通过。

## 任务 5：Media3 升级到 1.11.1

- 文件：`android/app/build.gradle.kts`
- 五个模块统一 `1.2.0 → 1.11.1`（exoplayer / exoplayer-hls / ui /
  datasource-okhttp / session），更新依赖块注释说明升级原因与验证范围。
- 验证：
  1. `./gradlew testDebugUnitTest --tests "*Video*" --tests "*FirstFrame*"`；
  2. 全量 `./gradlew testDebugUnitTest`。
- 预期无需改业务代码。若 Robolectric 4.13 与 1.11.1 不兼容（缺 SDK 34 之外的
  API），升级测试侧 `org.robolectric:robolectric` 到支持版本。

## 任务 6：音频 offload —— 已撤销

- 初版按计划设置 `AUDIO_OFFLOAD_MODE_ENABLED`。
- 复核 1.11.1 源码 `DefaultTrackSelector.maybeConfigureRendererForOffload()`：
  只要存在任何已选中的非音频渲染器轨道就跳过 offload 配置，即 offload 只对
  纯音频播放生效。本仓视频轨恒在，配置为 no-op。
- 真机 `dumpsys media.audio_flinger` 也只见 PCM 16-bit MIXER 轨道，无
  compressed-offload 线程。因此从 `VideoPlayerScreen.kt` 移除该设置与其
  `TrackSelectionParameters` import，不保留死配置。

## 任务 7：版本号

- 文件：`android/app/build.gradle.kts`
- `versionCode 12 → 13`，`versionName "1.5.5" → "1.5.6"`。

## 任务 8：全量验证

- `cd android && ./gradlew testDebugUnitTest assembleDebug`
- 期望：全部单测通过，Debug 构建成功。

## 任务 9：真机验证（Pixel 6）

- 安装 debug APK；`adb connect 192.168.31.64:39291`（`export MSYS_NO_PATHCONV=1`）。
- 准备临时样片（验证后删除）：
  - H.264 High 10 MP4；
  - H.264 High 4:4:4 MP4；
  - ASF/WMV 内容改名 `.mp4`（容器错误 3003 回归）；
  - 23.976fps H.264 MP4。
- 检查项与实际结果（2026-10-31）：
  1. High 10 → 回退已触发：`transcode fallback (error=4003)`。但服务端 NVENC
     不支持 10-bit 输入（`10 bit encode not supported`，缺少 `-pix_fmt
     yuv420p`），HLS 会话 503；属服务端残余，需单独授权。
  2. 4:4:4 → 解码器交付损坏帧（有 videoSize、播到片尾），`onRenderedFirstFrame`
     已触发，看门狗按设计不误判；该场景超出首帧检测能力，记录为残余。
  3. 容器错误回退（Phase 1 回归）→ `error=3003` 回退后 HLS 画面正常。
  4. 直连 MP4 / PiP / 双击 seek / 进度续播 / 常亮回归全部通过。
  5. 音频 offload → 未生效，已按任务 6 撤销。
  6. AFR → SurfaceFlinger 显示 `60.00 ExactOrMultiple OnlySeamless` 请求。
- 清理样片与后台 server 进程（已完成）。

## 任务 10：提交

- 用户确认后提交：`perf(android): upgrade Media3 and cover silent decode failures (Phase 3)`
- 仅提交本 Phase 文件（spec / plan / VideoPlayerScreen.kt / 测试 / build.gradle.kts）。

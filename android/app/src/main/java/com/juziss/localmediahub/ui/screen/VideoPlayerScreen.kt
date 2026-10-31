package com.juziss.localmediahub.ui.screen

import com.juziss.localmediahub.ui.component.SeekState
import com.juziss.localmediahub.ui.component.GestureIndicator
import com.juziss.localmediahub.ui.component.rememberPlayerGestureListener

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.juziss.localmediahub.R
import com.juziss.localmediahub.VideoPlayerActivity
import com.juziss.localmediahub.pip.PipControllerStore
import com.juziss.localmediahub.viewmodel.VideoPlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs



import androidx.compose.ui.res.vectorResource
import com.juziss.localmediahub.pip.PipController
import com.juziss.localmediahub.ui.component.DoubleTapSeekRippleOverlay
import com.juziss.localmediahub.ui.component.VolumeBrightnessPillHud

/**
 * Builds a stream URL for the given base and transcode flag.
 *
 * HLS era (spec 2026-09-03-hls-transcode): transcoded playback targets the
 * server's segmented HLS playlist — ExoPlayer's native HLS support gives
 * random seeking for free, so there is no start param anymore; position
 * restore and seeking both go through plain seekTo. Direct (copy) mode is
 * unchanged: http.ServeContent handles Range natively.
 *
 * Any stale transcode/start query params on [baseUrl] are stripped first;
 * the /stream path suffix is then swapped for /hls/playlist.
 */
internal fun buildStreamUrl(baseUrl: String, transcode: Boolean): String {
    var clean = baseUrl
        .replace(Regex("[?&]transcode=true"), "")
        .replace(Regex("[?&]start=[^&]*"), "")
    clean = clean.replace("?&", "?").removeSuffix("?").removeSuffix("&")

    if (!transcode) return clean
    return clean.replace(Regex("/stream(\\?|$)"), "/hls/playlist$1")
}

/**
 * Builds the ExoPlayer MediaItem for [streamUrl]. Transcode URLs are
 * rewritten to the server's HLS playlist and tagged with the HLS mime
 * type: the playlist path has no `.m3u8` suffix, so without the explicit
 * mime `DefaultMediaSourceFactory` would infer CONTENT_TYPE_OTHER and use
 * ProgressiveMediaSource on an m3u8 payload.
 */
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

/**
 * Whether [url] points at a local file rather than a server stream. Local
 * files have no server to transcode them, so codec errors there have no
 * fallback path. (Extracted from the inline check in the player builder.)
 */
internal fun isLocalStreamUri(url: String): Boolean {
    return url.startsWith("file://") ||
        url.startsWith("content://") ||
        url.startsWith("android.resource://")
}

/**
 * Decides whether a playback error should trigger an automatic ONE-SHOT
 * retry with server-side transcode (spec 2026-09-03-android-transcode-
 * fallback, Phase C). Only decoder/container-level failures qualify: the
 * device lacking a decoder, the decoder failing to initialize, or the
 * container being unsupported is exactly what transcoding to H.264/AAC
 * fixes. Network/IO/remote errors do not — transcoding cannot help those,
 * and retrying would just mask them.
 *
 * Decoder init/query/decoding failures (4001/4002/4003) are included
 * because `DefaultRenderersFactory.setEnableDecoderFallback` has already
 * exhausted the software decoders by the time they surface, and the real-
 * device H.264 High 10 sample failed with 4001 while not matching the
 * 4004/4005 capability codes.
 *
 * Guards: already-transcoding streams never retry (a failing transcode
 * means the problem is not the codec); the retry happens at most once
 * per video (loop prevention); local files never retry.
 */
internal fun shouldAutoFallbackToTranscode(
    isTranscoding: Boolean,
    alreadyAttempted: Boolean,
    isLocalUri: Boolean,
    errorCode: Int,
): Boolean {
    if (isTranscoding || alreadyAttempted || isLocalUri) return false
    return when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        -> true
        else -> false
    }
}

/**
 * A video that plays this long without rendering a single frame is treated
 * as a silent decode failure (decoder reports support but outputs nothing).
 * The first frame normally arrives well under a second on LAN; 6s leaves
 * room for slow seeks without making the user stare at a black screen.
 */
internal const val FIRST_FRAME_FALLBACK_TIMEOUT_MS: Long = 6_000L

internal const val FIRST_FRAME_WATCHDOG_TICK_MS: Long = 500L

/**
 * Whether the first-frame watchdog should trigger the one-shot transcode
 * fallback. Only counts time while actually playing a ready stream that
 * has a video track; buffering, pausing or a rendered frame reset the
 * accumulated time, so slow starts never false-positive.
 */
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

@Composable
fun VideoPlayerScreen(
    streamUrl: String,
    initialPositionMs: Long = 0L,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit = { _, _ -> },
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? VideoPlayerActivity
    // Read PiP mode defensively: the screen might be hosted by a non-MainActivity
    // context in tests — fall back to a MutableStateFlow(false) then.
    val fallbackPipState = remember { MutableStateFlow(false) }
    val isInPipMode by (activity?.isInPipMode ?: fallbackPipState)
        .collectAsState()
    // Captured from ExoPlayer.onVideoSizeChanged so enterPipMode can build the
    // correct aspect-ratio params. Defaults to 0 (PipController falls back to
    // 16:9 when width/height are not positive).
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    // Composables can't receive Hilt constructor injection directly; this
    // ViewModel is the injection seam for the shared singleton OkHttpClient
    // (Round 17 C3 — replaces the per-screen OkHttpClient.Builder()).
    val videoPlayerViewModel: VideoPlayerViewModel = hiltViewModel()
    val pausedText = stringResource(R.string.video_paused)
    val playingText = stringResource(R.string.video_playing)

    // Tracks the current playback position across configuration changes
    // (rotation / process death) so a rebuilt ExoPlayer can seek back.
    // Round 20: key on streamUrl so switching videos resets position.
    // The holder is plain state on purpose — see PlaybackProgressPolicy.kt.
    val positionHolder = rememberSaveable(streamUrl, saver = PlaybackPositionHolderSaver) {
        PlaybackPositionHolder(initialPositionMs)
    }

    // Last position written to the store; gates the periodic persistence.
    val lastPersistedPositionMs = remember(streamUrl) { AtomicLong(initialPositionMs) }

    // Wrap the caller's onProgress so this screen also tracks the position
    // for process-death survival. The periodic timer, the pause flush and
    // the dispose-time flush all go through this.
    val wrappedOnProgress: (Long, Long) -> Unit = { positionMs, durationMs ->
        positionHolder.value = positionMs
        onProgress(positionMs, durationMs)
    }

    val exoPlayer = remember(streamUrl) {
        val loadControl = DefaultLoadControl.Builder()
            // LAN playback: buffer further ahead to ride out Wi-Fi jitter,
            // and keep 15s of back buffer so rewinds within that window
            // replay from memory instead of re-requesting ranges.
            .setBufferDurationsMs(
                10000,  // minBufferMs
                45000,  // maxBufferMs
                250,    // bufferForPlaybackMs — start playing instantly after seek.
                500,    // bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(15000, /* retainBackBufferFromKeyframe = */ true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val okClient = videoPlayerViewModel.provideHttpClient().newBuilder()
            .cache(null) // Disable cache to prevent locking and disk thrashing on video streaming range requests
            // Video playback is latency-tolerant but stall-intolerant: the
            // shared client's 30s read timeout kills a stream during long
            // rebuffers (AP switch, server hiccup) before ExoPlayer's own
            // stall handling can react. Disable the read deadline here and
            // let ExoPlayer own load-error policy (it cancels the call on
            // stall timeout), keeping connectTimeout as-is.
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        // 离线下载的视频以 file://（本地文件）地址进入 —— OkHttpDataSource 只
        // 支持 http/https，遇到 file:// 会报 unsupported scheme 直接无法播放。
        // 本地 URI 改用 DefaultDataSource（原生支持 file:// / content://），
        // 远端流保持 OkHttp 工厂（Bearer token 注入 + 超时调优）。
        val isLocalUri = isLocalStreamUri(streamUrl)
        val dataSourceFactory: androidx.media3.datasource.DataSource.Factory = if (isLocalUri) {
            androidx.media3.datasource.DefaultDataSource.Factory(context)
        } else {
            androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
                .setUserAgent("LocalMediaHub")
        }

        val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        // HLS era: a transcode=true URL is normalized to the HLS playlist
        // form; both direct and HLS streams restore position via plain
        // seekTo below (HLS supports random access natively).
        // Decoder fallback: when the hardware decoder cannot be initialized
        // for a track, let ExoPlayer retry with the next (software) decoder
        // instead of failing playback outright.
        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSource)
            .build().apply {
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
                setMediaItem(buildPlaybackMediaItem(streamUrl))
                // Sync seek before prepare — valid for direct streams AND
                // HLS playlists (ExoPlayer clamps to the seekable range
                // once the manifest loads).
                if (positionHolder.value > 0L) {
                    seekTo(positionHolder.value)
                }
                // Hand off audio focus to ExoPlayer so playback pauses other
                // media apps and resumes when focus returns (Task 5 Step 2).
                val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
                    .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                    .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
                prepare()
                playWhenReady = true
            }
    }

    val mediaSession = remember(exoPlayer) {
        androidx.media3.session.MediaSession.Builder(context, exoPlayer).build()
    }
    DisposableEffect(mediaSession) {
        onDispose {
            mediaSession.release()
        }
    }

    // ---- PiP Action Listener ----
    DisposableEffect(activity, exoPlayer) {
        activity?.onPipActionReceived = { action ->
            when (action) {
                PipController.ACTION_PIP_PLAY_PAUSE -> {
                    if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                }
                PipController.ACTION_PIP_REWIND -> {
                    val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                    exoPlayer.seekTo(newPos)
                }
                PipController.ACTION_PIP_FORWARD -> {
                    val duration = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
                    val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(duration)
                    exoPlayer.seekTo(newPos)
                }
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && activity != null) {
                activity.pipController.updatePipParams(activity, videoWidth, videoHeight, exoPlayer.isPlaying)
            }
        }
        onDispose {
            activity?.onPipActionReceived = null
        }
    }

    // ---- Buffering indicator state (G2) ----
    // Declared right after exoPlayer remember and before the
    // DisposableEffect(exoPlayer) that captures it. Using explicit MutableState
    // (not `by` delegate) so the anonymous Player.Listener inner class can
    // write `.value` — Kotlin forbids writing delegated vars from inner classes.
    val isBufferingState = remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    // Read the live PiP state at callback time rather than the
                    // compose-captured value: entering PiP delivers ON_PAUSE and the
                    // StateFlow is updated by onPictureInPictureModeChanged before
                    // the lifecycle callback fires. Falling back to false keeps the
                    // screen testable in non-MainActivity hosts.
                    val inPip = activity?.isInPipMode?.value ?: false
                    // PiP 模式下进入后台：不要暂停，让浮窗继续播放。
                    // 非 PiP 模式（普通切后台）：正常暂停。
                    if (!inPip) {
                        exoPlayer.pause()
                    }
                }
                Lifecycle.Event.ON_STOP -> {
                    // 无论是否在 PiP 模式，一旦 Activity 处于不可见状态 (onStop)，
                    // 说明浮窗已被用户关闭，或者屏幕被锁屏/应用彻底切后台。
                    // 此时必须暂停播放器，以防后台音频泄漏。
                    exoPlayer.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(exoPlayer) {
        // 让进程级 PipControllerStore 能拿到 ExoPlayer 实例
        PipControllerStore.bind(exoPlayer)
        onDispose {
            // Guarded: savePlaybackProgress treats an unknown duration or a
            // zero position as "no progress" and DELETES the stored entry.
            // Flushing before the player is ready (or before the resume seek
            // is applied) would wipe the resume point.
            val duration = exoPlayer.duration
            val position = exoPlayer.currentPosition
            if (duration > 0L && position > 0L) {
                wrappedOnProgress(position, duration)
            }
            exoPlayer.release()
            PipControllerStore.unbind()
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Whether the current stream is transcoded. Explicit MutableState (no
    // delegate) because the Player.Listener inner class below writes it when
    // the auto transcode fallback fires — same convention as
    // isBufferingState above.
    val transcodeEnabledState = remember { mutableStateOf(streamUrl.contains("transcode=true")) }

    // Phase C (spec 2026-09-03-android-transcode-fallback): one-shot flag,
    // keyed on streamUrl so switching videos resets it. Explicit state for
    // the same inner-class-write reason.
    val autoFallbackAttemptedState = remember(streamUrl) { mutableStateOf(false) }

    // Phase 3 (spec 2026-10-31-...-phase3): silent decode failures never
    // raise onPlayerError, so watch for a missing first frame instead.
    val hasRenderedFirstFrameState = remember(streamUrl) { mutableStateOf(false) }

    // Shared by the error path and the first-frame watchdog so the swap
    // logic cannot drift between them.
    val triggerTranscodeFallback: (Long, String) -> Unit = { posMs, reason ->
        val at = posMs.coerceAtLeast(0L)
        autoFallbackAttemptedState.value = true
        positionHolder.value = at
        transcodeEnabledState.value = true
        android.util.Log.w("VideoPlayback", "transcode fallback ($reason) at ${at}ms")
        // HLS playlist URL (native random seeking) — resume right at the
        // failure position.
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

    // Auto-rotate based on video aspect ratio
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBufferingState.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    if (exoPlayer.duration > 0L) {
                        wrappedOnProgress(exoPlayer.duration, exoPlayer.duration)
                    }
                    // Spec §4.3: 视频在 PiP 中自然结束 → 退出 PiP 回全屏结束画面
                    // Android 没有直接 exitPictureInPictureMode() API；通过启动自己 + REORDER_TO_FRONT
                    // 把 Activity 拉回前台，触发 onPictureInPictureModeChanged(false)。
                    val act = context as? VideoPlayerActivity
                    if (act != null && act.isInPipMode.value) {
                        val bringToFront = Intent(act, VideoPlayerActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        }
                        act.startActivity(bringToFront)
                    }
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                videoWidth = videoSize.width
                videoHeight = videoSize.height
                if (videoSize.width > 0 && videoSize.height > 0) {
                    val activity = context as? Activity ?: return
                    activity.requestedOrientation = if (videoSize.width >= videoSize.height) {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                    }
                }
            }

            // Round 20: update the in-memory position immediately on any
            // user-initiated seek (scrubber drag, gesture). Without this, a
            // rotation within one save tick of a seek would lose the new
            // position. The store write stays gated (see the progress loop).
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                positionHolder.value = exoPlayer.currentPosition
            }

            // Pausing is the moment a user most often leaves the player, so
            // persist the exact stop point instead of waiting for the next
            // tick. STATE_ENDED already persisted the duration above.
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) return
                if (exoPlayer.playbackState == Player.STATE_ENDED) return
                val duration = exoPlayer.duration
                if (duration <= 0L) return
                val position = exoPlayer.currentPosition
                if (position <= 0L) return
                lastPersistedPositionMs.set(position)
                wrappedOnProgress(position, duration)
            }

            // Phase C (spec 2026-09-03-android-transcode-fallback): when the
            // device cannot decode the source (codec missing / exceeds
            // capabilities / unsupported container), retry once with
            // server-side transcode instead of dead-ending on a black
            // screen. Mirrors the in-place media-swap pattern the gesture
            // seek path uses; the URL rebuild strips any stale params, so
            // later gesture seeks and the restart chip keep transcode=true.
            override fun onPlayerError(error: PlaybackException) {
                if (!shouldAutoFallbackToTranscode(
                        isTranscoding = transcodeEnabledState.value,
                        alreadyAttempted = autoFallbackAttemptedState.value,
                        isLocalUri = isLocalStreamUri(streamUrl),
                        errorCode = error.errorCode,
                    )
                ) {
                    return
                }
                triggerTranscodeFallback(
                    exoPlayer.currentPosition,
                    "error=${error.errorCode}",
                )
            }

            // Disarms the first-frame watchdog for the current media item.
            override fun onRenderedFirstFrame() {
                hasRenderedFirstFrameState.value = true
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Refresh the in-memory holder every tick (cheap, unobservable) while
    // writing the store only when the position actually moved — writing on a
    // fixed 5 s cadence bumped `updatedAt` even for a paused player.
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

    // Phase 3 (spec 2026-10-31-...-phase3): some decoders report support but
    // never output a frame (real-device H.264 High 4:4:4), leaving ExoPlayer
    // READY/PLAYING on a black picture without ever calling onPlayerError.
    // Count only playing-ready time so buffering or a rendered frame resets
    // the window and slow starts cannot false-positive.
    LaunchedEffect(exoPlayer, streamUrl) {
        var unrenderedPlayingMs = 0L
        while (true) {
            delay(FIRST_FRAME_WATCHDOG_TICK_MS)
            // Once a frame is on screen the watchdog has nothing left to
            // watch — stop instead of ticking for the rest of the movie.
            if (hasRenderedFirstFrameState.value) break
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

    // Handle system back button (disabled in PiP so the system back action
    // exits PiP / navigates outside the app rather than popping the screen).
    if (!isInPipMode) {
        BackHandler(onBack = onBack)
    }

    // ---- Gesture overlay state ----
    // Plain @Stable holder: the touch listener writes these dozens of times
    // per drag, and every overlay reads its own slice in PlayerOverlays below.
    // Reading any of it in this body would recompose the whole player screen
    // (AndroidView update included) once per touch move.
    val overlay = remember {
        PlayerOverlayState(initialShowRestartChip = initialPositionMs > 0L)
    }

    val gestureListener = rememberPlayerGestureListener(
        exoPlayer = exoPlayer,
        pausedText = pausedText,
        playingText = playingText,
        onSeekStateChange = { overlay.seekState = it },
        onBrightnessIndicatorChange = { overlay.brightnessIndicator = it },
        onVolumeIndicatorChange = { overlay.volumeIndicator = it },
        onPlayPauseIndicatorChange = { overlay.playPauseIndicator = it },
        onDoubleTapSeek = { isForward, seconds ->
            if (isForward) {
                val duration = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
                val target = (exoPlayer.currentPosition + seconds * 1000L).coerceAtMost(duration)
                exoPlayer.seekTo(target)
                overlay.forwardSeekSeconds =
                    if (overlay.isForwardRippleVisible) overlay.forwardSeekSeconds + seconds else seconds
                overlay.isForwardRippleVisible = true
            } else {
                val target = (exoPlayer.currentPosition - seconds * 1000L).coerceAtLeast(0L)
                exoPlayer.seekTo(target)
                overlay.rewindSeekSeconds =
                    if (overlay.isRewindRippleVisible) overlay.rewindSeekSeconds + seconds else seconds
                overlay.isRewindRippleVisible = true
            }
        }
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ---- Video player with gesture detection via OnTouchListener ----
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    // Keep the display awake for the whole player session;
                    // the flag dies with view detachment, so backgrounding
                    // still lets the system sleep.
                    keepScreenOn = true
                    useController = !isInPipMode
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    if (!isInPipMode) setOnTouchListener(gestureListener)
                }
            },
            update = { view ->
                if (view.player != exoPlayer) {
                    view.player = exoPlayer
                }
                // Toggle controls + gesture layer when crossing the PiP boundary.
                view.useController = !isInPipMode
                if (isInPipMode) view.setOnTouchListener(null) else view.setOnTouchListener(gestureListener)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- Gesture indicator overlays (non-interactive, pass-through) ----
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

            // PiP 按钮（只在非 PiP 全屏模式下显示）
            IconButton(
                onClick = {
                    val act = activity ?: return@IconButton
                    val ok = act.enterPipMode(videoWidth, videoHeight, exoPlayer.isPlaying)
                    if (!ok) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.pip_unsupported),
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 8.dp, end = 12.dp),
            ) {
                Icon(
                    painter = painterResource(
                        android.R.drawable.ic_menu_crop  // 系统自带 "缩放/裁剪" 图标，近似悬浮窗概念
                    ),
                    contentDescription = stringResource(R.string.pip_button),
                    tint = Color.White,
                )
            }

            // Back button (只在非 PiP 模式下显示)
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 12.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                )
            }
        }
    }
}

private fun formatSeekOffset(offsetMs: Long, minUnit: String, secUnit: String): String {
    val seconds = abs(offsetMs) / 1000
    val sign = if (offsetMs >= 0) "+" else "-"
    return if (seconds >= 60) {
        val min = seconds / 60
        val sec = seconds % 60
        "$sign${min}$minUnit${sec}$secUnit"
    } else {
        "$sign${seconds}$secUnit"
    }
}

/**
 * Gesture overlay state, written by the touch listener dozens of times per
 * drag. Kept as a plain holder whose fields are snapshot states so that only
 * the overlay that reads a given field recomposes — never the player screen
 * (and therefore never the PlayerView `AndroidView`).
 */
@Stable
private class PlayerOverlayState(initialShowRestartChip: Boolean = false) {
    var seekState by mutableStateOf(SeekState())
    var playPauseIndicator by mutableStateOf(GestureIndicator())
    var brightnessIndicator by mutableStateOf(GestureIndicator())
    var volumeIndicator by mutableStateOf(GestureIndicator())

    var rewindSeekSeconds by mutableIntStateOf(0)
    var isRewindRippleVisible by mutableStateOf(false)
    var forwardSeekSeconds by mutableIntStateOf(0)
    var isForwardRippleVisible by mutableStateOf(false)

    // Round 22: "restart from beginning" chip, shown for 3s after resuming
    // from a non-zero position.
    var showRestartChip by mutableStateOf(initialShowRestartChip)
}

/**
 * Non-interactive gesture chrome. Each leaf reads only its own slice of
 * [PlayerOverlayState]; the container itself reads none, so a seek drag
 * recomposes [SeekIndicator] alone and a volume drag only
 * [VolumeBrightnessPill].
 */
@Composable
private fun BoxScope.PlayerOverlays(
    exoPlayer: Player,
    overlay: PlayerOverlayState,
    isBuffering: State<Boolean>,
    onRestart: () -> Unit,
) {
    SeekIndicator(exoPlayer, overlay)
    PlayPauseIndicator(overlay)
    VolumeBrightnessPill(overlay)
    DoubleTapRipples(overlay)
    RestartChip(overlay, onRestart)
    BufferingIndicator(isBuffering)
}

@Composable
private fun BoxScope.SeekIndicator(exoPlayer: Player, overlay: PlayerOverlayState) {
    val seekState = overlay.seekState

    // Apply seek on gesture end
    LaunchedEffect(seekState.isSeeking) {
        if (!seekState.isSeeking && seekState.offsetMs != 0L) {
            // Use the base position recorded at gesture start — NOT the
            // continuously-advancing currentPosition — so the final seek
            // target matches what the user saw in the overlay indicator.
            val basePos = seekState.basePositionMs
            val maxPos = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
            val newPos = (basePos + seekState.offsetMs).coerceIn(0L, maxPos)
            // HLS era: both direct and transcoded (HLS) streams seek
            // natively — the URL-rebuild workaround is gone.
            exoPlayer.seekTo(newPos)
            overlay.seekState = SeekState()
        }
    }

    AnimatedVisibility(
        visible = seekState.isSeeking,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Icon(
                painter = painterResource(if (seekState.offsetMs >= 0) R.drawable.ic_fast_forward else R.drawable.ic_fast_rewind),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = formatSeekOffset(
                    seekState.offsetMs,
                    stringResource(R.string.video_seek_min),
                    stringResource(R.string.video_seek_sec),
                ),
                color = Color.White,
                fontSize = 16.sp
            )
        }
    }
}

@Composable
private fun BoxScope.PlayPauseIndicator(overlay: PlayerOverlayState) {
    val indicator = overlay.playPauseIndicator

    LaunchedEffect(indicator.visible) {
        if (indicator.visible) {
            delay(800)
            overlay.playPauseIndicator = overlay.playPauseIndicator.copy(visible = false)
        }
    }

    AnimatedVisibility(
        visible = indicator.visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
        ) {
            val iconResId = indicator.iconResId
            val iconVector = indicator.icon
            val playPausePainter = when {
                iconResId != null -> painterResource(iconResId)
                iconVector != null -> rememberVectorPainter(iconVector)
                else -> rememberVectorPainter(Icons.Default.PlayArrow)
            }
            Icon(
                painter = playPausePainter,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun BoxScope.VolumeBrightnessPill(overlay: PlayerOverlayState) {
    LaunchedEffect(overlay.brightnessIndicator.visible) {
        if (overlay.brightnessIndicator.visible) {
            delay(1000)
            overlay.brightnessIndicator = overlay.brightnessIndicator.copy(visible = false)
        }
    }
    LaunchedEffect(overlay.volumeIndicator.visible) {
        if (overlay.volumeIndicator.visible) {
            delay(1000)
            overlay.volumeIndicator = overlay.volumeIndicator.copy(visible = false)
        }
    }

    val brightness = overlay.brightnessIndicator
    val volume = overlay.volumeIndicator
    val isPillVisible = brightness.visible || volume.visible
    val pillIcon = ImageVector.vectorResource(
        if (volume.visible) R.drawable.ic_volume_up else R.drawable.ic_brightness_6
    )
    val pillText = if (volume.visible) volume.text else brightness.text
    val pillProgress = if (volume.visible) volume.progress else brightness.progress

    VolumeBrightnessPillHud(
        icon = pillIcon,
        text = pillText,
        progress = pillProgress,
        visible = isPillVisible,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(top = 16.dp)
    )
}

@Composable
private fun BoxScope.DoubleTapRipples(overlay: PlayerOverlayState) {
    LaunchedEffect(overlay.isRewindRippleVisible) {
        if (overlay.isRewindRippleVisible) {
            delay(600)
            overlay.isRewindRippleVisible = false
        }
    }
    LaunchedEffect(overlay.isForwardRippleVisible) {
        if (overlay.isForwardRippleVisible) {
            delay(600)
            overlay.isForwardRippleVisible = false
        }
    }

    DoubleTapSeekRippleOverlay(
        isForward = false,
        seekSeconds = overlay.rewindSeekSeconds,
        visible = overlay.isRewindRippleVisible,
        modifier = Modifier.align(Alignment.CenterStart)
    )

    DoubleTapSeekRippleOverlay(
        isForward = true,
        seekSeconds = overlay.forwardSeekSeconds,
        visible = overlay.isForwardRippleVisible,
        modifier = Modifier.align(Alignment.CenterEnd)
    )
}

@Composable
private fun BoxScope.RestartChip(overlay: PlayerOverlayState, onRestart: () -> Unit) {
    LaunchedEffect(overlay.showRestartChip) {
        if (overlay.showRestartChip) {
            delay(3_000)
            overlay.showRestartChip = false
        }
    }

    AnimatedVisibility(
        visible = overlay.showRestartChip,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier
                .padding(end = 16.dp, bottom = 24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                .clickable {
                    overlay.showRestartChip = false
                    onRestart()
                }
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_fast_rewind),
                contentDescription = stringResource(R.string.video_restart_from_beginning),
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.resume_dialog_btn_restart),
                color = Color.White,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun BoxScope.BufferingIndicator(isBuffering: State<Boolean>) {
    val buffering by isBuffering

    AnimatedVisibility(
        visible = buffering,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.align(Alignment.Center)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(72.dp)
                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        }
    }
}

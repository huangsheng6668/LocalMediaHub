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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultLoadControl
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
import kotlin.math.abs



import androidx.compose.ui.res.vectorResource
import com.juziss.localmediahub.pip.PipController
import com.juziss.localmediahub.ui.component.DoubleTapSeekRippleOverlay
import com.juziss.localmediahub.ui.component.VolumeBrightnessPillHud

/**
 * Builds a stream URL for the given base, transcode flag and start position.
 *
 * For transcoded streams the server cannot satisfy byte-range seeks, so seeking
 * is done at the time level via `?start=<seconds>` (ffmpeg input seek). In
 * direct (copy) mode the server uses http.ServeContent which handles Range
 * natively, so no `start` param is added — ExoPlayer seeks via Range requests.
 *
 * Any existing transcode/start query params on [baseUrl] are stripped first so
 * re-seeking doesn't accumulate duplicate params.
 */
private fun buildStreamUrl(baseUrl: String, transcode: Boolean, startSec: Double): String {
    // Strip any previously applied transcode/start params to get the clean base.
    var clean = baseUrl
        .replace(Regex("[?&]transcode=true"), "")
        .replace(Regex("[?&]start=[^&]*"), "")
    // Fix up "?&" or trailing "?" left after stripping.
    clean = clean.replace("?&", "?").removeSuffix("?").removeSuffix("&")

    val params = mutableListOf<String>()
    if (transcode) params.add("transcode=true")
    // Only add start for transcoded streams, and only when actually seeking
    // past the beginning (start=0 is ffmpeg's default anyway).
    if (transcode && startSec > 0) params.add("start=%.3f".format(startSec))

    return if (params.isEmpty()) clean else {
        val sep = if (clean.contains("?")) "&" else "?"
        "$clean$sep${params.joinToString("&")}"
    }
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

    // Tracks the current playback position across configuration changes (rotation)
    // so the new ExoPlayer can seek to the correct spot.
    // Round 20: key on streamUrl so switching videos resets position.
    var savedPositionMs by rememberSaveable(streamUrl) { mutableLongStateOf(initialPositionMs) }

    // Wrap the caller's onProgress so this screen also tracks the position
    // for rememberSaveable (rotation survival). Both the periodic 5s timer
    // and the dispose-time flush update this.
    val wrappedOnProgress: (Long, Long) -> Unit = { positionMs, durationMs ->
        savedPositionMs = positionMs
        onProgress(positionMs, durationMs)
    }

    val exoPlayer = remember(streamUrl) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,   // minBufferMs — keeps ~5s buffered; prevents aggressive prefetching from saturating network on seek.
                30000,  // maxBufferMs — prefetches up to 30s ahead to save bandwidth and queue.
                250,    // bufferForPlaybackMs — start playing instantly after seek (250ms).
                300,    // bufferForPlaybackAfterRebufferMs — quick recovery after rebuffering (300ms).
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val okClient = videoPlayerViewModel.provideHttpClient().newBuilder()
            .cache(null) // Disable cache to prevent locking and disk thrashing on video streaming range requests
            .build()
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
            .setUserAgent("LocalMediaHub")

        val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

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
                setSeekParameters(androidx.media3.exoplayer.SeekParameters.DEFAULT)
                val mediaItem = MediaItem.fromUri(finalUrl)
                setMediaItem(mediaItem)
                // Round 20: sync seek before prepare (only for non-transcoded).
                // Transcoded streams are seeked via URL `start` param above.
                if (!isTranscoding && savedPositionMs > 0L) {
                    seekTo(savedPositionMs)
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
    val isBuffering: Boolean by isBufferingState

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
        // 让 PipActionReceiver 能拿到 ExoPlayer 实例
        PipControllerStore.bind(exoPlayer)
        onDispose {
            wrappedOnProgress(exoPlayer.currentPosition, exoPlayer.duration)
            exoPlayer.release()
            PipControllerStore.unbind()
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Auto-rotate based on video aspect ratio
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isBufferingState.value = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_ENDED) {
                    wrappedOnProgress(exoPlayer.duration, exoPlayer.duration)
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
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(5_000)
            if (exoPlayer.duration > 0L) {
                wrappedOnProgress(exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    // Handle system back button (disabled in PiP so the system back action
    // exits PiP / navigates outside the app rather than popping the screen).
    if (!isInPipMode) {
        BackHandler(onBack = onBack)
    }

    // ---- Gesture state ----
    var seekState by remember { mutableStateOf(SeekState()) }
    var playPauseIndicator by remember { mutableStateOf(GestureIndicator()) }
    var brightnessIndicator by remember { mutableStateOf(GestureIndicator()) }
    var volumeIndicator by remember { mutableStateOf(GestureIndicator()) }

    // Double-tap seek state
    var rewindSeekSeconds by remember { mutableIntStateOf(0) }
    var isRewindRippleVisible by remember { mutableStateOf(false) }
    var forwardSeekSeconds by remember { mutableIntStateOf(0) }
    var isForwardRippleVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isRewindRippleVisible) {
        if (isRewindRippleVisible) {
            delay(600)
            isRewindRippleVisible = false
        }
    }

    LaunchedEffect(isForwardRippleVisible) {
        if (isForwardRippleVisible) {
            delay(600)
            isForwardRippleVisible = false
        }
    }

    // Auto-hide play/pause indicator
    LaunchedEffect(playPauseIndicator.visible) {
        if (playPauseIndicator.visible) {
            delay(800)
            playPauseIndicator = playPauseIndicator.copy(visible = false)
        }
    }
    LaunchedEffect(brightnessIndicator.visible) {
        if (brightnessIndicator.visible) {
            delay(1000)
            brightnessIndicator = brightnessIndicator.copy(visible = false)
        }
    }
    LaunchedEffect(volumeIndicator.visible) {
        if (volumeIndicator.visible) {
            delay(1000)
            volumeIndicator = volumeIndicator.copy(visible = false)
        }
    }

    // Whether the current stream is transcoded. Declared here (before the seek
    // LaunchedEffect) because seek behaviour depends on it: transcoded streams
    // need a URL rebuild with ?start=<seconds> instead of a byte-range seek.
    var isTranscodingEnabled by remember { mutableStateOf(streamUrl.contains("transcode=true")) }

    // Round 22: when resuming from a non-zero position, show a temporary
    // "restart from beginning" affordance at the bottom-right for 3 seconds.
    // Gives users a one-tap escape if they actually wanted to start over.
    var showRestartChip by remember {
        mutableStateOf(initialPositionMs > 0L)
    }
    LaunchedEffect(showRestartChip) {
        if (showRestartChip) {
            delay(3_000)
            showRestartChip = false
        }
    }

    // Apply seek on gesture end
    LaunchedEffect(seekState.isSeeking) {
        if (!seekState.isSeeking && seekState.offsetMs != 0L) {
            // Use the base position recorded at gesture start — NOT the
            // continuously-advancing currentPosition — so the final seek
            // target matches what the user saw in the overlay indicator.
            val basePos = seekState.basePositionMs
            val maxPos = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
            val newPos = (basePos + seekState.offsetMs).coerceIn(0L, maxPos)
            if (isTranscodingEnabled) {
                // Transcoded streams are generated on the fly and cannot be
                // byte-seeked. Rebuild the URL with ?start=<seconds> so the
                // server re-opens the transcode at the requested position
                // (ffmpeg input seek), then resume playback from there.
                val newUrl = buildStreamUrl(streamUrl, isTranscodingEnabled, newPos / 1000.0)
                exoPlayer.setMediaItem(MediaItem.fromUri(newUrl))
                exoPlayer.prepare()
                exoPlayer.seekTo(0L)
                exoPlayer.play()
            } else {
                exoPlayer.seekTo(newPos)
            }
            seekState = SeekState()
        }
    }

    val gestureListener = rememberPlayerGestureListener(
        exoPlayer = exoPlayer,
        pausedText = pausedText,
        playingText = playingText,
        onSeekStateChange = { seekState = it },
        onBrightnessIndicatorChange = { brightnessIndicator = it },
        onVolumeIndicatorChange = { volumeIndicator = it },
        onPlayPauseIndicatorChange = { playPauseIndicator = it },
        onDoubleTapSeek = { isForward, seconds ->
            if (isForward) {
                val duration = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
                val target = (exoPlayer.currentPosition + seconds * 1000L).coerceAtMost(duration)
                exoPlayer.seekTo(target)
                forwardSeekSeconds = if (isForwardRippleVisible) forwardSeekSeconds + seconds else seconds
                isForwardRippleVisible = true
            } else {
                val target = (exoPlayer.currentPosition - seconds * 1000L).coerceAtLeast(0L)
                exoPlayer.seekTo(target)
                rewindSeekSeconds = if (isRewindRippleVisible) rewindSeekSeconds + seconds else seconds
                isRewindRippleVisible = true
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
            // Seek indicator
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
                        text = formatSeekOffset(seekState.offsetMs),
                        color = Color.White,
                        fontSize = 16.sp
                    )
                }
            }

            // Play/Pause indicator
            AnimatedVisibility(
                visible = playPauseIndicator.visible,
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
                    val iconResId = playPauseIndicator.iconResId
                    val iconVector = playPauseIndicator.icon
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

            // Top Center Volume / Brightness Pill HUD
            val isPillVisible = brightnessIndicator.visible || volumeIndicator.visible
            val pillIcon = ImageVector.vectorResource(if (volumeIndicator.visible) R.drawable.ic_volume_up else R.drawable.ic_brightness_6)
            val pillText = if (volumeIndicator.visible) volumeIndicator.text else brightnessIndicator.text
            val pillProgress = if (volumeIndicator.visible) volumeIndicator.progress else brightnessIndicator.progress

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

            // Double-Tap Seek Ripples
            DoubleTapSeekRippleOverlay(
                isForward = false,
                seekSeconds = rewindSeekSeconds,
                visible = isRewindRippleVisible,
                modifier = Modifier.align(Alignment.CenterStart)
            )

            DoubleTapSeekRippleOverlay(
                isForward = true,
                seekSeconds = forwardSeekSeconds,
                visible = isForwardRippleVisible,
                modifier = Modifier.align(Alignment.CenterEnd)
            )

            // Round 22: "restart from beginning" chip — shown for 3s after resuming
            // a non-zero position. Tap to seek back to 0 without reloading.
            AnimatedVisibility(
                visible = showRestartChip,
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
                            if (isTranscodingEnabled) {
                                // Transcoded streams can't byte-seek; rebuild URL
                                // without the ?start= param and re-prepare.
                                val restartedUrl = buildStreamUrl(streamUrl, true, 0.0)
                                exoPlayer.setMediaItem(MediaItem.fromUri(restartedUrl))
                                exoPlayer.prepare()
                                exoPlayer.seekTo(0L)
                                exoPlayer.play()
                            } else {
                                exoPlayer.seekTo(0L)
                            }
                            savedPositionMs = 0L
                            showRestartChip = false
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

            // Buffering indicator
            AnimatedVisibility(
                visible = isBuffering,
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

private fun formatSeekOffset(offsetMs: Long): String {
    val seconds = abs(offsetMs) / 1000
    val sign = if (offsetMs >= 0) "+" else "-"
    return if (seconds >= 60) {
        val min = seconds / 60
        val sec = seconds % 60
        "$sign${min}分${sec}秒"
    } else {
        "$sign${seconds}秒"
    }
}

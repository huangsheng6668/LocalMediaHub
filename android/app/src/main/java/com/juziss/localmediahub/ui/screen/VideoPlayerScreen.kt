package com.juziss.localmediahub.ui.screen

import com.juziss.localmediahub.ui.component.SeekState
import com.juziss.localmediahub.ui.component.GestureIndicator
import com.juziss.localmediahub.ui.component.rememberPlayerGestureListener

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.juziss.localmediahub.R
import kotlinx.coroutines.delay
import kotlin.math.abs



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
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Pre-resolve these strings in the composition so they can be used inside
    // gesture callbacks (detectTapGestures) where stringResource() is not allowed.
    val pausedText = stringResource(R.string.video_paused)
    val playingText = stringResource(R.string.video_playing)


    // Tracks the current playback position across configuration changes (rotation)
    // so the new ExoPlayer can seek to the correct spot.
    var savedPositionMs by rememberSaveable { mutableLongStateOf(initialPositionMs) }

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
                15000,  // minBufferMs — ExoPlayer default; keeps ~15s buffered
                        // so playback doesn't stutter every few seconds.
                50000,  // maxBufferMs — aggressively prefetch up to 50s ahead.
                1500,   // bufferForPlaybackMs — start quickly after seek.
                3000,   // bufferForPlaybackAfterRebufferMs — conservative after
                        // a rebuffer to avoid immediate re-stall.
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // OkHttp-backed data source for better connection reuse and timeout
        // handling on LAN — DefaultHttpDataSource can stall on some routers.
        val okClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS) // no overall timeout for streaming
            .retryOnConnectionFailure(true)
            .build()
        val dataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(okClient)
            .setUserAgent("LocalMediaHub")

        val mediaSource = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
            .setDataSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSource)
            .build().apply {
                val mediaItem = MediaItem.fromUri(streamUrl)
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            wrappedOnProgress(exoPlayer.currentPosition, exoPlayer.duration)
            exoPlayer.release()
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Auto-rotate based on video aspect ratio
    DisposableEffect(exoPlayer) {
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
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Initial seek on player creation only — NOT keyed on savedPositionMs,
    // otherwise every 5s progress update re-seeks and fights the user's scrubber.
    LaunchedEffect(exoPlayer) {
        if (savedPositionMs > 0L) {
            exoPlayer.seekTo(savedPositionMs)
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            delay(5_000)
            if (exoPlayer.duration > 0L) {
                wrappedOnProgress(exoPlayer.currentPosition, exoPlayer.duration)
            }
        }
    }

    // Handle system back button
    BackHandler(onBack = onBack)

    // ---- Gesture state ----
    var seekState by remember { mutableStateOf(SeekState()) }
    var playPauseIndicator by remember { mutableStateOf(GestureIndicator()) }
    var brightnessIndicator by remember { mutableStateOf(GestureIndicator()) }
    var volumeIndicator by remember { mutableStateOf(GestureIndicator()) }

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
        onPlayPauseIndicatorChange = { playPauseIndicator = it }
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
                    useController = true
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    setOnTouchListener(gestureListener)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ---- Gesture indicator overlays (non-interactive, pass-through) ----
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
                    if (seekState.offsetMs >= 0) Icons.Default.FastForward else Icons.Default.FastRewind,
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
                Icon(
                    playPauseIndicator.icon ?: Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Brightness indicator
        AnimatedVisibility(
            visible = brightnessIndicator.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterStart)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.Default.Brightness6, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(brightnessIndicator.text, color = Color.White, fontSize = 14.sp)
            }
        }

        // Volume indicator
        AnimatedVisibility(
            visible = volumeIndicator.visible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.CenterEnd)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(volumeIndicator.text, color = Color.White, fontSize = 14.sp)
            }
        }

        // Back button
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 8.dp, start = 4.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
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

package com.juziss.localmediahub.ui.screen

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
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.abs

private data class SeekState(
    val isSeeking: Boolean = false,
    val offsetMs: Long = 0L,
)

private data class GestureIndicator(
    val visible: Boolean = false,
    val icon: ImageVector? = null,
    val text: String = "",
)

@Composable
fun VideoPlayerScreen(
    streamUrl: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(streamUrl)
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
            (context as? Activity)?.requestedOrientation =
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Auto-rotate based on video aspect ratio
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
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

    // Apply seek on gesture end
    LaunchedEffect(seekState.isSeeking) {
        if (!seekState.isSeeking && seekState.offsetMs != 0L) {
            val currentPos = exoPlayer.currentPosition
            val newPos = (currentPos + seekState.offsetMs).coerceIn(0L, exoPlayer.duration)
            exoPlayer.seekTo(newPos)
            seekState = SeekState()
        }
    }

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

                    // Gesture detection on PlayerView — returns false so PlayerView
                    // still handles its own seekbar and controls normally.
                    var gestureStartX = 0f
                    var gestureStartY = 0f
                    var isDragging = false
                    var isHorizontal: Boolean? = null
                    var lastTapTime = 0L
                    var brightnessStart = 0f
                    var volumeStart = 0

                    fun getBrightness(): Float {
                        val activity = ctx as? Activity ?: return 0.5f
                        val params = activity.window.attributes
                        return if (params.screenBrightness < 0) 0.5f else params.screenBrightness
                    }

                    fun setBrightness(value: Float) {
                        val activity = ctx as? Activity ?: return
                        val clamped = value.coerceIn(0f, 1f)
                        val params = activity.window.attributes
                        params.screenBrightness = clamped
                        activity.window.attributes = params
                    }

                    fun getVolume(): Int {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }

                    fun getMaxVolume(): Int {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    }

                    fun setVolume(vol: Int) {
                        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.coerceIn(0, max), 0)
                    }

                    setOnTouchListener { _, event ->
                        val viewHeight = height.toFloat()
                        val viewWidth = width.toFloat()
                        val threshold = 30f * resources.displayMetrics.density

                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                gestureStartX = event.x
                                gestureStartY = event.y
                                isDragging = false
                                isHorizontal = null
                                brightnessStart = getBrightness()
                                volumeStart = getVolume()
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.x - gestureStartX
                                val dy = event.y - gestureStartY

                                if (!isDragging) {
                                    if (abs(dx) > threshold || abs(dy) > threshold) {
                                        isDragging = true
                                        isHorizontal = abs(dx) > abs(dy)
                                    }
                                }

                                if (isDragging) {
                                    if (isHorizontal == true) {
                                        val density = resources.displayMetrics.density
                                        val seekSec = (dx / density).toInt().coerceIn(-120, 120)
                                        val offsetMs = (seekSec.toLong() * 1000).coerceIn(-120_000L, 120_000L)
                                        seekState = SeekState(isSeeking = true, offsetMs = offsetMs)
                                    } else {
                                        val isLeftHalf = gestureStartX < viewWidth / 2
                                        val progress = -dy / viewHeight

                                        if (isLeftHalf) {
                                            val newBrightness = (brightnessStart + progress).coerceIn(0f, 1f)
                                            setBrightness(newBrightness)
                                            brightnessIndicator = GestureIndicator(
                                                visible = true,
                                                icon = Icons.Default.Brightness6,
                                                text = "${(newBrightness * 100).toInt()}%"
                                            )
                                        } else {
                                            val maxVol = getMaxVolume()
                                            val delta = (progress * maxVol).toInt()
                                            val newVol = (volumeStart + delta).coerceIn(0, maxVol)
                                            setVolume(newVol)
                                            volumeIndicator = GestureIndicator(
                                                visible = true,
                                                icon = Icons.Default.VolumeUp,
                                                text = "$newVol/$maxVol"
                                            )
                                        }
                                    }
                                }
                            }
                            MotionEvent.ACTION_UP -> {
                                if (isDragging) {
                                    // Finalize seek
                                    if (seekState.isSeeking) {
                                        seekState = seekState.copy(isSeeking = false)
                                    }
                                } else {
                                    // Tap → detect double tap for play/pause
                                    val now = System.currentTimeMillis()
                                    if (now - lastTapTime < 300) {
                                        if (exoPlayer.isPlaying) {
                                            exoPlayer.pause()
                                            playPauseIndicator = GestureIndicator(
                                                visible = true,
                                                icon = Icons.Default.Pause,
                                                text = "已暂停"
                                            )
                                        } else {
                                            exoPlayer.play()
                                            playPauseIndicator = GestureIndicator(
                                                visible = true,
                                                icon = Icons.Default.PlayArrow,
                                                text = "播放中"
                                            )
                                        }
                                        lastTapTime = 0L
                                    } else {
                                        lastTapTime = now
                                    }
                                }
                                isDragging = false
                            }
                        }
                        // Return false → PlayerView still handles seekbar & controls
                        false
                    }
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
                Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
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

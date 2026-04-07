package com.juziss.localmediahub.ui.component

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay

private data class SeekState(
    val isSeeking: Boolean = false,
    val offsetMs: Long = 0L,
)

private data class GestureIndicatorState(
    val visible: Boolean = false,
    val icon: ImageVector? = null,
    val text: String = "",
)

/**
 * Gesture overlay for video player.
 *
 * Gestures:
 * - Horizontal swipe: seek forward/backward (proportional, 5s-120s)
 * - Double tap: play/pause toggle
 * - Left half vertical swipe: brightness
 * - Right half vertical swipe: volume
 */
@Composable
fun VideoGestureOverlay(
    player: ExoPlayer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    var seekState by remember { mutableStateOf(SeekState()) }
    var playPauseIndicator by remember { mutableStateOf(GestureIndicatorState()) }
    var brightnessIndicator by remember { mutableStateOf(GestureIndicatorState()) }
    var volumeIndicator by remember { mutableStateOf(GestureIndicatorState()) }

    // Auto-hide indicators
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
            val currentPos = player.currentPosition
            val newPos = (currentPos + seekState.offsetMs).coerceIn(0L, player.duration)
            player.seekTo(newPos)
            seekState = SeekState()
        }
    }

    Box(modifier = modifier) {
        // Gesture detector fills entire area
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var dragStartX = 0f
                    var dragStartY = 0f
                    var isDragging = false
                    var isHorizontalDrag: Boolean? = null
                    var lastTapTime = 0L
                    var brightnessStart = 0f
                    var volumeStart = 0

                    fun getBrightness(): Float {
                        val activity = context as? Activity ?: return 0.5f
                        val params = activity.window.attributes
                        return if (params.screenBrightness < 0) 0.5f else params.screenBrightness
                    }

                    fun setBrightness(value: Float) {
                        val activity = context as? Activity ?: return
                        val clamped = value.coerceIn(0f, 1f)
                        val params = activity.window.attributes
                        params.screenBrightness = clamped
                        activity.window.attributes = params
                    }

                    fun getVolume(): Int {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        return am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    }

                    fun getMaxVolume(): Int {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    }

                    fun setVolume(volume: Int) {
                        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                        am.setStreamVolume(AudioManager.STREAM_MUSIC, volume.coerceIn(0, max), 0)
                    }

                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Press -> {
                                    val change = event.changes.first()
                                    dragStartX = change.position.x
                                    dragStartY = change.position.y
                                    isDragging = false
                                    isHorizontalDrag = null
                                    brightnessStart = getBrightness()
                                    volumeStart = getVolume()
                                }
                                PointerEventType.Move -> {
                                    val change = event.changes.first()
                                    val dx = change.position.x - dragStartX
                                    val dy = change.position.y - dragStartY
                                    val thresholdPx = with(density) { 30.dp.toPx() }

                                    if (!isDragging) {
                                        if (kotlin.math.abs(dx) > thresholdPx || kotlin.math.abs(dy) > thresholdPx) {
                                            isDragging = true
                                            isHorizontalDrag = kotlin.math.abs(dx) > kotlin.math.abs(dy)
                                        }
                                    }

                                    if (isDragging) {
                                        if (isHorizontalDrag == true) {
                                            val seekSec = (dx / density.density).toInt().coerceIn(-120, 120)
                                            val offsetMs = (seekSec.toLong() * 1000).coerceIn(-120_000L, 120_000L)
                                            seekState = SeekState(isSeeking = true, offsetMs = offsetMs)
                                        } else {
                                            val isLeftHalf = dragStartX < size.width / 2
                                            val progress = -dy / size.height

                                            if (isLeftHalf) {
                                                val newBrightness = (brightnessStart + progress).coerceIn(0f, 1f)
                                                setBrightness(newBrightness)
                                                brightnessIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.Brightness6,
                                                    text = "${(newBrightness * 100).toInt()}%"
                                                )
                                            } else {
                                                val maxVolume = getMaxVolume()
                                                val delta = (progress * maxVolume).toInt()
                                                val newVolume = (volumeStart + delta).coerceIn(0, maxVolume)
                                                setVolume(newVolume)
                                                volumeIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.VolumeUp,
                                                    text = "$newVolume/$maxVolume"
                                                )
                                            }
                                        }
                                    }
                                }
                                PointerEventType.Release -> {
                                    if (isDragging) {
                                        if (seekState.isSeeking) {
                                            seekState = seekState.copy(isSeeking = false)
                                        }
                                    } else {
                                        val now = System.currentTimeMillis()
                                        if (now - lastTapTime < 300) {
                                            if (player.isPlaying) {
                                                player.pause()
                                                playPauseIndicator = GestureIndicatorState(
                                                    visible = true,
                                                    icon = Icons.Default.Pause,
                                                    text = "已暂停"
                                                )
                                            } else {
                                                player.play()
                                                playPauseIndicator = GestureIndicatorState(
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
                        }
                    }
                }
        )

        // --- Seek indicator ---
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

        // --- Play/Pause indicator ---
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
                    playPauseIndicator.icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // --- Brightness indicator ---
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
                Icon(
                    Icons.Default.Brightness6,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(brightnessIndicator.text, color = Color.White, fontSize = 14.sp)
            }
        }

        // --- Volume indicator ---
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
                Icon(
                    Icons.Default.VolumeUp,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(volumeIndicator.text, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

private fun formatSeekOffset(offsetMs: Long): String {
    val seconds = kotlin.math.abs(offsetMs) / 1000
    val sign = if (offsetMs >= 0) "+" else "-"
    return if (seconds >= 60) {
        val min = seconds / 60
        val sec = seconds % 60
        "$sign${min}分${sec}秒"
    } else {
        "$sign${seconds}秒"
    }
}

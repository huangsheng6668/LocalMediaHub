package com.juziss.localmediahub.ui.component

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import kotlin.math.abs

data class SeekState(
    val isSeeking: Boolean = false,
    val offsetMs: Long = 0L,
    val basePositionMs: Long = 0L,
)

data class GestureIndicator(
    val visible: Boolean = false,
    val icon: ImageVector? = null,
    val text: String = "",
)

@Composable
fun rememberPlayerGestureListener(
    exoPlayer: Player,
    pausedText: String,
    playingText: String,
    onSeekStateChange: (SeekState) -> Unit,
    onBrightnessIndicatorChange: (GestureIndicator) -> Unit,
    onVolumeIndicatorChange: (GestureIndicator) -> Unit,
    onPlayPauseIndicatorChange: (GestureIndicator) -> Unit,
): View.OnTouchListener {
    return remember(exoPlayer) {
        var gestureStartX = 0f
        var gestureStartY = 0f
        var isDragging = false
        var isHorizontal: Boolean? = null
        var lastTapTime = 0L
        var brightnessStart = 0f
        var volumeStart = 0
        var initialPlayerPosition = 0L
        var gestureActive = false
        var lastSeekTargetPosition = -1L
        var currentSeekState = SeekState()

        fun getBrightness(context: Context): Float {
            val activity = context as? Activity ?: return 0.5f
            val params = activity.window.attributes
            return if (params.screenBrightness < 0) 0.5f else params.screenBrightness
        }

        fun setBrightness(context: Context, value: Float) {
            val activity = context as? Activity ?: return
            val clamped = value.coerceIn(0f, 1f)
            val params = activity.window.attributes
            params.screenBrightness = clamped
            activity.window.attributes = params
        }

        fun getVolume(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getStreamVolume(AudioManager.STREAM_MUSIC)
        }

        fun getMaxVolume(context: Context): Int {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        }

        fun setVolume(context: Context, vol: Int) {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, vol.coerceIn(0, max), 0)
        }

        View.OnTouchListener { view, event ->
            val playerView = view as? PlayerView
            val ctx = view.context
            val viewHeight = view.height.toFloat()
            val viewWidth = view.width.toFloat()
            val density = view.resources.displayMetrics.density
            val threshold = 20f * density

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // If ExoPlayer's control bar (SeekBar, buttons) is currently visible,
                    // pass ALL touches in this gesture to PlayerView natively so SeekBar dragging works 100%.
                    val controlsVisible = playerView?.isControllerFullyVisible == true
                    if (controlsVisible) {
                        gestureActive = false
                        return@OnTouchListener false
                    }

                    gestureActive = true
                    gestureStartX = event.x
                    gestureStartY = event.y

                    // Use lastSeekTargetPosition if player is still buffering from a rapid prior seek
                    // to prevent position lag from causing fast-forward to rewind.
                    val currentPos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    initialPlayerPosition = if (lastSeekTargetPosition >= 0L &&
                        abs(currentPos - lastSeekTargetPosition) > 1000L &&
                        exoPlayer.playbackState == Player.STATE_BUFFERING
                    ) {
                        lastSeekTargetPosition
                    } else {
                        currentPos
                    }

                    isDragging = false
                    isHorizontal = null
                    brightnessStart = getBrightness(ctx)
                    volumeStart = getVolume(ctx)
                    currentSeekState = SeekState()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!gestureActive) {
                        return@OnTouchListener false
                    }

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
                            // 1dp horizontal drag = 0.4 seconds seek offset
                            val seekSec = (dx / (density * 2.5f)).toLong().coerceIn(-300L, 300L)
                            val duration = if (exoPlayer.duration > 0) exoPlayer.duration else Long.MAX_VALUE
                            val targetPos = (initialPlayerPosition + seekSec * 1000L).coerceIn(0L, duration)
                            val offsetMs = targetPos - initialPlayerPosition

                            lastSeekTargetPosition = targetPos

                            currentSeekState = SeekState(
                                isSeeking = true,
                                offsetMs = offsetMs,
                                basePositionMs = initialPlayerPosition
                            )
                            onSeekStateChange(currentSeekState)
                        } else {
                            val isLeftHalf = gestureStartX < viewWidth / 2
                            val progress = -dy / viewHeight

                            if (isLeftHalf) {
                                val newBrightness = (brightnessStart + progress).coerceIn(0f, 1f)
                                setBrightness(ctx, newBrightness)
                                onBrightnessIndicatorChange(
                                    GestureIndicator(
                                        visible = true,
                                        icon = Icons.Default.Brightness6,
                                        text = "${(newBrightness * 100).toInt()}%"
                                    )
                                )
                            } else {
                                val maxVol = getMaxVolume(ctx)
                                val delta = (progress * maxVol).toInt()
                                val newVol = (volumeStart + delta).coerceIn(0, maxVol)
                                setVolume(ctx, newVol)
                                onVolumeIndicatorChange(
                                    GestureIndicator(
                                        visible = true,
                                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                                        text = "$newVol/$maxVol"
                                    )
                                )
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!gestureActive) {
                        return@OnTouchListener false
                    }
                    gestureActive = false

                    if (isDragging) {
                        if (currentSeekState.isSeeking) {
                            currentSeekState = currentSeekState.copy(isSeeking = false)
                            onSeekStateChange(currentSeekState)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            // Double tap: toggle play/pause
                            if (exoPlayer.isPlaying) {
                                exoPlayer.pause()
                                onPlayPauseIndicatorChange(
                                    GestureIndicator(
                                        visible = true,
                                        icon = Icons.Default.Pause,
                                        text = pausedText
                                    )
                                )
                            } else {
                                exoPlayer.play()
                                onPlayPauseIndicatorChange(
                                    GestureIndicator(
                                        visible = true,
                                        icon = Icons.Default.PlayArrow,
                                        text = playingText
                                    )
                                )
                            }
                            lastTapTime = 0L
                        } else {
                            // Single tap: toggle control bar visibility
                            lastTapTime = now
                            playerView?.showController()
                        }
                    }
                    isDragging = false
                }
            }
            false
        }
    }
}

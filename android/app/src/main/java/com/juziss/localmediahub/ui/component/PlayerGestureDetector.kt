package com.juziss.localmediahub.ui.component

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.view.MotionEvent
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.vector.ImageVector
import com.juziss.localmediahub.R
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
    val iconResId: Int? = null,
    val icon: ImageVector? = null,
    val text: String = "",
    val progress: Float = 0f,
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
    onDoubleTapSeek: ((isForward: Boolean, seekSeconds: Int) -> Unit)? = null,
): View.OnTouchListener {
    // The touch listener is keyed only on exoPlayer, so texts/callbacks must
    // not be frozen at first composition. rememberUpdatedState returns stable
    // State objects whose .value always reads the latest — capturing them in
    // the remember(exoPlayer) block below is safe (the State instances never
    // change identity).
    val currentPausedText = rememberUpdatedState(pausedText)
    val currentPlayingText = rememberUpdatedState(playingText)
    val currentOnSeekStateChange = rememberUpdatedState(onSeekStateChange)
    val currentOnBrightnessIndicatorChange = rememberUpdatedState(onBrightnessIndicatorChange)
    val currentOnVolumeIndicatorChange = rememberUpdatedState(onVolumeIndicatorChange)
    val currentOnPlayPauseIndicatorChange = rememberUpdatedState(onPlayPauseIndicatorChange)
    val currentOnDoubleTapSeek = rememberUpdatedState(onDoubleTapSeek)
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

                            // Emit only when the seek offset actually changes:
                            // ACTION_MOVE fires far more often than the
                            // (density * 2.5)px-per-second quantization steps,
                            // so identical SeekStates were re-emitted every
                            // move event, forcing needless recompositions.
                            if (currentSeekState.offsetMs != offsetMs) {
                                currentSeekState = SeekState(
                                    isSeeking = true,
                                    offsetMs = offsetMs,
                                    basePositionMs = initialPlayerPosition
                                )
                                currentOnSeekStateChange.value(currentSeekState)
                            }
                        } else {
                            val isLeftHalf = gestureStartX < viewWidth / 2
                            val progress = -dy / viewHeight

                            if (isLeftHalf) {
                                val newBrightness = (brightnessStart + progress).coerceIn(0f, 1f)
                                setBrightness(ctx, newBrightness)
                                currentOnBrightnessIndicatorChange.value(
                                    GestureIndicator(
                                        visible = true,
                                        iconResId = R.drawable.ic_brightness_6,
                                        text = "${(newBrightness * 100).toInt()}%",
                                        progress = newBrightness,
                                    )
                                )
                            } else {
                                val maxVol = getMaxVolume(ctx)
                                val delta = (progress * maxVol).toInt()
                                val newVol = (volumeStart + delta).coerceIn(0, maxVol)
                                setVolume(ctx, newVol)
                                val volProgress = if (maxVol > 0) newVol.toFloat() / maxVol else 0f
                                currentOnVolumeIndicatorChange.value(
                                    GestureIndicator(
                                        visible = true,
                                        iconResId = R.drawable.ic_volume_up,
                                        text = "${(volProgress * 100).toInt()}%",
                                        progress = volProgress,
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
                            currentOnSeekStateChange.value(currentSeekState)
                        }
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 300) {
                            val x = event.x
                            val width = viewWidth
                            if (currentOnDoubleTapSeek.value != null && x < width * 0.35f) {
                                currentOnDoubleTapSeek.value?.invoke(false, 10)
                            } else if (currentOnDoubleTapSeek.value != null && x > width * 0.65f) {
                                currentOnDoubleTapSeek.value?.invoke(true, 10)
                            } else {
                                // Double tap center: toggle play/pause
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                    currentOnPlayPauseIndicatorChange.value(
                                        GestureIndicator(
                                            visible = true,
                                            iconResId = R.drawable.ic_pause,
                                            text = currentPausedText.value
                                        )
                                    )
                                } else {
                                    exoPlayer.play()
                                    currentOnPlayPauseIndicatorChange.value(
                                        GestureIndicator(
                                            visible = true,
                                            icon = Icons.Default.PlayArrow,
                                            text = currentPlayingText.value
                                        )
                                    )
                                }
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

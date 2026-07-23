package com.juziss.localmediahub.pip

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class PipController {
    val isInPipMode: StateFlow<Boolean> get() = _isInPipMode
    private val _isInPipMode = MutableStateFlow(false)

    fun onPictureInPictureModeChanged(isInPipMode: Boolean) {
        _isInPipMode.value = isInPipMode
    }

    fun enterPipMode(activity: Activity, videoWidth: Int = 0, videoHeight: Int = 0, isPlaying: Boolean = true) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = buildPipParams(activity, videoWidth, videoHeight, isPlaying)
            activity.enterPictureInPictureMode(params)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun updatePipParams(activity: Activity, videoWidth: Int, videoHeight: Int, isPlaying: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.setPictureInPictureParams(buildPipParams(activity, videoWidth, videoHeight, isPlaying))
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun buildPipParams(context: Context, videoWidth: Int, videoHeight: Int, isPlaying: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
        if (videoWidth > 0 && videoHeight > 0) {
            val aspect = videoWidth.toFloat() / videoHeight
            if (aspect in 0.41841f..2.39f) {
                builder.setAspectRatio(Rational(videoWidth, videoHeight))
            } else {
                val clamped = aspect.coerceIn(0.41841f, 2.39f)
                builder.setAspectRatio(Rational((clamped * 1000).toInt(), 1000))
            }
        } else {
            builder.setAspectRatio(DEFAULT_RATIO)
        }
        val actions = createRemoteActions(context, isPlaying)
        builder.setActions(actions)
        return builder.build()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createRemoteActions(context: Context, isPlaying: Boolean): List<RemoteAction> {
        val actions = mutableListOf<RemoteAction>()

        // Rewind 10s Action
        val rewindIntent = Intent(ACTION_PIP_REWIND).setPackage(context.packageName)
        val rewindPending = PendingIntent.getBroadcast(
            context,
            1,
            rewindIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val rewindIcon = Icon.createWithResource(context, android.R.drawable.ic_media_rew)
        actions.add(RemoteAction(rewindIcon, "Rewind 10s", "Rewind 10s", rewindPending))

        // Play / Pause Action
        val playPauseIntent = Intent(ACTION_PIP_PLAY_PAUSE).setPackage(context.packageName)
        val playPausePending = PendingIntent.getBroadcast(
            context,
            2,
            playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying) "Pause" else "Play"
        actions.add(RemoteAction(Icon.createWithResource(context, playPauseIconRes), playPauseTitle, playPauseTitle, playPausePending))

        // Forward 10s Action
        val forwardIntent = Intent(ACTION_PIP_FORWARD).setPackage(context.packageName)
        val forwardPending = PendingIntent.getBroadcast(
            context,
            3,
            forwardIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val forwardIcon = Icon.createWithResource(context, android.R.drawable.ic_media_ff)
        actions.add(RemoteAction(forwardIcon, "Forward 10s", "Forward 10s", forwardPending))

        return actions
    }

    companion object {
        const val ACTION_PIP_PLAY_PAUSE = "com.juziss.localmediahub.PIP_PLAY_PAUSE"
        const val ACTION_PIP_REWIND = "com.juziss.localmediahub.PIP_REWIND"
        const val ACTION_PIP_FORWARD = "com.juziss.localmediahub.PIP_FORWARD"

        @Deprecated("Use ACTION_PIP_PLAY_PAUSE", ReplaceWith("ACTION_PIP_PLAY_PAUSE"))
        const val ACTION_PLAY_PAUSE = ACTION_PIP_PLAY_PAUSE

        private val DEFAULT_RATIO = Rational(16, 9)

        @RequiresApi(Build.VERSION_CODES.O)
        @JvmStatic
        fun buildParams(context: Context, width: Int, height: Int, isPlaying: Boolean): PictureInPictureParams {
            return PipController().buildPipParams(context, width, height, isPlaying)
        }
    }
}

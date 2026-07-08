package com.juziss.localmediahub.pip

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Rational
import com.juziss.localmediahub.R

/**
 * 无状态工具，封装 PiP 参数构造（宽高比 + RemoteAction）。
 *
 * 抽出来的目的：让 MainActivity 保持精简（当前 506 行，加 PiP 不希望膨胀到 700+）。
 * 同时把 "宽高比 fallback 到 16:9" 这类纯逻辑隔离出来，便于 Robolectric 单测。
 *
 * 使用 framework android.app.RemoteAction（非 androidx，后者不存在）。
 * RemoteAction 图标使用系统自带 android.R.drawable.ic_media_pause / ic_media_play，
 * 避免新增 drawable 资源。
 */
object PipController {

    const val ACTION_PLAY_PAUSE = "com.juziss.localmediahub.pip.ACTION_PLAY_PAUSE"

    private val DEFAULT_RATIO = Rational(16, 9)

    /**
     * 构建 PiP 参数：宽高比 + 1 个 RemoteAction（播放/暂停切换）。
     *
     * @param width  当前视频真实宽度（来自 ExoPlayer VideoSize.width），0 表示未知
     * @param height 当前视频真实高度，0 表示未知
     * @param isPlaying 当前 ExoPlayer 是否正在播放 —— 决定 RemoteAction 图标（播放/暂停）
     */
    fun buildParams(
        context: Context,
        width: Int,
        height: Int,
        isPlaying: Boolean,
    ): PictureInPictureParams {
        val ratio = if (width > 0 && height > 0) {
            Rational(width, height)
        } else {
            DEFAULT_RATIO
        }

        // 1 个 RemoteAction：根据 isPlaying 切换图标。PendingIntent 必须用 FLAG_IMMUTABLE
        // (Android 12+ 强制要求)。RequestCode 用 0 即可，因为每次进入 PiP 只有一个 action。
        val intent = Intent(ACTION_PLAY_PAUSE).setPackage(context.packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        // 使用系统自带 drawable，避免新增资源（Task 2 不需要新增 drawable）。
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val title = context.getString(R.string.pip_button)
        val action = RemoteAction(
            Icon.createWithResource(context, iconRes),
            title,
            title,
            pendingIntent
        )

        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setActions(listOf(action))
            .build()
    }
}

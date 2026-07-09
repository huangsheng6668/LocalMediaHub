package com.juziss.localmediahub

import android.content.Context
import android.content.Intent
import android.os.Build
import com.juziss.localmediahub.data.MediaFile

/**
 * 无状态工具，封装 MainActivity → VideoPlayerActivity 的 Intent 构造。
 *
 * 为什么单独成类：把 4 个 extras + FLAG_ACTIVITY_NEW_TASK + 目标 Activity 的构造逻辑
 * 隔离出来，便于 Robolectric 单测，且让 MainActivity 的 playVideo lambda 保持精简。
 *
 * Intent extras 通过 @Parcelize 的 MediaFile 直接 putExtra，零成本传递。
 * FLAG_ACTIVITY_NEW_TASK 是双重保障（Manifest 的 taskAffinity + 启动 Flag），
 * 确保系统在独立 task 中拉起 VideoPlayerActivity，绕过部分国产 ROM 强行合并
 * task 的兼容性问题（spec §4.9）。
 */
object VideoPlayerIntentBuilder {

    const val EXTRA_STREAM_URL = "streamUrl"
    const val EXTRA_INITIAL_POSITION_MS = "initialPositionMs"
    const val EXTRA_IS_SYSTEM_BROWSE = "isSystemBrowse"
    const val EXTRA_MEDIA_FILE = "mediaFile"

    fun build(
        context: Context,
        file: MediaFile,
        streamUrl: String,
        initialPositionMs: Long,
        isSystemBrowse: Boolean,
    ): Intent {
        return Intent(context, VideoPlayerActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(EXTRA_STREAM_URL, streamUrl)
            putExtra(EXTRA_INITIAL_POSITION_MS, initialPositionMs)
            putExtra(EXTRA_IS_SYSTEM_BROWSE, isSystemBrowse)
            // MediaFile 是 @Parcelize，可以直接 putExtra。getParcelableExtra 在 API 33+
            // 推荐显式传 Class，但为了 minSdk 26 兼容用旧 API（无 deprecated 警告影响功能）。
            putExtra(EXTRA_MEDIA_FILE, file)
        }
    }
}

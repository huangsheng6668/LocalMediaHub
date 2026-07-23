package com.juziss.localmediahub

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.rememberCoroutineScope
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.pip.PipController
import com.juziss.localmediahub.ui.screen.VideoPlayerScreen
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 视频播放独立 Activity（多 Activity PiP 架构）。
 *
 * 与 MainActivity 完全解耦：
 * - 独立 taskAffinity（Manifest 中声明 com.juziss.localmediahub.video）
 * - launchMode="singleTask" 确保不重复创建
 * - supportsPictureInPicture=true 让本 Activity 独立进入 PiP
 *
 * 用户在 MainActivity 浏览图片时，本 Activity 可保持 PiP 状态在角落播放视频，
 * 两个 Activity 生命周期完全独立（spec §3.7）。
 *
 * 进度保存：直接注入 RecentActivityStore（Hilt @Singleton），在 onProgress 回调里
 * 调 savePlaybackProgress，不需要回传给 MainActivity（spec §2.3 第 3 条）。
 */
@AndroidEntryPoint
class VideoPlayerActivity : ComponentActivity() {

    @Inject
    lateinit var recentActivityStore: RecentActivityStore

    val pipController = PipController()
    val isInPipMode: StateFlow<Boolean> get() = pipController.isInPipMode
    var onPipActionReceived: ((String) -> Unit)? = null

    /**
     * 临时标志：onPictureInPictureModeChanged(false) 时置 true，供 [onStop] 消费。
     * 用来区分「PiP 关闭后切到后台」(finish) 与「普通全屏切后台」(保留) 两种场景。
     */
    private var exitingFromPip: Boolean = false

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.action?.let { action ->
                onPipActionReceived?.invoke(action)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val file = parseMediaFileExtra(intent)
            ?: run {
                // 缺少必传参数，无法播放。直接 finish 让用户回到 MainActivity。
                finish()
                return
            }
        val streamUrl = intent.getStringExtra(VideoPlayerIntentBuilder.EXTRA_STREAM_URL) ?: ""
        val initialPositionMs = intent.getLongExtra(VideoPlayerIntentBuilder.EXTRA_INITIAL_POSITION_MS, 0L)
        val isSystemBrowse = intent.getBooleanExtra(VideoPlayerIntentBuilder.EXTRA_IS_SYSTEM_BROWSE, false)

        val filter = IntentFilter().apply {
            addAction(PipController.ACTION_PIP_PLAY_PAUSE)
            addAction(PipController.ACTION_PIP_REWIND)
            addAction(PipController.ACTION_PIP_FORWARD)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(pipReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(pipReceiver, filter)
        }

        setContent {
            LocalMediaHubTheme {
                val appScope = rememberCoroutineScope()
                VideoPlayerScreen(
                    streamUrl = streamUrl,
                    initialPositionMs = initialPositionMs,
                    onProgress = { positionMs, durationMs ->
                        appScope.launch {
                            recentActivityStore.savePlaybackProgress(
                                file = file,
                                isSystemBrowse = isSystemBrowse,
                                positionMs = positionMs,
                                durationMs = durationMs,
                            )
                        }
                    },
                    onBack = { finish() },
                )
            }
        }
    }

    private fun parseMediaFileExtra(intent: Intent): MediaFile? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(VideoPlayerIntentBuilder.EXTRA_MEDIA_FILE, MediaFile::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(VideoPlayerIntentBuilder.EXTRA_MEDIA_FILE)
        }
    }

    /**
     * 处理「VideoPlayerActivity 已存在（PiP 或全屏）+ 用户从 MainActivity 再次点视频」场景。
     *
     * launchMode="singleTask" → 系统复用现有实例，通过 onNewIntent 派发新参数。
     * spec §4.8：系统会自动把已处于 PiP 状态的 singleTask Activity 唤醒到前台
     * （自动退出 PiP 恢复全屏），符合用户点视频期望「全屏观看」的直觉。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    /**
     * 由 VideoPlayerScreen 的「悬浮窗」按钮调用。返回 true 表示成功进入 PiP。
     */
    @Suppress("DEPRECATION")
    fun enterPipMode(
        width: Int = 0,
        height: Int = 0,
        isPlaying: Boolean = true,
        sourceRectHint: android.graphics.Rect? = null,
    ): Boolean {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        pipController.enterPipMode(this, width, height, isPlaying)
        return true
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        pipController.enterPipMode(this)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        android.util.Log.d("PipDebug", "onPiPModeChanged: isInPip=$isInPictureInPictureMode, lifecycle=${lifecycle.currentState}")
        pipController.onPictureInPictureModeChanged(isInPictureInPictureMode)
        if (!isInPictureInPictureMode) {
            // 区分「点 × 关闭浮窗」(需 finish 释放资源) 和「点主体回全屏」(保留)：
            // - 关闭浮窗：Activity 即将进入 STOPPED/DESTROYED，lifecycle 是 CREATED 或更低
            // - 点主体回全屏：Activity 即将进入 RESUMED，lifecycle 是 STARTED 或更高
            val closing = !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
            android.util.Log.d("PipDebug", "onPiPModeChanged: closing=$closing (lifecycle=${lifecycle.currentState})")
            exitingFromPip = true

            if (closing && !isFinishing) {
                android.util.Log.d("PipDebug", "onPiPModeChanged: closing detected, finish()")
                exitingFromPip = false
                finish()
            }

            // 兜底：解决 Android 12+ 关闭 PiP 时先走 onStop 后走 onPictureInPictureModeChanged 的生命周期问题。
            if (lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED && !isFinishing) {
                android.util.Log.d("PipDebug", "onPiPModeChanged: lifecycle is CREATED (post-stop), finishing activity")
                exitingFromPip = false
                finish()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        android.util.Log.d("PipDebug", "onStop: exitingFromPip=$exitingFromPip, isFinishing=$isFinishing")
        if (exitingFromPip && !isFinishing) {
            exitingFromPip = false
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("PipDebug", "onDestroy called")
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: IllegalArgumentException) {
            // ignore if not registered
        }
    }

    companion object {
        const val EXTRA_STREAM_URL = "extra_stream_url"
        const val EXTRA_INITIAL_POSITION = "extra_initial_position"
    }
}

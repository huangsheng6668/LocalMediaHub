package com.juziss.localmediahub

import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.content.ContextCompat
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.pip.PipActionReceiver
import com.juziss.localmediahub.pip.PipController
import com.juziss.localmediahub.pip.PipControllerStore
import com.juziss.localmediahub.ui.screen.VideoPlayerScreen
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _isInPipMode = MutableStateFlow(false)
    /** 暴露给 VideoPlayerScreen Composable 读取的 PiP 状态。 */
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    /**
     * 已注册的 [PipActionReceiver] 实例。必须保存注册时创建的同一个实例：
     * Android 的 unregisterReceiver 按 binder 身份匹配（对象相等性），不是按类匹配。
     */
    private var pipReceiver: PipActionReceiver? = null

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
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
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
        // VideoPlayerScreen 内部用 remember(streamUrl) 重建 ExoPlayer。这里改了 intent
        // 后需要让 Composable 重新读取并重建。最简单的方式：recreate() 让 Activity
        // 重新走 onCreate（会从新 intent 读参数）。
        recreate()
    }

    /**
     * 由 VideoPlayerScreen 的「悬浮窗」按钮调用。返回 true 表示成功进入 PiP。
     *
     * 在进入 PiP 前动态注册 [PipActionReceiver] (RECEIVER_NOT_EXPORTED, targetSdk 34 强制)
     * 以便接收 RemoteAction 的 PendingIntent 派发。退出 PiP 时在
     * [onPictureInPictureModeChanged] 中解绑。
     */
    @Suppress("DEPRECATION")
    fun enterPipMode(
        width: Int,
        height: Int,
        isPlaying: Boolean,
        sourceRectHint: android.graphics.Rect? = null,
    ): Boolean {
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            return false
        }
        // NOTE: PipController.buildParams does not currently accept a sourceRectHint;
        // it is reserved for a future Task that extends buildParams. The parameter is kept
        // on this public API so VideoPlayerScreen can pass it through without a later
        // breaking change.
        val params = PipController.buildParams(this, width, height, isPlaying)
        return try {
            // compileSdk 36 上单参 enterPictureInPictureMode(PictureInPictureParams) 被弃用，
            // 替换签名要求 API 36+ 的 Executor + Consumer，超出本次范围；minSdk 26 不支持新重载，
            // 旧的弃用调用在所有 API 26+ 设备上仍工作正常。
            val entered = enterPictureInPictureMode(params)
            if (entered && pipReceiver == null) {
                val receiver = PipActionReceiver()
                ContextCompat.registerReceiver(
                    this,
                    receiver,
                    IntentFilter(PipController.ACTION_PLAY_PAUSE),
                    ContextCompat.RECEIVER_NOT_EXPORTED,
                )
                pipReceiver = receiver
            }
            entered
        } catch (e: IllegalStateException) {
            // 部分 ROM 在 Activity 非 resumed 时调用 enterPictureInPictureMode 会抛。
            false
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        _isInPipMode.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) {
            unregisterPipReceiver()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterPipReceiver()
        PipControllerStore.unbind()
    }

    /**
     * 解绑已注册的 [pipReceiver]（按 binder 身份匹配）。重复调用或未注册时静默忽略。
     */
    private fun unregisterPipReceiver() {
        val receiver = pipReceiver ?: return
        try {
            unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        pipReceiver = null
    }
}

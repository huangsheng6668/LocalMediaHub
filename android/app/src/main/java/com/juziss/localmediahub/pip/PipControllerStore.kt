package com.juziss.localmediahub.pip

import androidx.media3.exoplayer.ExoPlayer
import java.lang.ref.WeakReference

/**
 * 进程级单例，桥接 BroadcastReceiver（无 Compose 上下文）
 * 与 Composable 内创建的 ExoPlayer 实例。
 *
 * 用 WeakReference 防止 Activity 退出后仍持有 ExoPlayer 导致泄漏。如果
 * Composable 已 dispose，[togglePlayPause] 静默 no-op，Receiver 收到的
 * 派发也不会崩。
 */
object PipControllerStore {

    @Volatile
    private var playerRef: WeakReference<ExoPlayer>? = null

    fun bind(player: ExoPlayer) {
        playerRef = WeakReference(player)
    }

    fun unbind() {
        playerRef = null
    }

    fun isPlaying(): Boolean = playerRef?.get()?.isPlaying ?: false

    fun togglePlayPause() {
        val player = playerRef?.get() ?: return
        if (player.isPlaying) player.pause() else player.play()
    }
}

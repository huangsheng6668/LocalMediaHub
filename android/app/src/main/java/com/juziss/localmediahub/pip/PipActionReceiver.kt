package com.juziss.localmediahub.pip

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 接收 PiP 浮窗中央播放/暂停按钮的 PendingIntent 派发。
 *
 * 注意：动态注册时必须用 ContextCompat.RECEIVER_NOT_EXPORTED (targetSdk 34 强制)，
 * 这样这个 Receiver 只接收本应用发出的广播，无安全风险。
 */
class PipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PipController.ACTION_PLAY_PAUSE) {
            PipControllerStore.togglePlayPause()
        }
    }
}

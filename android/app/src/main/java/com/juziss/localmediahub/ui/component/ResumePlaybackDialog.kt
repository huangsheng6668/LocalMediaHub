package com.juziss.localmediahub.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.util.formatTime

/** 用户点击视频后,根据已保存进度判定的下一步动作。 */
sealed class VideoOpenAction {
    /** 直接以给定位置开始播放。 */
    data class PlayDirectly(val positionMs: Long) : VideoOpenAction()

    /** 已看完,需要弹窗让用户选择继续 / 从头。 */
    data class ShowCompletedDialog(val positionMs: Long, val durationMs: Long) : VideoOpenAction()
}

/** 触发 [ResumePlaybackDialog] 的请求负载。 */
data class ResumePlaybackRequest(
    val file: MediaFile,
    val isSystemBrowse: Boolean,
    val streamUrl: String,
    val positionMs: Long,
    val durationMs: Long,
)

/**
 * 续播确认对话框。仅当视频进度 >= 95% 时由调用方触发。
 *
 * 默认聚焦按钮由调用方根据进度阈值决定(95% <= progress < 98% 聚焦"继续播放",
 * progress >= 98% 聚焦"从头开始"),通过 [focusResume] 参数传入。
 */
@Composable
fun ResumePlaybackDialog(
    request: ResumePlaybackRequest,
    focusResume: Boolean,
    onRestart: () -> Unit,
    onResume: () -> Unit,
    onDismiss: () -> Unit,
) {
    val message = stringResource(
        R.string.resume_dialog_message,
        formatTime(request.positionMs),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resume_dialog_title)) },
        text = { Text(message) },
        dismissButton = {
            TextButton(onClick = onRestart) {
                Text(stringResource(R.string.resume_dialog_btn_restart))
            }
        },
        confirmButton = {
            TextButton(onClick = onResume) {
                Text(stringResource(R.string.resume_dialog_btn_resume))
            }
        },
    )
}

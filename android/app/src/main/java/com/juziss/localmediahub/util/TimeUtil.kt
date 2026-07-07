package com.juziss.localmediahub.util

/**
 * 把毫秒数格式化为可读的时间字符串。
 * - < 1 小时:显示为 `M:SS`(分钟不补零,秒补零)
 * - >= 1 小时:显示为 `H:MM:SS`
 *
 * 负数会被钳制为 0。
 */
fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

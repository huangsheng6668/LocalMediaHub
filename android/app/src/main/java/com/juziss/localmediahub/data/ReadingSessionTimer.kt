package com.juziss.localmediahub.data

/**
 * 阅读会话计时器（spec 2026-09-08-reading-stats-and-android-bookshelf）：
 * start/stop 括起活跃阅读（TextReaderActivity onResume/onPause 驱动），
 * takeDelta 取走并清零累积秒数（运行中取走不停止计时）。时钟注入便于单测。
 */
class ReadingSessionTimer(private val now: () -> Long = System::currentTimeMillis) {
    private var startTs: Long? = null
    private var pendingSec = 0L

    fun start() {
        if (startTs == null) startTs = now()
    }

    fun stop() {
        startTs?.let {
            pendingSec += (now() - it) / 1000
            startTs = null
        }
    }

    fun takeDelta(): Long {
        var d = pendingSec
        pendingSec = 0
        startTs?.let {
            d += (now() - it) / 1000
            startTs = now()
        }
        return d
    }

    fun isRunning(): Boolean = startTs != null
}

package com.juziss.localmediahub.data

/**
 * 阅读会话计时器（spec 2026-09-08-reading-stats-and-android-bookshelf）：
 * start/stop 括起活跃阅读（TextReaderActivity onResume/onPause 驱动），
 * takeDelta 取走并清零已满整秒的累积秒数，同时保留未满 1 秒的亚秒余数，
 * 并防护系统时钟回拨导致的负数耗时。时钟注入便于单测。
 */
class ReadingSessionTimer(private val now: () -> Long = System::currentTimeMillis) {
    private var startTs: Long? = null
    private var pendingSec = 0L

    @Synchronized
    fun start() {
        if (startTs == null) startTs = now()
    }

    @Synchronized
    fun stop() {
        startTs?.let {
            val elapsedMs = (now() - it).coerceAtLeast(0L)
            pendingSec += elapsedMs / 1000L
            startTs = null
        }
    }

    @Synchronized
    fun takeDelta(): Long {
        var d = pendingSec
        pendingSec = 0L
        startTs?.let {
            val cur = now()
            if (cur >= it) {
                val wholeSec = (cur - it) / 1000L
                d += wholeSec
                // 仅推进已结算的整秒部分，保留 (cur - it) % 1000 的亚秒余数
                startTs = it + wholeSec * 1000L
            } else {
                // 时钟回拨保护：重置锚点且不产生负数秒
                startTs = cur
            }
        }
        return d
    }

    @Synchronized
    fun isRunning(): Boolean = startTs != null
}

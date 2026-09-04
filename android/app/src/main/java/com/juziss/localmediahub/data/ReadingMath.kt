package com.juziss.localmediahub.data

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ReadingMath {
    fun percent(chapterIndex: Int, blockIndex: Int, chapterBlockCount: Int, totalChapters: Int): Double {
        val intra = if (chapterBlockCount > 0) min(1.0, blockIndex.toDouble() / chapterBlockCount) else 0.0
        val total = max(1, totalChapters)
        val raw = ((chapterIndex + intra) / total) * 100.0
        return (min(100.0, max(0.0, raw)) * 10).roundToInt() / 10.0
    }

    fun isFinished(chapterIndex: Int, totalChapters: Int, atChapterEnd: Boolean): Boolean =
        totalChapters > 0 && chapterIndex == totalChapters - 1 && atChapterEnd
}

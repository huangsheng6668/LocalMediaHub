package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderScrollProgressTest {

    private fun ch(index: Int, blocks: Int) = ScrollModeChapter(
        chapterIndex = index,
        title = "ch$index",
        blocks = List(blocks) { Block("text", "p$it") },
    )

    @Test
    fun emptyOrInvalidInputsReturnZero() {
        assertEquals(
            0,
            computeOverallPercent(0, 0, 10, emptyList(), 0, 0),
        )
        assertEquals(
            0,
            computeOverallPercent(0, 0, 10, listOf(ch(0, 3)), 0, 0),
        )
    }

    @Test
    fun firstItemOfFirstChapterIsZero() {
        val chapters = listOf(ch(0, 4), ch(1, 4))
        assertEquals(0, computeOverallPercent(0, 0, 10, chapters, 2, 0))
    }

    @Test
    fun chapterBoundaryMapsToChapterFraction() {
        val chapters = listOf(ch(0, 4), ch(1, 4))
        // 章节 1 的首个 item（累计 6 项之后），全书 2 章 → 50%
        assertEquals(50, computeOverallPercent(6, 0, 10, chapters, 2, 0))
    }

    @Test
    fun midChapterUsesItemAndSubItemFractions() {
        val chapters = listOf(ch(0, 4), ch(1, 4))
        // 章节 0 内第 3 个 item（=0.5），另加 item 内 50% → 0.5833 / 2 章 ≈ 29%
        assertEquals(29, computeOverallPercent(3, 5, 10, chapters, 2, 0))
    }

    @Test
    fun lastChapterEndStaysUnderHundred() {
        val chapters = listOf(ch(0, 4), ch(1, 4))
        // 章节 1 内第 5 个 item → (1 + 5/6) / 2 = 91%
        assertEquals(91, computeOverallPercent(11, 0, 10, chapters, 2, 0))
    }

    @Test
    fun outOfRangeIndexFallsBackToChapterIndex() {
        val chapters = listOf(ch(0, 4), ch(1, 4))
        // 超出全部 item：保留 fallback 章节索引，不越界
        assertEquals(50, computeOverallPercent(99, 0, 10, chapters, 2, 1))
    }

    @Test
    fun resultClampsAtHundred() {
        val chapters = listOf(ch(0, 1), ch(1, 1), ch(2, 1))
        assertEquals(100, computeOverallPercent(5, 10, 10, chapters, 1, 0))
    }
}

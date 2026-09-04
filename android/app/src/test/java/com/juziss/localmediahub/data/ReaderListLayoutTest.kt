package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderListLayoutTest {

    private fun ch(idx: Int, blocks: Int) = ScrollModeChapter(
        chapterIndex = idx,
        title = "C$idx",
        blocks = List(blocks) { Block(type = "text", value = "p$it") },
    )

    // 布局事实：每章 = [标题, blocks..., 分隔符] 共 blocks.size + 2 个 item
    private val chapters = listOf(ch(0, 2), ch(1, 3), ch(5, 1))

    @Test
    fun `scrollItemIndex maps within first chapter`() {
        // 章 0：标题=item0, b0=item1, b1=item2, 分隔符=item3
        assertEquals(1, ReaderListLayout.scrollItemIndex(chapters, 0, 0))
        assertEquals(2, ReaderListLayout.scrollItemIndex(chapters, 0, 1))
    }

    @Test
    fun `scrollItemIndex maps across chapters`() {
        // 章 1 起始 item = (2+2) = 4；b2 = item 4+1+2 = 7
        // （brief 原断言值 5 为笔误：b1 = 4+1+1 = 6，与 (1,2)→7 及逆向映射 scrollChapterBlock 自洽）
        assertEquals(6, ReaderListLayout.scrollItemIndex(chapters, 1, 1))
        assertEquals(7, ReaderListLayout.scrollItemIndex(chapters, 1, 2))
        // 章 5 起始 = 4 + (3+2) = 9；b0 = item 10
        assertEquals(10, ReaderListLayout.scrollItemIndex(chapters, 5, 0))
    }

    @Test
    fun `scrollItemIndex coerces out-of-range block`() {
        assertEquals(2, ReaderListLayout.scrollItemIndex(chapters, 0, 99)) // → 末块 b1
    }

    @Test
    fun `scrollItemIndex returns -1 when chapter absent`() {
        assertEquals(-1, ReaderListLayout.scrollItemIndex(chapters, 3, 0))
    }

    @Test
    fun `scrollChapterBlock reverse maps block items`() {
        assertEquals(0 to 0, ReaderListLayout.scrollChapterBlock(chapters, 1))
        assertEquals(0 to 1, ReaderListLayout.scrollChapterBlock(chapters, 2))
        assertEquals(1 to 2, ReaderListLayout.scrollChapterBlock(chapters, 7))
        assertEquals(5 to 0, ReaderListLayout.scrollChapterBlock(chapters, 10))
    }

    @Test
    fun `scrollChapterBlock maps title and separator items`() {
        assertEquals(0 to 0, ReaderListLayout.scrollChapterBlock(chapters, 0)) // 章 0 标题
        assertEquals(0 to 1, ReaderListLayout.scrollChapterBlock(chapters, 3)) // 章 0 分隔符 → 末块
        assertEquals(1 to 0, ReaderListLayout.scrollChapterBlock(chapters, 4)) // 章 1 标题
    }

    @Test
    fun `scrollChapterBlock returns minus1 pair beyond loaded range`() {
        assertEquals(-1 to -1, ReaderListLayout.scrollChapterBlock(chapters, 12)) // 全部 item = 4+5+3 = 12
    }

    @Test
    fun `empty chapters degenerate safely`() {
        assertEquals(-1, ReaderListLayout.scrollItemIndex(emptyList(), 0, 0))
        assertEquals(-1 to -1, ReaderListLayout.scrollChapterBlock(emptyList(), 0))
    }
}

package com.juziss.localmediahub.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 与 server/internal/service/bookparser/txt_test.go 的确定性用例保持同步。
 * 两侧断言一致：改任一端的分章行为时，先同步这里的期望值与 Go 侧测试。
 */
class TxtChapterParserTest {

    private fun titles(text: String): List<String> =
        TxtChapterParser.splitChapters(text, "fallback.txt").map { it.title }

    @Test
    fun comprehensiveChapterPatterns() {
        val text = "楔子～开启故事\n第一章　龙回故乡\n第２章：走马上任\n第229章-尾声\n" +
            "第０３章、第一次性交示范课\n第4章我成了神手\n第 5 章 我成了神手\n第6章\n后记"
        val chapters = TxtChapterParser.splitChapters(text, "fallback.txt")
        assertEquals(
            listOf(
                "楔子～开启故事",
                "第一章　龙回故乡",
                "第２章：走马上任",
                "第229章-尾声",
                "第０３章、第一次性交示范课",
                "第4章我成了神手",
                "第 5 章 我成了神手",
                "第6章",
                "后记",
            ),
            chapters.map { it.title },
        )
    }

    @Test
    fun bodyTextMentioningChapterIsNotSplit() {
        // 旧实现的第一条正则（^[\s\S]*?第..章 + find）会把正文里的"第三章"误判成章节标题
        val text = "第一章 开始\n他翻到了第三章，陷入了回忆。\n第二章 继续\n故事继续展开。"
        val chapters = TxtChapterParser.splitChapters(text, "fallback.txt")
        assertEquals(listOf("第一章 开始", "第二章 继续"), chapters.map { it.title })
    }

    @Test
    fun expandedBracketPatterns() {
        val text = "(1) 第一节内容\n这是(1)正文\n（二） 第二节内容\n这是（二）正文\n" +
            "3 第三节内容\n这是3正文\n（ 4 ） 第四节内容\n这是4正文"
        assertEquals(
            listOf("(1) 第一节内容", "（二） 第二节内容", "3 第三节内容", "（ 4 ） 第四节内容"),
            titles(text),
        )
    }

    @Test
    fun inlineChapterSuffix() {
        // 章节名内嵌在正文行末尾（server txt_test.go TestTxtInlineChapterSuffix）
        val text = "第一章\n这是第一章的内容，讲了很多故事。    第二章\n这是第二章的内容，继续讲故事啊。    第三章\n这是第三章的内容。"
        val chapters = TxtChapterParser.splitChapters(text, "inline.txt")
        assertEquals(listOf("第一章", "第二章", "第三章"), chapters.map { it.title })
        // 章节边界落在内嵌章节 token 处，第一章切片不得包含第三章标题
        val slice0 = text.substring(chapters[0].charStart, chapters[0].charEnd)
        assertTrue(slice0.contains("第一章"))
        assertTrue(!slice0.contains("第三章"))
    }

    @Test
    fun volumeHierarchy() {
        val text = "序章 准备\n准备正文\n第一卷 创世纪\n第1章 诞生\n诞生正文\n" +
            "第2章 崛起\n崛起正文\n第二卷 英雄传\n第3章 征程\n征程正文"
        val chapters = TxtChapterParser.splitChapters(text, "vol.txt")
        assertEquals(4, chapters.size)

        assertEquals("序章 准备", chapters[0].title)
        assertEquals("", chapters[0].volume)
        assertEquals(-1, chapters[0].volIndex)

        assertEquals("第1章 诞生", chapters[1].title)
        assertEquals("第一卷 创世纪", chapters[1].volume)
        assertEquals(0, chapters[1].volIndex)

        assertEquals("第2章 崛起", chapters[2].title)
        assertEquals("第一卷 创世纪", chapters[2].volume)
        assertEquals(0, chapters[2].volIndex)

        assertEquals("第3章 征程", chapters[3].title)
        assertEquals("第二卷 英雄传", chapters[3].volume)
        assertEquals(1, chapters[3].volIndex)
    }

    @Test
    fun compoundVolumeChapterHeading() {
        // 《重返乐园》回归：同一行携带卷号+章号（"第一卷 第1章" 裸文本、
        // "【第二卷 第1章】" 带括号）必须识别为该卷的章节，而非卷标记。
        // 旧实现里这类行被卷分支整行吞掉，全书章节全部丢失。
        val text = "《重返乐园》（全105章完结）\n\n【第一卷 卷简介】\n简介正文。\n\n" +
            "第一卷 第1章\n开篇正文内容。\n一回到家，妈妈就一头扑到自己的床上：“哎呀，今天累死了。”\n本章正文结束。\n\n" +
            "第一卷 第2章\n承转正文内容。\n\n【第二卷 卷简介】\n简介正文。\n\n" +
            "【第二卷 第1章】\n新卷开篇内容。\n\n【第二卷 第2章】\n新卷次章内容。"
        val chapters = TxtChapterParser.splitChapters(text, "compound.txt")
        assertEquals(5, chapters.size) // 序言 + 4 个复合章节标题

        assertEquals("序言", chapters[0].title)

        assertEquals("第一卷 第1章", chapters[1].title)
        assertEquals("第一卷", chapters[1].volume)
        assertEquals(0, chapters[1].volIndex)

        assertEquals("第一卷 第2章", chapters[2].title)
        assertEquals("第一卷", chapters[2].volume)
        assertEquals(0, chapters[2].volIndex)

        assertEquals("【第二卷 第1章】", chapters[3].title)
        assertEquals("第二卷", chapters[3].volume)
        assertEquals(1, chapters[3].volIndex)

        assertEquals("【第二卷 第2章】", chapters[4].title)
        assertEquals("第二卷", chapters[4].volume)
        assertEquals(1, chapters[4].volIndex)
    }

    @Test
    fun bareNumeralProseIsNotChapter() {
        // 《重返乐园》回归：裸数字+量词开头的正文行（"一回到家…"、"一部分…"）
        // 不得识别为章节标题。无「第」前缀时量词后必须跟分隔符或行尾。
        val text = "第1章 开始\n一回到家，妈妈就一头扑到自己的床上：“哎呀，今天累死了。”\n" +
            "一部分记忆涌上心头。\n第2章 结束\n正文内容。"
        assertEquals(listOf("第1章 开始", "第2章 结束"), titles(text))
    }

    @Test
    fun volumeFallbackPromotion() {
        // 只有卷标记（如 ━━━ 上 ━━━）时，卷提升为章节
        val text = "━━━ 上 ━━━\n上篇正文内容\n━━━ 中 ━━━\n中篇正文内容\n━━━ 下 ━━━\n下篇正文内容"
        assertEquals(listOf("━━━ 上 ━━━", "━━━ 中 ━━━", "━━━ 下 ━━━"), titles(text))
    }

    @Test
    fun decorativeAndBareNumeralPatterns() {
        val text = "＊＊＊（１）\n这是第一章的正文内容。\n　　一 邂逅\n这是第二章的正文内容。\n" +
            "【番外：测试分段】\n这是第三章的正文内容。"
        assertEquals(listOf("＊＊＊（１）", "一 邂逅", "【番外：测试分段】"), titles(text))
    }

    @Test
    fun chapterWithoutDiPrefix() {
        val text = "12章 标题十二\n这是12章正文\n13章 彻底决裂\n这是13章正文\n14章 睡梦中被侵犯\n这是14章正文"
        assertEquals(
            listOf("12章 标题十二", "13章 彻底决裂", "14章 睡梦中被侵犯"),
            titles(text),
        )
    }

    @Test
    fun noChapterMatchBecomesSingleChapter() {
        val chapters = TxtChapterParser.splitChapters("这是一本没有任何章节标记的书。", "plain.txt")
        assertEquals(1, chapters.size)
        assertEquals("plain.txt", chapters[0].title)
        assertEquals(0, chapters[0].charStart)
        assertEquals("这是一本没有任何章节标记的书。".length, chapters[0].charEnd)
    }

    @Test
    fun endMarkerAndDuplicateFilter() {
        val text = "第一章 开始\n正文1\n第一章 完\n第二章 生死之交\n作者寄语\n" +
            "第二章 生死之交 2004/09/29\n正文2\n第二章完评分完成：已经给 接触零距离 加上30银元！"
        val chapters = TxtChapterParser.splitChapters(text, "end.txt")
        // 与 server 实际输出逐字一致（Go 侧 txt_test.go 同一样本断言较宽松）：
        // "第一章 完" 命中章节规则（完? 可选）；同 key 3000 字内后者替换前者
        assertEquals(
            listOf("第一章 开始", "第一章 完", "第二章完评分完成：已经给 接触零距离 加上30银元！"),
            chapters.map { it.title },
        )
    }

    @Test
    fun preambleBecomesXuYanChapter() {
        val text = "书名：测试\n作者：某人\n\n第一章 启程\n正文内容。"
        val chapters = TxtChapterParser.splitChapters(text, "pre.txt")
        assertEquals(listOf("序言", "第一章 启程"), chapters.map { it.title })
        // 序言章覆盖文件头，第一章从标题行开始
        assertEquals("书名：测试\n作者：某人\n\n", text.substring(chapters[0].charStart, chapters[0].charEnd))
        assertTrue(text.substring(chapters[1].charStart).startsWith("第一章 启程"))
    }

    @Test
    fun crlfInputOffsetsStayAligned() {
        // CRLF 由入口归一化，偏移不会随行数漂移
        val text = "第一章 A\r\nline1\r\nline2\r\nline3\r\nline4\r\nline5\r\n" +
            "line6\r\nline7\r\nline8\r\nline9\r\nline10\r\n第二章 B\r\n这是第二章的正文内容"
        val chapters = TxtChapterParser.splitChapters(text, "crlf.txt")
        assertEquals(2, chapters.size)
        val normalized = text.replace("\r\n", "\n")
        assertTrue(normalized.substring(chapters[1].charStart).startsWith("第二章 B"))
    }

    @Test
    fun isChapterHeaderSpecialTitles() {
        assertTrue(TxtChapterParser.isChapterHeader("【第123章 决战】"))
        assertTrue(TxtChapterParser.isChapterHeader("=== 第5章 启程 ==="))
        assertTrue(TxtChapterParser.isChapterHeader("102. 再次重逢"))
        assertTrue(TxtChapterParser.isChapterHeader("妈妈是高级妓女 一、这就是工作"))
        assertTrue(!TxtChapterParser.isChapterHeader("这是一普通正文句子。"))
    }
}

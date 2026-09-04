package com.juziss.localmediahub.data

/**
 * 阅读器 LazyColumn 全局 item 索引 ↔ (章, 章内 block) 的双向映射（纯函数）。
 *
 * 布局契约（TextReaderScreen）：
 * - 分章模式：item 0 = 章标题，item 1..N = blocks，末尾 1 个 ❖。
 * - 滚动模式：每章 [标题, blocks..., 分隔符] 共 blocks.size + 2 个 item，
 *   列表末尾可能附加加载指示器（位于所有章之后）。
 */
object ReaderListLayout {

    /** 分章模式中章标题占据的前置 item 数。block b 对应全局 item 1 + b。 */
    const val CHAPTER_MODE_HEADER_ITEMS = 1

    /** 滚动模式：目标 (章, 段) 的全局 item 索引。目标章不在已加载列表返回 -1。 */
    fun scrollItemIndex(chapters: List<ScrollModeChapter>, chapterIndex: Int, blockIndex: Int): Int {
        var base = 0
        for (ch in chapters) {
            if (ch.chapterIndex == chapterIndex) {
                val lastBlock = (ch.blocks.size - 1).coerceAtLeast(0)
                return base + 1 + blockIndex.coerceIn(0, lastBlock)
            }
            base += ch.blocks.size + 2
        }
        return -1
    }

    /** 滚动模式：全局 item 索引 → (章, 章内 block)。超出已加载范围返回 (-1, -1)。 */
    fun scrollChapterBlock(chapters: List<ScrollModeChapter>, itemIndex: Int): Pair<Int, Int> {
        var base = 0
        for (ch in chapters) {
            val size = ch.blocks.size + 2
            if (itemIndex < base + size) {
                val local = itemIndex - base
                return when {
                    local == 0 -> ch.chapterIndex to 0                                  // 章标题
                    local <= ch.blocks.size -> ch.chapterIndex to (local - 1)           // 段落
                    else -> ch.chapterIndex to (ch.blocks.size - 1).coerceAtLeast(0)    // 分隔符 → 末段
                }
            }
            base += size
        }
        return -1 to -1
    }
}

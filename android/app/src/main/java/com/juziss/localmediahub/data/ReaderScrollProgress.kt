package com.juziss.localmediahub.data

/**
 * 滚动阅读模式的整体进度：把「首个可见 item 索引 + item 内偏移」映射为
 * 全书百分比。每个章节占用 `blocks.size + 2` 个列表项（章节头 + 正文 + 底部）。
 *
 * 纯函数，供 [com.juziss.localmediahub.ui.screen.TextReaderScreen] 在
 * `derivedStateOf` 内调用，避免组合期读取 `LazyListState.layoutInfo`。
 */
internal fun computeOverallPercent(
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    firstVisibleItemHeight: Int,
    scrollChapters: List<ScrollModeChapter>,
    totalChaptersCount: Int,
    fallbackChapterIndex: Int,
): Int {
    if (scrollChapters.isEmpty() || totalChaptersCount <= 0) return 0

    var accumulatedItems = 0
    var currentChIdx = fallbackChapterIndex
    var chFraction = 0f

    for (ch in scrollChapters) {
        val itemsInCh = ch.blocks.size + 2
        if (firstVisibleIndex < accumulatedItems + itemsInCh) {
            currentChIdx = ch.chapterIndex
            val itemInCh = firstVisibleIndex - accumulatedItems
            val subItemFrac = (
                firstVisibleScrollOffset.toFloat() / firstVisibleItemHeight.coerceAtLeast(1)
                ).coerceIn(0f, 1f)
            chFraction = (itemInCh.toFloat() + subItemFrac) / itemsInCh.coerceAtLeast(1)
            break
        }
        accumulatedItems += itemsInCh
    }

    val continuousBookPos = currentChIdx.toFloat() + chFraction.coerceIn(0f, 1f)
    return ((continuousBookPos / totalChaptersCount.coerceAtLeast(1)) * 100).toInt().coerceIn(0, 100)
}

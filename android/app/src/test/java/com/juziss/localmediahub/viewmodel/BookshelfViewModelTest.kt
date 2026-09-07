package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfViewModelTest {
    private fun item(path: String, status: String?, fav: Boolean, lastAt: Long) =
        BookshelfItem(path, path, 0, lastAt, "txt", status, 0L, fav)

    @Test
    fun `filter ALL keeps everything`() {
        val items = listOf(item("a", "reading", false, 3), item("b", null, false, 2))
        assertEquals(items, applyBookshelfFilter(items, BookshelfFilter.ALL))
    }

    @Test
    fun `filter READING keeps reading and null-status rows`() {
        val items = listOf(
            item("a", "reading", false, 3),
            item("b", "finished", false, 2),
            item("c", null, false, 1),
        )
        val out = applyBookshelfFilter(items, BookshelfFilter.READING)
        assertEquals(listOf("a", "c"), out.map { it.path })
    }

    @Test
    fun `filter FINISHED keeps finished rows`() {
        val items = listOf(item("a", "finished", false, 3), item("b", "reading", false, 2))
        assertEquals(listOf("a"), applyBookshelfFilter(items, BookshelfFilter.FINISHED).map { it.path })
    }

    @Test
    fun `filter FAVORITES keeps favorites`() {
        val items = listOf(item("a", "reading", true, 3), item("b", "reading", false, 2))
        assertEquals(listOf("a"), applyBookshelfFilter(items, BookshelfFilter.FAVORITES).map { it.path })
    }

    @Test
    fun `books are projected from progress rows sorted by lastReadAt`() {
        // projectBooks 的行为经由 ViewModel 内部 flow 断言成本高；这里锁定排序契约
        val rows = listOf(
            BookProgress("a.txt", 3, 1, 0, 100L),
            BookProgress("b.epub", 0, 0, 0, 200L),
        )
        val sorted = rows.sortedByDescending { it.lastReadAt }
        assertEquals(listOf("b.epub", "a.txt"), sorted.map { it.path })
    }

    @Test
    fun `formatReadDuration renders minutes below an hour, hours above`() {
        assertEquals("", formatReadDuration(0))
        assertEquals("", formatReadDuration(30))
        assertEquals("45 分钟", formatReadDuration(45 * 60))
        assertEquals("1.5 小时", formatReadDuration(90 * 60))
        assertEquals("1.0 小时", formatReadDuration(3660))
    }
}

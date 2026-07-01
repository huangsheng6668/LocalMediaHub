package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseSorterTest {
    private fun file(name: String, size: Long = 0L, time: String = "") =
        MediaFile(name, "p/$name", "p/$name", size, time, "image", ".jpg")
    private fun folder(name: String, time: String = "") =
        Folder(name, "p/$name", "p/$name", false, time)

    @Test
    fun `compareNatural orders numerically`() {
        assertTrue(compareNatural("2", "10") < 0)
        assertTrue(compareNatural("img2", "img10") < 0)
        assertTrue(compareNatural("a", "b") < 0)
        assertEquals(0, compareNatural("x", "x"))
        assertTrue(compareNatural("10", "2") > 0)
    }

    @Test
    fun `extractLeadingNumber parses leading digits`() {
        assertEquals(7.0, extractLeadingNumber("007_gjco"))
        assertEquals(10.0, extractLeadingNumber("10"))
        assertNull(extractLeadingNumber("abc"))
        assertNull(extractLeadingNumber(""))
    }

    @Test
    fun `sortFiles NAME_ASC is natural order`() {
        val sorted = BrowseSorter.sortFiles(
            listOf(file("img10.jpg"), file("img2.jpg"), file("img1.jpg")),
            SortOrder.NAME_ASC,
        )
        assertEquals(listOf("img1.jpg", "img2.jpg", "img10.jpg"), sorted.map { it.name })
    }

    @Test
    fun `sortFiles NAME_DESC is reverse natural order`() {
        val sorted = BrowseSorter.sortFiles(
            listOf(file("img1.jpg"), file("img2.jpg"), file("img10.jpg")),
            SortOrder.NAME_DESC,
        )
        assertEquals(listOf("img10.jpg", "img2.jpg", "img1.jpg"), sorted.map { it.name })
    }

    @Test
    fun `sortFiles SIZE_ASC and SIZE_DESC by size`() {
        val files = listOf(file("a", size = 30), file("b", size = 10), file("c", size = 20))
        assertEquals(
            listOf("b", "c", "a"),
            BrowseSorter.sortFiles(files, SortOrder.SIZE_ASC).map { it.name },
        )
        assertEquals(
            listOf("a", "c", "b"),
            BrowseSorter.sortFiles(files, SortOrder.SIZE_DESC).map { it.name },
        )
    }

    @Test
    fun `sortFolders ignores SIZE orders`() {
        val folders = listOf(folder("b"), folder("a"), folder("c"))
        assertEquals(
            listOf("b", "a", "c"),
            BrowseSorter.sortFolders(folders, SortOrder.SIZE_ASC).map { it.name },
        )
    }

    @Test
    fun `sortFolders TIME_DESC by modifiedTime`() {
        val folders = listOf(folder("old", "2024-01-01"), folder("new", "2024-12-31"))
        assertEquals(
            listOf("new", "old"),
            BrowseSorter.sortFolders(folders, SortOrder.TIME_DESC).map { it.name },
        )
    }

    @Test
    fun `sort handles empty and single-element lists`() {
        assertEquals(emptyList<MediaFile>(), BrowseSorter.sortFiles(emptyList(), SortOrder.NAME_ASC))
        assertEquals(
            listOf("only"),
            BrowseSorter.sortFiles(listOf(file("only")), SortOrder.NAME_ASC).map { it.name },
        )
    }
}

package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.*
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseLibraryFiltersTest {
    private val folders = listOf(Folder("comics", "/m/comics", "comics"), Folder("other", "/m/other", "other"))
    private val files = listOf(
        MediaFile("a.txt", "/m/a.txt", "a.txt", 1, "", "text", ".txt"),
        MediaFile("b.txt", "/m/b.txt", "b.txt", 1, "", "text", ".txt"),
        MediaFile("c.txt", "/m/c.txt", "c.txt", 1, "", "text", ".txt"),
        MediaFile("d.txt", "/m/d.txt", "d.txt", 1, "", "text", ".txt"),
        MediaFile("v.mp4", "/m/v.mp4", "v.mp4", 1, "", "video", ".mp4"),
    )
    private val favorites = setOf("b.txt", "/m/comics") // 文件 identity=relativePath，目录 identity=path
    private val states = mapOf(
        "/m/a.txt" to LibraryDecoration("/m/a.txt", ReadingStatus.READING, 42.5, 1),
        "/m/b.txt" to LibraryDecoration("/m/b.txt", ReadingStatus.FINISHED, 100.0, 2),
        "/m/c.txt" to LibraryDecoration("/m/c.txt", ReadingStatus.UNREAD, 0.0, 3),
    )

    @Test fun favoritesOnlyMatchesCurrentListing() {
        val (f, l) = applyBrowseFilters(folders, files, favorites, true, null, states)
        assertEquals(listOf("/m/comics"), f.map { it.path })
        assertEquals(listOf("/m/b.txt"), l.map { it.path })
    }
    @Test fun statusFilterHidesFoldersAndNonText() {
        val (f, l) = applyBrowseFilters(folders, files, emptySet(), false, ReadingStatus.READING, states)
        assertEquals(0, f.size)
        assertEquals(listOf("/m/a.txt"), l.map { it.path })
    }
    @Test fun unreadIncludesMissingState() {
        val (f, l) = applyBrowseFilters(folders, files, emptySet(), false, ReadingStatus.UNREAD, states)
        assertEquals(listOf("c.txt", "d.txt").sorted(), l.map { it.relativePath }.sorted())
    }
    @Test fun combinedIntersect() {
        val (f, l) = applyBrowseFilters(folders, files, favorites, true, ReadingStatus.FINISHED, states)
        assertEquals(0, f.size)
        assertEquals(listOf("/m/b.txt"), l.map { it.path })
    }
}

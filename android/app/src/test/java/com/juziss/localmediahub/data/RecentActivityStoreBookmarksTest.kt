package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RecentActivityStoreBookmarksTest {

    private lateinit var store: RecentActivityStore

    @Before
    fun setUp() {
        store = RecentActivityStore(ApplicationProvider.getApplicationContext())
        runBlocking {
            store.clearAllBookProgress()
            store.clearAllReaderSettings()
            store.clearAllBookmarks()
        }
    }

    @Test
    fun add_then_get_returns_entry() = runBlocking {
        val bm = Bookmark("/book.txt", 0, 3, "preview", 1000L)
        val ok = store.addBookmark(bm)
        assertTrue(ok)
        val list = store.getBookmarks("/book.txt")
        assertEquals(listOf(bm), list)
    }

    @Test
    fun duplicate_add_returns_false_and_does_not_grow_list() = runBlocking {
        val bm = Bookmark("/book.txt", 0, 3, "preview", 1000L)
        assertTrue(store.addBookmark(bm))
        assertFalse(store.addBookmark(bm.copy(createdAt = 2000L)))
        val list = store.getBookmarks("/book.txt")
        assertEquals(1, list.size)
        // Original createdAt preserved (no upsert)
        assertEquals(1000L, list.single().createdAt)
    }

    @Test
    fun delete_removes_matching_bookmark() = runBlocking {
        val bm1 = Bookmark("/book.txt", 0, 3, "p1", 1000L)
        val bm2 = Bookmark("/book.txt", 1, 5, "p2", 2000L)
        store.addBookmark(bm1)
        store.addBookmark(bm2)
        store.deleteBookmark(bm1)
        assertEquals(listOf(bm2), store.getBookmarks("/book.txt"))
    }

    @Test
    fun clear_bookmarks_for_one_book_leaves_others() = runBlocking {
        store.addBookmark(Bookmark("/a.txt", 0, 0, "a", 1L))
        store.addBookmark(Bookmark("/b.txt", 0, 0, "b", 2L))
        store.clearBookmarks("/a.txt")
        assertEquals(emptyList<Bookmark>(), store.getBookmarks("/a.txt"))
        assertEquals(1, store.getBookmarks("/b.txt").size)
    }

    @Test
    fun bookmarks_flow_emits_on_add_and_delete() = runBlocking {
        val flow = store.getBookmarksFlow("/book.txt")
        assertEquals(emptyList<Bookmark>(), flow.first())

        store.addBookmark(Bookmark("/book.txt", 0, 0, "p", 1L))
        assertEquals(1, flow.first().size)

        store.deleteBookmark(Bookmark("/book.txt", 0, 0, "p", 1L))
        assertEquals(emptyList<Bookmark>(), flow.first())
    }
}

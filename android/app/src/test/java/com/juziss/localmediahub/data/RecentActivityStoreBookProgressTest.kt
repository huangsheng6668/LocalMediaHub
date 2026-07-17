package com.juziss.localmediahub.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Robolectric tests for [RecentActivityStore] book-progress persistence.
 *
 * Test-isolation notes:
 * The DataStore delegate `Context.recentActivityDataStore` is a process-wide
 * singleton; once instantiated, its in-memory actor cache persists across
 * tests even if the on-disk datastore directory is deleted. File deletion
 * alone is therefore insufficient — earlier tests' writes would leak into
 * later tests through the cached actor. The reliable reset is to clear the
 * `book_progress` key through the live DataStore instance in [setUp], which
 * mirrors the approach in `ServerConfigStoreAuthTokenTest` (calling
 * `clearConfig()` per test). The on-disk file cleanup is still kept as
 * belt-and-suspenders so the very first edit of each test starts from an
 * absent file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RecentActivityStoreBookProgressTest {

    private lateinit var store: RecentActivityStore
    private lateinit var ctx: Context

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext<Context>()
        deleteDatastoreFiles(ctx)
        store = RecentActivityStore(ctx)
        // Clear the key through the live DataStore instance so the in-memory
        // actor cache is reset to empty — file deletion alone cannot do this
        // because the delegate is a process-wide singleton.
        runBlocking {
            withContext(Dispatchers.IO) {
                store.clearAllBookProgress()
            }
        }
    }

    @Test
    fun saveAndGetRoundTrip() = runTest {
        val p = BookProgress(
            path = "/x/novel.txt",
            chapterIndex = 3,
            scrollOffsetPx = 123,
            lastReadAt = 1000L,
        )
        withContext(Dispatchers.IO) {
            store.saveBookProgress(p)
        }
        val got = withContext(Dispatchers.IO) {
            store.getBookProgress("/x/novel.txt")
        }
        assertEquals(p, got)
    }

    @Test
    fun clearRemovesEntry() = runTest {
        withContext(Dispatchers.IO) {
            store.saveBookProgress(BookProgress("/x/a.txt", 1, 0, 1L))
            store.clearBookProgress("/x/a.txt")
        }
        val got = withContext(Dispatchers.IO) {
            store.getBookProgress("/x/a.txt")
        }
        assertNull(got)
    }

    @Test
    fun getAllReturnsSortedByLastReadAtDesc() = runTest {
        withContext(Dispatchers.IO) {
            store.saveBookProgress(BookProgress("/x/old.txt", 0, 0, 100L))
            store.saveBookProgress(BookProgress("/x/new.txt", 0, 0, 999L))
        }
        val all = withContext(Dispatchers.IO) {
            store.getAllBookProgress()
        }
        assertEquals(2, all.size)
        assertEquals("/x/new.txt", all[0].path)
        assertEquals("/x/old.txt", all[1].path)
    }

    @Test
    fun getAllFlowEmitsSortedByLastReadAtDesc() = runTest {
        withContext(Dispatchers.IO) {
            store.saveBookProgress(BookProgress("/x/old.txt", 0, 0, 100L))
            store.saveBookProgress(BookProgress("/x/new.txt", 0, 0, 999L))
        }
        val all = withContext(Dispatchers.IO) {
            store.getAllBookProgressFlow().first()
        }
        assertEquals(2, all.size)
        assertEquals("/x/new.txt", all[0].path)
    }

    @Test
    fun getBookProgressReturnsNullWhenEmpty() = runTest {
        val got = withContext(Dispatchers.IO) {
            store.getBookProgress("/x/missing.txt")
        }
        assertNull(got)
    }

    @Test
    fun saveOverwritesExistingEntryForSamePath() = runTest {
        withContext(Dispatchers.IO) {
            store.saveBookProgress(BookProgress("/x/same.txt", 1, 10, 100L))
            store.saveBookProgress(BookProgress("/x/same.txt", 5, 200, 999L))
        }
        val got = withContext(Dispatchers.IO) {
            store.getBookProgress("/x/same.txt")
        }
        assertEquals(5, got?.chapterIndex)
        assertEquals(200, got?.scrollOffsetPx)
        assertEquals(999L, got?.lastReadAt)
        // Map should still contain only one entry for this path.
        val all = withContext(Dispatchers.IO) {
            store.getAllBookProgress()
        }
        assertEquals(1, all.size)
    }

    private fun deleteDatastoreFiles(context: Context) {
        try {
            val datastoreDir = context.filesDir.resolve("datastore")
            if (datastoreDir.exists()) {
                datastoreDir.deleteRecursively()
            }
            TimeUnit.MILLISECONDS.sleep(50)
        } catch (_: Exception) {
            // best-effort cleanup; if deletion fails the test will surface
            // the symptom rather than this swallow.
        }
    }
}

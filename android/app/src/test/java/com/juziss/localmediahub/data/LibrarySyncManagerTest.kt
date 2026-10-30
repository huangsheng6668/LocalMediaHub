package com.juziss.localmediahub.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.ble.TestBleFixtures
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibrarySyncManagerTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var favoritesStore: FavoritesStore
    private lateinit var recentActivityStore: RecentActivityStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        try {
            context.filesDir.resolve("datastore").takeIf { it.exists() }?.deleteRecursively()
        } catch (_: Exception) {}
        favoritesStore = FavoritesStore(context, CoroutineScope(Dispatchers.Unconfined))
        recentActivityStore = RecentActivityStore(context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun ensureStartedIsIdempotentPerProcess() = runTest(dispatcher) {
        val repo = MediaRepository(
            OkHttpClient(),
            ServerConfig(),
            TestBleFixtures.disabledBleController(),
            BleTransportFallback(),
        )
        val mgr = LibrarySyncManager(
            favoritesStore, recentActivityStore,
            ReadingTimeStore(ApplicationProvider.getApplicationContext()),
            repo,
            CoroutineScope(dispatcher),
        )
        mgr.ensureStarted()
        mgr.ensureStarted() // 二次调用不重复（AtomicBoolean）
        advanceUntilIdle()
        // 无网络：上报失败，迁移未完成，但不崩溃
        assertFalse(favoritesStore.isProgressMigrationDone())
    }

    @Test
    fun mergeWithServerFavoritesPreservesOfflineFavoritesWithoutSubscribedStateFlow() = runTest(dispatcher) {
        val localFile = MediaFile("offline.txt", "/m/offline.txt", "offline.txt", 10L, "2026-01-01", "text", ".txt")
        val remoteFile = MediaFile("remote.txt", "/m/remote.txt", "remote.txt", 20L, "2026-01-02", "text", ".txt")

        // User adds a favorite while offline; no UI collector is subscribed to favoriteEntries.
        favoritesStore.addFavorite(localFile, isSystemBrowse = false)

        val snapshotBeforeMerge = favoritesStore.readFavoriteEntriesSnapshot()
        assertEquals(1, snapshotBeforeMerge.size)
        assertEquals("offline.txt", snapshotBeforeMerge[0].identity)

        // Server returns its own favorite list that doesn't yet know about offline.txt
        val merged = favoritesStore.mergeWithServerFavorites(
            listOf(
                ServerFavorite(
                    path = "/m/remote.txt",
                    isDir = false,
                    isSystem = false,
                    title = "remote.txt",
                    mediaType = "text",
                    snapshot = FavoriteEntry(file = remoteFile, addedAt = 500L),
                    addedAt = 500L,
                )
            )
        )

        val identities = merged.map { it.identity }.toSet()
        assertEquals(setOf("offline.txt", "remote.txt"), identities)
        assertEquals(
            setOf("offline.txt", "remote.txt"),
            favoritesStore.readFavoriteEntriesSnapshot().map { it.identity }.toSet()
        )
    }

    @Test
    fun resolveReadingTimeFlushStatePreservesFinishedAndLocalProgressFallback() {
        val finishedServer = ReadingStateFull(
            chapterIndex = 12,
            paraIndex = 34,
            percent = 1.0,
            finished = true,
            manualStatus = "finished",
            lastReadAt = 10_000L,
        )
        val state1 = resolveReadingTimeFlushState(
            serverState = finishedServer,
            localProgress = null,
            nowMs = 99_000L,
        )
        assertTrue("finished status on server must be preserved when flushing reading time", state1.finished)
        assertEquals(12, state1.chapterIndex)
        assertEquals(34, state1.paraIndex)
        assertEquals(10_000L, state1.lastReadAt)

        // When server has no state yet, fall back to local BookProgress instead of resetting to chapter 0
        val localProg = BookProgress(
            path = "/m/book.txt",
            chapterIndex = 5,
            blockIndex = 18,
            scrollOffsetPx = 120,
            lastReadAt = 25_000L,
        )
        val state2 = resolveReadingTimeFlushState(
            serverState = null,
            localProgress = localProg,
            nowMs = 99_000L,
        )
        assertFalse(state2.finished)
        assertEquals(5, state2.chapterIndex)
        assertEquals(18, state2.paraIndex)
        assertEquals(25_000L, state2.lastReadAt)
    }

    @Test
    fun mapBoundedRunsConcurrentUpToLimitAndPreservesOrder() = runTest(dispatcher) {
        val inFlight = AtomicInteger(0)
        val maxInFlight = AtomicInteger(0)
        val start = currentTime

        val result = (1..20).toList().mapBounded(limit = 4) { item ->
            maxInFlight.updateAndGet { maxOf(it, inFlight.incrementAndGet()) }
            delay(20)
            inFlight.decrementAndGet()
            item * 2
        }

        val elapsed = currentTime - start
        // 20 items at 4 concurrent / 20ms each = 5 waves = 100ms (serial would be 400ms)
        assertEquals(100L, elapsed)
        assertEquals(4, maxInFlight.get())
        assertEquals((1..20).map { it * 2 }, result)
    }

    @Test
    fun mapBoundedHandlesEmptyInput() = runTest(dispatcher) {
        val result = emptyList<Int>().mapBounded(limit = 4) { it * 2 }
        assertEquals(emptyList<Int>(), result)
    }
}

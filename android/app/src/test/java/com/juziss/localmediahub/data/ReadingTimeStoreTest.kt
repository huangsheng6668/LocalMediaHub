package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReadingTimeStoreTest {
    @Test
    fun `addPending accumulates per path and takePendingUpto caps`() = runBlocking {
        val store = ReadingTimeStore(ApplicationProvider.getApplicationContext())
        store.addPending("a.txt", 2000)
        store.addPending("a.txt", 3000)
        assertEquals(3600L, store.takePendingUpto("a.txt", 3600))
        assertEquals(1400L, store.takePendingUpto("a.txt", 3600))
        assertEquals(0L, store.takePendingUpto("a.txt", 3600))
    }

    @Test
    fun `pending is tracked per path`() = runBlocking {
        val store = ReadingTimeStore(ApplicationProvider.getApplicationContext())
        store.addPending("a.txt", 2000)
        store.addPending("b.txt", 100)
        assertEquals(setOf("a.txt", "b.txt"), store.pendingPaths().toSet())
        assertEquals(2000L, store.takePendingUpto("a.txt", 3600))
        assertEquals(setOf("b.txt"), store.pendingPaths().toSet())
    }
}

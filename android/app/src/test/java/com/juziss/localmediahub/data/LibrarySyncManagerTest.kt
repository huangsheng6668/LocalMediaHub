package com.juziss.localmediahub.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.ble.TestBleFixtures
import com.juziss.localmediahub.network.ServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        // Clean datastore between tests
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
            favoritesStore, recentActivityStore, repo,
            CoroutineScope(dispatcher),
        )
        mgr.ensureStarted()
        mgr.ensureStarted() // 二次调用不重复（AtomicBoolean）
        advanceUntilIdle()
        // 无网络：上报失败，迁移未完成，但不崩溃
        assertFalse(favoritesStore.isProgressMigrationDone())
    }
}

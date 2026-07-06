package com.juziss.localmediahub.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.network.ServerConfig
import okhttp3.OkHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var context: Context
    private lateinit var serverConfigStore: ServerConfigStore
    private lateinit var serverConfig: ServerConfig

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext<Context>()
            ?: RuntimeEnvironment.getApplication()
        deleteDatastoreFiles()
        serverConfigStore = ServerConfigStore(context)
        serverConfig = ServerConfig(OkHttpClient())
    }

    @After
    fun tearDown() = runTest {
        deleteDatastoreFiles()
        Dispatchers.resetMain()
    }

    private fun deleteDatastoreFiles() {
        try {
            val datastoreDir = context.filesDir.resolve("datastore")
            if (datastoreDir.exists()) {
                datastoreDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    @Test
    fun `saved server config initializes retrofit before first refresh`() = runTest(dispatcher) {
        serverConfigStore.saveServerConfig("127.0.0.1", "1")

        // Round 19 C1: ServerConfig (network) is a Hilt @Singleton holding the
        // shared OkHttpClient + baseUrl state. No reflection reset needed — it
        // is recreated as a fresh instance per test.
        val httpClient = OkHttpClient()
        val viewModel = HomeViewModel(
            favoritesStore = FavoritesStore(context, CoroutineScope(Dispatchers.Unconfined)),
            recentActivityStore = RecentActivityStore(context),
            serverConfigStore = serverConfigStore,
            serverConfig = serverConfig,
            repository = MediaRepository(httpClient, serverConfig),
        )

        advanceUntilIdle()

        assertTrue(serverConfig.isInitialized())
        assertNotEquals(
            "ServerConfig not initialized.",
            viewModel.uiState.value.errorMessage,
        )
    }
}

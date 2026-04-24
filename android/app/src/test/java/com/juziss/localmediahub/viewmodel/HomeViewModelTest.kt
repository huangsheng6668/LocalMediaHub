package com.juziss.localmediahub.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.ServerConfig
import com.juziss.localmediahub.network.RetrofitClient
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
    private lateinit var serverConfig: ServerConfig

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext<Context>()
            ?: RuntimeEnvironment.getApplication()
        serverConfig = ServerConfig(context)
        resetRetrofitClient()
    }

    @After
    fun tearDown() = runTest {
        resetRetrofitClient()
        Dispatchers.resetMain()
    }

    @Test
    fun `saved server config initializes retrofit before first refresh`() = runTest(dispatcher) {
        serverConfig.clearConfig()
        serverConfig.saveServerConfig("127.0.0.1", "1")

        val viewModel = HomeViewModel(
            favoritesStore = FavoritesStore(context),
            recentActivityStore = RecentActivityStore(context),
            serverConfig = serverConfig,
        )

        advanceUntilIdle()

        assertTrue(RetrofitClient.isInitialized())
        assertNotEquals(
            "RetrofitClient not initialized. Call initialize() first.",
            viewModel.uiState.value.errorMessage,
        )
    }

    private fun resetRetrofitClient() {
        RetrofitClient::class.java.getDeclaredField("_retrofit").apply {
            isAccessible = true
            set(RetrofitClient, null)
        }
        RetrofitClient::class.java.getDeclaredField("_baseUrl").apply {
            isAccessible = true
            set(RetrofitClient, "")
        }
    }
}

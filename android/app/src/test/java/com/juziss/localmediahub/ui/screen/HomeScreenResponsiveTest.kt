package com.juziss.localmediahub.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.LibrarySyncManager
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.ble.BleTransportFallback
import com.juziss.localmediahub.ble.TestBleFixtures
import com.juziss.localmediahub.network.ServerConfig
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import com.juziss.localmediahub.viewmodel.HomeViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Task 7 — HomeScreen responsive padding smoke test.
 *
 * Renders the full HomeScreen (TopAppBar + body) under the DAY theme and
 * asserts the persistent "LocalMediaHub" title is displayed. This verifies
 * that the newly added `material3-window-size-class` import + the
 * null-guarded `calculateWindowSizeClass((context as? Activity)?.let { it })`
 * call do not crash composition. The production code casts `context as?
 * Activity` and only invokes `calculateWindowSizeClass` when non-null, so it
 * fails safe to Compact padding when the host is not an Activity. Under
 * Robolectric the host is a `ComponentActivity`, the cast succeeds, and a
 * real Activity window is provided so the actual window-size class is used.
 *
 * `HomeViewModel` is `@HiltViewModel` and cannot be resolved via the default
 * `viewModel()` factory inside a plain `ComponentActivity` (no Hilt entry
 * point). To keep this a fast JVM unit test we instantiate the VM directly,
 * wiring its real store/repository dependencies against the Robolectric
 * Activity's applicationContext. DataStore flows surface as empty lists, so
 * the screen renders the loading state — which is fine: the TopAppBar title
 * is always composed regardless of state.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenResponsiveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun home_screen_renders_in_compact_width() {
        val ctx = composeRule.activity.applicationContext
        val appScope: CoroutineScope = TestScope(SupervisorJob())
        val favStore = FavoritesStore(ctx, appScope)
        val actStore = RecentActivityStore(ctx)
        val repo = MediaRepository(
            okhttp3.OkHttpClient(),
            ServerConfig(),
            TestBleFixtures.disabledBleController(),
            BleTransportFallback(),
        )
        val viewModel = HomeViewModel(
            favoritesStore = favStore,
            recentActivityStore = actStore,
            serverConfigStore = ServerConfigStore(ctx),
            serverConfig = ServerConfig(),
            repository = repo,
            librarySyncManager = LibrarySyncManager(favStore, actStore, repo, appScope),
        )

        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                HomeScreen(
                    onOpenLibrary = {},
                    onResumeBrowse = {},
                    onOpenFavorites = {},
                    onOpenCollection = {},
                    onContinueWatching = {},
                    onOpenRecentMedia = {},
                    viewModel = viewModel,
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("LocalMediaHub").assertIsDisplayed()
    }
}

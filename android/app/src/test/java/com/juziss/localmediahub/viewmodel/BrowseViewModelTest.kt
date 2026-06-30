package com.juziss.localmediahub.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.juziss.localmediahub.data.DownloadManager
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BrowseViewModelTest {

    @Test
    fun `setShowFavoritesOnly keeps favorites separate from tag collections`() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val favoritesStore = FavoritesStore(appContext, CoroutineScope(Dispatchers.Unconfined))
        val recentActivityStore = RecentActivityStore(appContext)
        val downloadsStore = DownloadsStore(appContext)
        val repository = MediaRepository()
        val downloadManager = DownloadManager(appContext, repository, downloadsStore)
        val viewModel = BrowseViewModel(
            appContext = appContext,
            favoritesStore = favoritesStore,
            recentActivityStore = recentActivityStore,
            downloadsStore = downloadsStore,
            repository = repository,
            downloadManager = downloadManager,
        )

        viewModel.setShowFavoritesOnly(true)

        assertTrue(viewModel.showFavoritesOnly.value)
        assertFalse(viewModel.browseState.value is BrowseState.TagCollection)
    }
}

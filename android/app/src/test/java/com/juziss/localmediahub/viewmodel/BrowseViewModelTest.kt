package com.juziss.localmediahub.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowseViewModelTest {

    @Test
    fun `setShowFavoritesOnly keeps favorites separate from tag collections`() {
        val viewModel = BrowseViewModel()

        viewModel.setShowFavoritesOnly(true)

        assertTrue(viewModel.showFavoritesOnly.value)
        assertFalse(viewModel.browseState.value is BrowseState.TagCollection)
    }
}

package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Holds StateFlows shared across BrowseViewModel's delegate controllers.
 *
 * Round 18 refactor: BrowseViewModel was 749 lines mixing 8 concerns.
 * State that crosses delegate boundaries (e.g. currentPath is read by
 * Navigator + Favorites + Tags) lives here so each delegate can read/write
 * without coupling to the ViewModel itself.
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows as public StateFlows for backward compat.
 */
internal class BrowseSharedState {
    val browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val currentPath = MutableStateFlow("")
    val pathStack = MutableStateFlow<List<String>>(emptyList())
    val isSystemBrowse = MutableStateFlow(false)
    val rawFolders = MutableStateFlow<List<Folder>>(emptyList())
    val rawFiles = MutableStateFlow<List<MediaFile>>(emptyList())

    val folderSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val fileSortOrder = MutableStateFlow(SortOrder.NAME_ASC)

    val activeTagFilter = MutableStateFlow<Tag?>(null)
    val showFavoritesOnly = MutableStateFlow(false)

    fun emitBrowseError(message: String) {
        browseState.value = BrowseState.Error(message)
    }
}

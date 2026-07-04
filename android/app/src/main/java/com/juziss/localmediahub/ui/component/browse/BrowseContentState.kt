package com.juziss.localmediahub.ui.component.browse

import com.juziss.localmediahub.viewmodel.SortOrder

/**
 * Reactive state the browse content grid needs (sort order + navigation path
 * + scroll-restore target). Built once in BrowseScreen from collected flows
 * and passed down; BrowseContent no longer reads BrowseViewModel directly.
 */
data class BrowseContentState(
    val folderSort: SortOrder,
    val fileSort: SortOrder,
    val currentPath: String,
    val restoreScrollTo: String?,
)

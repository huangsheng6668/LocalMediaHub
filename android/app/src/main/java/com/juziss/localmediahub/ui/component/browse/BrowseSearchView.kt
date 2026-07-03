package com.juziss.localmediahub.ui.component.browse

import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.SearchContent
import com.juziss.localmediahub.viewmodel.SearchState

@Composable
internal fun BrowseSearchView(
    searchState: SearchState,
    searchQuery: String,
    onClearSearch: () -> Unit,
    onBrowseFolder: (path: String, name: String) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    getThumbnailUrl: (MediaFile) -> String,
    onFileLongClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    SearchContent(
        searchState = searchState,
        searchQuery = searchQuery,
        onFolderClick = { folder ->
            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
            onClearSearch()
            onBrowseFolder(path, folder.name)
        },
        onVideoClick = onVideoClick,
        onImageClick = { file ->
            val allImages = when (val state = searchState) {
                is SearchState.Results -> state.result.files.filter { it.mediaType == "image" }
                else -> emptyList()
            }
            onImageClick(file, allImages)
        },
        onToggleFavorite = onToggleFavorite,
        isFavorite = isFavorite,
        getThumbnailUrl = getThumbnailUrl,
        onFileLongClick = onFileLongClick,
        modifier = modifier,
    )
}

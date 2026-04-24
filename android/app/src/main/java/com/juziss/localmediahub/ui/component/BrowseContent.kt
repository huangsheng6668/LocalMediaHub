package com.juziss.localmediahub.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import com.juziss.localmediahub.viewmodel.SearchState
import kotlinx.coroutines.launch

@Composable
internal fun FavoritesContent(
    favoriteFiles: List<MediaFile>,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    getThumbnailUrl: (MediaFile) -> String,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel,
) {
    if (favoriteFiles.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.FavoriteBorder,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "No favorites yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Tap the heart icon on any file to add it here",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    val images = favoriteFiles.filter { it.mediaType == "image" }

    if (images.isNotEmpty() && favoriteFiles.all { it.mediaType == "image" }) {
        WaterfallImageGrid(
            images = images,
            onImageClick = { file -> onImageClick(file, favoriteFiles) },
            getThumbnailUrl = getThumbnailUrl,
            isFavorite = { isFavorite(it) },
            onToggleFavorite = { onToggleFavorite(it) },
            modifier = modifier,
        )
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = modifier.fillMaxSize(),
        ) {
            items(favoriteFiles, key = { it.relativePath }) { file ->
                when (file.mediaType) {
                    "video" -> VideoCard(
                        file = file,
                        isFavorite = isFavorite(file.relativePath),
                        onToggleFavorite = { onToggleFavorite(file) },
                        onClick = { onVideoClick(file) },
                    )
                    "image" -> ImageCard(
                        file = file,
                        thumbnailUrl = getThumbnailUrl(file),
                        isFavorite = isFavorite(file.relativePath),
                        onToggleFavorite = { onToggleFavorite(file) },
                        onClick = { onImageClick(file, favoriteFiles) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchContent(
    searchState: SearchState,
    searchQuery: String,
    favorites: Set<String>,
    onFolderClick: (Folder) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel,
) {
    when (searchState) {
        is SearchState.Idle -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isEmpty()) "Type to search" else "Searching...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is SearchState.Loading -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is SearchState.Error -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    (searchState as SearchState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        is SearchState.Results -> {
            val result = (searchState as SearchState.Results).result
            if (result.folders.isEmpty() && result.files.isEmpty()) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No results for \"$searchQuery\"", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = modifier.fillMaxSize(),
                ) {
                    items(result.folders, key = { it.path }) { folder ->
                        FolderCard(folder = folder, onClick = { onFolderClick(folder) })
                    }
                    items(result.files, key = { it.relativePath }) { file ->
                        when (file.mediaType) {
                            "video" -> VideoCard(
                                file = file,
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = { onToggleFavorite(file) },
                                onClick = { onVideoClick(file) },
                            )
                            "image" -> ImageCard(
                                file = file,
                                thumbnailUrl = viewModel.getThumbnailUrl(file),
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = { onToggleFavorite(file) },
                                onClick = { onImageClick(file) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BrowseContent(
    folders: List<Folder>,
    files: List<MediaFile>,
    favorites: Set<String>,
    onFolderClick: (Folder) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel,
) {
    val folderSortOrder by viewModel.folderSortOrder.collectAsState()
    val fileSortOrder by viewModel.fileSortOrder.collectAsState()
    val gridState = rememberLazyGridState()
    val staggeredState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val restorePath by viewModel.restoreScrollTo.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()

    val images = files.filter { it.mediaType == "image" }
    val useStaggeredGrid = folders.isEmpty() && images.isNotEmpty()

    // Save scroll position whenever it changes
    LaunchedEffect(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) {
        viewModel.saveScrollPosition(currentPath, gridState.firstVisibleItemIndex)
    }

    // Restore scroll position when navigating back
    LaunchedEffect(restorePath) {
        if (restorePath != null) {
            val savedIndex = viewModel.getScrollPosition(restorePath!!)
            if (savedIndex > 0) {
                if (useStaggeredGrid) {
                    staggeredState.scrollToItem(savedIndex)
                } else {
                    gridState.scrollToItem(savedIndex)
                }
            }
            viewModel.consumeRestoreScroll()
        }
    }

    // Instant scroll to top when either sort order changes
    LaunchedEffect(folderSortOrder, fileSortOrder) {
        if (useStaggeredGrid) {
            staggeredState.scrollToItem(0)
        } else {
            gridState.scrollToItem(0)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (useStaggeredGrid) {
            WaterfallImageGrid(
                images = images,
                onImageClick = onImageClick,
                getThumbnailUrl = viewModel::getThumbnailUrl,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onFileLongClick = onFileLongClick,
                modifier = Modifier.fillMaxSize(),
                state = staggeredState,
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(folders, key = { it.path }) { folder ->
                    FolderCard(folder = folder, onClick = { onFolderClick(folder) })
                }
                items(files, key = { it.relativePath }) { file ->
                    when (file.mediaType) {
                        "video" -> VideoCard(
                            file = file,
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = { onToggleFavorite(file) },
                            onClick = { onVideoClick(file) },
                            onLongClick = { onFileLongClick(file) },
                        )
                        "image" -> ImageCard(
                            file = file,
                            thumbnailUrl = viewModel.getThumbnailUrl(file),
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = { onToggleFavorite(file) },
                            onClick = { onImageClick(file) },
                            onLongClick = { onFileLongClick(file) },
                        )
                    }
                }
            }
        }

        // Floating scroll buttons
        if (files.isNotEmpty() || folders.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (useStaggeredGrid) staggeredState.scrollToItem(0)
                            else gridState.scrollToItem(0)
                        }
                    },
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = "Scroll to top",
                        modifier = Modifier.size(24.dp),
                    )
                }
                FloatingActionButton(
                    onClick = {
                        scope.launch {
                            if (useStaggeredGrid) staggeredState.scrollToItem(Int.MAX_VALUE)
                            else gridState.scrollToItem(Int.MAX_VALUE)
                        }
                    },
                    modifier = Modifier.size(40.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Scroll to bottom",
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
    }
}

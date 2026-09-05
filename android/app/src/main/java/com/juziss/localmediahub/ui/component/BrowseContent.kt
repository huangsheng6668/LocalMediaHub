package com.juziss.localmediahub.ui.component
 
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.LibraryDecoration
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
import com.juziss.localmediahub.viewmodel.SearchState
import com.juziss.localmediahub.viewmodel.SortOrder
import kotlinx.coroutines.launch
 
@Composable
internal fun SearchContent(
    searchState: SearchState,
    searchQuery: String,
    onFolderClick: (Folder) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile) -> Unit,
    onTextClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    getThumbnailUrl: (MediaFile) -> String,
    onFileLongClick: (MediaFile) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    when (searchState) {
        is SearchState.Idle -> {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (searchQuery.isEmpty()) stringResource(R.string.content_search_placeholder) else stringResource(R.string.content_searching),
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
                    searchState.message,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        is SearchState.Results -> {
            val result = searchState.result
            if (result.folders.isEmpty() && result.files.isEmpty()) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.content_no_results, searchQuery), style = MaterialTheme.typography.bodyLarge)
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
                        val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
                        val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
                        when (file.mediaType) {
                            "video" -> VideoCard(
                                file = file,
                                thumbnailUrl = getThumbnailUrl(file),
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = toggle,
                                onClick = remember(file, onVideoClick) { { onVideoClick(file) } },
                                onLongClick = longClick,
                            )
                            "image" -> ImageCard(
                                file = file,
                                thumbnailUrl = getThumbnailUrl(file),
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = toggle,
                                onClick = remember(file, onImageClick) { { onImageClick(file) } },
                                onLongClick = longClick,
                            )
                            "text" -> TextCard(
                                file = file,
                                isUnsupported = file.extension.lowercase() !in SUPPORTED_TEXT_EXTENSIONS,
                                isFavorite = isFavorite(file.relativePath),
                                onToggleFavorite = toggle,
                                onClick = remember(file, onTextClick) { { onTextClick(file) } },
                                onLongClick = longClick,
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
    onFolderClick: (Folder) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile) -> Unit,
    onTextClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit = {},
    onFolderLongClick: (Folder) -> Unit = {},
    state: BrowseContentState,
    onSaveScrollPosition: (path: String, index: Int) -> Unit,
    onConsumeRestoreScroll: () -> Unit,
    getScrollPosition: (path: String) -> Int,
    getThumbnailUrl: (file: MediaFile) -> String,
    modifier: Modifier = Modifier,
    isSelected: (String) -> Boolean = { false },
    /**
     * Task 5: when false (BLE degraded mode active), video cards render greyed
     * and their click is routed to [onVideoDisabledClick] (the screen wires
     * that to a "BLE 模式下暂不支持播放视频" Snackbar). Non-video items stay
     * clickable. Defaults to true for backward-compat with callers that don't
     * care about degraded mode.
     */
    videoEnabled: Boolean = true,
    onVideoDisabledClick: () -> Unit = {},
    /**
     * Paged folder browse: when [hasMore] is true the grid requests the next
     * server page via [onLoadMore] as the user scrolls near the end;
     * [loadingMore] renders a footer spinner. Defaults keep legacy callers
     * (system browse / tag collections) untouched.
     */
    onLoadMore: () -> Unit = {},
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    decorationFor: (MediaFile) -> LibraryDecoration? = { null },
    onFolderToggleFavorite: (Folder) -> Unit = {},
) {
    val folderSortOrder = state.folderSort
    val fileSortOrder = state.fileSort
    val restorePath = state.restoreScrollTo
    val currentPath = state.currentPath
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = getScrollPosition(currentPath).coerceAtLeast(0),
    )
    val staggeredState = rememberLazyStaggeredGridState(
        initialFirstVisibleItemIndex = getScrollPosition(currentPath).coerceAtLeast(0),
    )
    val scope = rememberCoroutineScope()
 
    // Cached per list identity: recomputing this O(n) filter on every
    // recomposition (selection toggles, BLE badge flips) was wasted work —
    // the ViewModel replaces the list instance whenever its content changes.
    val images = remember(files) { files.filter { it.mediaType == "image" } }
    val useStaggeredGrid = folders.isEmpty() && images.isNotEmpty()
 
    // Save scroll position whenever it changes (supporting both grid and staggered waterfall grids)
    LaunchedEffect(
        gridState.firstVisibleItemIndex,
        gridState.firstVisibleItemScrollOffset,
        staggeredState.firstVisibleItemIndex,
        staggeredState.firstVisibleItemScrollOffset,
        useStaggeredGrid,
        currentPath
    ) {
        val index = if (useStaggeredGrid) {
            staggeredState.firstVisibleItemIndex
        } else {
            gridState.firstVisibleItemIndex
        }
        onSaveScrollPosition(currentPath, index)
    }
 
    // Restore scroll position when navigating back
    LaunchedEffect(restorePath) {
        if (restorePath != null) {
            val savedIndex = getScrollPosition(restorePath!!)
            if (savedIndex > 0) {
                if (useStaggeredGrid) {
                    staggeredState.scrollToItem(savedIndex)
                } else {
                    gridState.scrollToItem(savedIndex)
                }
            }
            onConsumeRestoreScroll()
        }
    }
 
    // Scroll to top ONLY when sort order actually changes (avoiding initial composition reset)
    var lastFolderSortOrder by remember { mutableStateOf<SortOrder?>(null) }
    var lastFileSortOrder by remember { mutableStateOf<SortOrder?>(null) }
    LaunchedEffect(folderSortOrder, fileSortOrder) {
        if (lastFolderSortOrder != null && lastFolderSortOrder != folderSortOrder) {
            if (useStaggeredGrid) staggeredState.scrollToItem(0)
            else gridState.scrollToItem(0)
        }
        if (lastFileSortOrder != null && lastFileSortOrder != fileSortOrder) {
            if (useStaggeredGrid) staggeredState.scrollToItem(0)
            else gridState.scrollToItem(0)
        }
        lastFolderSortOrder = folderSortOrder
        lastFileSortOrder = fileSortOrder
    }
 
    // Load-more trigger: derived from the scroll state, so the next page is
    // requested exactly when the user reaches the tail (no polling).
    LaunchedEffect(hasMore, files.size, folders.size) {
        if (!hasMore) return@LaunchedEffect
        snapshotFlow {
            if (useStaggeredGrid) {
                val info = staggeredState.layoutInfo
                val total = info.totalItemsCount
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                total > 0 && last >= total - 6
            } else {
                val info = gridState.layoutInfo
                val total = info.totalItemsCount
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                total > 0 && last >= total - 6
            }
        }.collect { nearEnd ->
            if (nearEnd) onLoadMore()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (useStaggeredGrid) {
            WaterfallImageGrid(
                images = images,
                onImageClick = onImageClick,
                getThumbnailUrl = getThumbnailUrl,
                isFavorite = isFavorite,
                onToggleFavorite = onToggleFavorite,
                onFileLongClick = onFileLongClick,
                modifier = Modifier.fillMaxSize(),
                state = staggeredState,
                isSelected = isSelected,
                onLoadMore = onLoadMore,
                hasMore = hasMore,
                loadingMore = loadingMore,
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
                items(folders, key = { it.path }, contentType = { "folder" }) { folder ->
                    FolderCard(
                        folder = folder,
                        onClick = { onFolderClick(folder) },
                        onLongClick = { onFolderLongClick(folder) },
                        isFavorite = isFavorite(folder.path),
                        onToggleFavorite = { onFolderToggleFavorite(folder) },
                    )
                }
                items(files, key = { it.relativePath }, contentType = { it.mediaType }) { file ->
                    val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
                    val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
                    when (file.mediaType) {
                        "video" -> VideoCard(
                            file = file,
                            thumbnailUrl = getThumbnailUrl(file),
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = toggle,
                            onClick = remember(file, onVideoClick) { { onVideoClick(file) } },
                            onLongClick = longClick,
                            isSelected = isSelected(file.relativePath),
                            // Task 5: degraded-mode UX is plumbed here so the
                            // main browse grid (the primary list surface)
                            // honors BLE degraded mode end-to-end.
                            enabled = videoEnabled,
                            onDisabledClick = onVideoDisabledClick,
                        )
                        "image" -> ImageCard(
                            file = file,
                            thumbnailUrl = getThumbnailUrl(file),
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = toggle,
                            onClick = remember(file, onImageClick) { { onImageClick(file) } },
                            onLongClick = longClick,
                            isSelected = isSelected(file.relativePath),
                        )
                        "text" -> TextCard(
                            file = file,
                            isUnsupported = file.extension.lowercase() !in SUPPORTED_TEXT_EXTENSIONS,
                            isFavorite = isFavorite(file.relativePath),
                            onToggleFavorite = toggle,
                            onClick = remember(file, onTextClick) { { onTextClick(file) } },
                            onLongClick = longClick,
                            isSelected = isSelected(file.relativePath),
                            readingStatus = decorationFor(file)?.status,
                            percent = decorationFor(file)?.percent ?: 0.0,
                        )
                    }
                }
                if (loadingMore) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "load-more-footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
 
        val totalItems = if (useStaggeredGrid) images.size else folders.size + files.size
        val fabVisibility by remember(useStaggeredGrid, totalItems) {
            derivedStateOf {
                if (useStaggeredGrid) {
                    val info = staggeredState.layoutInfo
                    val visibleItems = info.visibleItemsInfo
                    val firstIndex = staggeredState.firstVisibleItemIndex
                    val firstOffset = staggeredState.firstVisibleItemScrollOffset
                    val lastIndex = visibleItems.lastOrNull()?.index ?: 0
                    calculateScrollFabVisibility(firstIndex, firstOffset, lastIndex, totalItems, visibleItems.size)
                } else {
                    val info = gridState.layoutInfo
                    val visibleItems = info.visibleItemsInfo
                    val firstIndex = gridState.firstVisibleItemIndex
                    val firstOffset = gridState.firstVisibleItemScrollOffset
                    val lastIndex = visibleItems.lastOrNull()?.index ?: 0
                    calculateScrollFabVisibility(firstIndex, firstOffset, lastIndex, totalItems, visibleItems.size)
                }
            }
        }

        // Floating scroll buttons with smart visibility
        ScrollFabGroup(
            canScrollToTop = fabVisibility.canScrollToTop,
            canScrollToBottom = fabVisibility.canScrollToBottom,
            onScrollToTop = {
                scope.launch {
                    if (useStaggeredGrid) staggeredState.animateScrollToItem(0)
                    else gridState.animateScrollToItem(0)
                }
            },
            onScrollToBottom = {
                scope.launch {
                    val lastIndex = (totalItems - 1).coerceAtLeast(0)
                    if (useStaggeredGrid) staggeredState.animateScrollToItem(lastIndex)
                    else gridState.animateScrollToItem(lastIndex)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 16.dp),
        )
    }
}

/**
 * Extensions the TextReaderActivity can open. Anything else tagged as
 * mediaType="text" (e.g. .mobi, .azw3) renders with an "暂不支持" badge and
 * surfaces a Toast on click. Kept here so all Browse grids share one source of
 * truth for the unsupported-format check.
 */
internal val SUPPORTED_TEXT_EXTENSIONS: Set<String> = setOf(".txt", ".epub")

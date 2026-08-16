package com.juziss.localmediahub.ui.screen

import com.juziss.localmediahub.ui.component.browse.DeleteConfirmDialog
import com.juziss.localmediahub.ui.component.browse.DeleteLoadingDialog
import com.juziss.localmediahub.ui.component.browse.QuickActionsDialog
 
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.TagMenuDialog
import com.juziss.localmediahub.viewmodel.BrowseState
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import com.juziss.localmediahub.ui.component.browse.BrowseFavoritesView
import com.juziss.localmediahub.ui.component.browse.BrowseSearchView
import com.juziss.localmediahub.ui.component.browse.BrowseContentState
import com.juziss.localmediahub.ui.component.browse.BrowseStateContent
import com.juziss.localmediahub.ui.component.browse.BrowseTopBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onExitBrowse: () -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onTextClick: (MediaFile) -> Unit,
    onFavoriteVideoClick: (MediaFile, Boolean) -> Unit,
    onFavoriteImageClick: (MediaFile, List<MediaFile>, Boolean) -> Unit,
    onFavoriteTextClick: (MediaFile, Boolean) -> Unit = { file, _ -> onTextClick(file) },
    viewModel: BrowseViewModel = viewModel(),
) {
    val browseState by viewModel.browseState.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
    // Task 5: BLE degraded flag — drives the video-disabled UX (greyed cards +
    // Snackbar on click) and is read by the Coil placeholder interceptor via
    // the process-wide BleDegradedState mirror.
    val isBleDegraded by viewModel.isBleDegraded.collectAsState()
    val isSystemBrowse by viewModel.isSystemBrowse.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchState by viewModel.searchState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val favoriteFiles by viewModel.favoriteFiles.collectAsState()
    val showFavoritesOnly by viewModel.showFavoritesOnly.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val activeTagFilter by viewModel.activeTagFilter.collectAsState()
    val folderSort by viewModel.folderSortOrder.collectAsState()
    val fileSort by viewModel.fileSortOrder.collectAsState()
    val restoreScrollTo by viewModel.restoreScrollTo.collectAsState()
    val hasMore by viewModel.hasMore.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val contentState = BrowseContentState(folderSort, fileSort, currentPath, restoreScrollTo)
 
    var isSearchMode by remember { mutableStateOf(false) }
    var showTagMenuForFile by remember { mutableStateOf<MediaFile?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedFiles = remember { mutableStateListOf<MediaFile>() }
    var showBatchDeleteConfirm by remember { mutableStateOf(false) }

    // Task 5: Snackbar host for the BLE-degraded "video disabled" message.
    // Launched in a coroutine scope by onVideoDisabledClick below.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val videoDisabledMessage = stringResource(R.string.ble_video_disabled_message)
    val onVideoDisabledClick = remember(scope, videoDisabledMessage) {
        {
            scope.launch {
                snackbarHostState.showSnackbar(videoDisabledMessage)
            }
            Unit
        }
    }

    LaunchedEffect(currentPath) {
        selectionMode = false
        selectedFiles.clear()
        showBatchDeleteConfirm = false
    }
 
    LaunchedEffect(Unit) {
        if (browseState is BrowseState.Idle) {
            viewModel.loadRoots()
        }
        viewModel.loadTags()
    }

    // Surface download-related toast messages emitted by the ViewModel.
    val toastMessage by viewModel.toastMessage.collectAsState()
    val toastContext = LocalContext.current
    LaunchedEffect(toastMessage) {
        toastMessage?.let { msg ->
            Toast.makeText(toastContext, msg, Toast.LENGTH_SHORT).show()
            viewModel.onToastShown()
        }
    }
 
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            delay(500)
            viewModel.search()
        }
    }
 
    val isCollectionView = browseState is BrowseState.TagCollection
 
    BackHandler(enabled = selectionMode || isSearchMode || showFavoritesOnly || viewModel.canGoBack() || isCollectionView) {
        when {
            selectionMode -> {
                selectionMode = false
                selectedFiles.clear()
            }
            isSearchMode -> {
                isSearchMode = false
                viewModel.clearSearch()
            }
            showFavoritesOnly -> viewModel.setShowFavoritesOnly(false)
            isCollectionView -> onExitBrowse()
            viewModel.canGoBack() -> viewModel.navigateBack()
        }
    }
 
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "已选择 ${selectedFiles.size} 项",
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            selectionMode = false
                            selectedFiles.clear()
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "取消选择"
                            )
                        }
                    },
                    actions = {
                        val allFilesInFolder = when (val state = browseState) {
                            is BrowseState.Browsed -> state.result.files
                            is BrowseState.SystemBrowsed -> state.result.files
                            else -> emptyList()
                        }
                        if (allFilesInFolder.isNotEmpty()) {
                            val isAllSelected = selectedFiles.size == allFilesInFolder.size
                            TextButton(onClick = {
                                if (isAllSelected) {
                                    selectedFiles.clear()
                                } else {
                                    selectedFiles.clear()
                                    selectedFiles.addAll(allFilesInFolder)
                                }
                            }) {
                                Text(
                                    text = if (isAllSelected) "取消全选" else "全选",
                                    color = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            } else {
                val collectionTitle = (browseState as? BrowseState.TagCollection)?.title
                BrowseTopBar(
                    isSearchMode = isSearchMode,
                    searchQuery = searchQuery,
                    onSearchQueryChange = viewModel::updateSearchQuery,
                    onClearSearch = {
                        isSearchMode = false
                        viewModel.clearSearch()
                    },
                    title = when {
                        showFavoritesOnly -> stringResource(R.string.browse_favorites)
                        collectionTitle != null -> collectionTitle
                        isSystemBrowse && currentPath.isEmpty() -> stringResource(R.string.browse_drives)
                        isSystemBrowse -> currentPath
                        currentPath.isEmpty() -> stringResource(R.string.browse_libraries)
                        else -> currentPath
                    },
                    onBack = when {
                        showFavoritesOnly -> ({ viewModel.setShowFavoritesOnly(false) })
                        isCollectionView -> onExitBrowse
                        viewModel.canGoBack() -> ({ viewModel.navigateBack() })
                        else -> null
                    },
                    showLibraryActions = currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView,
                    isSystemBrowse = isSystemBrowse,
                    onToggleSystemMode = {
                        if (isSystemBrowse) viewModel.loadRoots() else viewModel.loadSystemDrives()
                    },
                    onShowFavorites = { viewModel.setShowFavoritesOnly(true) },
                    showSortAndSearch = !showFavoritesOnly,
                    folderSort = folderSort,
                    fileSort = fileSort,
                    onFolderSortChange = viewModel::setFolderSortOrder,
                    onFileSortChange = viewModel::setFileSortOrder,
                    showSearch = !showFavoritesOnly && !isCollectionView,
                    onEnterSearch = { isSearchMode = true },
                )
            }
        },
        bottomBar = {
            if (selectionMode && selectedFiles.isNotEmpty()) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    tonalElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "已选 ${selectedFiles.size} 个媒体",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = {
                                    showBatchDeleteConfirm = true
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                                )
                            ) {
                                Text("删除所选")
                            }
                            Button(
                                onClick = {
                                    selectedFiles.forEach { file ->
                                        viewModel.downloadFile(file)
                                    }
                                    selectionMode = false
                                    selectedFiles.clear()
                                },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text("下载所选")
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        var itemForActions by remember { mutableStateOf<Any?>(null) }
        // Stable callback refs so the grid content can skip recomposition when
        // these don't change (isFavorite only rebuilds when the favorites set does).
        val onToggleFavoriteCb: (MediaFile) -> Unit = remember(viewModel) {
            { file -> viewModel.toggleFavorite(file) }
        }
        val isFavoriteCb: (String) -> Boolean = remember(favorites) {
            { relativePath -> relativePath in favorites }
        }
        val onFileLongClickCb: (MediaFile) -> Unit = remember(selectionMode) {
            { file ->
                if (!selectionMode) {
                    selectionMode = true
                    selectedFiles.clear()
                    selectedFiles.add(file)
                }
            }
        }

        val handleVideoClick = remember(selectionMode, onVideoClick) {
            { file: MediaFile ->
                if (selectionMode) {
                    if (selectedFiles.any { it.relativePath == file.relativePath }) {
                        selectedFiles.removeAll { it.relativePath == file.relativePath }
                        if (selectedFiles.isEmpty()) {
                            selectionMode = false
                        }
                    } else {
                        selectedFiles.add(file)
                    }
                } else {
                    onVideoClick(file)
                }
                Unit
            }
        }

        val handleImageClick = remember(selectionMode, onImageClick) {
            { file: MediaFile, list: List<MediaFile> ->
                if (selectionMode) {
                    if (selectedFiles.any { it.relativePath == file.relativePath }) {
                        selectedFiles.removeAll { it.relativePath == file.relativePath }
                        if (selectedFiles.isEmpty()) {
                            selectionMode = false
                        }
                    } else {
                        selectedFiles.add(file)
                    }
                } else {
                    onImageClick(file, list)
                }
                Unit
            }
        }

        val handleTextClick = remember(selectionMode, onTextClick) {
            { file: MediaFile ->
                if (selectionMode) {
                    if (selectedFiles.any { it.relativePath == file.relativePath }) {
                        selectedFiles.removeAll { it.relativePath == file.relativePath }
                        if (selectedFiles.isEmpty()) {
                            selectionMode = false
                        }
                    } else {
                        selectedFiles.add(file)
                    }
                } else {
                    onTextClick(file)
                }
                Unit
            }
        }
        var itemToDelete by remember { mutableStateOf<Any?>(null) }
        var showDeleteConfirm by remember { mutableStateOf(false) }
        var deleteRecursive by remember { mutableStateOf(true) }
        val context = LocalContext.current
        val deleteState by viewModel.deleteState.collectAsState()
 
        LaunchedEffect(deleteState) {
            when (val state = deleteState) {
                is com.juziss.localmediahub.viewmodel.DeleteState.Success -> {
                    Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                    viewModel.clearDeleteState()
                    itemToDelete = null
                    showDeleteConfirm = false
                }
                is com.juziss.localmediahub.viewmodel.DeleteState.Error -> {
                    Toast.makeText(context, "错误: ${state.message}", Toast.LENGTH_LONG).show()
                    viewModel.clearDeleteState()
                }
                else -> {}
            }
        }
 
        // Action Sheet / Dialog for Long-press options
        if (itemForActions != null) {
            val item = itemForActions!!
            QuickActionsDialog(
                item = item,
                onEditTags = { file ->
                    showTagMenuForFile = file
                    itemForActions = null
                },
                onDownloadFile = { file ->
                    viewModel.downloadFile(file)
                    itemForActions = null
                },
                onDeleteFile = { file ->
                    itemToDelete = file
                    showDeleteConfirm = true
                    itemForActions = null
                },
                onDownloadFolder = { folder ->
                    viewModel.downloadFolder(folder)
                    itemForActions = null
                },
                onDeleteFolder = { folder ->
                    itemToDelete = folder
                    showDeleteConfirm = true
                    deleteRecursive = true
                    itemForActions = null
                },
                onDismiss = { itemForActions = null },
            )
        }
 
        // Delete Confirmation Dialog
        if (showDeleteConfirm && itemToDelete != null) {
            val item = itemToDelete!!
            DeleteConfirmDialog(
                item = item,
                deleteRecursive = deleteRecursive,
                onRecursiveChange = { deleteRecursive = it },
                onConfirm = { path, recursive -> viewModel.deletePath(path, recursive) },
                onDismiss = { showDeleteConfirm = false },
            )
        }

        if (showBatchDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showBatchDeleteConfirm = false },
                title = { Text("确认删除") },
                text = { Text("确认要从服务器上删除这 ${selectedFiles.size} 个选中的媒体文件吗？此操作不可撤销。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showBatchDeleteConfirm = false
                            viewModel.deletePaths(selectedFiles.map { it.relativePath })
                            selectionMode = false
                            selectedFiles.clear()
                        }
                    ) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBatchDeleteConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
 
        // Loading Overlay for Deletion
        if (deleteState is com.juziss.localmediahub.viewmodel.DeleteState.Loading) {
            DeleteLoadingDialog()
        }
 
        val taggedFile = showTagMenuForFile
        if (taggedFile != null) {
            TagMenuDialog(
                file = taggedFile,
                tags = tags,
                fileTags = viewModel.getTagsForFile(taggedFile.relativePath),
                onTagFile = { id -> viewModel.tagFile(id, taggedFile.relativePath) },
                onUntagFile = { id -> viewModel.untagFile(id, taggedFile.relativePath) },
                onDismiss = { showTagMenuForFile = null },
            )
        }
 
        when {
            isSearchMode -> BrowseSearchView(
                searchState = searchState,
                searchQuery = searchQuery,
                onClearSearch = {
                    isSearchMode = false
                    viewModel.clearSearch()
                },
                onBrowseFolder = viewModel::browseFolder,
                onVideoClick = onVideoClick,
                onImageClick = onImageClick,
                onTextClick = onTextClick,
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                getThumbnailUrl = viewModel::getThumbnailUrl,
                onFileLongClick = onFileLongClickCb,
                modifier = Modifier.padding(innerPadding),
            )
            showFavoritesOnly -> BrowseFavoritesView(
                favoriteFiles = favoriteFiles,
                onVideoClick = { file ->
                    onFavoriteVideoClick(file, viewModel.isFavoriteSystemBrowse(file))
                },
                onImageClick = { file, allFiles ->
                    onFavoriteImageClick(file, allFiles.filter { it.mediaType == "image" }, viewModel.isFavoriteSystemBrowse(file))
                },
                onTextClick = { file ->
                    onFavoriteTextClick(file, viewModel.isFavoriteSystemBrowse(file))
                },
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                getFavoriteThumbnailUrl = viewModel::getFavoriteThumbnailUrl,
                onFileLongClick = onFileLongClickCb,
                modifier = Modifier.padding(innerPadding),
            )
            else -> BrowseStateContent(
                browseState = browseState,
                state = contentState,
                isSystemBrowse = isSystemBrowse,
                tags = tags,
                activeTagFilter = activeTagFilter,
                onVideoClick = handleVideoClick,
                onImageClick = handleImageClick,
                onTextClick = handleTextClick,
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                onFileLongClick = onFileLongClickCb,
                onFolderLongClick = { folder -> itemForActions = folder },
                onRetry = { if (isSystemBrowse) viewModel.loadSystemDrives() else viewModel.loadRoots() },
                onBrowseFolder = viewModel::browseFolder,
                onBrowseSystemPath = viewModel::browseSystemPath,
                onActiveTagFilterChange = viewModel::setActiveTagFilter,
                filterFilesByTag = viewModel::filterFilesByTag,
                onSaveScrollPosition = viewModel::saveScrollPosition,
                onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
                getScrollPosition = viewModel::getScrollPosition,
                getThumbnailUrl = viewModel::getThumbnailUrl,
                innerPadding = innerPadding,
                isSelected = { path -> selectedFiles.any { it.relativePath == path } },
                // Task 5: video cards render greyed + intercept click to the
                // Snackbar while BLE degraded mode is active.
                videoEnabled = !isBleDegraded,
                onVideoDisabledClick = onVideoDisabledClick,
                // Paged folder browse: infinite scroll near the grid tail.
                onLoadMore = viewModel::loadMore,
                hasMore = hasMore,
                loadingMore = loadingMore,
            )
        }
    }
}
 

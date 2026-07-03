package com.juziss.localmediahub.ui.screen

import com.juziss.localmediahub.ui.component.browse.BrowseSummaryCard
import com.juziss.localmediahub.ui.component.browse.BrowseStateCard
import com.juziss.localmediahub.ui.component.browse.BrowseLoadingCard
import com.juziss.localmediahub.ui.component.browse.DeleteConfirmDialog
import com.juziss.localmediahub.ui.component.browse.DeleteLoadingDialog
import com.juziss.localmediahub.ui.component.browse.QuickActionsDialog
 
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import com.juziss.localmediahub.viewmodel.SortOrder
import com.juziss.localmediahub.ui.component.browse.BrowseFavoritesView
import com.juziss.localmediahub.ui.component.browse.BrowseSearchView
import com.juziss.localmediahub.ui.component.browse.BrowseSortMenu
import com.juziss.localmediahub.ui.component.browse.BrowseStateContent
import com.juziss.localmediahub.ui.component.browse.BrowseTopBar
import kotlinx.coroutines.delay
 
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onExitBrowse: () -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onFavoriteVideoClick: (MediaFile, Boolean) -> Unit,
    onFavoriteImageClick: (MediaFile, List<MediaFile>, Boolean) -> Unit,
    viewModel: BrowseViewModel = viewModel(),
) {
    val browseState by viewModel.browseState.collectAsState()
    val currentPath by viewModel.currentPath.collectAsState()
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
 
    var isSearchMode by remember { mutableStateOf(false) }
    var showTagMenuForFile by remember { mutableStateOf<MediaFile?>(null) }
 
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
 
    BackHandler(enabled = isSearchMode || showFavoritesOnly || viewModel.canGoBack() || isCollectionView) {
        when {
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
        topBar = {
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
        val onFileLongClickCb: (MediaFile) -> Unit = remember {
            { file -> itemForActions = file }
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
 
        // Loading Overlay for Deletion
        if (deleteState is com.juziss.localmediahub.viewmodel.DeleteState.Loading) {
            DeleteLoadingDialog()
        }
 
        val taggedFile = showTagMenuForFile
        if (taggedFile != null) {
            TagMenuDialog(
                file = taggedFile,
                tags = tags,
                viewModel = viewModel,
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
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                getFavoriteThumbnailUrl = viewModel::getFavoriteThumbnailUrl,
                onFileLongClick = onFileLongClickCb,
                modifier = Modifier.padding(innerPadding),
            )
            else -> BrowseStateContent(
                browseState = browseState,
                currentPath = currentPath,
                isSystemBrowse = isSystemBrowse,
                tags = tags,
                activeTagFilter = activeTagFilter,
                onVideoClick = onVideoClick,
                onImageClick = onImageClick,
                onToggleFavorite = onToggleFavoriteCb,
                isFavorite = isFavoriteCb,
                onFileLongClick = onFileLongClickCb,
                onFolderLongClick = { folder -> itemForActions = folder },
                viewModel = viewModel,
                innerPadding = innerPadding,
            )
        }
    }
}
 

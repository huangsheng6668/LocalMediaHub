package com.juziss.localmediahub.ui.screen

import com.juziss.localmediahub.ui.component.browse.BrowseSummaryCard
import com.juziss.localmediahub.ui.component.browse.BrowseStateCard
import com.juziss.localmediahub.ui.component.browse.BrowseLoadingCard
 
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.ui.component.BrowseContent
import com.juziss.localmediahub.ui.component.FavoritesContent
import com.juziss.localmediahub.ui.component.FolderGrid
import com.juziss.localmediahub.ui.component.SearchContent
import com.juziss.localmediahub.ui.component.SystemDrivesContent
import com.juziss.localmediahub.ui.component.TagFilterBar
import com.juziss.localmediahub.ui.component.TagMenuDialog
import com.juziss.localmediahub.viewmodel.BrowseState
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import com.juziss.localmediahub.viewmodel.SearchState
import com.juziss.localmediahub.viewmodel.SortOrder
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
            if (isSearchMode) {
                TopAppBar(
                    title = {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                            placeholder = { Text(stringResource(R.string.browse_search_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isSearchMode = false
                            viewModel.clearSearch()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            } else {
                TopAppBar(
                    title = {
                        val collectionTitle = (browseState as? BrowseState.TagCollection)?.title
                        Text(
                            when {
                                showFavoritesOnly -> stringResource(R.string.browse_favorites)
                                collectionTitle != null -> collectionTitle
                                isSystemBrowse && currentPath.isEmpty() -> stringResource(R.string.browse_drives)
                                isSystemBrowse -> currentPath
                                currentPath.isEmpty() -> stringResource(R.string.browse_libraries)
                                else -> currentPath
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        if (showFavoritesOnly) {
                            IconButton(onClick = { viewModel.setShowFavoritesOnly(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        } else if (isCollectionView) {
                            IconButton(onClick = onExitBrowse) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        } else if (viewModel.canGoBack()) {
                            IconButton(onClick = { viewModel.navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        }
                    },
                    actions = {
                        if (currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView) {
                            IconButton(onClick = {
                                if (isSystemBrowse) viewModel.loadRoots() else viewModel.loadSystemDrives()
                            }) {
                                Icon(
                                    Icons.Filled.Storage,
                                    contentDescription = if (isSystemBrowse) stringResource(R.string.browse_libraries) else stringResource(R.string.browse_title_drive),
                                )
                            }
                        }
                        if (currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView) {
                            IconButton(onClick = { viewModel.setShowFavoritesOnly(true) }) {
                                Icon(
                                    Icons.Outlined.FavoriteBorder,
                                    contentDescription = stringResource(R.string.browse_favorites),
                                )
                            }
                        }
                        if (!showFavoritesOnly) {
                            var showSortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(R.string.sort))
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                Text(
                                    stringResource(R.string.browse_sort_folder),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                val folderSort by viewModel.folderSortOrder.collectAsState()
                                listOf(
                                    SortOrder.NAME_ASC,
                                    SortOrder.NAME_DESC,
                                    SortOrder.NUMERIC_ASC,
                                    SortOrder.NUMERIC_DESC,
                                    SortOrder.TIME_ASC,
                                    SortOrder.TIME_DESC,
                                ).forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        trailingIcon = {
                                            if (order == folderSort) {
                                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = { viewModel.setFolderSortOrder(order) },
                                    )
                                }
                                HorizontalDivider()
                                Text(
                                    stringResource(R.string.browse_sort_file),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                                val fileSort by viewModel.fileSortOrder.collectAsState()
                                SortOrder.entries.forEach { order ->
                                    DropdownMenuItem(
                                        text = { Text(order.label) },
                                        trailingIcon = {
                                            if (order == fileSort) {
                                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(18.dp))
                                            }
                                        },
                                        onClick = { viewModel.setFileSortOrder(order) },
                                    )
                                }
                            }
                            if (!isCollectionView) {
                                IconButton(onClick = { isSearchMode = true }) {
                                    Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search))
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
    ) { innerPadding ->
        var itemForActions by remember { mutableStateOf<Any?>(null) }
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
            AlertDialog(
                onDismissRequest = { itemForActions = null },
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = stringResource(R.string.browse_quick_actions),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (item is MediaFile) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    showTagMenuForFile = item
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_edit_tags))
                                }
                            }
                            TextButton(
                                onClick = {
                                    viewModel.downloadFile(item)
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_download_file))
                                }
                            }
                            TextButton(
                                onClick = {
                                    itemToDelete = item
                                    showDeleteConfirm = true
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_delete_file))
                                }
                            }
                        } else if (item is com.juziss.localmediahub.data.Folder) {
                            Text(
                                text = item.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            TextButton(
                                onClick = {
                                    viewModel.downloadFolder(item)
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_download_folder))
                                }
                            }
                            TextButton(
                                onClick = {
                                    itemToDelete = item
                                    showDeleteConfirm = true
                                    deleteRecursive = true
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.error
                                )
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.browse_action_delete_folder))
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { itemForActions = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
 
        // Delete Confirmation Dialog
        if (showDeleteConfirm && itemToDelete != null) {
            val item = itemToDelete!!
            val name = when (item) {
                is MediaFile -> item.name
                is com.juziss.localmediahub.data.Folder -> item.name
                else -> ""
            }
            val isFolder = item is com.juziss.localmediahub.data.Folder
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                shape = RoundedCornerShape(20.dp),
                title = {
                    Text(
                        text = if (isFolder) stringResource(R.string.browse_delete_folder) else stringResource(R.string.browse_delete_file),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "您确定要从服务端永久删除 \"$name\" 吗？此操作不可撤销，文件将彻底消失。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isFolder) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { deleteRecursive = !deleteRecursive }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(
                                    checked = deleteRecursive,
                                    onCheckedChange = { deleteRecursive = it }
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.browse_delete_recursive),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val path = when (item) {
                                is MediaFile -> item.path
                                is com.juziss.localmediahub.data.Folder -> item.path
                                else -> ""
                            }
                            if (path.isNotEmpty()) {
                                viewModel.deletePath(path, if (isFolder) deleteRecursive else false)
                            }
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.confirm_delete))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            )
        }
 
        // Loading Overlay for Deletion
        if (deleteState is com.juziss.localmediahub.viewmodel.DeleteState.Loading) {
            AlertDialog(
                onDismissRequest = {},
                shape = RoundedCornerShape(20.dp),
                title = { Text(stringResource(R.string.browse_deleting), fontWeight = FontWeight.Bold) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.browse_deleting_desc))
                    }
                },
                confirmButton = {}
            )
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
 
        if (isSearchMode) {
            SearchContent(
                searchState = searchState,
                searchQuery = searchQuery,
                onFolderClick = { folder ->
                    val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                    isSearchMode = false
                    viewModel.clearSearch()
                    viewModel.browseFolder(path, folder.name)
                },
                onVideoClick = onVideoClick,
                onImageClick = { file ->
                    val allImages = when (val state = searchState) {
                        is SearchState.Results -> state.result.files.filter { it.mediaType == "image" }
                        else -> emptyList()
                    }
                    onImageClick(file, allImages)
                },
                onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                isFavorite = { relativePath -> relativePath in favorites },
                getThumbnailUrl = viewModel::getThumbnailUrl,
                onFileLongClick = { file -> itemForActions = file },
                modifier = Modifier.padding(innerPadding),
            )
            return@Scaffold
        }
 
        if (showFavoritesOnly) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = Icons.Outlined.FavoriteBorder,
                    title = stringResource(R.string.browse_favorites),
                    message = stringResource(R.string.browse_fav_card_desc),
                    meta = "共 ${favoriteFiles.size} 个收藏",
                    badge = null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                FavoritesContent(
                    favoriteFiles = favoriteFiles,
                    onVideoClick = { file ->
                        onFavoriteVideoClick(file, viewModel.isFavoriteSystemBrowse(file))
                    },
                    onImageClick = { file, allFiles ->
                        val allImages = allFiles.filter { it.mediaType == "image" }
                        onFavoriteImageClick(file, allImages, viewModel.isFavoriteSystemBrowse(file))
                    },
                    onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                    isFavorite = { relativePath -> relativePath in favorites },
                    getThumbnailUrl = viewModel::getFavoriteThumbnailUrl,
                    onFileLongClick = { file -> itemForActions = file },
                    modifier = Modifier.weight(1f),
                )
            }
            return@Scaffold
        }
 
        when (browseState) {
            is BrowseState.Idle -> {
                BrowseStateCard(
                    title = stringResource(R.string.browse_loading_files),
                    message = stringResource(R.string.browse_loading_files_desc),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
            is BrowseState.Loading -> {
                BrowseLoadingCard(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                )
            }
            is BrowseState.Error -> {
                BrowseStateCard(
                    title = stringResource(R.string.browse_error_title),
                    message = (browseState as BrowseState.Error).message,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    actionLabel = stringResource(R.string.browse_retry),
                    onAction = {
                        if (isSystemBrowse) viewModel.loadSystemDrives() else viewModel.loadRoots()
                    },
                )
            }
            is BrowseState.RootFolders -> {
                val folders = (browseState as BrowseState.RootFolders).folders
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    BrowseSummaryCard(
                        icon = Icons.Filled.Storage,
                        title = stringResource(R.string.browse_lib_card_title),
                        message = stringResource(R.string.browse_lib_card_desc),
                        meta = "共 ${folders.size} 个共享盘符",
                        badge = null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    FolderGrid(
                        folders = folders,
                        onFolderClick = { folder ->
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            viewModel.browseFolder(path, folder.name)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is BrowseState.SystemDrives -> {
                val drives = (browseState as BrowseState.SystemDrives).drives
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    BrowseSummaryCard(
                        icon = Icons.Filled.Storage,
                        title = stringResource(R.string.browse_drive_card_title),
                        message = stringResource(R.string.browse_drive_card_desc),
                        meta = "检测到 ${drives.size} 个磁盘分区",
                        badge = null,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    SystemDrivesContent(
                        drives = drives,
                        onDriveClick = { drivePath ->
                            viewModel.browseSystemPath(drivePath, drivePath)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            is BrowseState.SystemBrowsed -> {
                val result = (browseState as BrowseState.SystemBrowsed).result
                val filteredFiles = viewModel.filterFilesByTag(result.files)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    BrowseSummaryCard(
                        icon = Icons.Filled.Storage,
                        title = stringResource(R.string.browse_path_title),
                        message = result.currentPath ?: currentPath,
                        meta = "${result.folders.size} 文件夹 · ${filteredFiles.size} 文件",
                        badge = activeTagFilter?.name,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    BrowseContent(
                        folders = result.folders,
                        files = filteredFiles,
                        onFolderClick = { folder ->
                            viewModel.browseSystemPath(folder.path, folder.name)
                        },
                        onVideoClick = onVideoClick,
                        onImageClick = { file ->
                            onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                        },
                        onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                        isFavorite = { relativePath -> relativePath in favorites },
                        onFileLongClick = { file -> itemForActions = file },
                        onFolderLongClick = { folder -> itemForActions = folder },
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                    )
                }
            }
            is BrowseState.Browsed -> {
                val result = (browseState as BrowseState.Browsed).result
                val filteredFiles = viewModel.filterFilesByTag(result.files)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    BrowseSummaryCard(
                        icon = Icons.Filled.Folder,
                        title = if (currentPath.isBlank()) stringResource(R.string.browse_browsed_title) else currentPath,
                        message = stringResource(R.string.browse_browsed_desc),
                        meta = "${result.folders.size} 文件夹 · ${filteredFiles.size} 文件",
                        badge = activeTagFilter?.name,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (tags.isNotEmpty()) {
                        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
                            TagFilterBar(
                                tags = tags,
                                activeTagFilter = activeTagFilter,
                                onTagClick = { tag ->
                                    viewModel.setActiveTagFilter(
                                        if (activeTagFilter?.id == tag.id) null else tag
                                    )
                                },
                            )
                        }
                    }
                    BrowseContent(
                        folders = result.folders,
                        files = filteredFiles,
                        onFolderClick = { folder ->
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            viewModel.browseFolder(path, folder.name)
                        },
                        onVideoClick = onVideoClick,
                        onImageClick = { file ->
                            onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                        },
                        onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                        isFavorite = { relativePath -> relativePath in favorites },
                        onFileLongClick = { file -> itemForActions = file },
                        onFolderLongClick = { folder -> itemForActions = folder },
                        modifier = Modifier.weight(1f),
                        viewModel = viewModel,
                    )
                }
            }
            is BrowseState.TagCollection -> {
                val collection = browseState as BrowseState.TagCollection
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    BrowseSummaryCard(
                        icon = Icons.Filled.Bookmarks,
                        title = collection.title,
                        message = stringResource(R.string.browse_collection_desc),
                        meta = "共 ${collection.files.size} 个媒体文件",
                        badge = stringResource(R.string.browse_collection_title),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (collection.files.isEmpty()) {
                        BrowseStateCard(
                            title = stringResource(R.string.browse_collection_empty),
                            message = "您可以在浏览媒体文件时长按并贴上 \"${collection.title}\" 标签，以便在此快速查看。",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .weight(1f),
                        )
                    } else {
                        BrowseContent(
                            folders = emptyList(),
                            files = collection.files,
                            onFolderClick = {},
                            onVideoClick = onVideoClick,
                            onImageClick = { file ->
                                onImageClick(file, collection.files.filter { it.mediaType == "image" })
                            },
                            onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                            isFavorite = { relativePath -> relativePath in favorites },
                            onFileLongClick = { file -> itemForActions = file },
                            modifier = Modifier.weight(1f),
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}
 

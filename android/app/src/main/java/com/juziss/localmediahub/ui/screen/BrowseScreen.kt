package com.juziss.localmediahub.ui.screen

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.Button
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
                            placeholder = { Text("Search files...") },
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                                showFavoritesOnly -> "Favorites"
                                collectionTitle != null -> collectionTitle
                                isSystemBrowse && currentPath.isEmpty() -> "Drives"
                                isSystemBrowse -> currentPath
                                currentPath.isEmpty() -> "Libraries"
                                else -> currentPath
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        if (showFavoritesOnly) {
                            IconButton(onClick = { viewModel.setShowFavoritesOnly(false) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else if (isCollectionView) {
                            IconButton(onClick = onExitBrowse) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        } else if (viewModel.canGoBack()) {
                            IconButton(onClick = { viewModel.navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                                    contentDescription = if (isSystemBrowse) "Media roots" else "All drives",
                                )
                            }
                        }
                        if (currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView) {
                            IconButton(onClick = { viewModel.setShowFavoritesOnly(true) }) {
                                Icon(
                                    Icons.Outlined.FavoriteBorder,
                                    contentDescription = "Show Favorites",
                                )
                            }
                        }
                        if (!showFavoritesOnly) {
                            var showSortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                Text(
                                    "Folders",
                                    style = MaterialTheme.typography.labelMedium,
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
                                    "Files",
                                    style = MaterialTheme.typography.labelMedium,
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
                                    Icon(Icons.Filled.Search, contentDescription = "Search")
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
                favorites = favorites,
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
                modifier = Modifier.padding(innerPadding),
                viewModel = viewModel,
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
                    title = "Favorites",
                    message = "Pinned media from across your libraries stays here for instant access.",
                    meta = "${favoriteFiles.size} saved items",
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
                    modifier = Modifier.weight(1f),
                    viewModel = viewModel,
                )
            }
            return@Scaffold
        }

        when (browseState) {
            is BrowseState.Idle -> {
                BrowseStateCard(
                    title = "Preparing your library view",
                    message = "Loading roots, tags, and shortcuts so browsing starts with the right context.",
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
                    title = "Browse error",
                    message = (browseState as BrowseState.Error).message,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    actionLabel = "Retry",
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
                    title = "Libraries",
                    message = "Choose one of the media roots exposed by your PC server.",
                    meta = "${folders.size} roots available",
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
                    title = "Device drives",
                    message = "Switch to a whole-device browse when you need paths outside your configured libraries.",
                    meta = "${drives.size} drives detected",
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
                    title = "Device path browse",
                    message = result.currentPath ?: currentPath,
                    meta = "${result.folders.size} folders · ${filteredFiles.size} files",
                    badge = activeTagFilter?.name,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                    BrowseContent(
                        folders = result.folders,
                        files = filteredFiles,
                        favorites = favorites,
                        onFolderClick = { folder ->
                            viewModel.browseSystemPath(folder.path, folder.name)
                        },
                        onVideoClick = onVideoClick,
                        onImageClick = { file ->
                            onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                        },
                        onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                        isFavorite = { relativePath -> relativePath in favorites },
                        onFileLongClick = { file -> showTagMenuForFile = file },
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
                    title = if (currentPath.isBlank()) "Library browse" else currentPath,
                    message = "Navigate this library path, filter by tag, and open media in place.",
                    meta = "${result.folders.size} folders · ${filteredFiles.size} files",
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
                                onManageTags = { },
                            )
                        }
                    }
                    BrowseContent(
                        folders = result.folders,
                        files = filteredFiles,
                        favorites = favorites,
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
                        onFileLongClick = { file -> showTagMenuForFile = file },
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
                    message = "A cross-library collection gathered from your tags.",
                    meta = "${collection.files.size} items",
                    badge = "Collection",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                    if (collection.files.isEmpty()) {
                        BrowseStateCard(
                            title = "This collection is empty",
                            message = "Tag more files with ${collection.title} to turn this view into a useful shortcut.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .weight(1f),
                        )
                    } else {
                        BrowseContent(
                            folders = emptyList(),
                            files = collection.files,
                            favorites = favorites,
                            onFolderClick = {},
                            onVideoClick = onVideoClick,
                            onImageClick = { file ->
                                onImageClick(file, collection.files.filter { it.mediaType == "image" })
                            },
                            onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                            isFavorite = { relativePath -> relativePath in favorites },
                            onFileLongClick = { file -> showTagMenuForFile = file },
                            modifier = Modifier.weight(1f),
                            viewModel = viewModel,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseSummaryCard(
    icon: ImageVector,
    title: String,
    message: String,
    meta: String,
    badge: String?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (!badge.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun BrowseStateCard(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    Button(onClick = onAction) {
                        Text(actionLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseLoadingCard(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "Loading this view",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Fetching folders, files, and tag state for this location.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

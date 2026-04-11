package com.juziss.localmediahub.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
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
    onFolderClick: (relativePath: String, folderName: String) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
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
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) {
        if (browseState is BrowseState.Idle) {
            viewModel.loadSystemDrives()
        }
        viewModel.loadTags()
    }

    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            delay(500)
            viewModel.search()
        }
    }

    BackHandler(enabled = isSearchMode || viewModel.canGoBack()) {
        when {
            isSearchMode -> {
                isSearchMode = false
                viewModel.clearSearch()
            }
            viewModel.canGoBack() -> viewModel.navigateBack()
        }
    }

    Scaffold(
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
                        Text(
                            when {
                                showFavoritesOnly -> "Favorites"
                                isSystemBrowse && currentPath.isEmpty() -> "Drives"
                                isSystemBrowse -> currentPath
                                currentPath.isEmpty() -> "LocalMediaHub"
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
                        } else if (viewModel.canGoBack()) {
                            IconButton(onClick = { viewModel.navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        }
                    },
                    actions = {
                        if (currentPath.isEmpty() && !showFavoritesOnly) {
                            IconButton(onClick = {
                                if (isSystemBrowse) viewModel.loadRoots() else viewModel.loadSystemDrives()
                            }) {
                                Icon(
                                    Icons.Filled.Storage,
                                    contentDescription = if (isSystemBrowse) "Media roots" else "All drives",
                                )
                            }
                        }
                        if (currentPath.isEmpty() && !showFavoritesOnly) {
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
                                Icon(Icons.Filled.Sort, contentDescription = "Sort")
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
                                SortOrder.entries.forEach { order ->
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
                            IconButton(onClick = { isSearchMode = true }) {
                                Icon(Icons.Filled.Search, contentDescription = "Search")
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
        } else if (showFavoritesOnly) {
            FavoritesContent(
                favoriteFiles = favoriteFiles,
                onVideoClick = onVideoClick,
                onImageClick = { file, allFiles ->
                    val allImages = allFiles.filter { it.mediaType == "image" }
                    onImageClick(file, allImages)
                },
                onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                isFavorite = { relativePath -> relativePath in favorites },
                modifier = Modifier.padding(innerPadding),
                viewModel = viewModel,
            )
        } else {
            when (browseState) {
                is BrowseState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("Ready", style = MaterialTheme.typography.bodyLarge)
                    }
                }
                is BrowseState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                is BrowseState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(innerPadding),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Error",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                (browseState as BrowseState.Error).message,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadRoots() }) {
                                Text("Retry")
                            }
                        }
                    }
                }
                is BrowseState.RootFolders -> {
                    val folders = (browseState as BrowseState.RootFolders).folders
                    Column(modifier = Modifier.padding(innerPadding)) {
                        if (tags.isNotEmpty()) {
                            TagFilterBar(
                                tags = tags,
                                activeTagFilter = activeTagFilter,
                                onTagClick = { tag ->
                                    viewModel.setActiveTagFilter(
                                        if (activeTagFilter?.id == tag.id) null else tag
                                    )
                                },
                                onManageTags = { /* handled via long-press on files */ },
                            )
                        }
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
                    SystemDrivesContent(
                        drives = drives,
                        onDriveClick = { drivePath ->
                            viewModel.browseSystemPath(drivePath, drivePath)
                        },
                        modifier = Modifier.padding(innerPadding),
                    )
                }
                is BrowseState.SystemBrowsed -> {
                    val result = (browseState as BrowseState.SystemBrowsed).result
                    BrowseContent(
                        folders = result.folders,
                        files = result.files,
                        favorites = favorites,
                        onFolderClick = { folder ->
                            viewModel.browseSystemPath(folder.path, folder.name)
                        },
                        onVideoClick = onVideoClick,
                        onImageClick = { file ->
                            onImageClick(file, result.files.filter { it.mediaType == "image" })
                        },
                        onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                        isFavorite = { relativePath -> relativePath in favorites },
                        onFileLongClick = { file -> showTagMenuForFile = file },
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel,
                    )
                }
                is BrowseState.Browsed -> {
                    val result = (browseState as BrowseState.Browsed).result
                    BrowseContent(
                        folders = result.folders,
                        files = result.files,
                        favorites = favorites,
                        onFolderClick = { folder ->
                            val path = if (folder.relativePath.isEmpty()) folder.name else folder.relativePath
                            viewModel.browseFolder(path, folder.name)
                        },
                        onVideoClick = onVideoClick,
                        onImageClick = { file ->
                            onImageClick(file, result.files.filter { it.mediaType == "image" })
                        },
                        onToggleFavorite = { file -> viewModel.toggleFavorite(file) },
                        isFavorite = { relativePath -> relativePath in favorites },
                        onFileLongClick = { file -> showTagMenuForFile = file },
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

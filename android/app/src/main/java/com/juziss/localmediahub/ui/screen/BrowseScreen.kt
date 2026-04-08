package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.viewmodel.BrowseState
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import com.juziss.localmediahub.viewmodel.SearchState
import com.juziss.localmediahub.viewmodel.SortOrder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
        // Only load on first entry, not when returning from preview/video
        if (browseState is BrowseState.Idle) {
            viewModel.loadSystemDrives()
        }
        viewModel.loadTags()
    }

    // Auto-search with debounce
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            delay(500)
            viewModel.search()
        }
    }

    // Intercept system back gesture
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
                                focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
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
                        // Toggle between system drives and media roots
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
                        // Favorites filter toggle (only in root view)
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
        // Tag context menu dialog
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

@Composable
private fun FavoritesContent(
    favoriteFiles: List<MediaFile>,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
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
            getThumbnailUrl = viewModel::getThumbnailUrl,
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
                        thumbnailUrl = viewModel.getThumbnailUrl(file),
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
private fun SearchContent(
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

@Composable
private fun FolderGrid(
    folders: List<Folder>,
    onFolderClick: (Folder) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("No folders found", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(folders, key = { it.path }) { folder ->
            FolderCard(folder = folder, onClick = { onFolderClick(folder) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BrowseContent(
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

    val images = files.filter { it.mediaType == "image" }
    val useStaggeredGrid = folders.isEmpty() && images.isNotEmpty()

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

@Composable
private fun FavoriteToggleIcon(
    isFavorite: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(32.dp),
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
            tint = if (isFavorite) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun FolderCard(
    folder: Folder,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = "Folder",
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = folder.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoCard(
    file: MediaFile,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Movie,
                    contentDescription = "Video",
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.tertiary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = file.extension.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FavoriteToggleIcon(
                isFavorite = isFavorite,
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImageCard(
    file: MediaFile,
    thumbnailUrl: String,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Box {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                AsyncImage(
                    model = thumbnailUrl,
                    contentDescription = file.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Crop,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = file.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            FavoriteToggleIcon(
                isFavorite = isFavorite,
                onClick = onToggleFavorite,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WaterfallImageGrid(
    images: List<MediaFile>,
    onImageClick: (MediaFile) -> Unit,
    getThumbnailUrl: (MediaFile) -> String,
    modifier: Modifier = Modifier,
    state: LazyStaggeredGridState = rememberLazyStaggeredGridState(),
    isFavorite: (String) -> Boolean = { false },
    onToggleFavorite: (MediaFile) -> Unit = {},
    onFileLongClick: (MediaFile) -> Unit = {},
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(2),
        state = state,
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalItemSpacing = 8.dp,
        modifier = modifier.fillMaxSize(),
    ) {
        items(images, key = { it.relativePath }) { file ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onImageClick(file) },
                        onLongClick = { onFileLongClick(file) },
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Box {
                    AsyncImage(
                        model = getThumbnailUrl(file),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                    FavoriteToggleIcon(
                        isFavorite = isFavorite(file.relativePath),
                        onClick = { onToggleFavorite(file) },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

// ── Tag composables ──────────────────────────────────────────────

@Composable
private fun TagFilterBar(
    tags: List<Tag>,
    activeTagFilter: Tag?,
    onTagClick: (Tag) -> Unit,
    onManageTags: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = "Tags",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            tags.forEach { tag ->
                val isActive = activeTagFilter?.id == tag.id
                FilterChip(
                    selected = isActive,
                    onClick = { onTagClick(tag) },
                    label = { Text(tag.name) },
                    leadingIcon = {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .padding(1.dp)
                        ) {
                            androidx.compose.foundation.Canvas(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                drawCircle(
                                    color = parseColorHex(tag.color),
                                )
                            }
                        }
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = parseColorHex(tag.color).copy(alpha = 0.2f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun TagMenuDialog(
    file: MediaFile,
    tags: List<Tag>,
    viewModel: BrowseViewModel,
    onDismiss: () -> Unit,
) {
    val fileTags = viewModel.getTagsForFile(file.relativePath)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Tags for ${file.name}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            if (tags.isEmpty()) {
                Text("No tags created yet. Create tags first.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    tags.forEach { tag ->
                        val isApplied = fileTags.any { it.id == tag.id }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isApplied) {
                                        viewModel.untagFile(tag.id, file.relativePath)
                                    } else {
                                        viewModel.tagFile(tag.id, file.relativePath)
                                    }
                                }
                                .padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = isApplied,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        viewModel.tagFile(tag.id, file.relativePath)
                                    } else {
                                        viewModel.untagFile(tag.id, file.relativePath)
                                    }
                                },
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                            ) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    drawCircle(color = parseColorHex(tag.color))
                                }
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(tag.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
    )
}

private fun parseColorHex(hex: String): Color {
    return try {
        val cleaned = hex.removePrefix("#")
        val argb = when (cleaned.length) {
            6 -> "FF$cleaned"
            8 -> cleaned
            else -> return Color.Gray
        }
        Color(argb.toLong(16))
    } catch (_: Exception) {
        Color.Gray
    }
}

@Composable
private fun SystemDrivesContent(
    drives: List<String>,
    onDriveClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (drives.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text("No drives found", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(drives, key = { it }) { drivePath ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDriveClick(drivePath) },
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Storage,
                        contentDescription = "Drive",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = drivePath,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

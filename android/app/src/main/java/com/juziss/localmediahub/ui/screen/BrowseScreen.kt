package com.juziss.localmediahub.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.viewmodel.BrowseState
import com.juziss.localmediahub.viewmodel.BrowseViewModel

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

    LaunchedEffect(Unit) {
        viewModel.loadRoots()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (currentPath.isEmpty()) "LocalMediaHub" else currentPath,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (viewModel.canGoBack()) {
                        IconButton(onClick = { viewModel.navigateBack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                )
            )
        }
    ) { innerPadding ->
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
                FolderGrid(
                    folders = folders,
                    onFolderClick = { folder ->
                        viewModel.browseFolder(folder.relativePath, folder.name)
                    },
                    modifier = Modifier.padding(innerPadding),
                )
            }
            is BrowseState.Browsed -> {
                val result = (browseState as BrowseState.Browsed).result
                BrowseContent(
                    folders = result.folders,
                    files = result.files,
                    onFolderClick = { folder ->
                        viewModel.browseFolder(folder.relativePath, folder.name)
                    },
                    onVideoClick = onVideoClick,
                    onImageClick = { file ->
                        onImageClick(file, result.files.filter { it.mediaType == "image" })
                    },
                    modifier = Modifier.padding(innerPadding),
                    viewModel = viewModel,
                )
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
        items(folders) { folder ->
            FolderCard(folder = folder, onClick = { onFolderClick(folder) })
        }
    }
}

@Composable
private fun BrowseContent(
    folders: List<Folder>,
    files: List<MediaFile>,
    onFolderClick: (Folder) -> Unit,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BrowseViewModel,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        // Folders
        items(folders) { folder ->
            FolderCard(folder = folder, onClick = { onFolderClick(folder) })
        }
        // Files
        items(files) { file ->
            when (file.mediaType) {
                "video" -> VideoCard(
                    file = file,
                    onClick = { onVideoClick(file) },
                )
                "image" -> ImageCard(
                    file = file,
                    thumbnailUrl = viewModel.getThumbnailUrl(file),
                    onClick = { onImageClick(file) },
                )
            }
        }
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

@Composable
private fun VideoCard(
    file: MediaFile,
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
    }
}

@Composable
private fun ImageCard(
    file: MediaFile,
    thumbnailUrl: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
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
    }
}

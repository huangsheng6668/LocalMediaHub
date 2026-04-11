package com.juziss.localmediahub.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WaterfallImageGrid(
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

@Composable
internal fun FolderGrid(
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

@Composable
internal fun SystemDrivesContent(
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

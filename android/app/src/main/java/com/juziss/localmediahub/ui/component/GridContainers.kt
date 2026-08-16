package com.juziss.localmediahub.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
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
    isSelected: (String) -> Boolean = { false },
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
            val imageClick = remember(file, onImageClick) { { onImageClick(file) } }
            val longClick = remember(file, onFileLongClick) { { onFileLongClick(file) } }
            val toggle = remember(file, onToggleFavorite) { { onToggleFavorite(file) } }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = imageClick,
                        onLongClick = longClick,
                    ),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Neutral fallback block: a failed thumbnail shows
                        // this tint instead of a blank card area.
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                ) {
                    AsyncImage(
                        model = getThumbnailUrl(file),
                        contentDescription = file.name,
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth,
                    )
                    FavoriteToggleIcon(
                        isFavorite = isFavorite(file.relativePath),
                        onClick = toggle,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp),
                    )
                    if (isSelected(file.relativePath)) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                            modifier = Modifier
                                .padding(8.dp)
                                .size(24.dp)
                                .align(Alignment.TopStart)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Done,
                                    contentDescription = "Selected",
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
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
        GridEmptyState(
            icon = painterResource(R.drawable.ic_folder_off),
            title = stringResource(R.string.grid_no_folders),
            message = stringResource(R.string.grid_no_folders_desc),
            modifier = modifier,
        )
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
        GridEmptyState(
            icon = painterResource(R.drawable.ic_storage),
            title = stringResource(R.string.grid_no_drives),
            message = stringResource(R.string.grid_no_drives_desc),
            modifier = modifier,
        )
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
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onDriveClick(drivePath) },
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                painterResource(R.drawable.ic_storage),
                                contentDescription = "Drive",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(R.string.grid_drive_root),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = drivePath,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.grid_drive_root_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun GridEmptyState(
    icon: androidx.compose.ui.graphics.painter.Painter,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
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
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = MaterialTheme.shapes.large,
                ) {
                    Icon(
                        painter = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp),
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

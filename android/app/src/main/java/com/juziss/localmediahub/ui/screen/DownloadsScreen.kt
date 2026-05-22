package com.juziss.localmediahub.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.juziss.localmediahub.data.DownloadEntry
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.viewmodel.BrowseViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onVideoClick: (MediaFile, String) -> Unit,
    onImageClick: (MediaFile, String) -> Unit,
    viewModel: BrowseViewModel,
) {
    val downloads by viewModel.downloadedFiles.collectAsState(initial = emptyList())
    var selectedEntryForDelete by remember { mutableStateOf<DownloadEntry?>(null) }
    var currentPath by remember { mutableStateOf(emptyList<String>()) }

    // Intercept hardware system back button if we are inside a subfolder
    BackHandler(enabled = currentPath.isNotEmpty()) {
        currentPath = currentPath.dropLast(1)
    }

    // Filter subfolders and files at the current dynamic path level
    val itemsAtCurrentLevel = remember(downloads, currentPath) {
        val folders = mutableSetOf<String>()
        val files = mutableListOf<DownloadEntry>()

        for (entry in downloads) {
            val segments = entry.file.relativePath
                .split('/', '\\')
                .filter { it.isNotEmpty() }
            
            // Check if this file is within the current browsing directory
            if (segments.size > currentPath.size && 
                segments.take(currentPath.size) == currentPath
            ) {
                if (segments.size == currentPath.size + 1) {
                    files.add(entry)
                } else {
                    folders.add(segments[currentPath.size])
                }
            }
        }
        Pair(folders.sorted(), files)
    }
    val (foldersAtLevel, filesAtLevel) = itemsAtCurrentLevel

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Local Downloads")
                        Text(
                            text = "Offline media library",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentPath.isNotEmpty()) {
                            currentPath = currentPath.dropLast(1)
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Horizontal Breadcrumbs Bar
            if (currentPath.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        TextButton(
                            onClick = { currentPath = emptyList() },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp)
                        ) {
                            Text(
                                text = "Root",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    itemsIndexed(currentPath) { index, segment ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp)
                            )
                            TextButton(
                                onClick = { currentPath = currentPath.take(index + 1) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp),
                                enabled = index < currentPath.size - 1
                            ) {
                                Text(
                                    text = segment,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (index == currentPath.size - 1) FontWeight.Bold else FontWeight.Medium,
                                    color = if (index == currentPath.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Main Contents
            if (downloads.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "No Offline Downloads",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "Download videos or images from your server to access them offline anytime.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            } else if (foldersAtLevel.isEmpty() && filesAtLevel.isEmpty()) {
                // If a subfolder has no files left (e.g. after deletion)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            Icons.Filled.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "This Folder is Empty",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "All downloaded files in this directory have been removed.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Button(
                            onClick = { currentPath = currentPath.dropLast(1) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Go to Parent Folder")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 1. Render Subfolders
                    items(foldersAtLevel, key = { "dir_$it" }) { folderName ->
                        // Calculate subfolder statistics dynamically in parallel
                        val (count, sizeStr) = remember(downloads, currentPath, folderName) {
                            val targetPath = currentPath + folderName
                            var fileCount = 0
                            var totalSize = 0L
                            for (entry in downloads) {
                                val segments = entry.file.relativePath
                                    .split('/', '\\')
                                    .filter { it.isNotEmpty() }
                                if (segments.size > targetPath.size && 
                                    segments.take(targetPath.size) == targetPath
                                ) {
                                    fileCount++
                                    val f = File(entry.localPath)
                                    if (f.exists()) {
                                        totalSize += f.length()
                                    }
                                }
                            }
                            val formattedSize = if (totalSize >= 1024 * 1024 * 1024) {
                                String.format(Locale.US, "%.2f GB", totalSize.toDouble() / (1024 * 1024 * 1024))
                            } else if (totalSize >= 1024 * 1024) {
                                String.format(Locale.US, "%.2f MB", totalSize.toDouble() / (1024 * 1024))
                            } else {
                                "${totalSize / 1024} KB"
                            }
                            Pair(fileCount, formattedSize)
                        }

                        FolderItemCard(
                            name = folderName,
                            itemCount = count,
                            totalSizeString = sizeStr,
                            onClick = { currentPath = currentPath + folderName }
                        )
                    }

                    // 2. Render Media Files
                    items(filesAtLevel, key = { it.file.relativePath }) { entry ->
                        DownloadItemCard(
                            entry = entry,
                            onClick = {
                                if (entry.file.mediaType == "video") {
                                    onVideoClick(entry.file, entry.localPath)
                                } else {
                                    onImageClick(entry.file, entry.localPath)
                                }
                            },
                            onDeleteClick = {
                                selectedEntryForDelete = entry
                            }
                        )
                    }
                }
            }
        }
    }

    selectedEntryForDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { selectedEntryForDelete = null },
            title = { Text("Delete Downloaded File?") },
            text = { Text("This will permanently delete \"${entry.file.name}\" from your device's local storage.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeDownload(entry.file)
                        selectedEntryForDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntryForDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderItemCard(
    name: String,
    itemCount: Int,
    totalSizeString: String,
    onClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {}
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (itemCount == 1) "1 item" else "$itemCount items",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = totalSizeString,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadItemCard(
    entry: DownloadEntry,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val fileDate = remember(entry.addedAt) {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        sdf.format(Date(entry.addedAt))
    }

    val fileSize = remember(entry.localPath) {
        val file = File(entry.localPath)
        if (file.exists()) {
            val sizeBytes = file.length()
            if (sizeBytes >= 1024 * 1024 * 1024) {
                String.format(Locale.US, "%.2f GB", sizeBytes.toDouble() / (1024 * 1024 * 1024))
            } else if (sizeBytes >= 1024 * 1024) {
                String.format(Locale.US, "%.2f MB", sizeBytes.toDouble() / (1024 * 1024))
            } else {
                "${sizeBytes / 1024} KB"
            }
        } else {
            "Unknown size"
        }
    }

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDeleteClick
            ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (entry.file.mediaType == "image") {
                    AsyncImage(
                        model = "file://${entry.localPath}",
                        contentDescription = entry.file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(32.dp)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = entry.file.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (entry.file.mediaType == "video") "Video" else "Image",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = fileSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = fileDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            IconButton(onClick = onDeleteClick) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }
    }
}

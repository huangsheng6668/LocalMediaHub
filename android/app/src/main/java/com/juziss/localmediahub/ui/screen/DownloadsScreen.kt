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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.res.painterResource
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
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import com.juziss.localmediahub.R
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
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    viewModel: BrowseViewModel,
    onTextClick: (MediaFile) -> Unit = {},
) {
    val downloads by viewModel.downloadedFiles.collectAsState(initial = emptyList())
    var selectedEntryForDelete by remember { mutableStateOf<DownloadEntry?>(null) }
    var selectedFolderForDelete by remember { mutableStateOf<String?>(null) }
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
        Pair(folders.sorted(), files.sortedBy { it.file.name })
    }
    val (foldersAtLevel, filesAtLevel) = itemsAtCurrentLevel
 
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.downloads_title), fontWeight = FontWeight.Bold)
                        Text(
                            text = stringResource(R.string.downloads_subtitle),
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
                            contentDescription = stringResource(R.string.back),
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
                                text = stringResource(R.string.downloads_root),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
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
                            painterResource(R.drawable.ic_cloud_off),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "暂无离线下载内容",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "您可以在浏览共享媒体库时，长按文件并选择“下载到本地”，以便随时在此离线播放和浏览。",
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
                            painterResource(R.drawable.ic_folder_open),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "此文件夹为空",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = stringResource(R.string.downloads_folder_empty_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                        Button(
                            onClick = { currentPath = currentPath.dropLast(1) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("返回上一级文件夹")
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
                            onClick = { currentPath = currentPath + folderName },
                            onLongClick = { selectedFolderForDelete = folderName }
                        )
                    }
 
                    // 2. Render Media Files
                    items(filesAtLevel, key = { it.file.relativePath }) { entry ->
                        DownloadItemCard(
                            entry = entry,
                            onClick = {
                                when (entry.file.mediaType) {
                                    "video" -> onVideoClick(entry.file, entry.localPath)
                                    "image" -> {
                                        val imagesAtLevel = filesAtLevel
                                            .filter { it.file.mediaType == "image" }
                                            .map { it.file }
                                            .sortedBy { it.name }
                                        onImageClick(entry.file, imagesAtLevel)
                                    }
                                    "text" -> onTextClick(entry.file)
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
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.downloads_delete_file_title), fontWeight = FontWeight.Bold) },
            text = { Text("这将从您手机的本地存储中永久删除文件 \"${entry.file.name}\"，该操作不可撤销。") },
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
                    Text(stringResource(R.string.confirm_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedEntryForDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
 
    selectedFolderForDelete?.let { folderName ->
        val folderFiles = remember(downloads, currentPath, folderName) {
            val targetPath = currentPath + folderName
            downloads.filter { entry ->
                val segments = entry.file.relativePath
                    .split('/', '\\')
                    .filter { it.isNotEmpty() }
                segments.size > targetPath.size &&
                        segments.take(targetPath.size) == targetPath
            }
        }
 
        AlertDialog(
            onDismissRequest = { selectedFolderForDelete = null },
            shape = RoundedCornerShape(20.dp),
            title = { Text(stringResource(R.string.downloads_delete_folder_title), fontWeight = FontWeight.Bold) },
            text = { Text("这将从您手机的本地存储中永久删除文件夹 \"$folderName\" 及其包含的 ${folderFiles.size} 个离线媒体文件，该操作不可撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val relativePaths = folderFiles.map { it.file.relativePath }
                        viewModel.removeDownloads(relativePaths)
                        selectedFolderForDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.downloads_delete_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedFolderForDelete = null }) {
                    Text(stringResource(R.string.cancel))
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
    onLongClick: () -> Unit,
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_folder),
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
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.downloads_file_count, itemCount),
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
            "未知大小"
        }
    }
 
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onDeleteClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
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
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                when (entry.file.mediaType) {
                    "image" -> AsyncImage(
                        model = "file://${entry.localPath}",
                        contentDescription = entry.file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    "video" -> {
                        Icon(
                            painterResource(R.drawable.ic_movie),
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
                    "text" -> Icon(
                        painterResource(R.drawable.ic_text_file),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
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
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = when (entry.file.mediaType) {
                        "video" -> stringResource(R.string.video)
                        "image" -> stringResource(R.string.image)
                        "text" -> "小说"
                        else -> ""
                    },
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
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                )
            }
        }
    }
}

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
                            placeholder = { Text("搜索媒体文件...") },
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
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                                showFavoritesOnly -> "我的最爱"
                                collectionTitle != null -> collectionTitle
                                isSystemBrowse && currentPath.isEmpty() -> "磁盘驱动器"
                                isSystemBrowse -> currentPath
                                currentPath.isEmpty() -> "共享媒体库"
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
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        } else if (isCollectionView) {
                            IconButton(onClick = onExitBrowse) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                        } else if (viewModel.canGoBack()) {
                            IconButton(onClick = { viewModel.navigateBack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
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
                                    contentDescription = if (isSystemBrowse) "共享媒体库" else "磁盘分区",
                                )
                            }
                        }
                        if (currentPath.isEmpty() && !showFavoritesOnly && !isCollectionView) {
                            IconButton(onClick = { viewModel.setShowFavoritesOnly(true) }) {
                                Icon(
                                    Icons.Outlined.FavoriteBorder,
                                    contentDescription = "我的最爱",
                                )
                            }
                        }
                        if (!showFavoritesOnly) {
                            var showSortMenu by remember { mutableStateOf(false) }
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false },
                            ) {
                                Text(
                                    "文件夹排序",
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
                                    "文件排序",
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
                                    Icon(Icons.Filled.Search, contentDescription = "搜索")
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
                        text = "快捷操作选项",
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
                                    Text("编辑文件标签")
                                }
                            }
                            TextButton(
                                onClick = {
                                    viewModel.downloadFile(context, item)
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("下载媒体到本地")
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
                                    Text("永久从服务端删除该文件")
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
                                    viewModel.downloadFolder(context, item)
                                    itemForActions = null
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.Start,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("下载整个文件夹内容")
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
                                    Text("永久从服务端删除该文件夹")
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { itemForActions = null }) {
                        Text("取消")
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
                        text = if (isFolder) "删除文件夹" else "删除文件",
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
                                    text = "强制递归删除（如果文件夹内有其他内容，必须勾选）",
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
                        Text("确认删除")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("取消")
                    }
                }
            )
        }
 
        // Loading Overlay for Deletion
        if (deleteState is com.juziss.localmediahub.viewmodel.DeleteState.Loading) {
            AlertDialog(
                onDismissRequest = {},
                shape = RoundedCornerShape(20.dp),
                title = { Text("正在删除资源...", fontWeight = FontWeight.Bold) },
                text = {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        CircularProgressIndicator()
                        Text("正在清理云端多媒体文件，这可能需要一点时间。")
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
                onFileLongClick = { file -> itemForActions = file },
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
                    title = "我的最爱",
                    message = "收藏的媒体将集中在此处，方便您随时快捷播放。",
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
                    viewModel = viewModel,
                )
            }
            return@Scaffold
        }
 
        when (browseState) {
            is BrowseState.Idle -> {
                BrowseStateCard(
                    title = "正在准备文件列表...",
                    message = "正在载入媒体共享根目录、文件属性及快捷标签，请稍候。",
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
                    title = "浏览目录发生错误",
                    message = (browseState as BrowseState.Error).message,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    actionLabel = "重新尝试",
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
                        title = "本地共享媒体库",
                        message = "请选择运行中的电脑服务端所共享的本地磁盘目录。",
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
                        title = "设备系统盘符",
                        message = "在需要浏览共享媒体库以外的完整磁盘路径时进行切换。",
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
                        title = "系统盘符路径",
                        message = result.currentPath ?: currentPath,
                        meta = "${result.folders.size} 文件夹 · ${filteredFiles.size} 文件",
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
                        title = if (currentPath.isBlank()) "共享媒体库浏览" else currentPath,
                        message = "浏览当前共享目录，支持按标签过滤、长按管理标签和播放离线下载。",
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
                        message = "将您贴上相同标签的多媒体文件跨库收集于此。",
                        meta = "共 ${collection.files.size} 个媒体文件",
                        badge = "主题收藏集",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    if (collection.files.isEmpty()) {
                        BrowseStateCard(
                            title = "该主题收藏集目前为空",
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
                            favorites = favorites,
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
        shape = RoundedCornerShape(16.dp),
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
                shape = RoundedCornerShape(10.dp),
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
                        shape = RoundedCornerShape(4.dp),
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
                    fontWeight = FontWeight.Bold,
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
                    fontWeight = FontWeight.Bold,
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
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (actionLabel != null && onAction != null) {
                    Button(
                        onClick = onAction,
                        shape = RoundedCornerShape(8.dp)
                    ) {
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
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = "正在加载文件列表...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "正在努力加载文件夹、文件列表和标签属性。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

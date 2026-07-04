package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.ui.component.BrowseContent
import com.juziss.localmediahub.ui.component.FolderGrid
import com.juziss.localmediahub.ui.component.SystemDrivesContent
import com.juziss.localmediahub.ui.component.TagFilterBar
import com.juziss.localmediahub.viewmodel.BrowseState
import com.juziss.localmediahub.viewmodel.BrowseViewModel

@Composable
internal fun BrowseStateContent(
    browseState: BrowseState,
    currentPath: String,
    isSystemBrowse: Boolean,
    tags: List<Tag>,
    activeTagFilter: Tag?,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit,
    viewModel: BrowseViewModel,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val contentState = BrowseContentState(
        folderSort = viewModel.folderSortOrder.value,
        fileSort = viewModel.fileSortOrder.value,
        currentPath = currentPath,
        restoreScrollTo = viewModel.restoreScrollTo.value,
    )
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
                message = browseState.message,
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
            val folders = browseState.folders
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
            val drives = browseState.drives
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
            val result = browseState.result
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
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onFileLongClick = onFileLongClick,
                    onFolderLongClick = onFolderLongClick,
                    modifier = Modifier.weight(1f),
                    state = contentState,
                    onSaveScrollPosition = viewModel::saveScrollPosition,
                    onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
                    getScrollPosition = viewModel::getScrollPosition,
                    getThumbnailUrl = viewModel::getThumbnailUrl,
                )
            }
        }
        is BrowseState.Browsed -> {
            val result = browseState.result
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
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onFileLongClick = onFileLongClick,
                    onFolderLongClick = onFolderLongClick,
                    modifier = Modifier.weight(1f),
                    state = contentState,
                    onSaveScrollPosition = viewModel::saveScrollPosition,
                    onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
                    getScrollPosition = viewModel::getScrollPosition,
                    getThumbnailUrl = viewModel::getThumbnailUrl,
                )
            }
        }
        is BrowseState.TagCollection -> {
            val collection = browseState
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
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        onFileLongClick = onFileLongClick,
                        modifier = Modifier.weight(1f),
                        state = contentState,
                        onSaveScrollPosition = viewModel::saveScrollPosition,
                        onConsumeRestoreScroll = viewModel::consumeRestoreScroll,
                        getScrollPosition = viewModel::getScrollPosition,
                        getThumbnailUrl = viewModel::getThumbnailUrl,
                    )
                }
            }
        }
    }
}

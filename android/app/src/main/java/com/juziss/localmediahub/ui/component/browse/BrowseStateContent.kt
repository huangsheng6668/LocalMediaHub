package com.juziss.localmediahub.ui.component.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.LibraryDecoration
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.ui.component.BrowseContent
import com.juziss.localmediahub.ui.component.FolderGrid
import com.juziss.localmediahub.ui.component.SystemDrivesContent
import com.juziss.localmediahub.ui.component.TagFilterBar
import com.juziss.localmediahub.viewmodel.BrowseState

@Composable
internal fun BrowseStateContent(
    browseState: BrowseState,
    state: BrowseContentState,
    isSystemBrowse: Boolean,
    tags: List<Tag>,
    activeTagFilter: Tag?,
    onVideoClick: (MediaFile) -> Unit,
    onImageClick: (MediaFile, List<MediaFile>) -> Unit,
    onTextClick: (MediaFile) -> Unit,
    onToggleFavorite: (MediaFile) -> Unit,
    isFavorite: (String) -> Boolean,
    onFileLongClick: (MediaFile) -> Unit,
    onFolderLongClick: (Folder) -> Unit,
    onRetry: () -> Unit,
    onBrowseFolder: (path: String, name: String) -> Unit,
    onBrowseSystemPath: (path: String, name: String) -> Unit,
    onActiveTagFilterChange: (Tag?) -> Unit,
    filterFilesByTag: (List<MediaFile>) -> List<MediaFile>,
    onSaveScrollPosition: (String, Int) -> Unit,
    onConsumeRestoreScroll: () -> Unit,
    getScrollPosition: (String) -> Int,
    getThumbnailUrl: (MediaFile) -> String,
    innerPadding: androidx.compose.foundation.layout.PaddingValues,
    isSelected: (String) -> Boolean = { false },
    /** Task 5: forwarded to [BrowseContent.videoEnabled]. */
    videoEnabled: Boolean = true,
    /** Task 5: forwarded to [BrowseContent.onVideoDisabledClick]. */
    onVideoDisabledClick: () -> Unit = {},
    /** Paged folder browse: forwarded to the Browsed branch's [BrowseContent]. */
    onLoadMore: () -> Unit = {},
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    decorationFor: (MediaFile) -> LibraryDecoration? = { null },
    onFolderToggleFavorite: (Folder) -> Unit = {},
) {
    val currentPath = state.currentPath
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
                onAction = onRetry,
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
                    icon = painterResource(R.drawable.ic_storage),
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
                        onBrowseFolder(path, folder.name)
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
                    icon = painterResource(R.drawable.ic_storage),
                    title = stringResource(R.string.browse_drive_card_title),
                    message = stringResource(R.string.browse_drive_card_desc),
                    meta = "检测到 ${drives.size} 个磁盘分区",
                    badge = null,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                SystemDrivesContent(
                    drives = drives,
                    onDriveClick = { drivePath ->
                        onBrowseSystemPath(drivePath, drivePath)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        is BrowseState.SystemBrowsed -> {
            val result = browseState.result
            val filteredFiles = filterFilesByTag(result.files)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = painterResource(R.drawable.ic_storage),
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
                        onBrowseSystemPath(folder.path, folder.name)
                    },
                    onVideoClick = onVideoClick,
                    onImageClick = { file ->
                        onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                    },
                    onTextClick = onTextClick,
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onFileLongClick = onFileLongClick,
                    onFolderLongClick = onFolderLongClick,
                    modifier = Modifier.weight(1f),
                    state = state,
                    onSaveScrollPosition = onSaveScrollPosition,
                    onConsumeRestoreScroll = onConsumeRestoreScroll,
                    getScrollPosition = getScrollPosition,
                    getThumbnailUrl = getThumbnailUrl,
                    isSelected = isSelected,
                    videoEnabled = videoEnabled,
                    onVideoDisabledClick = onVideoDisabledClick,
                    // System directories are server-paged like folder browse.
                    onLoadMore = onLoadMore,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    decorationFor = decorationFor,
                    onFolderToggleFavorite = onFolderToggleFavorite,
                )
            }
        }
        is BrowseState.Browsed -> {
            val result = browseState.result
            val filteredFiles = filterFilesByTag(result.files)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                BrowseSummaryCard(
                    icon = painterResource(R.drawable.ic_folder),
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
                                onActiveTagFilterChange(
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
                        onBrowseFolder(path, folder.name)
                    },
                    onVideoClick = onVideoClick,
                    onImageClick = { file ->
                        onImageClick(file, filteredFiles.filter { it.mediaType == "image" })
                    },
                    onTextClick = onTextClick,
                    onToggleFavorite = onToggleFavorite,
                    isFavorite = isFavorite,
                    onFileLongClick = onFileLongClick,
                    onFolderLongClick = onFolderLongClick,
                    modifier = Modifier.weight(1f),
                    state = state,
                    onSaveScrollPosition = onSaveScrollPosition,
                    onConsumeRestoreScroll = onConsumeRestoreScroll,
                    getScrollPosition = getScrollPosition,
                    getThumbnailUrl = getThumbnailUrl,
                    isSelected = isSelected,
                    videoEnabled = videoEnabled,
                    onVideoDisabledClick = onVideoDisabledClick,
                    onLoadMore = onLoadMore,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    decorationFor = decorationFor,
                    onFolderToggleFavorite = onFolderToggleFavorite,
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
                    icon = painterResource(R.drawable.ic_bookmarks),
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
                        onTextClick = onTextClick,
                        onToggleFavorite = onToggleFavorite,
                        isFavorite = isFavorite,
                        onFileLongClick = onFileLongClick,
                        modifier = Modifier.weight(1f),
                        state = state,
                        onSaveScrollPosition = onSaveScrollPosition,
                        onConsumeRestoreScroll = onConsumeRestoreScroll,
                        getScrollPosition = getScrollPosition,
                        getThumbnailUrl = getThumbnailUrl,
                        videoEnabled = videoEnabled,
                        onVideoDisabledClick = onVideoDisabledClick,
                        decorationFor = decorationFor,
                        onFolderToggleFavorite = onFolderToggleFavorite,
                    )
                }
            }
        }
    }
}

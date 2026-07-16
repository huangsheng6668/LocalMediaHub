package com.juziss.localmediahub.viewmodel

import android.content.Context
import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.SystemBrowseResult
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * BrowseViewModel delegate responsible for navigation (loadRoots / browseFolder /
 * navigateBack / etc), sort ordering, scroll-position persistence and the URL
 * builders for media playback.
 *
 * Round 18 refactor: extracted verbatim from BrowseViewModel. Shared state
 * (browseState / currentPath / sort orders / raw folders+files / tag filter)
 * is owned by [BrowseSharedState]; this delegate reads and writes those flows
 * directly. Scroll-position bookkeeping is private to this delegate because
 * no other delegate consumes it.
 *
 * Navigation entry points that previously wrapped their body in
 * `viewModelScope.launch { ... }` are now `suspend fun`s — the caller
 * (BrowseViewModel) supplies the coroutine scope. This keeps the delegate
 * free of Android lifecycle dependencies.
 */
internal class BrowseNavigator(
    @ApplicationContext private val appContext: Context,
    private val repository: MediaRepository,
    private val recentActivityStore: RecentActivityStore,
    private val sharedState: BrowseSharedState,
) {

    // ── Scroll position persistence ──────────────────────────
    private val _scrollPositions = mutableMapOf<String, Int>()
    private val _restoreScrollTo = MutableStateFlow<String?>(null)
    val restoreScrollTo: StateFlow<String?> = _restoreScrollTo.asStateFlow()

    fun saveScrollPosition(path: String, index: Int) {
        if (index > 0) _scrollPositions[path] = index
    }

    fun getScrollPosition(path: String): Int = _scrollPositions[path] ?: 0

    fun consumeRestoreScroll() {
        _restoreScrollTo.value = null
    }

    /** Load root folders. */
    suspend fun loadRoots() {
        sharedState.browseState.value = BrowseState.Loading
        sharedState.isSystemBrowse.value = false
        sharedState.activeTagFilter.value = null
        when (val result = repository.getFolders()) {
            is NetworkResult.Success -> {
                sharedState.browseState.value = BrowseState.RootFolders(result.data)
                sharedState.currentPath.value = ""
                sharedState.pathStack.value = emptyList()
            }
            is NetworkResult.Error -> {
                sharedState.browseState.value = BrowseState.Error(result.message)
            }
            is NetworkResult.Loading -> {}
        }
    }

    /** Load system drives (full filesystem browse mode). */
    suspend fun loadSystemDrives() {
        sharedState.browseState.value = BrowseState.Loading
        sharedState.isSystemBrowse.value = true
        sharedState.activeTagFilter.value = null
        when (val result = repository.getSystemDrives()) {
            is NetworkResult.Success -> {
                val drives = result.data
                sharedState.browseState.value = BrowseState.SystemDrives(drives)
                sharedState.currentPath.value = ""
                sharedState.pathStack.value = emptyList()
            }
            is NetworkResult.Error -> {
                sharedState.browseState.value = BrowseState.Error(result.message)
            }
            is NetworkResult.Loading -> {}
        }
    }

    /** Browse a system path (absolute path, any drive). */
    suspend fun browseSystemPath(absolutePath: String, folderName: String) {
        // Save current scroll position before navigating
        saveScrollPosition(sharedState.currentPath.value, 0) // will be updated by UI
        sharedState.browseState.value = BrowseState.Loading
        sharedState.pathStack.value = sharedState.pathStack.value + sharedState.currentPath.value
        sharedState.currentPath.value = absolutePath
        sharedState.isSystemBrowse.value = true

        when (val result = repository.browseSystemPath(absolutePath)) {
            is NetworkResult.Success -> {
                recentActivityStore.saveLastBrowseLocation(
                    path = absolutePath,
                    title = folderName,
                    isSystemBrowse = true,
                )
                applySystemResult(result.data)
            }
            is NetworkResult.Error -> sharedState.emitBrowseError(result.message)
            is NetworkResult.Loading -> {}
        }
    }

    /** Browse into a specific folder. */
    suspend fun browseFolder(relativePath: String, folderName: String) {
        sharedState.browseState.value = BrowseState.Loading
        sharedState.pathStack.value = sharedState.pathStack.value + sharedState.currentPath.value
        sharedState.currentPath.value = relativePath

        when (val result = repository.browseFolder(relativePath)) {
            is NetworkResult.Success -> {
                recentActivityStore.saveLastBrowseLocation(
                    path = relativePath,
                    title = folderName,
                    isSystemBrowse = false,
                )
                applyFolderResult(result.data)
            }
            is NetworkResult.Error -> sharedState.emitBrowseError(result.message)
            is NetworkResult.Loading -> {}
        }
    }

    /** Go back to previous path level. */
    suspend fun navigateBack() {
        val stack = sharedState.pathStack.value
        if (stack.isEmpty()) {
            if (sharedState.browseState.value is BrowseState.TagCollection) {
                loadRoots()
                return
            }
            if (sharedState.isSystemBrowse.value) {
                loadSystemDrives()
            } else {
                loadRoots()
            }
            return
        }
        val previousPath = stack.last()
        sharedState.pathStack.value = stack.dropLast(1)

        sharedState.currentPath.value = previousPath

        if (previousPath.isEmpty()) {
            if (sharedState.isSystemBrowse.value) {
                loadSystemDrives()
            } else {
                loadRoots()
            }
        } else if (sharedState.isSystemBrowse.value) {
            when (val result = repository.browseSystemPath(previousPath)) {
                is NetworkResult.Success -> {
                    applySystemResult(result.data)
                    _restoreScrollTo.value = previousPath
                }
                is NetworkResult.Error -> sharedState.emitBrowseError(result.message)
                is NetworkResult.Loading -> {}
            }
        } else {
            when (val result = repository.browseFolder(previousPath)) {
                is NetworkResult.Success -> {
                    applyFolderResult(result.data)
                    _restoreScrollTo.value = previousPath
                }
                is NetworkResult.Error -> sharedState.emitBrowseError(result.message)
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun canGoBack(): Boolean = sharedState.pathStack.value.isNotEmpty()

    suspend fun setFolderSortOrder(order: SortOrder) {
        sharedState.folderSortOrder.value = order
        val rawFolders = sharedState.rawFolders.value
        if (rawFolders.isEmpty()) return
        val sortedFolders = withContext(Dispatchers.Default) {
            BrowseSorter.sortFolders(rawFolders, sharedState.folderSortOrder.value)
        }
        when (val state = sharedState.browseState.value) {
            is BrowseState.Browsed -> {
                sharedState.browseState.value = BrowseState.Browsed(
                    state.result.copy(folders = sortedFolders)
                )
            }
            is BrowseState.SystemBrowsed -> {
                sharedState.browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
                    currentPath = state.result.currentPath,
                    drives = state.result.drives,
                    folders = sortedFolders,
                    files = state.result.files,
                ))
            }
            else -> {}
        }
    }

    suspend fun setFileSortOrder(order: SortOrder) {
        sharedState.fileSortOrder.value = order
        val rawFiles = sharedState.rawFiles.value
        if (rawFiles.isEmpty()) return
        val sortedFiles = withContext(Dispatchers.Default) {
            BrowseSorter.sortFiles(rawFiles, sharedState.fileSortOrder.value)
        }
        when (val state = sharedState.browseState.value) {
            is BrowseState.Browsed -> {
                sharedState.browseState.value = BrowseState.Browsed(
                    state.result.copy(files = sortedFiles)
                )
            }
            is BrowseState.SystemBrowsed -> {
                sharedState.browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
                    currentPath = state.result.currentPath,
                    drives = state.result.drives,
                    folders = state.result.folders,
                    files = sortedFiles,
                ))
            }
            is BrowseState.TagCollection -> {
                sharedState.browseState.value = BrowseState.TagCollection(
                    title = state.title,
                    files = sortedFiles,
                )
            }
            else -> {}
        }
    }

    /** 成功的文件夹浏览结果：存 raw、排序、emit Browsed。 */
    private suspend fun applyFolderResult(data: BrowseResult) {
        sharedState.rawFolders.value = data.folders
        sharedState.rawFiles.value = data.files
        val sortedFolders = withContext(Dispatchers.Default) {
            BrowseSorter.sortFolders(data.folders, sharedState.folderSortOrder.value)
        }
        val sortedFiles = withContext(Dispatchers.Default) {
            BrowseSorter.sortFiles(data.files, sharedState.fileSortOrder.value)
        }
        sharedState.browseState.value = BrowseState.Browsed(
            data.copy(folders = sortedFolders, files = sortedFiles)
        )
    }

    /** 成功的系统浏览结果：存 raw、排序、emit SystemBrowsed。 */
    private suspend fun applySystemResult(data: SystemBrowseResult) {
        sharedState.rawFolders.value = data.folders
        sharedState.rawFiles.value = data.files
        val sortedFolders = withContext(Dispatchers.Default) {
            BrowseSorter.sortFolders(data.folders, sharedState.folderSortOrder.value)
        }
        val sortedFiles = withContext(Dispatchers.Default) {
            BrowseSorter.sortFiles(data.files, sharedState.fileSortOrder.value)
        }
        sharedState.browseState.value = BrowseState.SystemBrowsed(
            SystemBrowseResult(
                currentPath = data.currentPath,
                drives = data.drives,
                folders = sortedFolders,
                files = sortedFiles,
            )
        )
    }

    fun getVideoStreamUrl(file: MediaFile): String {
        return repository.getMediaStreamUrl(file.path)
    }

    fun getThumbnailUrl(file: MediaFile): String {
        return repository.getMediaThumbnailUrl(file.path)
    }

    fun getOriginalImageUrl(file: MediaFile): String {
        return repository.getMediaOriginalImageUrl(file.path)
    }

    suspend fun refreshCurrentDirectory(forceNetwork: Boolean = false) {
        val path = sharedState.currentPath.value
        val state = sharedState.browseState.value
        if (state is BrowseState.TagCollection) {
            val tag = sharedState.activeTagFilter.value
            if (tag != null) {
                openCollection(tag)
            }
            return
        }

        if (sharedState.isSystemBrowse.value) {
            if (path.isEmpty()) {
                loadSystemDrives()
            } else {
                when (val result = repository.browseSystemPath(path, forceNetwork = forceNetwork)) {
                    is NetworkResult.Success -> applySystemResult(result.data)
                    is NetworkResult.Error -> sharedState.emitBrowseError(result.message)
                    is NetworkResult.Loading -> {}
                }
            }
        } else {
            if (path.isEmpty()) {
                loadRoots()
            } else {
                when (val result = repository.browseFolder(path, forceNetwork = forceNetwork)) {
                    is NetworkResult.Success -> applyFolderResult(result.data)
                    is NetworkResult.Error -> sharedState.emitBrowseError(result.message)
                    is NetworkResult.Loading -> {}
                }
            }
        }
    }

    /** Mirrors BrowseViewModel.openCollection so refreshCurrentDirectory can
     *  re-enter a tag collection. Kept verbatim from BrowseViewModel except for
     *  sharedState substitutions. */
    suspend fun openCollection(tag: Tag) {
        sharedState.browseState.value = BrowseState.Loading
        sharedState.showFavoritesOnly.value = false
        sharedState.activeTagFilter.value = tag
        sharedState.currentPath.value = ""
        sharedState.pathStack.value = emptyList()
        sharedState.isSystemBrowse.value = false

        when (val result = repository.getTaggedMedia(tag.id)) {
            is NetworkResult.Success -> {
                sharedState.rawFolders.value = emptyList()
                sharedState.rawFiles.value = result.data
                val sortedFiles = withContext(Dispatchers.Default) {
                    BrowseSorter.sortFiles(result.data, sharedState.fileSortOrder.value)
                }
                sharedState.browseState.value = BrowseState.TagCollection(
                    title = tag.name,
                    files = sortedFiles,
                )
            }
            is NetworkResult.Error -> {
                sharedState.browseState.value = BrowseState.Error(result.message)
            }
            is NetworkResult.Loading -> {}
        }
    }
}

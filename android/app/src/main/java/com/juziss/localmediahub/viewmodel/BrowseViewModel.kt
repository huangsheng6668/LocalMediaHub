package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.SearchResult
import com.juziss.localmediahub.data.SystemBrowseResult
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.DownloadEntry
import com.juziss.localmediahub.data.LibraryDecoration
import com.juziss.localmediahub.data.ReadingStatus
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import com.juziss.localmediahub.data.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context
import androidx.annotation.StringRes
import com.juziss.localmediahub.R
import javax.inject.Inject

enum class SortOrder(@StringRes val labelRes: Int) {
    NAME_ASC(R.string.sort_name_asc),
    NAME_DESC(R.string.sort_name_desc),
    NUMERIC_ASC(R.string.sort_numeric_asc),
    NUMERIC_DESC(R.string.sort_numeric_desc),
    SIZE_ASC(R.string.sort_size_asc),
    SIZE_DESC(R.string.sort_size_desc),
    TIME_ASC(R.string.sort_time_asc),
    TIME_DESC(R.string.sort_time_desc),
}

@HiltViewModel
class BrowseViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val downloadsStore: DownloadsStore,
    private val repository: MediaRepository,
    private val downloadManager: DownloadManager,
) : ViewModel() {

    // ── Delegates (Round 18 C1-C7) ───────────────────────────────

    private val sharedState = BrowseSharedState()
    private val navigator = BrowseNavigator(appContext, repository, recentActivityStore, sharedState)
    private val favoritesController = FavoritesController(favoritesStore, repository, sharedState)
    private val libraryController = LibraryController(repository, sharedState)
    private val tagController = TagController(repository, sharedState)
    private val searchController = SearchController(repository, sharedState)
    private val downloadController = DownloadController(downloadManager, repository, downloadsStore, sharedState)
    private val deleteController = DeleteController(repository, sharedState)

    init {
        favoritesController.startCollecting(viewModelScope)
        libraryController.startCollecting(viewModelScope)
    }

    // ── Toast (cross-cutting, stays in ViewModel) ─────────────────

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    // ── Public state: SharedState flows ───────────────────────────

    val browseState: StateFlow<BrowseState>
        get() = sharedState.browseState.asStateFlow()

    // Task 5: surfaces MediaRepository.isBleDegraded to the browse UI so video
    // list items can render greyed + intercept clicks (Snackbar) while the
    // list is being served over the BLE fallback transport. The underlying
    // StateFlow is the repository's; exposing it here lets BrowseScreen
    // collectAsState() without grabbing the repository directly.
    val isBleDegraded: StateFlow<Boolean>
        get() = repository.isBleDegraded

    val currentPath: StateFlow<String>
        get() = sharedState.currentPath.asStateFlow()

    val isSystemBrowse: StateFlow<Boolean>
        get() = sharedState.isSystemBrowse.asStateFlow()

    val folderSortOrder: StateFlow<SortOrder>
        get() = sharedState.folderSortOrder.asStateFlow()

    val fileSortOrder: StateFlow<SortOrder>
        get() = sharedState.fileSortOrder.asStateFlow()

    val activeTagFilter: StateFlow<Tag?>
        get() = sharedState.activeTagFilter.asStateFlow()

    // ── Public state: Navigator flows ─────────────────────────────

    val restoreScrollTo: StateFlow<String?>
        get() = navigator.restoreScrollTo

    // ── Public state: Favorites flows ─────────────────────────────

    val favorites: StateFlow<Set<String>>
        get() = favoritesController.favorites

    val favoriteFiles: StateFlow<List<MediaFile>>
        get() = favoritesController.favoriteFiles

    val showFavoritesOnly: StateFlow<Boolean>
        get() = favoritesController.showFavoritesOnly

    // ── Public state: Library flows ───────────────────────────────

    val libraryStates: StateFlow<Map<String, LibraryDecoration>>
        get() = sharedState.libraryStates.asStateFlow()

    val statusFilter: StateFlow<ReadingStatus?>
        get() = sharedState.statusFilter.asStateFlow()

    // ── Public state: Tags flows ──────────────────────────────────

    val tags: StateFlow<List<Tag>>
        get() = tagController.tags

    val fileTags: StateFlow<Map<String, List<Tag>>>
        get() = tagController.fileTags

    // ── Public state: Search flows ────────────────────────────────

    val searchQuery: StateFlow<String>
        get() = searchController.searchQuery

    val searchState: StateFlow<SearchState>
        get() = searchController.searchState

    // ── Public state: Delete flows ────────────────────────────────

    val deleteState: StateFlow<DeleteState>
        get() = deleteController.deleteState

    // ── Public state: Downloads flows ─────────────────────────────

    val downloadedFiles = downloadController.downloadedFiles

    // ── Navigation ────────────────────────────────────────────────

    fun loadRoots() {
        viewModelScope.launch { navigator.loadRoots() }
    }

    fun loadSystemDrives() {
        viewModelScope.launch { navigator.loadSystemDrives() }
    }

    fun browseSystemPath(absolutePath: String, folderName: String) {
        viewModelScope.launch { navigator.browseSystemPath(absolutePath, folderName) }
    }

    fun browseFolder(relativePath: String, folderName: String) {
        viewModelScope.launch { navigator.browseFolder(relativePath, folderName) }
    }

    fun navigateBack() {
        viewModelScope.launch { navigator.navigateBack() }
    }

    fun canGoBack(): Boolean = navigator.canGoBack()

    fun setFolderSortOrder(order: SortOrder) {
        viewModelScope.launch { navigator.setFolderSortOrder(order) }
    }

    fun setFileSortOrder(order: SortOrder) {
        viewModelScope.launch { navigator.setFileSortOrder(order) }
    }

    fun refreshCurrentDirectory() {
        viewModelScope.launch { navigator.refreshCurrentDirectory() }
    }

    // ── Scroll ────────────────────────────────────────────────────

    fun saveScrollPosition(path: String, index: Int) {
        navigator.saveScrollPosition(path, index)
    }

    fun getScrollPosition(path: String): Int = navigator.getScrollPosition(path)

    fun consumeRestoreScroll() {
        navigator.consumeRestoreScroll()
    }

    // ── Paged load-more ────────────────────────────────────────────

    val hasMore: StateFlow<Boolean>
        get() = navigator.hasMore

    val loadingMore: StateFlow<Boolean>
        get() = navigator.loadingMore

    fun loadMore() {
        viewModelScope.launch { navigator.loadMore() }
    }

    // ── URL builders ──────────────────────────────────────────────

    fun getVideoStreamUrl(file: MediaFile): String {
        return navigator.getVideoStreamUrl(file)
    }

    fun getThumbnailUrl(file: MediaFile): String {
        return navigator.getThumbnailUrl(file)
    }

    fun getOriginalImageUrl(file: MediaFile): String {
        return navigator.getOriginalImageUrl(file)
    }

    // ── Favorites ─────────────────────────────────────────────────

    fun isFavorite(relativePath: String): Boolean {
        return favoritesController.isFavorite(relativePath)
    }

    fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean = sharedState.isSystemBrowse.value) {
        viewModelScope.launch { favoritesController.toggleFavorite(file, isSystemBrowse) }
    }

    fun toggleFavoriteFolder(folder: Folder, isSystemBrowse: Boolean = sharedState.isSystemBrowse.value) {
        viewModelScope.launch { favoritesController.toggleFavoriteFolder(folder, isSystemBrowse) }
    }

    fun setStatus(path: String, status: ReadingStatus?) {
        viewModelScope.launch { libraryController.setStatus(path, status) }
    }

    fun setStatusFilter(s: ReadingStatus?) {
        libraryController.setStatusFilter(s)
    }

    fun decorationFor(file: MediaFile): LibraryDecoration? {
        return sharedState.libraryStates.value[file.path]
    }

    fun setShowFavoritesOnly(show: Boolean) {
        favoritesController.setShowFavoritesOnly(show)
    }

    fun isFavoriteSystemBrowse(file: MediaFile): Boolean {
        return favoritesController.isFavoriteSystemBrowse(file)
    }

    fun getFavoriteVideoStreamUrl(file: MediaFile): String {
        return favoritesController.getFavoriteVideoStreamUrl(file)
    }

    fun getFavoriteThumbnailUrl(file: MediaFile): String {
        return favoritesController.getFavoriteThumbnailUrl(file)
    }

    fun getFavoriteOriginalImageUrl(file: MediaFile): String {
        return favoritesController.getFavoriteOriginalImageUrl(file)
    }

    // ── Downloads ─────────────────────────────────────────────────

    fun removeDownload(file: MediaFile) {
        downloadController.removeDownload(file, viewModelScope)
    }

    fun removeDownloads(relativePaths: List<String>) {
        downloadController.removeDownloads(relativePaths, viewModelScope)
    }

    fun downloadFile(file: MediaFile) {
        downloadController.downloadFile(
            file = file,
            videoStreamUrl = getVideoStreamUrl(file),
            imageUrl = getOriginalImageUrl(file),
            onMessage = ::showToast,
            scope = viewModelScope
        )
    }

    fun downloadFolder(folder: Folder) {
        downloadController.downloadFolder(
            folder = folder,
            onMessage = ::showToast,
            scope = viewModelScope
        )
    }

    // ── Tags ──────────────────────────────────────────────────────

    fun loadTags() {
        tagController.loadTags(viewModelScope)
    }

    fun createTag(name: String, color: String = "#808080") {
        tagController.createTag(name, color, viewModelScope)
    }

    fun deleteTag(tagId: String) {
        tagController.deleteTag(tagId, viewModelScope)
    }

    fun tagFile(tagId: String, filePath: String) {
        tagController.tagFile(tagId, filePath, viewModelScope)
    }

    fun untagFile(tagId: String, filePath: String) {
        tagController.untagFile(tagId, filePath, viewModelScope)
    }

    fun getTagsForFile(filePath: String): List<Tag> {
        return tagController.getTagsForFile(filePath)
    }

    fun setActiveTagFilter(tag: Tag?) {
        tagController.setActiveTagFilter(tag)
    }

    fun openCollection(tag: Tag) {
        tagController.openCollection(tag, viewModelScope)
    }

    fun filterFilesByTag(files: List<MediaFile>): List<MediaFile> {
        return tagController.filterFilesByTag(files)
    }

    // ── Search ────────────────────────────────────────────────────

    fun updateSearchQuery(query: String) {
        searchController.updateSearchQuery(query)
    }

    fun search() {
        searchController.search(viewModelScope)
    }

    fun clearSearch() {
        searchController.clearSearch()
    }

    fun isSystemBrowseMode(): Boolean = sharedState.isSystemBrowse.value

    // ── Deletion ──────────────────────────────────────────────────

    fun clearDeleteState() {
        deleteController.clearDeleteState()
    }

    fun deletePath(path: String, recursive: Boolean) {
        deleteController.deletePath(
            path = path,
            recursive = recursive,
            onRefresh = navigator::refreshCurrentDirectory,
            scope = viewModelScope
        )
    }

    fun deletePaths(paths: List<String>, recursive: Boolean = true) {
        deleteController.deletePaths(
            paths = paths,
            recursive = recursive,
            onRefresh = navigator::refreshCurrentDirectory,
            scope = viewModelScope
        )
    }
}

sealed class BrowseState {
    data object Idle : BrowseState()
    data object Loading : BrowseState()
    data class RootFolders(val folders: List<com.juziss.localmediahub.data.Folder>) : BrowseState()
    data class SystemDrives(val drives: List<String>) : BrowseState()
    data class SystemBrowsed(val result: SystemBrowseResult) : BrowseState()
    data class Browsed(val result: BrowseResult) : BrowseState()
    data class TagCollection(val title: String, val files: List<MediaFile>) : BrowseState()
    data class Error(val message: String) : BrowseState()
}

sealed class SearchState {
    data object Idle : SearchState()
    data object Loading : SearchState()
    data class Results(val result: SearchResult) : SearchState()
    data class Error(val message: String) : SearchState()
}

sealed class DeleteState {
    data object Idle : DeleteState()
    data object Loading : DeleteState()
    data class Success(val message: String) : DeleteState()
    data class Error(val message: String) : DeleteState()
}

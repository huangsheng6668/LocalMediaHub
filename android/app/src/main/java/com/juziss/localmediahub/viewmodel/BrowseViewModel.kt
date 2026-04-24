package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.BrowseResult
import com.juziss.localmediahub.data.FavoriteMediaEntry
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.SearchResult
import com.juziss.localmediahub.data.SystemBrowseResult
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SortOrder(val label: String) {
    NAME_ASC("Name A-Z"),
    NAME_DESC("Name Z-A"),
    NUMERIC_ASC("Number 1→9"),
    NUMERIC_DESC("Number 9→1"),
    SIZE_ASC("Smallest first"),
    SIZE_DESC("Largest first"),
    TIME_ASC("Oldest first"),
    TIME_DESC("Newest first"),
}

/** Extract leading number from a string like "007_gjco" → 7.0, "abc" → null */
private fun extractLeadingNumber(s: String): Double? {
    val sb = StringBuilder()
    for (ch in s) {
        if (ch.isDigit()) sb.append(ch) else break
    }
    return if (sb.isNotEmpty()) sb.toString().toDouble() else null
}

/** Compare two strings with natural/numeric ordering (e.g., "2" < "10"). */
private fun compareNatural(a: String, b: String): Int {
    val regex = Regex("\\d+|\\D+")
    val tokensA = regex.findAll(a.lowercase()).map { it.value }.toList()
    val tokensB = regex.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(tokensA.size, tokensB.size)) {
        val ta = tokensA[i]
        val tb = tokensB[i]
        val numA = ta.toIntOrNull()
        val numB = tb.toIntOrNull()
        val cmp = if (numA != null && numB != null) {
            numA.compareTo(numB)
        } else {
            ta.compareTo(tb)
        }
        if (cmp != 0) return cmp
    }
    return tokensA.size.compareTo(tokensB.size)
}

class BrowseViewModel(
    private val favoritesStore: FavoritesStore? = null,
    private val recentActivityStore: RecentActivityStore? = null,
) : ViewModel() {

    private val repository = MediaRepository()

    private val _browseState = MutableStateFlow<BrowseState>(BrowseState.Idle)
    val browseState: StateFlow<BrowseState> = _browseState.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    private val _pathStack = MutableStateFlow<List<String>>(emptyList())

    // Whether we're in system-wide browse mode (drives → arbitrary paths)
    private val _isSystemBrowse = MutableStateFlow(false)
    val isSystemBrowse: StateFlow<Boolean> = _isSystemBrowse.asStateFlow()

    // Raw unsorted results
    private val _rawFolders = MutableStateFlow<List<Folder>>(emptyList())
    private val _rawFiles = MutableStateFlow<List<MediaFile>>(emptyList())

    private val _folderSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val folderSortOrder: StateFlow<SortOrder> = _folderSortOrder.asStateFlow()

    private val _fileSortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val fileSortOrder: StateFlow<SortOrder> = _fileSortOrder.asStateFlow()

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

    // ── Favorites ─────────────────────────────────────────────

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _favoriteFiles = MutableStateFlow<List<MediaFile>>(emptyList())
    val favoriteFiles: StateFlow<List<MediaFile>> = _favoriteFiles.asStateFlow()

    private val _favoriteAccessModes = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    init {
        favoritesStore?.let { store ->
            viewModelScope.launch {
                store.favorites.collect { favoritePaths ->
                    _favorites.value = favoritePaths
                }
            }
            viewModelScope.launch {
                store.favoriteFiles.collect { files ->
                    _favoriteFiles.value = files
                }
            }
            viewModelScope.launch {
                store.favoriteEntries.collect { entries ->
                    _favoriteAccessModes.value = entries.associateFavoriteModes()
                }
            }
        }
    }

    fun isFavorite(relativePath: String): Boolean {
        return relativePath in _favorites.value
    }

    fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean = _isSystemBrowse.value) {
        favoritesStore?.let { store ->
            viewModelScope.launch {
                store.toggleFavorite(file, isSystemBrowse)
            }
        }
    }

    fun setShowFavoritesOnly(show: Boolean) {
        _showFavoritesOnly.value = show
    }

    /** Filter files to only show favorites when the filter is active. */
    fun filterFilesByFavorites(files: List<MediaFile>): List<MediaFile> {
        return if (_showFavoritesOnly.value) {
            files.filter { it.relativePath in _favorites.value }
        } else {
            files
        }
    }

    /** Load root folders. */
    fun loadRoots() {
        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            _isSystemBrowse.value = false
            _activeTagFilter.value = null
            when (val result = repository.getFolders()) {
                is NetworkResult.Success -> {
                    _browseState.value = BrowseState.RootFolders(result.data)
                    _currentPath.value = ""
                    _pathStack.value = emptyList()
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** Load system drives (full filesystem browse mode). */
    fun loadSystemDrives() {
        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            _isSystemBrowse.value = true
            _activeTagFilter.value = null
            when (val result = repository.getSystemDrives()) {
                is NetworkResult.Success -> {
                    val drives = result.data
                    _browseState.value = BrowseState.SystemDrives(drives)
                    _currentPath.value = ""
                    _pathStack.value = emptyList()
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** Browse a system path (absolute path, any drive). */
    fun browseSystemPath(absolutePath: String, folderName: String) {
        viewModelScope.launch {
            // Save current scroll position before navigating
            saveScrollPosition(_currentPath.value, 0) // will be updated by UI
            _browseState.value = BrowseState.Loading
            _pathStack.value = _pathStack.value + _currentPath.value
            _currentPath.value = absolutePath
            _isSystemBrowse.value = true

            when (val result = repository.browseSystemPath(absolutePath)) {
                is NetworkResult.Success -> {
                    val data = result.data
                    _rawFolders.value = data.folders
                    _rawFiles.value = data.files
                    recentActivityStore?.saveLastBrowseLocation(
                        path = absolutePath,
                        title = folderName,
                        isSystemBrowse = true,
                    )
                    _browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
                        currentPath = data.currentPath,
                        drives = data.drives,
                        folders = applySortToFolders(data.folders),
                        files = applySortToFiles(data.files),
                    ))
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** Browse into a specific folder. */
    fun browseFolder(relativePath: String, folderName: String) {
        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            _pathStack.value = _pathStack.value + _currentPath.value
            _currentPath.value = relativePath

            when (val result = repository.browseFolder(relativePath)) {
                is NetworkResult.Success -> {
                    _rawFolders.value = result.data.folders
                    _rawFiles.value = result.data.files
                    recentActivityStore?.saveLastBrowseLocation(
                        path = relativePath,
                        title = folderName,
                        isSystemBrowse = false,
                    )
                    _browseState.value = BrowseState.Browsed(result.data.copy(
                        folders = applySortToFolders(result.data.folders),
                        files = applySortToFiles(result.data.files),
                    ))
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    /** Go back to previous path level. */
    fun navigateBack() {
        val stack = _pathStack.value
        if (stack.isEmpty()) {
            if (_browseState.value is BrowseState.TagCollection) {
                loadRoots()
                return
            }
            if (_isSystemBrowse.value) {
                loadSystemDrives()
            } else {
                loadRoots()
            }
            return
        }
        val previousPath = stack.last()
        _pathStack.value = stack.dropLast(1)

        viewModelScope.launch {
            _currentPath.value = previousPath

            if (previousPath.isEmpty()) {
                if (_isSystemBrowse.value) {
                    loadSystemDrives()
                } else {
                    loadRoots()
                }
            } else if (_isSystemBrowse.value) {
                when (val result = repository.browseSystemPath(previousPath)) {
                    is NetworkResult.Success -> {
                        val data = result.data
                        _rawFolders.value = data.folders
                        _rawFiles.value = data.files
                        _browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
                            currentPath = data.currentPath,
                            drives = data.drives,
                            folders = applySortToFolders(data.folders),
                            files = applySortToFiles(data.files),
                        ))
                        _restoreScrollTo.value = previousPath
                    }
                    is NetworkResult.Error -> {
                        _browseState.value = BrowseState.Error(result.message)
                    }
                    is NetworkResult.Loading -> {}
                }
            } else {
                when (val result = repository.browseFolder(previousPath)) {
                    is NetworkResult.Success -> {
                        _rawFolders.value = result.data.folders
                        _rawFiles.value = result.data.files
                        _browseState.value = BrowseState.Browsed(result.data.copy(
                            folders = applySortToFolders(result.data.folders),
                            files = applySortToFiles(result.data.files),
                        ))
                        _restoreScrollTo.value = previousPath
                    }
                    is NetworkResult.Error -> {
                        _browseState.value = BrowseState.Error(result.message)
                    }
                    is NetworkResult.Loading -> {}
                }
            }
        }
    }

    fun canGoBack(): Boolean = _pathStack.value.isNotEmpty()

    fun setFolderSortOrder(order: SortOrder) {
        _folderSortOrder.value = order
        val rawFolders = _rawFolders.value
        if (rawFolders.isEmpty()) return
        val sortedFolders = applySortToFolders(rawFolders)
        when (val state = _browseState.value) {
            is BrowseState.Browsed -> {
                _browseState.value = BrowseState.Browsed(
                    state.result.copy(folders = sortedFolders)
                )
            }
            is BrowseState.SystemBrowsed -> {
                _browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
                    currentPath = state.result.currentPath,
                    drives = state.result.drives,
                    folders = sortedFolders,
                    files = state.result.files,
                ))
            }
            else -> {}
        }
    }

    fun setFileSortOrder(order: SortOrder) {
        _fileSortOrder.value = order
        val rawFiles = _rawFiles.value
        if (rawFiles.isEmpty()) return
        val sortedFiles = applySortToFiles(rawFiles)
        when (val state = _browseState.value) {
            is BrowseState.Browsed -> {
                _browseState.value = BrowseState.Browsed(
                    state.result.copy(files = sortedFiles)
                )
            }
            is BrowseState.SystemBrowsed -> {
                _browseState.value = BrowseState.SystemBrowsed(SystemBrowseResult(
                    currentPath = state.result.currentPath,
                    drives = state.result.drives,
                    folders = state.result.folders,
                    files = sortedFiles,
                ))
            }
            is BrowseState.TagCollection -> {
                _browseState.value = BrowseState.TagCollection(
                    title = state.title,
                    files = sortedFiles,
                )
            }
            else -> {}
        }
    }

    private fun applySortToFolders(folders: List<Folder>): List<Folder> {
        return when (_folderSortOrder.value) {
            SortOrder.NAME_ASC -> folders.sortedWith { a, b -> compareNatural(a.name, b.name) }
            SortOrder.NAME_DESC -> folders.sortedWith { a, b -> compareNatural(b.name, a.name) }
            SortOrder.NUMERIC_ASC -> folders.sortedBy { extractLeadingNumber(it.name) ?: Double.MAX_VALUE }
            SortOrder.NUMERIC_DESC -> folders.sortedByDescending { extractLeadingNumber(it.name) ?: Double.MIN_VALUE }
            SortOrder.TIME_ASC -> folders.sortedBy { it.modifiedTime }
            SortOrder.TIME_DESC -> folders.sortedByDescending { it.modifiedTime }
            else -> folders // SIZE sorts don't apply to folders
        }
    }

    private fun applySortToFiles(files: List<MediaFile>): List<MediaFile> {
        return when (_fileSortOrder.value) {
            SortOrder.NAME_ASC -> files.sortedWith { a, b -> compareNatural(a.name, b.name) }
            SortOrder.NAME_DESC -> files.sortedWith { a, b -> compareNatural(b.name, a.name) }
            SortOrder.NUMERIC_ASC -> files.sortedBy { extractLeadingNumber(it.name) ?: Double.MAX_VALUE }
            SortOrder.NUMERIC_DESC -> files.sortedByDescending { extractLeadingNumber(it.name) ?: Double.MIN_VALUE }
            SortOrder.SIZE_ASC -> files.sortedBy { it.size }
            SortOrder.SIZE_DESC -> files.sortedByDescending { it.size }
            SortOrder.TIME_ASC -> files.sortedBy { it.modifiedTime }
            SortOrder.TIME_DESC -> files.sortedByDescending { it.modifiedTime }
        }
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

    fun isFavoriteSystemBrowse(file: MediaFile): Boolean {
        return _favoriteAccessModes.value[file.relativePath] == true
    }

    fun getFavoriteVideoStreamUrl(file: MediaFile): String {
        return repository.getMediaStreamUrl(file.path)
    }

    fun getFavoriteThumbnailUrl(file: MediaFile): String {
        return repository.getMediaThumbnailUrl(file.path)
    }

    fun getFavoriteOriginalImageUrl(file: MediaFile): String {
        return repository.getMediaOriginalImageUrl(file.path)
    }

    // ── Tags ──────────────────────────────────────────────

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _fileTags = MutableStateFlow<Map<String, List<Tag>>>(emptyMap())
    val fileTags: StateFlow<Map<String, List<Tag>>> = _fileTags.asStateFlow()

    private val _activeTagFilter = MutableStateFlow<Tag?>(null)
    val activeTagFilter: StateFlow<Tag?> = _activeTagFilter.asStateFlow()


    fun loadTags() {
        viewModelScope.launch {
            when (val result = repository.getTags()) {
                is NetworkResult.Success -> {
                    _tags.value = result.data
                    loadAllFileTags() // Preload all file-tag mappings
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun createTag(name: String, color: String = "#808080") {
        viewModelScope.launch {
            when (repository.createTag(name, color)) {
                is NetworkResult.Success -> {
                    loadTags()
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun deleteTag(tagId: String) {
        viewModelScope.launch {
            when (repository.deleteTag(tagId)) {
                is NetworkResult.Success -> {
                    loadTags()
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun tagFile(tagId: String, filePath: String) {
        viewModelScope.launch {
            when (repository.tagFile(tagId, filePath)) {
                is NetworkResult.Success -> {
                    loadFileTagsForFile(filePath)
                    currentCollectionTag()?.let { openCollection(it) }
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun untagFile(tagId: String, filePath: String) {
        viewModelScope.launch {
            when (repository.untagFile(tagId, filePath)) {
                is NetworkResult.Success -> {
                    loadFileTagsForFile(filePath)
                    currentCollectionTag()?.let { openCollection(it) }
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun loadFileTagsForFile(filePath: String) {
        viewModelScope.launch {
            when (val result = repository.getFileTags(listOf(filePath))) {
                is NetworkResult.Success -> {
                    val current = _fileTags.value.toMutableMap()
                    current[filePath] = result.data[filePath] ?: emptyList()
                    _fileTags.value = current
                }
                else -> {
                    val current = _fileTags.value.toMutableMap()
                    current[filePath] = emptyList()
                    _fileTags.value = current
                }
            }
        }
    }

    fun loadAllFileTags() {
        viewModelScope.launch {
            when (val result = repository.getFileTags()) {
                is NetworkResult.Success -> {
                    _fileTags.value = result.data
                }
                else -> {}
            }
        }
    }


    fun getTagsForFile(filePath: String): List<Tag> {
        return _fileTags.value[filePath] ?: emptyList()
    }

    fun setActiveTagFilter(tag: Tag?) {
        _activeTagFilter.value = tag
    }

    fun openCollection(tag: Tag) {
        viewModelScope.launch {
            _browseState.value = BrowseState.Loading
            _showFavoritesOnly.value = false
            _activeTagFilter.value = tag
            _currentPath.value = ""
            _pathStack.value = emptyList()
            _isSystemBrowse.value = false

            when (val result = repository.getTaggedMedia(tag.id)) {
                is NetworkResult.Success -> {
                    _rawFolders.value = emptyList()
                    _rawFiles.value = result.data
                    _browseState.value = BrowseState.TagCollection(
                        title = tag.name,
                        files = applySortToFiles(result.data),
                    )
                }
                is NetworkResult.Error -> {
                    _browseState.value = BrowseState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun currentCollectionTag(): Tag? {
        val active = _browseState.value as? BrowseState.TagCollection ?: return null
        return _activeTagFilter.value?.takeIf { it.name == active.title }
    }

    fun filterFilesByTag(files: List<MediaFile>): List<MediaFile> {
        val activeTag = _activeTagFilter.value ?: return files
        val taggedPaths = _fileTags.value.entries
            .filter { (_, tags) -> tags.any { it.id == activeTag.id } }
            .map { it.key }
            .toSet()
        return files.filter { it.relativePath in taggedPaths }
    }

    // ── Search ──────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchState = MutableStateFlow<SearchState>(SearchState.Idle)
    val searchState: StateFlow<SearchState> = _searchState.asStateFlow()

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun search() {
        val query = _searchQuery.value.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _searchState.value = SearchState.Loading
            when (val result = repository.search(query, _currentPath.value)) {
                is NetworkResult.Success -> {
                    _searchState.value = SearchState.Results(result.data)
                }
                is NetworkResult.Error -> {
                    _searchState.value = SearchState.Error(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun clearSearch() {
        _searchQuery.value = ""
        _searchState.value = SearchState.Idle
    }

    fun isSystemBrowseMode(): Boolean = _isSystemBrowse.value
}

private fun List<FavoriteMediaEntry>.associateFavoriteModes(): Map<String, Boolean> {
    return associate { entry -> entry.file.relativePath to entry.isSystemBrowse }
}

/**
 * Factory to create BrowseViewModel with a FavoritesStore dependency.
 */
class BrowseViewModelFactory(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return BrowseViewModel(favoritesStore, recentActivityStore) as T
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

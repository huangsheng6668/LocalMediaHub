package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.LastBrowseLocation
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.PlaybackProgressEntry
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.RecentMediaEntry
import com.juziss.localmediahub.data.ServerConfig
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.RetrofitClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibrarySummary(
    val name: String,
    val path: String,
)

data class CollectionSummary(
    val tag: Tag,
    val itemCount: Int,
)

data class HomeUiState(
    val isLoading: Boolean = true,
    val libraries: List<LibrarySummary> = emptyList(),
    val continueWatching: List<PlaybackProgressEntry> = emptyList(),
    val recentMedia: List<RecentMediaEntry> = emptyList(),
    val favoriteFiles: List<MediaFile> = emptyList(),
    val collections: List<CollectionSummary> = emptyList(),
    val lastBrowseLocation: LastBrowseLocation? = null,
    val serverLabel: String = "",
    val errorMessage: String? = null,
)

class HomeViewModel(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val serverConfig: ServerConfig,
) : ViewModel() {

    private val repository = MediaRepository()

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            favoritesStore.favoriteFiles.collect { files ->
                _uiState.value = _uiState.value.copy(favoriteFiles = files.take(6))
            }
        }
        viewModelScope.launch {
            recentActivityStore.recentMedia.collect { recent ->
                _uiState.value = _uiState.value.copy(recentMedia = recent)
            }
        }
        viewModelScope.launch {
            recentActivityStore.playbackProgress.collect { progress ->
                _uiState.value = _uiState.value.copy(continueWatching = progress)
            }
        }
        viewModelScope.launch {
            recentActivityStore.lastBrowseLocation.collect { location ->
                _uiState.value = _uiState.value.copy(lastBrowseLocation = location)
            }
        }
        viewModelScope.launch {
            serverConfig.serverUrl.collect { url ->
                _uiState.value = _uiState.value.copy(serverLabel = url)
                if (url.isBlank()) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    return@collect
                }

                ensureClientInitialized(url)
                refresh()
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val serverUrl = _uiState.value.serverLabel
            if (!ensureClientInitialized(serverUrl)) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = "Connect to a server first.",
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            val librariesResult = repository.getFolders()
            val tagsResult = repository.getTags()
            val fileTagsResult = repository.getFileTags()

            val libraries = when (librariesResult) {
                is NetworkResult.Success -> librariesResult.data.map { folder ->
                    LibrarySummary(
                        name = folder.name.ifBlank { folder.path },
                        path = folder.relativePath.ifBlank { folder.path },
                    )
                }
                is NetworkResult.Error -> {
                    _uiState.value = _uiState.value.copy(errorMessage = librariesResult.message)
                    emptyList()
                }
                is NetworkResult.Loading -> emptyList()
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                libraries = libraries,
                collections = buildCollections(tagsResult, fileTagsResult),
            )
        }
    }

    fun getThumbnailUrl(entry: RecentMediaEntry): String {
        return repository.getMediaThumbnailUrl(entry.file.path)
    }

    fun getVideoStreamUrl(entry: RecentMediaEntry): String {
        return repository.getMediaStreamUrl(entry.file.path)
    }

    fun getOriginalImageUrl(entry: RecentMediaEntry): String {
        return repository.getMediaOriginalImageUrl(entry.file.path)
    }

    fun getVideoStreamUrl(entry: PlaybackProgressEntry): String {
        return repository.getMediaStreamUrl(entry.file.path)
    }

    private val favoriteAccessModes = mutableMapOf<String, Boolean>()

    init {
        viewModelScope.launch {
            favoritesStore.favoriteEntries.collect { entries ->
                favoriteAccessModes.clear()
                entries.forEach { entry ->
                    favoriteAccessModes[entry.file.relativePath] = entry.isSystemBrowse
                }
            }
        }
    }

    fun isFavoriteSystemBrowse(file: MediaFile): Boolean {
        return favoriteAccessModes[file.relativePath] == true
    }

    fun getFavoriteStreamUrl(file: MediaFile): String {
        return repository.getMediaStreamUrl(file.path)
    }

    private fun buildCollections(
        tagsResult: NetworkResult<List<Tag>>,
        fileTagsResult: NetworkResult<Map<String, List<Tag>>>,
    ): List<CollectionSummary> {
        val tags = when (tagsResult) {
            is NetworkResult.Success -> tagsResult.data
            else -> emptyList()
        }
        val counts = when (fileTagsResult) {
            is NetworkResult.Success -> {
                fileTagsResult.data.values
                    .flatten()
                    .groupingBy { it.id }
                    .eachCount()
            }
            else -> emptyMap()
        }
        return tags.map { tag ->
            CollectionSummary(tag = tag, itemCount = counts[tag.id] ?: 0)
        }.sortedByDescending { it.itemCount }
    }

    private fun ensureClientInitialized(serverUrl: String): Boolean {
        if (serverUrl.isBlank()) return RetrofitClient.isInitialized()

        if (!RetrofitClient.isInitialized() || RetrofitClient.getBaseUrl() != serverUrl) {
            RetrofitClient.initialize(serverUrl)
        }
        return true
    }
}

class HomeViewModelFactory(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val serverConfig: ServerConfig,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(favoritesStore, recentActivityStore, serverConfig) as T
    }
}

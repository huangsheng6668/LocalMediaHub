package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.FavoriteMediaEntry
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.LastBrowseLocation
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.PlaybackProgressEntry
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.RecentMediaEntry
import com.juziss.localmediahub.data.ServerConfigStore
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LibrarySummary(
    val name: String,
    val path: String,
)

data class CollectionSummary(
    val tag: Tag,
    val itemCount: Int,
)

/**
 * Bookshelf tile shown on the Home screen. Derived from [BookProgress] rows
 * in RecentActivityStore: one entry per book the user has opened in the
 * text reader, filtered to formats the reader actually supports (txt/epub)
 * and capped to the 10 most recently read titles.
 */
data class RecentBookEntry(
    val path: String,
    val title: String,
    val chapterIndex: Int,
    val lastReadAt: Long,
    val format: String,
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

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val serverConfigStore: ServerConfigStore,
    private val serverConfig: ServerConfig,
    private val repository: MediaRepository,
) : ViewModel() {

    private companion object {
        /** Cap on the number of book tiles shown in the "我的书架" card. */
        const val BOOKSHELF_MAX_ITEMS: Int = 10
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * "我的书架" tile data — the user's 10 most recently opened books.
     *
     * Source: [RecentActivityStore.getAllBookProgressFlow] (already sorted
     * by lastReadAt desc). We filter to formats the text reader supports
     * (txt/epub) and project each row into the lighter [RecentBookEntry]
     * shape that the Home composable consumes. Empty list = hide card.
     */
    val recentBooks: StateFlow<List<RecentBookEntry>> =
        recentActivityStore.getAllBookProgressFlow()
            .map { progressList ->
                progressList
                    .filter { isSupportedBookFormat(it.path) }
                    .take(BOOKSHELF_MAX_ITEMS)
                    .map { p ->
                        RecentBookEntry(
                            path = p.path,
                            title = p.path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'),
                            chapterIndex = p.chapterIndex,
                            lastReadAt = p.lastReadAt,
                            format = bookFormatFromPath(p.path),
                        )
                    }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Single combine collector over the 6 store flows feeding HomeUiState.
    // Replaces the prior 6 independent launches, each of which did its own
    // _uiState.value.copy(...) — this version emits one state update per
    // upstream change, reducing redundant Compose recompositions.
    init {
        viewModelScope.launch {
            combine(
                favoritesStore.favoriteFiles,
                recentActivityStore.recentMedia,
                recentActivityStore.playbackProgress,
                recentActivityStore.lastBrowseLocation,
                serverConfigStore.serverUrl,
                favoritesStore.favoriteEntries,
            ) { favs, recent, progress, loc, url, favEntries ->
                HomeRawInputs(favs, recent, progress, loc, url, favEntries)
            }.collect { raw ->
                _uiState.value = _uiState.value.copy(
                    favoriteFiles = raw.favoriteFiles.take(6),
                    recentMedia = raw.recentMedia,
                    continueWatching = filterContinueWatching(raw.playbackProgress),
                    lastBrowseLocation = raw.lastBrowseLocation,
                    serverLabel = raw.serverUrl,
                )
                favoriteAccessModes = raw.favoriteEntries.associate {
                    it.file.relativePath to it.isSystemBrowse
                }
                if (raw.serverUrl.isBlank()) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    // Side-effect launch: serverUrl changes trigger refresh(). Separated from
    // the combine collector above so the data projection and the refresh side
    // effect are independent. Using collect (not drop) preserves the original
    // behavior where the first non-blank url also triggers refresh.
    init {
        viewModelScope.launch {
            serverConfigStore.serverUrl.collect { url ->
                if (url.isBlank()) {
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

    private var favoriteAccessModes: Map<String, Boolean> = emptyMap()

    fun isFavoriteSystemBrowse(file: MediaFile): Boolean {
        return favoriteAccessModes[file.relativePath] == true
    }

    fun getFavoriteStreamUrl(file: MediaFile): String {
        return repository.getMediaStreamUrl(file.path)
    }

    private fun isSupportedBookFormat(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext == "txt" || ext == "epub"
    }

    private fun bookFormatFromPath(path: String): String =
        path.substringAfterLast('.', "").lowercase()

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
        if (serverUrl.isBlank()) return serverConfig.isInitialized()

        if (!serverConfig.isInitialized() || serverConfig.getBaseUrl() != serverUrl) {
            serverConfig.setBaseUrl(serverUrl)
        }
        return true
    }

    /**
     * 获取与指定媒体文件在同一目录下的所有图片文件列表。
     * 用于从首页（最近播放/收藏）打开图片时，支持左右滑动切换同目录下的其他图片。
     */
    suspend fun getSisterImages(file: MediaFile, isSystemBrowse: Boolean): List<MediaFile> {
        val parentPath = getParentPath(if (isSystemBrowse) file.path else file.relativePath)
        return try {
            if (isSystemBrowse) {
                when (val res = repository.browseSystemPath(parentPath)) {
                    is NetworkResult.Success -> {
                        val images = res.data.files.filter { it.mediaType == "image" }
                        if (images.any { it.relativePath == file.relativePath }) images else listOf(file)
                    }
                    else -> listOf(file)
                }
            } else {
                when (val res = repository.browseFolder(parentPath)) {
                    is NetworkResult.Success -> {
                        val images = res.data.files.filter { it.mediaType == "image" }
                        if (images.any { it.relativePath == file.relativePath }) images else listOf(file)
                    }
                    else -> listOf(file)
                }
            }
        } catch (e: Exception) {
            listOf(file)
        }
    }

    private fun getParentPath(path: String): String {
        val lastSlash = path.lastIndexOfAny(charArrayOf('/', '\\'))
        return if (lastSlash != -1) {
            path.substring(0, lastSlash)
        } else {
            ""
        }
    }
}

/** 过滤掉"已看完"(进度 >= 95%)的条目,只保留还会用到的续播记录。 */
internal fun filterContinueWatching(
    entries: List<PlaybackProgressEntry>,
): List<PlaybackProgressEntry> {
    return entries.filterNot { entry ->
        com.juziss.localmediahub.data.isCompleted(entry.positionMs, entry.durationMs)
    }
}

/** Snapshot of all store flows that feed HomeUiState. Internal only. */
private data class HomeRawInputs(
    val favoriteFiles: List<MediaFile> = emptyList(),
    val recentMedia: List<RecentMediaEntry> = emptyList(),
    val playbackProgress: List<PlaybackProgressEntry> = emptyList(),
    val lastBrowseLocation: LastBrowseLocation? = null,
    val serverUrl: String = "",
    val favoriteEntries: List<FavoriteMediaEntry> = emptyList(),
)

/**
 * 6-parameter combine for Flow. kotlinx.coroutines.flow.combine's typed
 * overloads only go up to 5 flows; this wrapper delegates to the varargs
 * Array<Any?> form to support 6 typed flows with compile-time type safety
 * at the call site.
 */
@Suppress("UNCHECKED_CAST")
private fun <T1, T2, T3, T4, T5, T6, R> combine(
    flow1: Flow<T1>,
    flow2: Flow<T2>,
    flow3: Flow<T3>,
    flow4: Flow<T4>,
    flow5: Flow<T5>,
    flow6: Flow<T6>,
    transform: suspend (T1, T2, T3, T4, T5, T6) -> R,
): Flow<R> = kotlinx.coroutines.flow.combine(
    flow1, flow2, flow3, flow4, flow5, flow6,
) { args ->
    transform(
        args[0] as T1,
        args[1] as T2,
        args[2] as T3,
        args[3] as T4,
        args[4] as T5,
        args[5] as T6,
    )
}


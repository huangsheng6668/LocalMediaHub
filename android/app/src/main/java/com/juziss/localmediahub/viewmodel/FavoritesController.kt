package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.FavoriteEntry
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.buildFavoriteBody
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * BrowseViewModel delegate responsible for favorites state and operations:
 * tracking which files are favorited, toggling favorites, filtering file
 * lists by the favorites-only flag, building media URLs for favorite
 * entries, and tracking each favorite's system-browse access mode.
 *
 * Round 18 refactor: extracted verbatim from BrowseViewModel. The
 * `showFavoritesOnly` flag is shared across delegates via
 * [BrowseSharedState] (e.g. BrowseNavigator clears it when entering a tag
 * collection); this delegate owns the read/write through the shared state
 * and re-exposes it as a read-only StateFlow for the ViewModel. The
 * favorites file/path/access-mode state is private to this delegate
 * because no other delegate consumes it.
 *
 * `toggleFavorite` previously wrapped its body in `viewModelScope.launch`
 * — it is now a `suspend fun` so the caller (BrowseViewModel) supplies the
 * coroutine scope. The init-block collectors are gathered into
 * [startCollecting] for the same reason.
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows/functions for backward compat.
 */
internal class FavoritesController(
    private val favoritesStore: FavoritesStore,
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {

    // ── Favorites state (private to this delegate) ──────────────

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favorites: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _favoriteFiles = MutableStateFlow<List<MediaFile>>(emptyList())
    val favoriteFiles: StateFlow<List<MediaFile>> = _favoriteFiles.asStateFlow()

    private val _favoriteAccessModes = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    // showFavoritesOnly lives in BrowseSharedState so other delegates
    // (e.g. BrowseNavigator.openCollection) can clear it; this delegate
    // owns reads/writes and re-exposes a read-only view.
    val showFavoritesOnly: StateFlow<Boolean> = sharedState.showFavoritesOnly.asStateFlow()

    // pushToggleToServer 需要 fire-and-forget 作用域；由 startCollecting 传入
    // （BrowseViewModel 传 viewModelScope），与收藏流收集共用同一生命周期。
    private var scope: CoroutineScope? = null

    /**
     * Start collecting favorites flows from [favoritesStore] into local
     * StateFlows. The caller supplies the [scope] (typically the
     * ViewModel's viewModelScope); collectors stay active for the life of
     * that scope.
     */
    fun startCollecting(scope: CoroutineScope) {
        this.scope = scope
        scope.launch {
            favoritesStore.favorites.collect { favoritePaths ->
                _favorites.value = favoritePaths
            }
        }
        scope.launch {
            favoritesStore.favoriteFiles.collect { files ->
                _favoriteFiles.value = files
            }
        }
        scope.launch {
            favoritesStore.favoriteEntries.collect { entries ->
                _favoriteAccessModes.value = entries.associateFavoriteModes()
            }
        }
    }

    fun isFavorite(relativePath: String): Boolean {
        return relativePath in _favorites.value
    }

    suspend fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean = sharedState.isSystemBrowse.value) {
        val added = favoritesStore.toggleFavorite(file, isSystemBrowse)
        pushToggleToServer(
            added,
            FavoriteEntry(file = file, isSystemBrowse = isSystemBrowse, addedAt = System.currentTimeMillis()),
        )
    }

    suspend fun toggleFavoriteFolder(folder: Folder, isSystemBrowse: Boolean = sharedState.isSystemBrowse.value) {
        val added = favoritesStore.toggleFavoriteFolder(folder, isSystemBrowse)
        pushToggleToServer(
            added,
            FavoriteEntry(folder = folder, isSystemBrowse = isSystemBrowse, addedAt = System.currentTimeMillis()),
        )
    }

    /**
     * 收藏变更即时推送服务端：取消必须下发 DELETE——否则服务端残留行会被
     * LibrarySyncManager 的并集拉取复活回本地，且其他端心形状态永不更新。
     * 失败静默（下次启动全量同步兜底）。
     */
    private fun pushToggleToServer(added: Boolean, entry: FavoriteEntry) {
        val scope = scope ?: return
        scope.launch {
            runCatching {
                if (added) {
                    repository.pushServerFavorite(buildFavoriteBody(entry))
                } else {
                    repository.removeServerFavorite(entry.path)
                }
            }
        }
    }

    fun setShowFavoritesOnly(show: Boolean) {
        sharedState.showFavoritesOnly.value = show
    }

    /** Filter files to only show favorites when the filter is active. */
    fun filterFilesByFavorites(files: List<MediaFile>): List<MediaFile> {
        return if (sharedState.showFavoritesOnly.value) {
            files.filter { it.relativePath in _favorites.value }
        } else {
            files
        }
    }

    fun isFavoriteSystemBrowse(file: MediaFile): Boolean {
        return _favoriteAccessModes.value[file.relativePath] == true
    }

    fun getFavoriteVideoStreamUrl(file: MediaFile): String {
        return repository.getMediaStreamUrl(file.path)
    }

    fun getFavoriteThumbnailUrl(file: MediaFile): String {
        return repository.getMediaThumbnailUrl(file.path, file.modifiedTime)
    }

    fun getFavoriteOriginalImageUrl(file: MediaFile): String {
        return repository.getMediaOriginalImageUrl(file.path)
    }
}

private fun List<FavoriteEntry>.associateFavoriteModes(): Map<String, Boolean> {
    return mapNotNull { entry ->
        val key = entry.file?.relativePath ?: entry.folder?.path ?: return@mapNotNull null
        key to entry.isSystemBrowse
    }.toMap()
}

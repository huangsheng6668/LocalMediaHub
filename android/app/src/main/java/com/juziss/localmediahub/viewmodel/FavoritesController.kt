package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.FavoriteEntry
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
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

    /**
     * Start collecting favorites flows from [favoritesStore] into local
     * StateFlows. The caller supplies the [scope] (typically the
     * ViewModel's viewModelScope); collectors stay active for the life of
     * that scope.
     */
    fun startCollecting(scope: CoroutineScope) {
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
        favoritesStore.toggleFavorite(file, isSystemBrowse)
    }

    suspend fun toggleFavoriteFolder(folder: Folder, isSystemBrowse: Boolean = sharedState.isSystemBrowse.value) {
        favoritesStore.toggleFavoriteFolder(folder, isSystemBrowse)
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

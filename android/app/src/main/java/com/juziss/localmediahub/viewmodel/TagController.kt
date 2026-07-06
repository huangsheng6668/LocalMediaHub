package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.Tag
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BrowseViewModel delegate responsible for tag state and operations:
 * CRUD on tags, tagging/untagging files, tag-based file filtering,
 * and navigating into tag collections.
 *
 * Round 18 refactor: extracted verbatim from BrowseViewModel. The
 * `activeTagFilter` flag is shared across delegates via
 * [BrowseSharedState] (e.g. BrowseNavigator.loadRoots clears it); this
 * delegate owns the read/write through the shared state. The tags-list
 * and file-tags-mapping state is private to this delegate because no
 * other delegate consumes it.
 *
 * Functions that previously wrapped their bodies in `viewModelScope.launch`
 * now take a `scope: CoroutineScope` parameter so the caller
 * (BrowseViewModel) supplies the coroutine scope. `loadFileTagsForFile`
 * and `loadAllFileTags` also take a `scope` parameter (brief requirement).
 *
 * Internal to the viewmodel package — NOT exposed to UI. The ViewModel
 * re-exposes the relevant flows/functions for backward compat.
 */
internal class TagController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {

    // ── Tags state (private to this delegate) ───────────────────

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _fileTags = MutableStateFlow<Map<String, List<Tag>>>(emptyMap())
    val fileTags: StateFlow<Map<String, List<Tag>>> = _fileTags.asStateFlow()

    fun loadTags(scope: CoroutineScope) {
        scope.launch {
            when (val result = repository.getTags()) {
                is NetworkResult.Success -> {
                    _tags.value = result.data
                    loadAllFileTags(scope) // Preload all file-tag mappings
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun createTag(name: String, color: String = "#808080", scope: CoroutineScope) {
        scope.launch {
            when (repository.createTag(name, color)) {
                is NetworkResult.Success -> {
                    loadTags(scope)
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun deleteTag(tagId: String, scope: CoroutineScope) {
        scope.launch {
            when (repository.deleteTag(tagId)) {
                is NetworkResult.Success -> {
                    loadTags(scope)
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun tagFile(tagId: String, filePath: String, scope: CoroutineScope) {
        scope.launch {
            when (repository.tagFile(tagId, filePath)) {
                is NetworkResult.Success -> {
                    loadFileTagsForFile(filePath, scope)
                    currentCollectionTag()?.let { openCollection(it, scope) }
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun untagFile(tagId: String, filePath: String, scope: CoroutineScope) {
        scope.launch {
            when (repository.untagFile(tagId, filePath)) {
                is NetworkResult.Success -> {
                    loadFileTagsForFile(filePath, scope)
                    currentCollectionTag()?.let { openCollection(it, scope) }
                }
                is NetworkResult.Error -> {}
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun loadFileTagsForFile(filePath: String, scope: CoroutineScope) {
        scope.launch {
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

    fun loadAllFileTags(scope: CoroutineScope) {
        scope.launch {
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
        sharedState.activeTagFilter.value = tag
    }

    fun openCollection(tag: Tag, scope: CoroutineScope) {
        scope.launch {
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
                    sharedState.emitBrowseError(result.message)
                }
                is NetworkResult.Loading -> {}
            }
        }
    }

    fun currentCollectionTag(): Tag? {
        val active = sharedState.browseState.value as? BrowseState.TagCollection ?: return null
        return sharedState.activeTagFilter.value?.takeIf { it.name == active.title }
    }

    fun filterFilesByTag(files: List<MediaFile>): List<MediaFile> {
        val activeTag = sharedState.activeTagFilter.value ?: return files
        val taggedPaths = _fileTags.value.entries
            .filter { (_, tags) -> tags.any { it.id == activeTag.id } }
            .map { it.key }
            .toSet()
        return files.filter { it.relativePath in taggedPaths }
    }
}

package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.LibraryDecoration
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.ReadingStatus
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

internal class LibraryController(
    private val repository: MediaRepository,
    private val sharedState: BrowseSharedState,
) {
    @OptIn(FlowPreview::class)
    fun startCollecting(scope: CoroutineScope) {
        scope.launch {
            combine(sharedState.rawFolders, sharedState.rawFiles) { f, l -> f to l }
                .debounce(300)
                .collect { (folders, files) -> loadDecorations(folders, files) }
        }
    }

    suspend fun loadDecorations(folders: List<Folder>, files: List<MediaFile>) {
        val paths = folders.map { it.path } + files.filter { it.mediaType == "text" }.map { it.path }
        if (paths.isEmpty()) {
            sharedState.libraryStates.value = emptyMap()
            return
        }
        when (val r = repository.fetchDecorations(paths)) {
            is NetworkResult.Success -> sharedState.libraryStates.value = r.data.states.entries
                .mapNotNull { (p, b) ->
                    ReadingStatus.fromRaw(b.status)?.let {
                        p to LibraryDecoration(p, it, b.percent, b.lastReadAt)
                    }
                }
                .toMap()
            else -> {} // 静默降级：无徽章
        }
    }

    suspend fun setStatus(path: String, status: ReadingStatus?) {
        repository.setReadingStatus(path, status?.toRaw())
        loadDecorations(sharedState.rawFolders.value, sharedState.rawFiles.value) // 刷新徽章
    }

    fun setStatusFilter(s: ReadingStatus?) {
        sharedState.statusFilter.value = s
    }
}

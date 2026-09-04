package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.LibraryDecoration
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.ReadingStatus

internal fun applyBrowseFilters(
    folders: List<Folder>,
    files: List<MediaFile>,
    favorites: Set<String>,
    favoritesOnly: Boolean,
    statusFilter: ReadingStatus?,
    states: Map<String, LibraryDecoration>,
): Pair<List<Folder>, List<MediaFile>> {
    var outFolders = folders
    var outFiles = files
    if (statusFilter != null) {
        outFolders = emptyList()
        outFiles = outFiles.filter { f ->
            f.mediaType == "text" && (states[f.path]?.status ?: ReadingStatus.UNREAD) == statusFilter
        }
    }
    if (favoritesOnly) {
        outFolders = outFolders.filter { it.path in favorites }
        outFiles = outFiles.filter { it.relativePath in favorites }
    }
    return outFolders to outFiles
}

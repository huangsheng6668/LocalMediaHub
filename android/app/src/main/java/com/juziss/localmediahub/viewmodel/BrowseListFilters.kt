package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.LibraryDecoration
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.ReadingStatus
import com.juziss.localmediahub.data.Tag

/**
 * 标签过滤纯函数。无激活标签时原样返回入参（保持列表引用稳定），
 * 供组合层 `remember` 缓存使用。
 */
internal fun applyTagFilter(
    files: List<MediaFile>,
    fileTags: Map<String, List<Tag>>,
    activeTag: Tag?,
): List<MediaFile> {
    if (activeTag == null) return files
    val taggedPaths = fileTags.entries
        .filter { (_, tags) -> tags.any { it.id == activeTag.id } }
        .mapTo(HashSet()) { it.key }
    return files.filter { it.relativePath in taggedPaths }
}

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

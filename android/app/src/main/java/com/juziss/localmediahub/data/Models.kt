package com.juziss.localmediahub.data

import com.google.gson.annotations.SerializedName

/**
 * Matches server/models.py MediaFile
 */
data class MediaFile(
    val name: String,
    val path: String,
    @SerializedName("relative_path")
    val relativePath: String,
    val size: Long,
    @SerializedName("modified_time")
    val modifiedTime: String,
    @SerializedName("media_type")
    val mediaType: String,
    val extension: String,
)

/**
 * Matches server/models.py Folder
 */
data class Folder(
    val name: String,
    val path: String,
    @SerializedName("relative_path")
    val relativePath: String,
    @SerializedName("is_root")
    val isRoot: Boolean = false,
)

/**
 * Matches server/models.py BrowseResult
 */
data class BrowseResult(
    @SerializedName("current_path")
    val currentPath: String,
    val folders: List<Folder>,
    val files: List<MediaFile>,
)

/**
 * Matches server/models.py PaginatedMediaFiles
 */
data class PaginatedMediaFiles(
    val items: List<MediaFile>,
    val total: Int,
    val page: Int,
    @SerializedName("page_size")
    val pageSize: Int,
    @SerializedName("has_more")
    val hasMore: Bool,
)

package com.juziss.localmediahub.data

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Matches server/models.py MediaFile
 */
@Parcelize
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
) : Parcelable

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
    @SerializedName("modified_time")
    val modifiedTime: String = "",
)

/**
 * Matches server/models.py BrowseResult
 */
data class BrowseResult(
    @SerializedName("current_path")
    val currentPath: String,
    val folders: List<Folder>,
    val files: List<MediaFile>,
    @SerializedName("has_more")
    val hasMore: Boolean = false,
)

/**
 * Matches server/models.py SearchResult
 */
data class SearchResult(
    val query: String,
    val folders: List<Folder>,
    val files: List<MediaFile>,
)

/**
 * Matches server/models.py FileTag
 */
data class Tag(
    val id: String,
    val name: String,
    val color: String = "#808080",
)

/**
 * Matches server/models.py TagCreateRequest
 */
data class TagCreateRequest(
    val name: String,
    val color: String = "#808080",
)

/**
 * Result from system browse endpoint.
 */
data class SystemBrowseResult(
    @SerializedName("current_path")
    val currentPath: String? = null,
    val drives: List<String>? = null,
    val folders: List<Folder>,
    val files: List<MediaFile>,
    @SerializedName("has_more")
    val hasMore: Boolean = false,
)

/**
 * Matches server/service/bookparser.Chapter — one entry in a Book's table of contents.
 */
@Parcelize
data class BookChapter(
    val index: Int,
    val title: String,
    @SerializedName("char_start") val charStart: Int = 0,
    @SerializedName("char_end") val charEnd: Int = 0,
    @SerializedName("manifest_id") val manifestId: String? = null,
) : Parcelable

/**
 * Matches server/service/bookparser.Book — parsed metadata for a text/epub file.
 */
@Parcelize
data class Book(
    val path: String,
    val format: String,
    val title: String,
    val charset: String? = null,
    val chapters: List<BookChapter>,
    @SerializedName("mod_time") val modTime: String,
) : Parcelable

/**
 * One ordered content unit of a chapter. Type is "text" (value holds the
 * paragraph text) or "image" (src holds a URL or data: URI). Mirrors the
 * server's bookparser.Block.
 */
@Parcelize
data class Block(
    val type: String,
    @SerializedName("value") val value: String? = null,
    @SerializedName("src") val src: String? = null,
) : Parcelable

/**
 * Matches server/server/handler.chapterResponse — single-chapter payload.
 * Blocks is the ordered list of text/image content units.
 */
@Parcelize
data class BookChapterContent(
    val title: String,
    val blocks: List<Block> = emptyList(),
) : Parcelable

/**
 * Holds a loaded chapter for scroll-all-chapters reading mode.
 */
@Parcelize
data class ScrollModeChapter(
    val chapterIndex: Int,
    val title: String,
    val blocks: List<Block>,
) : Parcelable

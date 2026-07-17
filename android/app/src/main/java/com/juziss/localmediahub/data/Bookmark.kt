package com.juziss.localmediahub.data

/**
 * Per-book bookmark. (bookPath, chapterIndex, paragraphIndex) uniquely
 * identifies a bookmark; duplicate add returns false (see RecentActivityStore).
 *
 * paragraphIndex is the index into the LazyColumn items list (chapter text
 * split on "\n\n", blank paragraphs filtered). More stable than charOffset
 * because chapter text edits shift offsets but rarely reorder paragraphs.
 */
data class Bookmark(
    val bookPath: String,
    val chapterIndex: Int,
    val paragraphIndex: Int,
    val preview: String,
    val createdAt: Long,
)

package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.native.NaturalSorter

/** Extract leading number from a string like "007_gjco" → 7.0, "abc" → null. */
internal fun extractLeadingNumber(s: String): Double? {
    val sb = StringBuilder()
    for (ch in s) if (ch.isDigit()) sb.append(ch) else break
    return if (sb.isNotEmpty()) sb.toString().toDouble() else null
}

/**
 * Compare two strings with natural/numeric ordering (e.g., "2" < "10").
 *
 * Delegates to the Rust-backed [NaturalSorter] for zero-allocation natural
 * ordering. The previous Kotlin implementation used a `Regex` and two
 * `List<String>` allocations per call; the Rust path performs a single
 * byte-stream scan with no allocation beyond lowercase normalisation.
 *
 * On the host JVM (Robolectric unit tests) where the arm64 `.so` cannot
 * load, [NaturalSorter] transparently falls back to a pure-Kotlin
 * implementation with identical semantics, so `BrowseSorterTest` continues
 * to pass without modification.
 */
internal fun compareNatural(a: String, b: String): Int = NaturalSorter.compare(a, b)

/** Pure, stateless browse-grid sort logic. Extracted from BrowseViewModel for testability. */
object BrowseSorter {
    fun sortFolders(folders: List<Folder>, order: SortOrder): List<Folder> = when (order) {
        SortOrder.NAME_ASC -> folders.sortedWith { a, b -> compareNatural(a.name, b.name) }
        SortOrder.NAME_DESC -> folders.sortedWith { a, b -> compareNatural(b.name, a.name) }
        SortOrder.NUMERIC_ASC -> folders.sortedBy { extractLeadingNumber(it.name) ?: Double.MAX_VALUE }
        SortOrder.NUMERIC_DESC -> folders.sortedByDescending { extractLeadingNumber(it.name) ?: Double.MIN_VALUE }
        SortOrder.TIME_ASC -> folders.sortedBy { it.modifiedTime }
        SortOrder.TIME_DESC -> folders.sortedByDescending { it.modifiedTime }
        else -> folders // SIZE 排序不适用于文件夹
    }

    fun sortFiles(files: List<MediaFile>, order: SortOrder): List<MediaFile> = when (order) {
        SortOrder.NAME_ASC -> files.sortedWith { a, b -> compareNatural(a.name, b.name) }
        SortOrder.NAME_DESC -> files.sortedWith { a, b -> compareNatural(b.name, a.name) }
        SortOrder.NUMERIC_ASC -> files.sortedBy { extractLeadingNumber(it.name) ?: Double.MAX_VALUE }
        SortOrder.NUMERIC_DESC -> files.sortedByDescending { extractLeadingNumber(it.name) ?: Double.MIN_VALUE }
        SortOrder.SIZE_ASC -> files.sortedBy { it.size }
        SortOrder.SIZE_DESC -> files.sortedByDescending { it.size }
        SortOrder.TIME_ASC -> files.sortedBy { it.modifiedTime }
        SortOrder.TIME_DESC -> files.sortedByDescending { it.modifiedTime }
    }
}

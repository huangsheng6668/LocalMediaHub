package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile

/** Extract leading number from a string like "007_gjco" → 7.0, "abc" → null. */
internal fun extractLeadingNumber(s: String): Double? {
    val sb = StringBuilder()
    for (ch in s) if (ch.isDigit()) sb.append(ch) else break
    return if (sb.isNotEmpty()) sb.toString().toDouble() else null
}

/** Compare two strings with natural/numeric ordering (e.g., "2" < "10"). */
internal fun compareNatural(a: String, b: String): Int {
    val regex = Regex("\\d+|\\D+")
    val tokensA = regex.findAll(a.lowercase()).map { it.value }.toList()
    val tokensB = regex.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(tokensA.size, tokensB.size)) {
        val ta = tokensA[i]
        val tb = tokensB[i]
        val numA = ta.toIntOrNull()
        val numB = tb.toIntOrNull()
        val cmp = if (numA != null && numB != null) numA.compareTo(numB) else ta.compareTo(tb)
        if (cmp != 0) return cmp
    }
    return tokensA.size.compareTo(tokensB.size)
}

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

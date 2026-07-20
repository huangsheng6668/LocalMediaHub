package com.juziss.localmediahub.data

import com.juziss.localmediahub.network.NetworkResult
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.regex.Pattern
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocalBookRepository parses offline book files (.txt / .epub) and optional
 * sidecar JSON metadata generated during download.
 */
@Singleton
class LocalBookRepository @Inject constructor() {
    private val gson = Gson()
    private val commonChapterPatterns = listOf(
        Pattern.compile("^[\\s\\S]*?第\\s*[一二三四五六七八九十百千零0-9０-９]+\\s*[章节回卷集部篇]"),
        Pattern.compile("^Chapter\\s+\\d+", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^楔子($|[\\s　：:～~、，,;；])"),
        Pattern.compile("^序章($|[\\s　：:～~、，,;；])"),
        Pattern.compile("^尾声($|[\\s　：:～~、，,;；])"),
        Pattern.compile("^前言($|[\\s　：:～~、，,;；])"),
        Pattern.compile("^后记($|[\\s　：:～~、，,;；])")
    )

    /**
     * Reads book info from a local file path.
     * Prefers reading from <localPath>.json sidecar if present.
     */
    fun getLocalBookInfo(localPath: String, originalPath: String): NetworkResult<Book> {
        val file = File(localPath)
        if (!file.exists()) {
            return NetworkResult.Error("本地文件不存在: ${file.name}")
        }

        val sidecar = File(file.parentFile, "${file.name}.json")
        if (sidecar.exists()) {
            try {
                val jsonStr = sidecar.readText(Charsets.UTF_8)
                val book = gson.fromJson(jsonStr, Book::class.java)
                if (book != null && book.chapters.isNotEmpty()) {
                    return NetworkResult.Success(book)
                }
            } catch (e: Exception) {
                // Fallback to manual parsing if sidecar JSON parsing fails
            }
        }

        val ext = file.extension.lowercase()
        return when (ext) {
            "txt" -> parseLocalTxtInfo(file, originalPath)
            "epub" -> parseLocalEpubInfo(file, originalPath)
            else -> NetworkResult.Success(
                Book(
                    path = originalPath,
                    format = "unsupported",
                    title = file.name,
                    chapters = emptyList(),
                    modTime = file.lastModified().toString()
                )
            )
        }
    }

    /**
     * Loads a specific chapter content for a local book file.
     */
    fun getLocalBookChapter(
        localPath: String,
        book: Book,
        index: Int
    ): NetworkResult<BookChapterContent> {
        val file = File(localPath)
        if (!file.exists()) {
            return NetworkResult.Error("本地文件不存在: ${file.name}")
        }
        if (index !in book.chapters.indices) {
            return NetworkResult.Error("章节索引超出范围")
        }

        val chapter = book.chapters[index]
        val ext = file.extension.lowercase()

        return try {
            when (ext) {
                "txt" -> getTxtChapterContent(file, chapter)
                "epub" -> getEpubChapterContent(file, chapter)
                else -> NetworkResult.Error("暂不支持该格式")
            }
        } catch (e: Exception) {
            NetworkResult.Error("读取本地章节失败: ${e.message}")
        }
    }

    private fun parseLocalTxtInfo(file: File, originalPath: String): NetworkResult<Book> {
        return try {
            val bytes = file.readBytes()
            val (text, charsetName) = decodeTxtBytes(bytes)
            val chapters = splitTxtChapters(text, file.name)
            NetworkResult.Success(
                Book(
                    path = originalPath,
                    format = "txt",
                    title = file.name,
                    charset = charsetName,
                    chapters = chapters,
                    modTime = file.lastModified().toString()
                )
            )
        } catch (e: Exception) {
            NetworkResult.Error("解析本地 TXT 失败: ${e.message}")
        }
    }

    private fun decodeTxtBytes(raw: ByteArray): Pair<String, String> {
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return Pair(String(raw, 3, raw.size - 3, Charsets.UTF_8), "UTF-8")
        }
        // Try UTF-8 first
        val utf8String = String(raw, Charsets.UTF_8)
        if (!utf8String.contains("\uFFFD")) {
            return Pair(utf8String, "UTF-8")
        }
        // Try GB18030 / GBK
        try {
            val gbCharset = Charset.forName("GB18030")
            val gbString = String(raw, gbCharset)
            return Pair(gbString, "GB18030")
        } catch (e: Exception) {
            // Fallback to UTF-8
            return Pair(utf8String, "UTF-8")
        }
    }

    private fun splitTxtChapters(text: String, fallbackTitle: String): List<BookChapter> {
        val lines = text.split("\r\n", "\n")
        data class Mark(val title: String, val startRune: Int)

        val marks = mutableListOf<Mark>()
        var currentRuneOffset = 0

        for (line in lines) {
            val trim = line.trim()
            for (pattern in commonChapterPatterns) {
                if (pattern.matcher(trim).find()) {
                    marks.add(Mark(trim, currentRuneOffset))
                    break
                }
            }
            currentRuneOffset += line.length + 1
        }

        if (marks.isEmpty()) {
            return listOf(
                BookChapter(
                    index = 0,
                    title = fallbackTitle,
                    charStart = 0,
                    charEnd = text.length
                )
            )
        }

        val chapters = mutableListOf<BookChapter>()
        if (marks[0].startRune > 0) {
            val preamble = text.substring(0, marks[0].startRune).trimEnd('\r', '\n')
            if (preamble.isNotBlank()) {
                chapters.add(
                    BookChapter(
                        index = 0,
                        title = "序言",
                        charStart = 0,
                        charEnd = marks[0].startRune
                    )
                )
            }
        }

        for (i in marks.indices) {
            val m = marks[i]
            val end = if (i + 1 < marks.size) marks[i + 1].startRune else text.length
            chapters.add(
                BookChapter(
                    index = chapters.size,
                    title = m.title,
                    charStart = m.startRune,
                    charEnd = end
                )
            )
        }
        return chapters
    }

    private fun getTxtChapterContent(file: File, chapter: BookChapter): NetworkResult<BookChapterContent> {
        val bytes = file.readBytes()
        val (text, _) = decodeTxtBytes(bytes)

        val start = chapter.charStart.coerceIn(0, text.length)
        val end = chapter.charEnd.coerceIn(start, text.length)

        val slice = text.substring(start, end)
        val paragraphs = slice.split("\n\n", "\r\n\r\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val blocks = if (paragraphs.isEmpty()) {
            // Also split by single newline if no double newline paragraphs
            val lineParas = slice.split("\n", "\r\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lineParas.isEmpty()) {
                listOf(Block(type = "text", value = "[本章节为空]"))
            } else {
                lineParas.map { Block(type = "text", value = it) }
            }
        } else {
            paragraphs.map { Block(type = "text", value = it) }
        }

        return NetworkResult.Success(
            BookChapterContent(
                title = chapter.title,
                blocks = blocks
            )
        )
    }

    private fun parseLocalEpubInfo(file: File, originalPath: String): NetworkResult<Book> {
        return try {
            ZipFile(file).use { zip ->
                val entries = zip.entries().toList()
                val htmlEntries = entries.filter {
                    !it.isDirectory && (it.name.endsWith(".html", ignoreCase = true) || it.name.endsWith(".xhtml", ignoreCase = true) || it.name.endsWith(".htm", ignoreCase = true))
                }.sortedBy { it.name }

                val chapters = htmlEntries.mapIndexed { index, entry ->
                    BookChapter(
                        index = index,
                        title = "第 ${index + 1} 章 (${File(entry.name).nameWithoutExtension})",
                        manifestId = entry.name
                    )
                }

                NetworkResult.Success(
                    Book(
                        path = originalPath,
                        format = "epub",
                        title = file.nameWithoutExtension,
                        chapters = chapters.ifEmpty {
                            listOf(BookChapter(index = 0, title = file.name, manifestId = null))
                        },
                        modTime = file.lastModified().toString()
                    )
                )
            }
        } catch (e: Exception) {
            NetworkResult.Error("解析本地 EPUB 失败: ${e.message}")
        }
    }

    private fun getEpubChapterContent(file: File, chapter: BookChapter): NetworkResult<BookChapterContent> {
        return try {
            ZipFile(file).use { zip ->
                val entryName = chapter.manifestId
                val entry = if (entryName != null) {
                    zip.getEntry(entryName) ?: zip.entries().asSequence().find { it.name.endsWith(entryName, ignoreCase = true) }
                } else null

                if (entry == null) {
                    return NetworkResult.Error("未找到章节对应文件")
                }

                val contentStr = zip.getInputStream(entry).use { streamToText(it) }
                // Strip HTML tags for clean text blocks
                val cleanText = contentStr.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
                    .replace(Regex("<[^>]*>"), "")
                    .replace("&nbsp;", " ")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")

                val paragraphs = cleanText.split("\n")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                val blocks = if (paragraphs.isEmpty()) {
                    listOf(Block(type = "text", value = "[本章节为空]"))
                } else {
                    paragraphs.map { Block(type = "text", value = it) }
                }

                NetworkResult.Success(
                    BookChapterContent(
                        title = chapter.title,
                        blocks = blocks
                    )
                )
            }
        } catch (e: Exception) {
            NetworkResult.Error("读取本地 EPUB 章节失败: ${e.message}")
        }
    }

    private fun streamToText(inputStream: InputStream): String {
        return inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }
}

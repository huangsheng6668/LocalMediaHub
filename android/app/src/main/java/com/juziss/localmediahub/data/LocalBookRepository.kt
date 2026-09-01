package com.juziss.localmediahub.data

import com.juziss.localmediahub.network.NetworkResult
import com.google.gson.Gson
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LocalBookRepository parses offline book files (.txt / .epub) and optional
 * sidecar JSON metadata generated during download.
 *
 * TXT 分章规则与 server 的 bookparser 对齐，实现在 [TxtChapterParser]。
 */
@Singleton
class LocalBookRepository @Inject constructor() {
    private val gson = Gson()

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
        // 与 server bookparser.decodeTxt 一致：统一换行符，保证 charStart/charEnd
        // 偏移与 getTxtChapterContent 的切片基于同一份归一化文本
        fun normalize(s: String) = s.replace("\r\n", "\n")
        if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() && raw[2] == 0xBF.toByte()) {
            return Pair(normalize(String(raw, 3, raw.size - 3, Charsets.UTF_8)), "UTF-8")
        }
        // Try UTF-8 first
        val utf8String = String(raw, Charsets.UTF_8)
        if (!utf8String.contains("\uFFFD")) {
            return Pair(normalize(utf8String), "UTF-8")
        }
        // Try GB18030 / GBK
        return try {
            val gbCharset = Charset.forName("GB18030")
            Pair(normalize(String(raw, gbCharset)), "GB18030")
        } catch (e: Exception) {
            // Fallback to UTF-8
            Pair(normalize(utf8String), "UTF-8")
        }
    }

    private fun splitTxtChapters(text: String, fallbackTitle: String): List<BookChapter> =
        TxtChapterParser.splitChapters(text, fallbackTitle)

    private fun getTxtChapterContent(file: File, chapter: BookChapter): NetworkResult<BookChapterContent> {
        val bytes = file.readBytes()
        val (text, _) = decodeTxtBytes(bytes)

        val start = chapter.charStart.coerceIn(0, text.length)
        val end = chapter.charEnd.coerceIn(start, text.length)

        // 与 server GetChapterBlocksFromRunes 对齐：每行一块，tab 视为换行
        val slice = text.substring(start, end)
            .replace("\r\n", "\n")
            .replace("\t", "\n")

        var blocks = slice.split("\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { Block(type = "text", value = it) }
        if (blocks.isEmpty()) {
            blocks = listOf(Block(type = "text", value = "[本章节为空]"))
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

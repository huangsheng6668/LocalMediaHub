package com.juziss.localmediahub.data

import com.juziss.localmediahub.network.NetworkResult
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalBookRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repo: LocalBookRepository
    private val gson = Gson()

    @Before
    fun setup() {
        repo = LocalBookRepository()
    }

    @Test
    fun testParseTxtFile_withChapters() {
        val txtContent = """
            前言
            这是前言的内容。

            第一章 开始
            这是第一章的第一段。

            这是第一章的第二段。

            第二章 结束
            这是第二章的内容。
        """.trimIndent()

        val txtFile = tempFolder.newFile("test_novel.txt")
        txtFile.writeText(txtContent, Charsets.UTF_8)

        val bookResult = repo.getLocalBookInfo(txtFile.absolutePath, "books/test_novel.txt")
        assertTrue(bookResult is NetworkResult.Success<Book>)

        val book = (bookResult as NetworkResult.Success<Book>).data
        assertEquals("txt", book.format)
        assertEquals("test_novel.txt", book.title)
        assertTrue(book.chapters.size >= 2)

        val chapter1Content = repo.getLocalBookChapter(txtFile.absolutePath, book, 1)
        assertTrue(chapter1Content is NetworkResult.Success<BookChapterContent>)

        val content = (chapter11Content(chapter1Content))
        assertTrue(content.blocks.isNotEmpty())
        assertEquals("text", content.blocks[0].type)
    }

    @Test
    fun testParseTxtFile_crlfOffsetsStayAligned() {
        // CRLF 文件：decodeTxtBytes 统一换行符后，章节偏移不得随行数漂移
        val content = "第一章 A\r\nline1\r\nline2\r\nline3\r\nline4\r\nline5\r\n" +
            "line6\r\nline7\r\nline8\r\nline9\r\nline10\r\n第二章 B\r\n这是第二章的正文内容"
        val txtFile = tempFolder.newFile("crlf_novel.txt")
        txtFile.writeText(content, Charsets.UTF_8)

        val bookResult = repo.getLocalBookInfo(txtFile.absolutePath, "books/crlf_novel.txt")
        assertTrue(bookResult is NetworkResult.Success<Book>)
        val book = (bookResult as NetworkResult.Success<Book>).data
        assertEquals(2, book.chapters.size)

        val chapterResult = repo.getLocalBookChapter(txtFile.absolutePath, book, 1)
        assertTrue(chapterResult is NetworkResult.Success<BookChapterContent>)
        val blocks = (chapterResult as NetworkResult.Success<BookChapterContent>).data.blocks
        // 第二章切片从标题行开始，而不是漂移到第一章的正文行
        assertEquals("第二章 B", blocks.first().value)
    }

    private fun chapter11Content(res: NetworkResult<BookChapterContent>): BookChapterContent {
        return (res as NetworkResult.Success<BookChapterContent>).data
    }

    @Test
    fun testParseTxtFile_withSidecarJson() {
        val txtFile = tempFolder.newFile("sidecar_novel.txt")
        txtFile.writeText("这是任意内容", Charsets.UTF_8)

        val sidecarFile = File(txtFile.parentFile, "${txtFile.name}.json")
        val mockBook = Book(
            path = "books/sidecar_novel.txt",
            format = "txt",
            title = "Sidecar Book",
            chapters = listOf(
                BookChapter(index = 0, title = "Sidecar Chapter 1", charStart = 0, charEnd = 6)
            ),
            modTime = "2026-07-20"
        )
        sidecarFile.writeText(gson.toJson(mockBook), Charsets.UTF_8)

        val bookResult = repo.getLocalBookInfo(txtFile.absolutePath, "books/sidecar_novel.txt")
        assertTrue(bookResult is NetworkResult.Success<Book>)

        val book = (bookResult as NetworkResult.Success<Book>).data
        assertEquals("Sidecar Book", book.title)
        assertEquals(1, book.chapters.size)
        assertEquals("Sidecar Chapter 1", book.chapters[0].title)

        val chapterContent = repo.getLocalBookChapter(txtFile.absolutePath, book, 0)
        assertTrue(chapterContent is NetworkResult.Success<BookChapterContent>)
        val content = (chapterContent as NetworkResult.Success<BookChapterContent>).data
        assertEquals("这是任意内容", content.blocks[0].value)
    }
}

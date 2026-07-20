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

package com.juziss.localmediahub.data

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritesStoreTest {

    private val gson = Gson()

    @Test
    fun `decodeFavoriteEntry reads new favorite entry format with system flag`() {
        val file = MediaFile(
            name = "page-001.jpg",
            path = """S:\telegramSavePath\page-001.jpg""",
            relativePath = """S:\telegramSavePath\page-001.jpg""",
            size = 1L,
            modifiedTime = "",
            mediaType = "image",
            extension = ".jpg",
        )

        val entry = decodeFavoriteEntry(
            gson,
            gson.toJson(FavoriteMediaEntry(file = file, isSystemBrowse = true)),
        )

        assertNotNull(entry)
        assertTrue(entry!!.isSystemBrowse)
        assertEquals(file.relativePath, entry.file.relativePath)
    }

    @Test
    fun `decodeFavoriteEntry keeps backward compatibility with legacy media file json`() {
        val legacy = MediaFile(
            name = "clip.mp4",
            path = """F:\media\clip.mp4""",
            relativePath = """F:\media\clip.mp4""",
            size = 1L,
            modifiedTime = "",
            mediaType = "video",
            extension = ".mp4",
        )

        val entry = decodeFavoriteEntry(gson, gson.toJson(legacy))

        assertNotNull(entry)
        assertFalse(entry!!.isSystemBrowse)
        assertEquals(legacy.relativePath, entry.file.relativePath)
    }

    @Test
    fun `favoriteEntriesToPaths maps entries to their relative-path set`() {
        val a = MediaFile("a.jpg", "p/a.jpg", "p/a.jpg", 1L, "", "image", ".jpg")
        val b = MediaFile("b.mp4", "p/b.mp4", "p/b.mp4", 1L, "", "video", ".mp4")
        val entries = listOf(
            FavoriteMediaEntry(a, true),
            FavoriteMediaEntry(b, false),
        )

        val paths = favoriteEntriesToPaths(entries)

        assertEquals(setOf("p/a.jpg", "p/b.mp4"), paths)
    }

    @Test
    fun `favoriteEntriesToFiles preserves order and file payload`() {
        val a = MediaFile("a.jpg", "p/a.jpg", "p/a.jpg", 1L, "", "image", ".jpg")
        val entries = listOf(FavoriteMediaEntry(a, true))

        val files = favoriteEntriesToFiles(entries)

        assertEquals(1, files.size)
        assertEquals("p/a.jpg", files[0].relativePath)
    }

    @Test
    fun `derived helpers handle empty lists`() {
        assertEquals(emptySet<String>(), favoriteEntriesToPaths(emptyList()))
        assertEquals(emptyList<MediaFile>(), favoriteEntriesToFiles(emptyList()))
    }
}

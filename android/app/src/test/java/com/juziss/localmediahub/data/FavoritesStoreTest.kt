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
}

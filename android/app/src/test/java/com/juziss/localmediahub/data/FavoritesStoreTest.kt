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
            FavoriteEntry(file = a, isSystemBrowse = true),
            FavoriteEntry(file = b, isSystemBrowse = false),
        )

        val paths = favoriteEntriesToPaths(entries)

        assertEquals(setOf("p/a.jpg", "p/b.mp4"), paths)
    }

    @Test
    fun `favoriteEntriesToFiles preserves order and file payload`() {
        val a = MediaFile("a.jpg", "p/a.jpg", "p/a.jpg", 1L, "", "image", ".jpg")
        val entries = listOf(FavoriteEntry(file = a, isSystemBrowse = true))

        val files = favoriteEntriesToFiles(entries)

        assertEquals(1, files.size)
        assertEquals("p/a.jpg", files[0].relativePath)
    }

    @Test
    fun `derived helpers handle empty lists`() {
        assertEquals(emptySet<String>(), favoriteEntriesToPaths(emptyList()))
        assertEquals(emptyList<MediaFile>(), favoriteEntriesToFiles(emptyList()))
    }

    // ── Task 14: 三代兼容解码 + merge ─────────────────────────────────────

    @Test
    fun decodeThreeGenerations() {
        // 第一代：裸 MediaFile
        val bare = """{"name":"a.txt","path":"/m/a.txt","relative_path":"a.txt","size":1,"modified_time":"2026-01-01","media_type":"text","extension":".txt"}"""
        val e1 = decodeFavoriteEntryV2(gson, bare)!!
        assertEquals("/m/a.txt", e1.path)
        assertFalse(e1.isDir)
        assertFalse(e1.isSystemBrowse)

        // 第二代：{file, isSystemBrowse}
        val old = """{"file":{"name":"a.txt","path":"/m/a.txt","relative_path":"a.txt","size":1,"modified_time":"2026-01-01","media_type":"text","extension":".txt"},"isSystemBrowse":true}"""
        val e2 = decodeFavoriteEntryV2(gson, old)!!
        assertTrue(e2.isSystemBrowse)

        // 第三代：folder
        val folder = """{"folder":{"name":"comics","path":"/m/comics","relative_path":"comics","is_root":false},"isSystemBrowse":false,"addedAt":123}"""
        val e3 = decodeFavoriteEntryV2(gson, folder)!!
        assertTrue(e3.isDir)
        assertEquals("/m/comics", e3.path)
        assertEquals(123L, e3.addedAt)
        assertEquals("/m/comics", e3.identity)
    }

    @Test
    fun mergeFavoriteEntriesUnionByAddedAt() {
        val f = MediaFile("a.txt", "/m/a.txt", "a.txt", 1, "2026-01-01", "text", ".txt")
        val local = listOf(FavoriteEntry(file = f, isSystemBrowse = false, addedAt = 100))
        val folderEntry = FavoriteEntry(folder = Folder("comics", "/m/comics", "comics"), addedAt = 300)
        val server = listOf(
            ServerFavorite("/m/a.txt", false, false, "a.txt", "text",
                FavoriteEntry(file = f, addedAt = 200), 200),
            ServerFavorite("/m/comics", true, false, "comics", "folder", folderEntry, 300),
            ServerFavorite("/m/foreign", false, false, "x", "text", null, 50), // snapshot null → 跳过
        )
        val merged = mergeFavoriteEntries(local, server)
        assertEquals(2, merged.size)
        val byId = merged.associateBy { it.identity }
        assertEquals(200L, byId["a.txt"]!!.addedAt)     // server 较新胜出
        assertNotNull(byId["/m/comics"])                // 目录收藏并入
    }
}

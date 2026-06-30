package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.juziss.localmediahub.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class FavoriteMediaEntry(
    val file: MediaFile,
    val isSystemBrowse: Boolean,
)

internal fun decodeFavoriteEntry(gson: Gson, json: String): FavoriteMediaEntry? {
    return try {
        val element = JsonParser.parseString(json)
        val obj = element.takeIf { it.isJsonObject }?.asJsonObject
        if (obj?.has("file") == true) {
            gson.fromJson(json, FavoriteMediaEntry::class.java)
        } else {
            gson.fromJson(json, MediaFile::class.java)?.let { FavoriteMediaEntry(it, false) }
        }
    } catch (_: Exception) {
        try {
            gson.fromJson(json, MediaFile::class.java)?.let { FavoriteMediaEntry(it, false) }
        } catch (_: Exception) {
            null
        }
    }
}

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/**
 * Persists favorite files with full metadata using Jetpack DataStore.
 * Stores MediaFile as JSON so favorites list works independently of browse state.
 */
class FavoritesStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val gson = Gson()

    private val favoritesKey = stringSetPreferencesKey("favorite_files_json")

    // 解码一次：所有收藏条目（含 access-mode）。
    private val decoded: Flow<List<FavoriteMediaEntry>> =
        context.favoritesDataStore.data.map { preferences ->
            preferences[favoritesKey]?.mapNotNull { json ->
                decodeFavoriteEntry(gson, json)
            } ?: emptyList()
        }

    /** 共享热流：解码只在 upstream 发生一次，多消费方共享。 */
    val favoriteEntries: Flow<List<FavoriteMediaEntry>> =
        decoded.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 派生：路径集（无 JSON 解码）。 */
    val favorites: Flow<Set<String>> =
        favoriteEntries.map(::favoriteEntriesToPaths)

    /** 派生：文件列表（无 JSON 解码）。 */
    val favoriteFiles: Flow<List<MediaFile>> =
        favoriteEntries.map(::favoriteEntriesToFiles)

    /** Add a file to favorites. No-op if already present. */
    suspend fun addFavorite(file: MediaFile, isSystemBrowse: Boolean) {
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                decodeFavoriteEntry(gson, json)?.file?.relativePath == file.relativePath
            }.toSet()
            val json = toJson(FavoriteMediaEntry(file, isSystemBrowse))
            preferences[favoritesKey] = filtered + json
        }
    }

    /** Remove a file from favorites by path. */
    suspend fun removeFavorite(relativePath: String) {
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            preferences[favoritesKey] = current.filterNot { json ->
                decodeFavoriteEntry(gson, json)?.file?.relativePath == relativePath
            }.toSet()
        }
    }

    /** Toggle favorite status for a file. */
    suspend fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean): Boolean {
        var isNowFavorite = false
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val existing = current.find { decodeFavoriteEntry(gson, it)?.file?.relativePath == file.relativePath }
            if (existing != null) {
                preferences[favoritesKey] = current - existing
                isNowFavorite = false
            } else {
                preferences[favoritesKey] = current + toJson(FavoriteMediaEntry(file, isSystemBrowse))
                isNowFavorite = true
            }
        }
        return isNowFavorite
    }

    private fun toJson(entry: FavoriteMediaEntry): String = gson.toJson(entry)
}

/** 派生：收藏条目 → 相对路径集（无 JSON 解码）。 */
internal fun favoriteEntriesToPaths(entries: List<FavoriteMediaEntry>): Set<String> =
    entries.map { it.file.relativePath }.toSet()

/** 派生：收藏条目 → 文件列表（无 JSON 解码）。 */
internal fun favoriteEntriesToFiles(entries: List<FavoriteMediaEntry>): List<MediaFile> =
    entries.map { it.file }


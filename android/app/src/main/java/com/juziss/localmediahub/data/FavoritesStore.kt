package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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

/**
 * Persists favorite files with full metadata using Jetpack DataStore.
 * Stores MediaFile as JSON so favorites list works independently of browse state.
 */
class FavoritesStore(private val context: Context) {

    private val Context.dataStore by preferencesDataStore(name = "favorites")
    private val gson = Gson()

    private val favoritesKey = stringSetPreferencesKey("favorite_files_json")

    /** Emits the current set of favorite paths. */
    val favorites: Flow<Set<String>> = context.dataStore.data.map { preferences ->
        preferences[favoritesKey]?.mapNotNull { json ->
            decodeFavoriteEntry(gson, json)?.file?.relativePath
        }?.toSet() ?: emptySet()
    }

    /** Emits the full list of favorited MediaFile objects. */
    val favoriteFiles: Flow<List<MediaFile>> = context.dataStore.data.map { preferences ->
        preferences[favoritesKey]?.mapNotNull { json ->
            decodeFavoriteEntry(gson, json)?.file
        } ?: emptyList()
    }

    /** Emits the full list of favorited entries with access-mode metadata. */
    val favoriteEntries: Flow<List<FavoriteMediaEntry>> = context.dataStore.data.map { preferences ->
        preferences[favoritesKey]?.mapNotNull { json ->
            decodeFavoriteEntry(gson, json)
        } ?: emptyList()
    }

    /** Add a file to favorites. No-op if already present. */
    suspend fun addFavorite(file: MediaFile, isSystemBrowse: Boolean) {
        context.dataStore.edit { preferences ->
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
        context.dataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            preferences[favoritesKey] = current.filterNot { json ->
                decodeFavoriteEntry(gson, json)?.file?.relativePath == relativePath
            }.toSet()
        }
    }

    /** Toggle favorite status for a file. */
    suspend fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean): Boolean {
        var isNowFavorite = false
        context.dataStore.edit { preferences ->
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

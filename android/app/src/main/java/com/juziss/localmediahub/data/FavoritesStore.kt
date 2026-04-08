package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
            fromJson(json)?.relativePath
        }?.toSet() ?: emptySet()
    }

    /** Emits the full list of favorited MediaFile objects. */
    val favoriteFiles: Flow<List<MediaFile>> = context.dataStore.data.map { preferences ->
        preferences[favoritesKey]?.mapNotNull { json ->
            fromJson(json)
        } ?: emptyList()
    }

    /** Add a file to favorites. No-op if already present. */
    suspend fun addFavorite(file: MediaFile) {
        context.dataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val json = toJson(file)
            preferences[favoritesKey] = current + json
        }
    }

    /** Remove a file from favorites by path. */
    suspend fun removeFavorite(relativePath: String) {
        context.dataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            preferences[favoritesKey] = current.filterNot { json ->
                fromJson(json)?.relativePath == relativePath
            }.toSet()
        }
    }

    /** Toggle favorite status for a file. */
    suspend fun toggleFavorite(file: MediaFile): Boolean {
        var isNowFavorite = false
        context.dataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val existing = current.find { fromJson(it)?.relativePath == file.relativePath }
            if (existing != null) {
                preferences[favoritesKey] = current - existing
                isNowFavorite = false
            } else {
                preferences[favoritesKey] = current + toJson(file)
                isNowFavorite = true
            }
        }
        return isNowFavorite
    }

    private fun toJson(file: MediaFile): String = gson.toJson(file)

    private fun fromJson(json: String): MediaFile? {
        return try {
            gson.fromJson(json, MediaFile::class.java)
        } catch (_: Exception) {
            null
        }
    }
}

package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.recentActivityDataStore by preferencesDataStore(name = "recent_activity")

data class LastBrowseLocation(
    val path: String,
    val title: String,
    val isSystemBrowse: Boolean,
)

data class RecentMediaEntry(
    val file: MediaFile,
    val isSystemBrowse: Boolean,
    val openedAt: Long,
)

data class PlaybackProgressEntry(
    val file: MediaFile,
    val isSystemBrowse: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

internal fun deriveLocationTitle(path: String, fallback: String = ""): String {
    if (fallback.isNotBlank()) return fallback

    val normalized = path.replace('\\', '/').trimEnd('/')
    if (normalized.isBlank()) return "Libraries"

    val lastSegment = normalized.substringAfterLast('/')
    return when {
        lastSegment.isNotBlank() -> lastSegment
        normalized.endsWith(":") -> normalized
        else -> path
    }
}

internal fun mergeRecentMedia(
    existing: List<RecentMediaEntry>,
    incoming: RecentMediaEntry,
    limit: Int = 8,
): List<RecentMediaEntry> {
    return (listOf(incoming) + existing.filterNot {
        it.file.relativePath == incoming.file.relativePath && it.isSystemBrowse == incoming.isSystemBrowse
    })
        .sortedByDescending { it.openedAt }
        .take(limit)
}

internal fun shouldKeepPlaybackProgress(
    positionMs: Long,
    durationMs: Long,
): Boolean {
    if (positionMs < 10_000L || durationMs <= 0L) return false
    return positionMs < (durationMs * 0.95).toLong()
}

internal fun mergePlaybackProgress(
    existing: List<PlaybackProgressEntry>,
    incoming: PlaybackProgressEntry,
    limit: Int = 8,
): List<PlaybackProgressEntry> {
    return (listOf(incoming) + existing.filterNot {
        it.file.relativePath == incoming.file.relativePath && it.isSystemBrowse == incoming.isSystemBrowse
    })
        .sortedByDescending { it.updatedAt }
        .take(limit)
}

class RecentActivityStore(private val context: Context) {

    private val gson = Gson()

    private val recentMediaKey = stringPreferencesKey("recent_media")
    private val lastBrowseLocationKey = stringPreferencesKey("last_browse_location")
    private val playbackProgressKey = stringPreferencesKey("playback_progress")

    val recentMedia: Flow<List<RecentMediaEntry>> = context.recentActivityDataStore.data.map { preferences ->
        decodeRecentMedia(preferences[recentMediaKey])
    }

    val lastBrowseLocation: Flow<LastBrowseLocation?> = context.recentActivityDataStore.data.map { preferences ->
        decodeLastBrowseLocation(preferences[lastBrowseLocationKey])
    }

    val playbackProgress: Flow<List<PlaybackProgressEntry>> = context.recentActivityDataStore.data.map { preferences ->
        decodePlaybackProgress(preferences[playbackProgressKey])
    }

    suspend fun addRecentMedia(
        file: MediaFile,
        isSystemBrowse: Boolean,
        openedAt: Long = System.currentTimeMillis(),
    ) {
        context.recentActivityDataStore.edit { preferences ->
            val current = decodeRecentMedia(preferences[recentMediaKey])
            val merged = mergeRecentMedia(
                existing = current,
                incoming = RecentMediaEntry(
                    file = file,
                    isSystemBrowse = isSystemBrowse,
                    openedAt = openedAt,
                ),
            )
            preferences[recentMediaKey] = gson.toJson(merged)
        }
    }

    suspend fun saveLastBrowseLocation(
        path: String,
        title: String,
        isSystemBrowse: Boolean,
    ) {
        context.recentActivityDataStore.edit { preferences ->
            preferences[lastBrowseLocationKey] = gson.toJson(
                LastBrowseLocation(
                    path = path,
                    title = deriveLocationTitle(path, title),
                    isSystemBrowse = isSystemBrowse,
                )
            )
        }
    }

    suspend fun savePlaybackProgress(
        file: MediaFile,
        isSystemBrowse: Boolean,
        positionMs: Long,
        durationMs: Long,
        updatedAt: Long = System.currentTimeMillis(),
    ) {
        context.recentActivityDataStore.edit { preferences ->
            val current = decodePlaybackProgress(preferences[playbackProgressKey])
            if (!shouldKeepPlaybackProgress(positionMs, durationMs)) {
                preferences[playbackProgressKey] = gson.toJson(
                    current.filterNot {
                        it.file.relativePath == file.relativePath && it.isSystemBrowse == isSystemBrowse
                    }
                )
                return@edit
            }

            val merged = mergePlaybackProgress(
                existing = current,
                incoming = PlaybackProgressEntry(
                    file = file,
                    isSystemBrowse = isSystemBrowse,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = updatedAt,
                ),
            )
            preferences[playbackProgressKey] = gson.toJson(merged)
        }
    }

    suspend fun clearPlaybackProgress(
        file: MediaFile,
        isSystemBrowse: Boolean,
    ) {
        context.recentActivityDataStore.edit { preferences ->
            val current = decodePlaybackProgress(preferences[playbackProgressKey])
            preferences[playbackProgressKey] = gson.toJson(
                current.filterNot {
                    it.file.relativePath == file.relativePath && it.isSystemBrowse == isSystemBrowse
                }
            )
        }
    }

    private fun decodeRecentMedia(json: String?): List<RecentMediaEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<RecentMediaEntry>>() {}.type
            gson.fromJson<List<RecentMediaEntry>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun decodeLastBrowseLocation(json: String?): LastBrowseLocation? {
        if (json.isNullOrBlank()) return null
        return try {
            gson.fromJson(json, LastBrowseLocation::class.java)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePlaybackProgress(json: String?): List<PlaybackProgressEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<PlaybackProgressEntry>>() {}.type
            gson.fromJson<List<PlaybackProgressEntry>>(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

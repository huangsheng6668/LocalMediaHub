package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject

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

/**
 * 客户端保存的电子书阅读进度。path 为书在服务端的 relativePath;
 * chapterIndex 是当前章节索引;scrollOffsetPx 是章节内滚动像素偏移;
 * lastReadAt 是 epoch 毫秒,用于排序书架展示。
 */
data class BookProgress(
    val path: String,
    val chapterIndex: Int,
    val scrollOffsetPx: Int,
    val lastReadAt: Long,
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

/** 进度低于此值(毫秒)的播放不保存。 */
internal const val MIN_KEEP_POSITION_MS: Long = 10_000L

/** 进度达到 duration × 此比例视为"已看完",会触发弹窗。 */
internal const val COMPLETED_RATIO: Double = 0.95

/** 进度达到 duration × 此比例时,弹窗默认聚焦"从头开始"。 */
internal const val COMPLETED_FOCUS_RATIO: Double = 0.98

/** 进度是否值得保存(>= 10 秒且有时长)。 */
internal fun isValidProgress(positionMs: Long, durationMs: Long): Boolean {
    if (positionMs < MIN_KEEP_POSITION_MS || durationMs <= 0L) return false
    return true
}

/** 进度是否视为"已看完"(>= 95%)。仅在有时长时有意义。 */
internal fun isCompleted(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    return positionMs >= (durationMs * COMPLETED_RATIO).toLong()
}

/** 弹窗是否应当默认聚焦"从头开始"(>= 98%)。仅在有时长时有意义。 */
internal fun shouldFocusRestart(positionMs: Long, durationMs: Long): Boolean {
    if (durationMs <= 0L) return false
    return positionMs >= (durationMs * COMPLETED_FOCUS_RATIO).toLong()
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

/** 在已保存的进度列表中按 key 查找单条记录。 */
internal fun findPlaybackProgress(
    list: List<PlaybackProgressEntry>,
    file: MediaFile,
    isSystemBrowse: Boolean,
): PlaybackProgressEntry? {
    return list.firstOrNull {
        it.file.relativePath == file.relativePath && it.isSystemBrowse == isSystemBrowse
    }
}

class RecentActivityStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val gson = Gson()

    private val recentMediaKey = stringPreferencesKey("recent_media")
    private val lastBrowseLocationKey = stringPreferencesKey("last_browse_location")
    private val playbackProgressKey = stringPreferencesKey("playback_progress")
    private val bookProgressKey = stringPreferencesKey("book_progress")

    private val typeMapBookProgress = object : TypeToken<MutableMap<String, BookProgress>>() {}.type

    val recentMedia: Flow<List<RecentMediaEntry>> = context.recentActivityDataStore.data.map { preferences ->
        decodeRecentMedia(preferences[recentMediaKey])
    }

    val lastBrowseLocation: Flow<LastBrowseLocation?> = context.recentActivityDataStore.data.map { preferences ->
        decodeLastBrowseLocation(preferences[lastBrowseLocationKey])
    }

    val playbackProgress: Flow<List<PlaybackProgressEntry>> = context.recentActivityDataStore.data.map { preferences ->
        decodePlaybackProgress(preferences[playbackProgressKey])
    }

    val bookProgressFlow: Flow<Map<String, BookProgress>> = context.recentActivityDataStore.data.map { preferences ->
        decodeBookProgress(preferences[bookProgressKey])
    }

    /** 以 lastReadAt 倒序返回全部阅读进度(供 HomeViewModel 书架使用)。 */
    fun getAllBookProgressFlow(): Flow<List<BookProgress>> = bookProgressFlow
        .map { map -> map.values.sortedByDescending { it.lastReadAt } }

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
            if (!isValidProgress(positionMs, durationMs)) {
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

    /** 查询单个视频当前已保存的进度。无记录返回 null。 */
    suspend fun getPlaybackProgress(
        file: MediaFile,
        isSystemBrowse: Boolean,
    ): PlaybackProgressEntry? {
        val current = context.recentActivityDataStore.data.map { preferences ->
            decodePlaybackProgress(preferences[playbackProgressKey])
        }.firstOrNull() ?: emptyList()
        return findPlaybackProgress(current, file, isSystemBrowse)
    }

    /** 查询单本书的阅读进度。无记录返回 null。 */
    suspend fun getBookProgress(path: String): BookProgress? {
        val current = context.recentActivityDataStore.data.map { preferences ->
            decodeBookProgress(preferences[bookProgressKey])
        }.firstOrNull() ?: emptyMap()
        return current[path]
    }

    /** 保存或覆盖一本书的阅读进度(以 path 为 key)。 */
    suspend fun saveBookProgress(progress: BookProgress) {
        context.recentActivityDataStore.edit { preferences ->
            val current = decodeBookProgress(preferences[bookProgressKey]).toMutableMap()
            current[progress.path] = progress
            preferences[bookProgressKey] = if (current.isEmpty()) "" else gson.toJson(current)
        }
    }

    /** 删除单本书的阅读进度。不存在时为 no-op。 */
    suspend fun clearBookProgress(path: String) {
        context.recentActivityDataStore.edit { preferences ->
            val current = decodeBookProgress(preferences[bookProgressKey]).toMutableMap()
            if (current.remove(path) == null) return@edit
            preferences[bookProgressKey] = if (current.isEmpty()) "" else gson.toJson(current)
        }
    }

    /** 清空全部阅读进度。用于书架"清空"操作及测试隔离。 */
    suspend fun clearAllBookProgress() {
        context.recentActivityDataStore.edit { preferences ->
            preferences[bookProgressKey] = ""
        }
    }

    /** 以 lastReadAt 倒序返回全部阅读进度。 */
    suspend fun getAllBookProgress(): List<BookProgress> =
        getAllBookProgressFlow().firstOrNull() ?: emptyList()

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

    private fun decodeBookProgress(json: String?): Map<String, BookProgress> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            gson.fromJson<Map<String, BookProgress>>(json, typeMapBookProgress) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

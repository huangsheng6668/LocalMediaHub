package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
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

/**
 * Global Gson enum-default factory.
 *
 * Gson's default enum deserializer silently sets a non-nullable Kotlin enum field
 * to `null` when it encounters an unknown enum-name string (it does NOT throw).
 * For `ReaderSettings` this means `theme`/`readingMode`/`pageTurnStyle` would all
 * null-out on a corrupt or future enum value, causing NPEs at first property access.
 *
 * This factory intercepts every `Enum<*>` type: on read it looks up the token in a
 * name→constant map; if absent (unknown name), it falls back to the enum's first
 * declared constant — the conservative default for all three enums
 * (DAY / CHAPTER / NONE). Non-enum types return `null` from [create] so Gson's
 * built-in adapters handle them (Int/Float/List/Map/Boolean/String unchanged).
 *
 * Registered once on the shared Gson instance in [RecentActivityStore]; proguard
 * already keeps `TypeAdapterFactory` (see proguard-rules.pro).
 */
private object EnumDefaultFactory : TypeAdapterFactory {
    override fun <T> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        if (!raw.isEnum) return null
        // Fixed at factory-creation time: the enum constants of this exact type.
        // Lookup by name is O(n) over a tiny enum set — negligible and avoids the
        // generic `Class<T : Enum<T>>` inference trap of `java.lang.Enum.valueOf`.
        val constants = raw.enumConstants ?: return null
        val byName = constants.associateBy { (it as Enum<*>).name }
        val fallback = constants.firstOrNull() ?: return null

        return object : TypeAdapter<Any>() {
            override fun write(out: JsonWriter, value: Any?) {
                out.value((value as Enum<*>).name)
            }

            override fun read(reader: JsonReader): Any? {
                val name = reader.nextString()
                return byName[name] ?: fallback
            }
        } as TypeAdapter<T>
    }
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

    private val gson: Gson =
        GsonBuilder().registerTypeAdapterFactory(EnumDefaultFactory).create()

    private val recentMediaKey = stringPreferencesKey("recent_media")
    private val lastBrowseLocationKey = stringPreferencesKey("last_browse_location")
    private val playbackProgressKey = stringPreferencesKey("playback_progress")
    private val bookProgressKey = stringPreferencesKey("book_progress")
    private val readerSettingsKey = stringPreferencesKey("reader_settings")
    private val bookBookmarksKey = stringPreferencesKey("book_bookmarks")

    private val typeMapBookProgress = object : TypeToken<MutableMap<String, BookProgress>>() {}.type
    private val typeMapBookmarks = object : TypeToken<MutableMap<String, MutableList<Bookmark>>>() {}.type

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

    val readerSettingsFlow: Flow<ReaderSettings> = context.recentActivityDataStore.data.map { preferences ->
        decodeReaderSettings(preferences[readerSettingsKey])
    }

    fun getBookmarksFlow(path: String): Flow<List<Bookmark>> =
        context.recentActivityDataStore.data.map { preferences ->
            decodeBookmarks(preferences[bookBookmarksKey])[path] ?: emptyList()
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

    /** 读取全局阅读器设置;未保存过时返回默认值。 */
    suspend fun getReaderSettings(): ReaderSettings {
        return readerSettingsFlow.firstOrNull() ?: ReaderSettings()
    }

    /** 保存或覆盖全局阅读器设置。 */
    suspend fun saveReaderSettings(settings: ReaderSettings) {
        context.recentActivityDataStore.edit { preferences ->
            preferences[readerSettingsKey] = gson.toJson(settings)
        }
    }

    /** 清空全局阅读器设置(回退到默认)。用于测试隔离与重置。 */
    suspend fun clearAllReaderSettings() {
        context.recentActivityDataStore.edit { preferences ->
            preferences[readerSettingsKey] = ""
        }
    }

    /** 查询单本书的全部书签;无记录返回空列表。 */
    suspend fun getBookmarks(path: String): List<Bookmark> {
        val all = context.recentActivityDataStore.data.map { preferences ->
            decodeBookmarks(preferences[bookBookmarksKey])
        }.firstOrNull() ?: emptyMap()
        return all[path] ?: emptyList()
    }

    /**
     * 添加书签。若已存在相同 (bookPath, chapterIndex, paragraphIndex) 的书签,
     * 返回 false 且不覆盖原有 createdAt(不做 upsert)。
     */
    suspend fun addBookmark(bookmark: Bookmark): Boolean {
        var added = false
        context.recentActivityDataStore.edit { preferences ->
            val all = decodeBookmarks(preferences[bookBookmarksKey])
                .mapValuesTo(mutableMapOf()) { it.value.toMutableList() }
            val list = all.getOrPut(bookmark.bookPath) { mutableListOf() }
            val exists = list.any {
                it.chapterIndex == bookmark.chapterIndex &&
                    it.paragraphIndex == bookmark.paragraphIndex
            }
            if (!exists) {
                list.add(bookmark)
                all[bookmark.bookPath] = list
                preferences[bookBookmarksKey] = encodeBookmarks(all)
                added = true
            }
        }
        return added
    }

    /** 删除匹配 (bookPath, chapterIndex, paragraphIndex) 的书签;不存在时为 no-op。 */
    suspend fun deleteBookmark(bookmark: Bookmark) {
        context.recentActivityDataStore.edit { preferences ->
            val all = decodeBookmarks(preferences[bookBookmarksKey]).toMutableMap()
            val list = all[bookmark.bookPath]?.toMutableList() ?: return@edit
            list.removeAll {
                it.chapterIndex == bookmark.chapterIndex &&
                    it.paragraphIndex == bookmark.paragraphIndex
            }
            if (list.isEmpty()) {
                all.remove(bookmark.bookPath)
            } else {
                all[bookmark.bookPath] = list
            }
            preferences[bookBookmarksKey] = if (all.isEmpty()) "" else encodeBookmarks(all)
        }
    }

    /** 清空单本书的全部书签;不存在时为 no-op。 */
    suspend fun clearBookmarks(path: String) {
        context.recentActivityDataStore.edit { preferences ->
            val all = decodeBookmarks(preferences[bookBookmarksKey]).toMutableMap()
            if (all.remove(path) == null) return@edit
            preferences[bookBookmarksKey] = if (all.isEmpty()) "" else encodeBookmarks(all)
        }
    }

    /** 清空所有书签(跨全部书)。用于测试隔离与全局重置。 */
    suspend fun clearAllBookmarks() {
        context.recentActivityDataStore.edit { preferences ->
            preferences[bookBookmarksKey] = ""
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

    private fun decodeBookProgress(json: String?): Map<String, BookProgress> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            gson.fromJson<Map<String, BookProgress>>(json, typeMapBookProgress) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun decodeReaderSettings(json: String?): ReaderSettings {
        if (json.isNullOrBlank()) return ReaderSettings()
        return try {
            val migrated = migrateReaderSettingsJson(json)
            gson.fromJson(migrated, ReaderSettings::class.java) ?: ReaderSettings()
        } catch (_: Exception) {
            ReaderSettings()
        }
    }

    /** Test-only: 直接注入 raw JSON 到 reader_settings key，用于 V1→V2 迁移测试。 */
    internal suspend fun injectRawReaderSettingsForTest(raw: String) {
        context.recentActivityDataStore.edit { preferences ->
            preferences[readerSettingsKey] = raw
        }
    }

    private fun decodeBookmarks(json: String?): Map<String, List<Bookmark>> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            gson.fromJson<Map<String, List<Bookmark>>>(json, typeMapBookmarks) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun encodeBookmarks(map: Map<String, List<Bookmark>>): String {
        return gson.toJson(map)
    }
}

/**
 * 把 V1 reader_settings JSON 改写为 V2 形态，交给 Gson 反序列化。
 *
 * V1 → V2 三种变化都要处理：
 *  1. `fontSize`（V1 字符串枚举名 "SMALL"/"MEDIUM"/"LARGE"/"XLARGE"）
 *     → `fontSizeSp`（V2 Int 12..28）。
 *  2. `lineHeight`（V1 字符串枚举名 "COMPACT"/"STANDARD"/"LOOSE"）
 *     → `lineHeightMultiplier`（V2 Float）。
 *  3. 其他字段（theme/autoScrollSpeed）形态未变，透传即可。
 *
 * Gson 默认无法把字符串 `"MEDIUM"` 反序列化为 Int，会抛 JsonSyntaxException。
 * 我们在 Gson 解析前手工改写 JsonObject，保证迁移不丢失非 font-size 字段。
 *
 * 关键点：V1 key 是 `fontSize`/`lineHeight`，V2 字段是 `fontSizeSp`/
 * `lineHeightMultiplier`，因此同时改 key 名 + value 类型。如果只改 value 不
 * 改 key，Gson 找不到对应字段会静默忽略，得到 default 16/1.8。
 */
private val v1FontSizeMap = mapOf(
    "SMALL" to 14, "MEDIUM" to 16, "LARGE" to 18, "XLARGE" to 20,
)
private val v1LineHeightMap = mapOf(
    "COMPACT" to 1.4f, "STANDARD" to 1.8f, "LOOSE" to 2.2f,
)

private fun migrateReaderSettingsJson(raw: String): String {
    return try {
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject

        // fontSize -> fontSizeSp, 字符串枚举名 -> 数字
        val fs = obj.remove("fontSize")
        if (fs != null && fs.isJsonPrimitive) {
            val prim = fs.asJsonPrimitive
            val mapped: Any? = when {
                prim.isString -> v1FontSizeMap[prim.asString]
                prim.isNumber -> prim.asNumber.toInt()
                else -> null
            }
            if (mapped != null) obj.addProperty("fontSizeSp", mapped as Int)
        }

        // lineHeight -> lineHeightMultiplier, 字符串枚举名 -> 数字
        val lh = obj.remove("lineHeight")
        if (lh != null && lh.isJsonPrimitive) {
            val prim = lh.asJsonPrimitive
            val mapped: Any? = when {
                prim.isString -> v1LineHeightMap[prim.asString]
                prim.isNumber -> prim.asNumber.toFloat()
                else -> null
            }
            if (mapped != null) obj.addProperty("lineHeightMultiplier", mapped as Float)
        }

        obj.toString()
    } catch (_: Exception) {
        // 解析失败就让上层 Gson 再失败、走 fallback 默认值
        raw
    }
}

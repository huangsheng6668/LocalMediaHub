package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.juziss.localmediahub.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ── 第三代收藏条目模型 ──────────────────────────────────────────────────────

/**
 * 第三代收藏条目，支持文件与目录，兼容一、二代 JSON。
 *
 * - 第一代：裸 MediaFile JSON
 * - 第二代：{file: MediaFile, isSystemBrowse: Boolean}
 * - 第三代：{file?: MediaFile, folder?: Folder, isSystemBrowse: Boolean, addedAt: Long}
 */
data class FavoriteEntry(
    val file: MediaFile? = null,
    val folder: Folder? = null,
    val isSystemBrowse: Boolean = false,
    val addedAt: Long = 0L,
) {
    val path: String get() = file?.path ?: folder?.path ?: ""
    val isDir: Boolean get() = folder != null
    /** 用于去重/匹配的 identity key：文件用 relativePath，目录用 path。 */
    val identity: String get() = file?.relativePath ?: folder?.path ?: ""
}

/**
 * 三代兼容解码器：将 DataStore 中的 JSON 字符串解码为 FavoriteEntry。
 */
internal fun decodeFavoriteEntryV2(gson: Gson, json: String): FavoriteEntry? = try {
    val obj: JsonObject = gson.fromJson(json, JsonObject::class.java) ?: return null
    when {
        obj.has("folder") || obj.has("file") || obj.has("addedAt") ->
            gson.fromJson(json, FavoriteEntry::class.java)
                ?.takeIf { it.file != null || it.folder != null }
        else -> FavoriteEntry(file = gson.fromJson(json, MediaFile::class.java)) // 第一代裸 MediaFile
    }
} catch (_: Exception) { null }

/**
 * 合并本地收藏与服务端收藏：
 * - 以 identity 为 key 做并集
 * - 冲突时取 addedAt 较大者，相等取 local
 * - server 行 snapshot 为 null 或 file/folder 均为 null 时跳过（Web 来源无法渲染）
 */
internal fun mergeFavoriteEntries(
    local: List<FavoriteEntry>,
    server: List<ServerFavorite>,
): List<FavoriteEntry> {
    val byId = LinkedHashMap<String, FavoriteEntry>()
    local.forEach { byId[it.identity] = it }
    for (rec in server) {
        val snap = rec.snapshot ?: continue
        if (snap.file == null && snap.folder == null) continue
        val entry = snap.copy(
            isSystemBrowse = rec.isSystem || snap.isSystemBrowse,
            addedAt = rec.addedAt,
        )
        val existing = byId[entry.identity]
        if (existing == null || entry.addedAt > existing.addedAt) byId[entry.identity] = entry
    }
    return byId.values.toList()
}

// ── 旧模型保留（二代兼容 + 现有测试） ─────────────────────────────────────

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

// ── DataStore ──────────────────────────────────────────────────────────────

private val Context.favoritesDataStore by preferencesDataStore(name = "favorites")

/**
 * Persists favorite files/folders with full metadata using Jetpack DataStore.
 * Stores entries as JSON so favorites list works independently of browse state.
 */
class FavoritesStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private val gson = Gson()

    private val favoritesKey = stringSetPreferencesKey("favorite_files_json")
    private val progressSyncedKey = booleanPreferencesKey("library_progress_synced")

    // 解码一次：所有收藏条目，优先用 V2 三代解码器。
    private val decoded: Flow<List<FavoriteEntry>> =
        context.favoritesDataStore.data.map { preferences ->
            preferences[favoritesKey]?.mapNotNull { json ->
                decodeFavoriteEntryV2(gson, json)
            } ?: emptyList()
        }

    /** 共享热流：解码只在 upstream 发生一次，多消费方共享。 */
    val favoriteEntries: Flow<List<FavoriteEntry>> =
        decoded.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 派生：路径/identity 集（文件用 relativePath，目录用 path）。 */
    val favorites: Flow<Set<String>> =
        favoriteEntries.map(::favoriteEntriesToPaths)

    /** 派生：文件列表（仅文件收藏）。 */
    val favoriteFiles: Flow<List<MediaFile>> =
        favoriteEntries.map(::favoriteEntriesToFiles)

    /** 派生：目录列表（仅目录收藏）。 */
    val favoriteFolders: Flow<List<Folder>> =
        favoriteEntries.map(::favoriteEntriesToFolders)

    /** Add a file to favorites. No-op if already present (identity dedup). */
    suspend fun addFavorite(file: MediaFile, isSystemBrowse: Boolean) {
        val entry = FavoriteEntry(
            file = file, isSystemBrowse = isSystemBrowse,
            addedAt = System.currentTimeMillis(),
        )
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                decodeFavoriteEntryV2(gson, json)?.identity == entry.identity
            }.toSet()
            preferences[favoritesKey] = filtered + toJson(entry)
        }
    }

    /** Add a folder to favorites. No-op if already present. */
    suspend fun addFavoriteFolder(folder: Folder, isSystemBrowse: Boolean) {
        val entry = FavoriteEntry(
            folder = folder, isSystemBrowse = isSystemBrowse,
            addedAt = System.currentTimeMillis(),
        )
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                decodeFavoriteEntryV2(gson, json)?.identity == entry.identity
            }.toSet()
            preferences[favoritesKey] = filtered + toJson(entry)
        }
    }

    /** Remove a file from favorites by relativePath (identity). */
    suspend fun removeFavorite(relativePath: String) {
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            preferences[favoritesKey] = current.filterNot { json ->
                decodeFavoriteEntryV2(gson, json)?.identity == relativePath
            }.toSet()
        }
    }

    /** Toggle favorite status for a file. Returns true if now favorited. */
    suspend fun toggleFavorite(file: MediaFile, isSystemBrowse: Boolean): Boolean {
        var isNowFavorite = false
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val existing = current.find {
                decodeFavoriteEntryV2(gson, it)?.identity == file.relativePath
            }
            if (existing != null) {
                preferences[favoritesKey] = current - existing
                isNowFavorite = false
            } else {
                val entry = FavoriteEntry(
                    file = file, isSystemBrowse = isSystemBrowse,
                    addedAt = System.currentTimeMillis(),
                )
                preferences[favoritesKey] = current + toJson(entry)
                isNowFavorite = true
            }
        }
        return isNowFavorite
    }

    /** Toggle favorite status for a folder. Returns true if now favorited. */
    suspend fun toggleFavoriteFolder(folder: Folder, isSystemBrowse: Boolean): Boolean {
        var isNowFavorite = false
        context.favoritesDataStore.edit { preferences ->
            val current = preferences[favoritesKey] ?: emptySet()
            val existing = current.find {
                decodeFavoriteEntryV2(gson, it)?.identity == folder.path
            }
            if (existing != null) {
                preferences[favoritesKey] = current - existing
                isNowFavorite = false
            } else {
                val entry = FavoriteEntry(
                    folder = folder, isSystemBrowse = isSystemBrowse,
                    addedAt = System.currentTimeMillis(),
                )
                preferences[favoritesKey] = current + toJson(entry)
                isNowFavorite = true
            }
        }
        return isNowFavorite
    }

    /**
     * 事务性替换全部收藏（双向同步 merge 落盘用）。
     */
    suspend fun replaceAll(entries: List<FavoriteEntry>) {
        context.favoritesDataStore.edit { preferences ->
            preferences[favoritesKey] = entries.map { toJson(it) }.toSet()
        }
    }

    // ── 进度迁移 flag ──────────────────────────────────────────────────────

    /** 是否已完成本地进度向服务端的一次性迁移。 */
    suspend fun isProgressMigrationDone(): Boolean =
        context.favoritesDataStore.data.first()[progressSyncedKey] == true

    /** 标记进度迁移完成（幂等）。 */
    suspend fun setProgressMigrationDone() {
        context.favoritesDataStore.edit { it[progressSyncedKey] = true }
    }

    private fun toJson(entry: FavoriteEntry): String = gson.toJson(entry)
}

// ── 派生 helpers ───────────────────────────────────────────────────────────

/** 路径/identity 集（文件用 relativePath，目录用 path）。 */
internal fun favoriteEntriesToPaths(entries: List<FavoriteEntry>): Set<String> =
    entries.map { it.identity }.toSet()

/** 仅文件收藏 → MediaFile 列表。 */
internal fun favoriteEntriesToFiles(entries: List<FavoriteEntry>): List<MediaFile> =
    entries.mapNotNull { it.file }

/** 仅目录收藏 → Folder 列表。 */
internal fun favoriteEntriesToFolders(entries: List<FavoriteEntry>): List<Folder> =
    entries.mapNotNull { it.folder }

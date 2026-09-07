package com.juziss.localmediahub.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private val Context.readingTimeDataStore by preferencesDataStore("reading_time")

/**
 * 阅读时长 pending 缓冲（per-path Map，DataStore 里持久为 JSON 字符串）：
 * 离线累积、上报失败回退、连线分片补传的单一事实源。
 * takePendingUpto(path, max) 取出该书 ≤max 的一 片并扣除（与服务端 clamp 3600 对齐）。
 */
@Singleton
class ReadingTimeStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()
    private val mapKey = stringPreferencesKey("pending_read_seconds_map")
    private val mutex = Mutex()

    private suspend fun readMap(): MutableMap<String, Long> {
        val raw = context.readingTimeDataStore.data.firstOrNull()?.get(mapKey)
        if (raw.isNullOrEmpty()) return mutableMapOf()
        return try {
            gson.fromJson(raw, object : TypeToken<MutableMap<String, Long>>() {}.type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    private suspend fun writeMap(map: Map<String, Long>) {
        context.readingTimeDataStore.edit { prefs ->
            val filtered = map.filterValues { it > 0 }
            if (filtered.isEmpty()) prefs.remove(mapKey) else prefs[mapKey] = gson.toJson(filtered)
        }
    }

    suspend fun addPending(path: String, delta: Long) {
        if (delta <= 0) return
        mutex.withLock {
            val m = readMap()
            m[path] = (m[path] ?: 0L) + delta
            writeMap(m)
        }
    }

    /** 取出该书 pending 中 ≤max 的一片并扣除；返回 0 表示该书无待传。 */
    suspend fun takePendingUpto(path: String, max: Long): Long = mutex.withLock {
        val m = readMap()
        val pending = m[path] ?: return@withLock 0L
        val take = pending.coerceAtMost(max)
        val rest = pending - take
        if (rest > 0) m[path] = rest else m.remove(path)
        writeMap(m)
        take
    }

    suspend fun pendingPaths(): List<String> = mutex.withLock { readMap().keys.toList() }
}

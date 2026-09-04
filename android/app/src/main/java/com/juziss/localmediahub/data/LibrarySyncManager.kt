package com.juziss.localmediahub.data

import com.juziss.localmediahub.di.ApplicationScope
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双向同步管理器，进程内只执行一次（由 [ensureStarted] 保证）。
 *
 * 职责：
 * 1. 一次性本地进度迁移上报：将 [RecentActivityStore] 中的 BookProgress 行上报到服务端，
 *    任一成功即置位迁移完成 flag（服务端 lastReadAt 守卫幂等）。
 * 2. 全量收藏双向同步：将本地收藏推送到服务端（幂等），再拉取服务端收藏合并落盘。
 *
 * 任何上报失败均静默降级（[runCatching]）。
 */
@Singleton
class LibrarySyncManager @Inject constructor(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val repository: MediaRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    /**
     * 每进程只允许触发一次同步，幂等。
     * 调用方无需等待（fire-and-forget）。
     */
    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { runCatching { syncOnce() } }
    }

    private suspend fun syncOnce() {
        // ── 1. 进度一次性迁移上报 ─────────────────────────────────────────
        if (!favoritesStore.isProgressMigrationDone()) {
            val all = recentActivityStore.getAllBookProgressFlow().first()
            var anySuccess = all.isEmpty() // 空列表视为成功（无需迁移）
            all.forEach { p ->
                val result = repository.reportReadingState(
                    path = p.path,
                    chapterIndex = p.chapterIndex,
                    paraIndex = p.blockIndex,
                    percent = 0.0,
                    finished = false,
                    lastReadAt = p.lastReadAt,
                )
                if (result is NetworkResult.Success) anySuccess = true
            }
            if (anySuccess) favoritesStore.setProgressMigrationDone()
        }

        // ── 2. 收藏：全量推送（幂等）+ 拉取合并 ──────────────────────────
        val local = favoritesStore.favoriteEntries.first()
        local.forEach { entry ->
            repository.pushServerFavorite(buildFavoriteBody(entry))
        }
        val remote = repository.listServerFavorites()
        if (remote is NetworkResult.Success) {
            val merged = mergeFavoriteEntries(local, remote.data)
            favoritesStore.replaceAll(merged)
        }
    }
}

/** 收藏上报表体（toggle 即时推送与启动全量同步共用；snapshot 为完整 FavoriteEntry）。 */
internal fun buildFavoriteBody(entry: FavoriteEntry): Map<String, Any?> = mapOf(
    "path" to entry.path,
    "is_dir" to entry.isDir,
    "is_system" to entry.isSystemBrowse,
    "title" to (entry.file?.name ?: entry.folder?.name ?: ""),
    "media_type" to (if (entry.isDir) "folder" else entry.file?.mediaType.orEmpty()),
    "snapshot" to entry,
    "added_at" to entry.addedAt,
)

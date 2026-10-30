package com.juziss.localmediahub.data

import com.juziss.localmediahub.di.ApplicationScope
import com.juziss.localmediahub.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双向同步管理器，进程内只执行一次（由 [ensureStarted] 保证）。
 *
 * 职责：
 * 1. 一次性本地进度迁移上报：将 [RecentActivityStore] 中的 BookProgress 行上报到服务端，
 *    全部成功才置位迁移完成 flag（服务端 lastReadAt 守卫幂等）。
 * 2. 全量收藏双向同步：直接从 DataStore 读取本地收藏快照推送到服务端（幂等），
 *    再拉取服务端收藏并在事务内原子合并落盘（防 `StateFlow` 初始空列表抹除离线收藏）。
 * 3. 阅读时长 pending 补传：保留服务端已读完状态与已有阅读位置。
 */
@Singleton
class LibrarySyncManager @Inject constructor(
    private val favoritesStore: FavoritesStore,
    private val recentActivityStore: RecentActivityStore,
    private val readingTimeStore: ReadingTimeStore,
    private val repository: MediaRepository,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    private companion object {
        /** 补传分片上限（与服务端 read_seconds_delta clamp 3600 对齐）。 */
        const val MAX_SYNC_DELTA_SECONDS = 3600L

        /** 同步请求的在途并发上限（局域网单次往返 10-50ms，4 路已足以摊平延迟）。 */
        const val SYNC_CONCURRENCY = 4
    }

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
            val results = all.mapBounded(SYNC_CONCURRENCY) { p ->
                repository.reportReadingState(
                    path = p.path,
                    chapterIndex = p.chapterIndex,
                    paraIndex = p.blockIndex,
                    percent = 0.0,
                    finished = false,
                    lastReadAt = p.lastReadAt,
                )
            }
            if (results.all { it is NetworkResult.Success }) {
                favoritesStore.setProgressMigrationDone()
            }
        }

        // ── 2. 收藏：全量推送（幂等）+ 拉取事务合并 ──────────────────────
        val local = favoritesStore.readFavoriteEntriesSnapshot()
        local.mapBounded(SYNC_CONCURRENCY) { entry ->
            repository.pushServerFavorite(buildFavoriteBody(entry))
        }
        val remote = repository.listServerFavorites()
        if (remote is NetworkResult.Success) {
            favoritesStore.mergeWithServerFavorites(remote.data)
        }

        // ── 3. 阅读时长 pending 补传：逐书按 ≤3600 分片，规避服务端 clamp 截断 ──
        readingTimeStore.pendingPaths().mapBounded(SYNC_CONCURRENCY) { bookPath ->
            flushReadingTimeForBook(bookPath)
        }
    }

    /**
     * 同一本书的分片必须串行（[ReadingTimeStore.takePendingUpto] 是消费式的），
     * 不同书之间并行由调用方负责。
     */
    private suspend fun flushReadingTimeForBook(bookPath: String) {
        val remoteState = repository.getReadingState(bookPath)
        if (remoteState !is NetworkResult.Success) {
            return
        }
        val localProg = recentActivityStore.getBookProgress(bookPath)
        val flushState = resolveReadingTimeFlushState(
            serverState = remoteState.data.state,
            localProgress = localProg,
            nowMs = System.currentTimeMillis(),
        )
        while (true) {
            val chunk = readingTimeStore.takePendingUpto(bookPath, MAX_SYNC_DELTA_SECONDS)
            if (chunk <= 0) break
            val result = repository.reportReadingState(
                path = bookPath,
                chapterIndex = flushState.chapterIndex,
                paraIndex = flushState.paraIndex,
                percent = flushState.percent,
                finished = flushState.finished,
                lastReadAt = flushState.lastReadAt,
                readSecondsDelta = chunk,
            )
            if (result !is NetworkResult.Success) {
                readingTimeStore.addPending(bookPath, chunk)
                break // 该书剩余片下轮再试
            }
        }
    }
}

/**
 * 有界并发 map：最多 [limit] 个 [block] 同时在途，结果顺序与输入一致。
 * 任一 block 抛异常时沿结构化并发取消其余任务（与 [coroutineScope] 语义一致）。
 */
internal suspend fun <T, R> Iterable<T>.mapBounded(
    limit: Int,
    block: suspend (T) -> R,
): List<R> = coroutineScope {
    val semaphore = Semaphore(limit)
    map { item ->
        async { semaphore.withPermit { block(item) } }
    }.awaitAll()
}

internal data class ReadingTimeFlushState(
    val chapterIndex: Int,
    val paraIndex: Int,
    val percent: Double,
    val finished: Boolean,
    val lastReadAt: Long,
)

/**
 * 推导阅读时长补传时携带的进度参数：
 * - 优先沿用服务端已有进度，若服务端尚无记录则回退本地 [BookProgress]，避免把离线已读章节重置为 0；
 * - 若服务端已处于 `finished`（或手动标记 `finished`），保持 `finished = true`，防止补传阅读时长冲掉已读完状态；
 * - 保持真实的 `lastReadAt` 时间戳，避免伪造当前系统时间覆盖其他设备的更新进度。
 */
internal fun resolveReadingTimeFlushState(
    serverState: ReadingStateFull?,
    localProgress: BookProgress?,
    nowMs: Long,
): ReadingTimeFlushState {
    val ch = serverState?.chapterIndex ?: localProgress?.chapterIndex ?: 0
    val para = serverState?.paraIndex ?: localProgress?.blockIndex ?: 0
    val pct = serverState?.percent ?: 0.0
    val finished = serverState?.finished == true ||
        ReadingStatus.fromRaw(serverState?.manualStatus) == ReadingStatus.FINISHED
    val lastReadAt = maxOf(serverState?.lastReadAt ?: 0L, localProgress?.lastReadAt ?: 0L)
        .takeIf { it > 0L } ?: nowMs
    return ReadingTimeFlushState(
        chapterIndex = ch,
        paraIndex = para,
        percent = pct,
        finished = finished,
        lastReadAt = lastReadAt,
    )
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

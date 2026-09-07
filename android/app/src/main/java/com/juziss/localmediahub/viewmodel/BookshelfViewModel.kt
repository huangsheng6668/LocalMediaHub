package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.DecorationBadge
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

data class BookshelfItem(
    val path: String,
    val title: String,
    val chapterIndex: Int,
    val lastReadAt: Long,
    val format: String,
    val status: String?, // null = 服务端无状态行（视为在读）
    val readSeconds: Long,
    val isFavorite: Boolean,
)

enum class BookshelfFilter { ALL, READING, FINISHED, FAVORITES }

data class BookshelfUiState(
    val items: List<BookshelfItem> = emptyList(),
    val filter: BookshelfFilter = BookshelfFilter.ALL,
    val isLoading: Boolean = true,
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
)

private data class BookshelfMeta(
    val isLoading: Boolean = true,
    val todaySeconds: Long = 0,
    val weekSeconds: Long = 0,
)

fun applyBookshelfFilter(items: List<BookshelfItem>, filter: BookshelfFilter): List<BookshelfItem> =
    when (filter) {
        BookshelfFilter.ALL -> items
        BookshelfFilter.READING -> items.filter { it.status == null || it.status == "reading" || it.status == "unread" }
        BookshelfFilter.FINISHED -> items.filter { it.status == "finished" }
        BookshelfFilter.FAVORITES -> items.filter { it.isFavorite }
    }

fun formatReadDuration(sec: Long): String = when {
    sec < 60 -> ""
    sec < 3600 -> "${sec / 60} 分钟"
    else -> String.format(Locale.ROOT, "%.1f 小时", sec / 3600.0)
}

private fun isSupportedBookFormat(path: String): Boolean {
    val lower = path.lowercase()
    return lower.endsWith(".txt") || lower.endsWith(".epub")
}

/**
 * 独立书架页 ViewModel（spec 2026-09-08-reading-stats-and-android-bookshelf）：
 * 本地 RecentActivityStore 全量书籍 + 服务端 decorations（状态/时长/收藏）合并，
 * 离线降级为纯本地进度书架；今日/本周时长来自 stats summary。
 */
@HiltViewModel
class BookshelfViewModel @Inject constructor(
    recentActivityStore: RecentActivityStore,
    private val repository: MediaRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(BookshelfFilter.ALL)
    private val decorated = MutableStateFlow<Map<String, DecorationBadge>>(emptyMap())
    private val favPaths = MutableStateFlow<Set<String>>(emptySet())
    private val meta = MutableStateFlow(BookshelfMeta())

    init {
        viewModelScope.launch {
            val local = recentActivityStore.getAllBookProgressFlow().firstOrNull().orEmpty()
            val paths = local.filter { isSupportedBookFormat(it.path) }.map { it.path }
            if (paths.isNotEmpty()) {
                when (val res = repository.fetchDecorations(paths)) {
                    is NetworkResult.Success -> {
                        decorated.value = res.data.states
                        favPaths.value = res.data.favorites.toSet()
                    }
                    else -> Unit // 离线降级：仅本地进度书架
                }
            }
            val todayWeek = when (val s = repository.getStatsSummary()) {
                is NetworkResult.Success -> s.data.todaySeconds to s.data.weekSeconds
                else -> 0L to 0L
            }
            meta.value = BookshelfMeta(isLoading = false, todaySeconds = todayWeek.first, weekSeconds = todayWeek.second)
        }
    }

    fun setFilter(f: BookshelfFilter) { filter.value = f }

    val uiState: StateFlow<BookshelfUiState> = combine(
        recentActivityStore.getAllBookProgressFlow().map { list ->
            list.filter { isSupportedBookFormat(it.path) }
        },
        decorated,
        favPaths,
        filter,
        meta,
    ) { rows, dec, favs, fl, m ->
        val items = rows.map { p ->
            val badge = dec[p.path]
            BookshelfItem(
                path = p.path,
                title = p.path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.'),
                chapterIndex = p.chapterIndex,
                lastReadAt = p.lastReadAt,
                format = if (p.path.lowercase().endsWith(".epub")) "epub" else "txt",
                status = badge?.status,
                readSeconds = badge?.readSeconds ?: 0L,
                isFavorite = p.path in favs,
            )
        }
        BookshelfUiState(
            items = applyBookshelfFilter(items, fl),
            filter = fl,
            isLoading = m.isLoading,
            todaySeconds = m.todaySeconds,
            weekSeconds = m.weekSeconds,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BookshelfUiState())
}

package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.Block
import com.juziss.localmediahub.data.Book
import com.juziss.localmediahub.data.BookProgress
import com.juziss.localmediahub.data.Bookmark
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Hilt ViewModel backing [com.juziss.localmediahub.TextReaderActivity].
 *
 * Exposes the current [Book], chapter index, chapter text and loading/error
 * state as cold [StateFlow]s for Compose collection. On every successful
 * chapter load, persists progress via [RecentActivityStore.saveBookProgress]
 * so the next session can resume at the same chapter.
 *
 * The chapter content is fetched on demand from the server (Round 31 books
 * API, see `server/internal/server/handler/handler.go`); only metadata is
 * preloaded via [MediaRepository.getBookInfo].
 */
@HiltViewModel
class TextReaderViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val store: RecentActivityStore,
) : ViewModel() {

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _chapterBlocks = MutableStateFlow<List<Block>>(emptyList())
    val chapterBlocks: StateFlow<List<Block>> = _chapterBlocks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Task 5 (text-reader C-phase): reader settings, auto-scroll, bookmarks.

    private val _readerSettings = MutableStateFlow(ReaderSettings())
    val readerSettings: StateFlow<ReaderSettings> = _readerSettings.asStateFlow()

    // Phase 5: 沉浸模式 — TopAppBar/BottomAppBar 可见性。当用户启用 immersiveMode
    // 时，loadBook 成功后 1.5s 自动隐藏；中区域点击切换。immersiveMode 关闭时
    // toggleChrome/hideChrome 为 no-op（栏始终可见）。
    private val _chromeVisible = MutableStateFlow(true)
    val chromeVisible: StateFlow<Boolean> = _chromeVisible.asStateFlow()

    private val _isAutoScrolling = MutableStateFlow(false)
    val isAutoScrolling: StateFlow<Boolean> = _isAutoScrolling.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    // One-shot toast channel for bookmark add feedback. Non-null means a toast
    // should be shown; the UI calls [consumeBookmarkToast] after presenting it.
    // Success path stays silent (the bookmarks list simply refreshes).
    private val _bookmarkToast = MutableStateFlow<String?>(null)
    val bookmarkToast: StateFlow<String?> = _bookmarkToast.asStateFlow()

    init {
        // Bootstrap reader settings from DataStore so the UI starts with the
        // persisted font/line-height/theme instead of the default on every cold start.
        viewModelScope.launch {
            store.readerSettingsFlow.collect { _readerSettings.value = it }
        }
    }

    /**
     * Loads book metadata for [path] and resumes at the last saved chapter
     * (or chapter 0 if no progress record exists). Sets [error] when the
     * server returns an unsupported format.
     */
    fun loadBook(path: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val r = repo.getBookInfo(path)) {
                is NetworkResult.Success -> {
                    val b = r.data
                    _book.value = b
                    if (b.format == "unsupported") {
                        _error.value = "暂不支持该格式"
                        _isLoading.value = false
                        return@launch
                    }
                    val saved = store.getBookProgress(path)
                    val lastValid = b.chapters.lastIndex.coerceAtLeast(0)
                    val idx = saved?.chapterIndex?.coerceIn(0, lastValid) ?: 0
                    loadChapter(idx)
                    // Phase 5: 进入阅读器时栏先显示 1.5s 作为视觉锚点，再在用户
                    // 启用沉浸模式时隐藏。launch 一个独立协程等待 delay，避免
                    // 阻塞 loadBook 的其他分支；loadBook 重入由上层导航保证
                    // 单实例（Activity 一次只持有一个 ViewModel）。
                    _chromeVisible.value = true
                    launch {
                        delay(1500)
                        if (_readerSettings.value.immersiveMode) {
                            _chromeVisible.value = false
                        }
                    }
                }
                is NetworkResult.Error -> {
                    _error.value = r.message ?: "加载失败"
                    _isLoading.value = false
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    /**
     * Fetches the chapter at [index] and updates state. Persists progress on
     * success so the next session resumes here. No-op if the book is not
     * loaded or [index] is out of range.
     */
    fun loadChapter(index: Int) {
        val b = _book.value ?: return
        if (index !in b.chapters.indices) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val r = repo.getBookChapter(b.path, index)) {
                is NetworkResult.Success -> {
                    _currentIndex.value = index
                    _chapterBlocks.value = r.data.blocks
                    store.saveBookProgress(
                        BookProgress(
                            path = b.path,
                            chapterIndex = index,
                            scrollOffsetPx = 0,
                            lastReadAt = System.currentTimeMillis(),
                        )
                    )
                    // Chapter change halts auto-scroll — continuing across the
                    // boundary would jump-scroll the new chapter unexpectedly.
                    _isAutoScrolling.value = false
                }
                is NetworkResult.Error -> _error.value = r.message ?: "加载失败"
                NetworkResult.Loading -> Unit
            }
            _isLoading.value = false
        }
    }

    /** Advances to the next chapter when one exists. */
    fun nextChapter() {
        val b = _book.value ?: return
        if (_currentIndex.value < b.chapters.lastIndex) loadChapter(_currentIndex.value + 1)
    }

    /** Returns to the previous chapter when not already at the start. */
    fun prevChapter() {
        if (_currentIndex.value > 0) loadChapter(_currentIndex.value - 1)
    }

    /**
     * Called by the UI layer (throttled via snapshotFlow + debounce) to persist
     * the within-chapter scroll position. Unlike [loadChapter], this does NOT
     * re-fetch chapter content — it only writes the scroll offset so the next
     * session can resume mid-chapter.
     *
     * Task 6 (text-reader C-phase).
     */
    fun persistScrollProgress(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val b = _book.value ?: return
        viewModelScope.launch {
            store.saveBookProgress(
                BookProgress(
                    path = b.path,
                    chapterIndex = _currentIndex.value,
                    scrollOffsetPx = firstVisibleItemScrollOffset,
                    lastReadAt = System.currentTimeMillis(),
                )
            )
        }
    }

    // ---- Task 5: reader settings, auto-scroll, bookmarks ------------------

    /** Updates and persists reader settings. */
    fun updateSettings(settings: ReaderSettings) {
        _readerSettings.value = settings
        viewModelScope.launch { store.saveReaderSettings(settings) }
    }

    // ---- Phase 5: 沉浸模式 chrome 可见性 --------------------------------

    /**
     * Toggles TopAppBar/BottomAppBar visibility — but only when the user has
     * enabled immersiveMode. When immersiveMode is off, chrome is always
     * visible and this call is a no-op (avoids surprising the user with a
     * hidden bar they cannot reach).
     */
    fun toggleChrome() {
        if (_readerSettings.value.immersiveMode) {
            _chromeVisible.value = !_chromeVisible.value
        }
    }

    /** Force-shows chrome (e.g. when opening settings). */
    fun showChrome() {
        _chromeVisible.value = true
    }

    /** Hides chrome — only effective when immersiveMode is enabled. */
    fun hideChrome() {
        if (_readerSettings.value.immersiveMode) {
            _chromeVisible.value = false
        }
    }

    /** Toggles auto-scroll on/off. UI runs the scroll loop via LaunchedEffect. */
    fun toggleAutoScroll() {
        _isAutoScrolling.value = !_isAutoScrolling.value
    }

    /**
     * Stops auto-scroll. Called by UI on manual scroll, chapter change, or
     * any user-initiated navigation that would conflict with auto-scroll.
     */
    fun stopAutoScroll() {
        _isAutoScrolling.value = false
    }

    /** Reloads the bookmark list for [path] from DataStore into [_bookmarks]. */
    fun loadBookmarksFor(path: String) {
        viewModelScope.launch {
            store.getBookmarksFlow(path).collect { _bookmarks.value = it }
        }
    }

    /**
     * Adds a bookmark for the current chapter + given block. The preview is
     * extracted internally from the block's text value (first 30 chars). Image
     * blocks and out-of-range indices are rejected (returns false) — only text
     * blocks can carry a bookmark. On duplicate (same bookPath, chapterIndex,
     * paragraphIndex already exists) the store returns false and
     * [bookmarkToast] is populated for the UI to display "已存在书签".
     *
     * The store call runs in [viewModelScope] (non-blocking) — the dedup
     * outcome is surfaced via [bookmarkToast] for the UI to display ("已存在
     * 书签" on duplicate). The success path stays silent because the
     * [bookmarks] flow refreshes via [loadBookmarksFor]'s ongoing collection
     * without any extra refresh call here.
     */
    fun addBookmarkFromParagraph(blockIndex: Int): Boolean {
        val b = _book.value ?: return false
        val blocks = _chapterBlocks.value
        if (blockIndex !in blocks.indices) return false
        val block = blocks[blockIndex]
        if (block.type != "text") return false  // bookmarks only on text blocks
        val preview = block.value?.take(30) ?: ""
        val bm = Bookmark(
            bookPath = b.path,
            chapterIndex = _currentIndex.value,
            paragraphIndex = blockIndex,  // field name retained per spec
            preview = preview,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            val added = store.addBookmark(bm)
            if (!added) _bookmarkToast.value = "已存在书签"
        }
        return true
    }

    /**
     * Clears [bookmarkToast] once the UI has shown it. Idempotent — calling
     * when the value is already null is a no-op.
     */
    fun consumeBookmarkToast() {
        _bookmarkToast.value = null
    }

    /** Deletes a bookmark from DataStore. Flow collection refreshes [bookmarks]. */
    fun deleteBookmark(bm: Bookmark) {
        viewModelScope.launch {
            store.deleteBookmark(bm)
        }
    }
}

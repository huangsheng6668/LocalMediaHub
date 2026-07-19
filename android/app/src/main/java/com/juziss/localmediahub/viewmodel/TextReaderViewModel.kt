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

    private val _scrollChapters = MutableStateFlow<List<com.juziss.localmediahub.data.ScrollModeChapter>>(emptyList())
    val scrollChapters: StateFlow<List<com.juziss.localmediahub.data.ScrollModeChapter>> = _scrollChapters.asStateFlow()

    private val _isScrollLoadingMore = MutableStateFlow(false)
    val isScrollLoadingMore: StateFlow<Boolean> = _isScrollLoadingMore.asStateFlow()

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
                    if (_readerSettings.value.immersiveMode) {
                        viewModelScope.launch {
                            delay(1500)
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
    /**
     * Fetches the chapter at [index] and updates state. Persists progress on
     * success so the next session resumes here. No-op if the book is not
     * loaded or [index] is out of range.
     */
    fun loadChapter(index: Int, resetScroll: Boolean = false) {
        val b = _book.value ?: return
        if (index !in b.chapters.indices) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            when (val r = repo.getBookChapter(b.path, index)) {
                is NetworkResult.Success -> {
                    _currentIndex.value = index
                    _chapterBlocks.value = r.data.blocks
                    val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                        chapterIndex = index,
                        title = r.data.title,
                        blocks = r.data.blocks,
                    )
                    if (resetScroll || _scrollChapters.value.isEmpty()) {
                        _scrollChapters.value = listOf(newCh)
                    } else {
                        val existing = _scrollChapters.value.find { it.chapterIndex == index }
                        if (existing == null) {
                            _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                        }
                    }
                    store.saveBookProgress(
                        BookProgress(
                            path = b.path,
                            chapterIndex = index,
                            scrollOffsetPx = 0,
                            lastReadAt = System.currentTimeMillis(),
                        )
                    )
                    _isAutoScrolling.value = false
                }
                is NetworkResult.Error -> _error.value = r.message ?: "加载失败"
                NetworkResult.Loading -> Unit
            }
            _isLoading.value = false
        }
    }

    /**
     * 将 [_scrollChapters] 中尚未包含的下一章追加进来（滚动模式尾部选管用）。
     * 内部加锁防重入，已加载过的章节不重复请求。
     */
    fun loadNextChapterForScroll() {
        val b = _book.value ?: return
        val currentList = _scrollChapters.value
        val lastIdx = currentList.maxOfOrNull { it.chapterIndex } ?: _currentIndex.value
        val nextIdx = lastIdx + 1
        if (nextIdx !in b.chapters.indices || _isScrollLoadingMore.value) return

        viewModelScope.launch {
            _isScrollLoadingMore.value = true
            when (val r = repo.getBookChapter(b.path, nextIdx)) {
                is NetworkResult.Success -> {
                    val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                        chapterIndex = nextIdx,
                        title = r.data.title,
                        blocks = r.data.blocks,
                    )
                    // 取最新的列表，防止并发覆盖
                    _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                }
                is NetworkResult.Error -> Unit
                NetworkResult.Loading -> Unit
            }
            _isScrollLoadingMore.value = false
        }
    }

    /**
     * 滚动模式初始化：顶多预加载 [preloadCount] 章，按顺序串行请求避免并发竞争。
     * 与 [loadNextChapterForScroll] 的区别：这里不依赖 _isScrollLoadingMore 锁，
     * 而是直接将 [preloadCount] 章串行加载完成。
     */
    fun preloadScrollChapters(preloadCount: Int) {
        val b = _book.value ?: return
        viewModelScope.launch {
            repeat(preloadCount) {
                val currentList = _scrollChapters.value
                val lastIdx = currentList.maxOfOrNull { it.chapterIndex } ?: _currentIndex.value
                val nextIdx = lastIdx + 1
                if (nextIdx !in b.chapters.indices) return@launch
                when (val r = repo.getBookChapter(b.path, nextIdx)) {
                    is NetworkResult.Success -> {
                        val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                            chapterIndex = nextIdx,
                            title = r.data.title,
                            blocks = r.data.blocks,
                        )
                        _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                    }
                    is NetworkResult.Error -> return@launch
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    /**
     * 向前预加载 [preloadCount] 章（比当前列表最小章节索引更靠前），串行执行。
     * 返回本次实际新增的 item 总数，供 UI 层补偿滚动位置。
     */
    suspend fun preloadPreviousScrollChapters(preloadCount: Int): Int {
        val b = _book.value ?: return 0
        var totalNewItems = 0
        repeat(preloadCount) {
            val currentList = _scrollChapters.value
            val firstIdx = currentList.minOfOrNull { it.chapterIndex } ?: _currentIndex.value
            val prevIdx = firstIdx - 1
            if (prevIdx < 0) return@repeat
            when (val r = repo.getBookChapter(b.path, prevIdx)) {
                is NetworkResult.Success -> {
                    val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                        chapterIndex = prevIdx,
                        title = r.data.title,
                        blocks = r.data.blocks,
                    )
                    _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                    // 每章新增 item = 1标题 + blocks数量 + 1分隔符
                    totalNewItems += newCh.blocks.size + 2
                }
                is NetworkResult.Error -> return@repeat
                NetworkResult.Loading -> Unit
            }
        }
        return totalNewItems
    }

    /**
     * 向前加载单章（顶部触发用），返回新增的 item 数量供 UI 补偿滚动。
     */
    suspend fun loadPreviousChapterForScroll(): Int {
        val b = _book.value ?: return 0
        val currentList = _scrollChapters.value
        val firstIdx = currentList.minOfOrNull { it.chapterIndex } ?: _currentIndex.value
        val prevIdx = firstIdx - 1
        if (prevIdx < 0) return 0
        return when (val r = repo.getBookChapter(b.path, prevIdx)) {
            is NetworkResult.Success -> {
                val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                    chapterIndex = prevIdx,
                    title = r.data.title,
                    blocks = r.data.blocks,
                )
                _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                newCh.blocks.size + 2
            }
            else -> 0
        }
    }

    /** Updates the current active chapter index when scrolling in SCROLL mode. */
    fun updateCurrentIndex(index: Int) {
        val b = _book.value ?: return
        if (index in b.chapters.indices && _currentIndex.value != index) {
            _currentIndex.value = index
            viewModelScope.launch {
                store.saveBookProgress(
                    BookProgress(
                        path = b.path,
                        chapterIndex = index,
                        scrollOffsetPx = 0,
                        lastReadAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    /** Advances to the next chapter when one exists. */
    fun nextChapter() {
        val b = _book.value ?: return
        if (_currentIndex.value < b.chapters.lastIndex) loadChapter(_currentIndex.value + 1, resetScroll = true)
    }

    /** Returns to the previous chapter when not already at the start. */
    fun prevChapter() {
        if (_currentIndex.value > 0) loadChapter(_currentIndex.value - 1, resetScroll = true)
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
        val oldMode = _readerSettings.value.readingMode
        _readerSettings.value = settings
        viewModelScope.launch { store.saveReaderSettings(settings) }
        if (oldMode != settings.readingMode) {
            loadChapter(_currentIndex.value, resetScroll = true)
        }
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

    /** Exits immersive mode, shows chrome, and updates settings. */
    fun exitImmersiveMode() {
        _chromeVisible.value = true
        if (_readerSettings.value.immersiveMode) {
            updateSettings(_readerSettings.value.copy(immersiveMode = false))
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
     * Adds a bookmark for the specified chapter + given block.
     */
    fun addBookmarkFromParagraph(blockIndex: Int, chapterIndex: Int = _currentIndex.value): Boolean {
        val b = _book.value ?: return false
        val blocks = if (_readerSettings.value.readingMode == com.juziss.localmediahub.data.ReadingMode.SCROLL) {
            _scrollChapters.value.find { it.chapterIndex == chapterIndex }?.blocks ?: emptyList()
        } else {
            _chapterBlocks.value
        }
        if (blockIndex !in blocks.indices) return false
        val block = blocks[blockIndex]
        if (block.type != "text") return false  // bookmarks only on text blocks
        val preview = block.value?.take(30) ?: ""
        val bm = Bookmark(
            bookPath = b.path,
            chapterIndex = chapterIndex,
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

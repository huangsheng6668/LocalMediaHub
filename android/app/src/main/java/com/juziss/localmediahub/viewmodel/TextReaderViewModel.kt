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
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.LocalBookRepository
import com.juziss.localmediahub.data.BookChapterContent
import kotlinx.coroutines.flow.firstOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class TextReaderViewModel @Inject constructor(
    private val repo: MediaRepository,
    private val store: RecentActivityStore,
    private val localBookRepo: LocalBookRepository,
    private val downloadsStore: DownloadsStore,
) : ViewModel() {

    private var isLocalMode = false
    private var activeLocalPath: String? = null

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

    // Task 3: BLE degradation signal forwarded from MediaRepository. True when
    // the most recent chapter was served via the BLE fallback path. The UI
    // shows a 3-second auto-dismissing chip while this is true.
    val isBleDegraded: StateFlow<Boolean> = repo.isBleDegraded

    // I2: one-shot BLE-degradation event stream. Emits once PER BLE-served
    // chapter so the UI can re-show + re-arm the 3-second auto-dismiss timer
    // on every delivery, not just the first emission after the sticky boolean
    // flips true. The boolean above is kept for any consumer that wants the
    // current degradation state; the badge trigger comes from this flow.
    val bleDegradedEvents: kotlinx.coroutines.flow.SharedFlow<Unit> = repo.bleDegradedEvents

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
            store.readerSettingsFlow.collect { incoming ->
                val previous = _readerSettings.value
                _readerSettings.value = incoming
                // 设置首次从 DataStore 加载完成（从默认 false 变为持久化的 true）时，
                // 若当前书已加载但 chrome 仍为可见，立即同步隐藏，避免用户需要再操作开关。
                if (!previous.immersiveMode && incoming.immersiveMode && _book.value != null) {
                    _chromeVisible.value = false
                }
            }
        }
    }

    /**
     * Loads book metadata for [path] and resumes at the last saved chapter
     * (or chapter 0 if no progress record exists).
     */
    fun loadBook(path: String, isLocal: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val downloads = downloadsStore.downloadedFiles.firstOrNull() ?: emptyList()
            val entry = downloads.find { it.file.relativePath == path || it.file.path == path || it.localPath == path }
            val resolvedLocalPath = entry?.localPath ?: if (java.io.File(path).exists()) path else null

            activeLocalPath = resolvedLocalPath
            isLocalMode = isLocal || (resolvedLocalPath != null && java.io.File(resolvedLocalPath).exists())

            if (isLocalMode && resolvedLocalPath != null) {
                val localResult = localBookRepo.getLocalBookInfo(resolvedLocalPath, path)
                if (localResult is NetworkResult.Success<Book>) {
                    processBookLoaded(localResult.data, path)
                    return@launch
                }
            }

            when (val r = repo.getBookInfo(path)) {
                is NetworkResult.Success<Book> -> {
                    processBookLoaded(r.data, path)
                }
                is NetworkResult.Error -> {
                    if (resolvedLocalPath != null) {
                        val localResult = localBookRepo.getLocalBookInfo(resolvedLocalPath, path)
                        if (localResult is NetworkResult.Success<Book>) {
                            isLocalMode = true
                            processBookLoaded(localResult.data, path)
                            return@launch
                        }
                    }
                    _error.value = r.message
                    _isLoading.value = false
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private suspend fun processBookLoaded(b: Book, path: String) {
        _book.value = b
        if (b.format == "unsupported") {
            _error.value = "暂不支持该格式"
            _isLoading.value = false
            return
        }
        val saved = store.getBookProgress(path)
        val lastValid = b.chapters.lastIndex.coerceAtLeast(0)
        val idx = saved?.chapterIndex?.coerceIn(0, lastValid) ?: 0
        loadChapter(idx)
        // 若全局已启用沉浸模式，打开书即立即隐藏 chrome（Activity 据此同步隐藏系统栏）；
        // 否则保持 chrome 可见。去掉旧的 1.5s 延迟，避免打开时短暂闪现顶/底栏。
        _chromeVisible.value = !_readerSettings.value.immersiveMode
    }

    private suspend fun fetchBookChapter(path: String, index: Int): NetworkResult<BookChapterContent> {
        val b = _book.value
        val localPath = activeLocalPath
        if (isLocalMode || localPath != null) {
            val targetPath = localPath ?: path
            if (b != null) {
                val localRes = localBookRepo.getLocalBookChapter(targetPath, b, index)
                if (localRes is NetworkResult.Success<BookChapterContent> || isLocalMode) {
                    return localRes
                }
            }
        }
        val r = repo.getBookChapter(path, index)
        if (r is NetworkResult.Error && localPath != null && b != null) {
            val localRes = localBookRepo.getLocalBookChapter(localPath, b, index)
            if (localRes is NetworkResult.Success<BookChapterContent>) {
                return localRes
            }
        }
        return r
    }

    /**
     * Fetches the chapter at [index] and updates state. Persists progress on
     * success so the next session resumes here. Returns true on success.
     */
    suspend fun loadChapter(index: Int, resetScroll: Boolean = false): Boolean {
        val b = _book.value ?: return false
        if (index !in b.chapters.indices) return false
        _isLoading.value = true
        _error.value = null
        var success = false
        when (val r = fetchBookChapter(b.path, index)) {
            is NetworkResult.Success<BookChapterContent> -> {
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
                success = true
            }
            is NetworkResult.Error -> _error.value = r.message
            NetworkResult.Loading -> Unit
        }
        _isLoading.value = false
        return success
    }

    /**
     * 将 [_scrollChapters] 中尚未包含的下一章追加进来（滚动模式尾部选管用）。
     */
    fun loadNextChapterForScroll() {
        val b = _book.value ?: return
        val currentList = _scrollChapters.value
        val lastIdx = currentList.maxOfOrNull { it.chapterIndex } ?: _currentIndex.value
        val nextIdx = lastIdx + 1
        if (nextIdx !in b.chapters.indices || _isScrollLoadingMore.value) return

        viewModelScope.launch {
            _isScrollLoadingMore.value = true
            when (val r = fetchBookChapter(b.path, nextIdx)) {
                is NetworkResult.Success<BookChapterContent> -> {
                    val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                        chapterIndex = nextIdx,
                        title = r.data.title,
                        blocks = r.data.blocks,
                    )
                    _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                }
                is NetworkResult.Error -> Unit
                NetworkResult.Loading -> Unit
            }
            _isScrollLoadingMore.value = false
        }
    }

    /**
     * 滚动模式初始化：顶多预加载 [preloadCount] 章。
     */
    fun preloadScrollChapters(preloadCount: Int) {
        val b = _book.value ?: return
        viewModelScope.launch {
            repeat(preloadCount) {
                val currentList = _scrollChapters.value
                val lastIdx = currentList.maxOfOrNull { it.chapterIndex } ?: _currentIndex.value
                val nextIdx = lastIdx + 1
                if (nextIdx !in b.chapters.indices) return@launch
                when (val r = fetchBookChapter(b.path, nextIdx)) {
                    is NetworkResult.Success<BookChapterContent> -> {
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
     * 向前预加载 [preloadCount] 章。
     */
    suspend fun preloadPreviousScrollChapters(preloadCount: Int): Int {
        val b = _book.value ?: return 0
        var totalNewItems = 0
        repeat(preloadCount) {
            val currentList = _scrollChapters.value
            val firstIdx = currentList.minOfOrNull { it.chapterIndex } ?: _currentIndex.value
            val prevIdx = firstIdx - 1
            if (prevIdx < 0) return@repeat
            when (val r = fetchBookChapter(b.path, prevIdx)) {
                is NetworkResult.Success<BookChapterContent> -> {
                    val newCh = com.juziss.localmediahub.data.ScrollModeChapter(
                        chapterIndex = prevIdx,
                        title = r.data.title,
                        blocks = r.data.blocks,
                    )
                    _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                    totalNewItems += newCh.blocks.size + 2
                }
                is NetworkResult.Error -> return@repeat
                NetworkResult.Loading -> Unit
            }
        }
        return totalNewItems
    }

    /**
     * 向前加载单章（顶部触发用）。
     */
    suspend fun loadPreviousChapterForScroll(): Int {
        val b = _book.value ?: return 0
        val currentList = _scrollChapters.value
        val firstIdx = currentList.minOfOrNull { it.chapterIndex } ?: _currentIndex.value
        val prevIdx = firstIdx - 1
        if (prevIdx < 0) return 0
        return when (val r = fetchBookChapter(b.path, prevIdx)) {
            is NetworkResult.Success<BookChapterContent> -> {
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
        if (_currentIndex.value < b.chapters.lastIndex) {
            viewModelScope.launch {
                loadChapter(_currentIndex.value + 1, resetScroll = true)
            }
        }
    }

    /** Returns to the previous chapter when not already at the start. */
    fun prevChapter() {
        if (_currentIndex.value > 0) {
            viewModelScope.launch {
                loadChapter(_currentIndex.value - 1, resetScroll = true)
            }
        }
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
        val oldImmersive = _readerSettings.value.immersiveMode
        _readerSettings.value = settings
        viewModelScope.launch { store.saveReaderSettings(settings) }
        if (oldMode != settings.readingMode) {
            viewModelScope.launch {
                loadChapter(_currentIndex.value, resetScroll = true)
            }
        }
        // 沉浸开关切换时立即同步 chrome 可见性：
        // - 开启：立即隐藏 chrome（Activity 据此隐藏 systemBars），用户进入沉浸
        // - 关闭：立即还原 chrome，让顶/底栏重新可用
        if (oldImmersive != settings.immersiveMode) {
            _chromeVisible.value = !settings.immersiveMode
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

package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Block
import com.juziss.localmediahub.data.Book
import com.juziss.localmediahub.data.BookProgress
import com.juziss.localmediahub.data.Bookmark
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReadingSessionTimer
import com.juziss.localmediahub.data.ReadingTimeStore
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.network.NetworkResult
import com.juziss.localmediahub.network.userText
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.LocalBookRepository
import com.juziss.localmediahub.data.ReadingMath
import com.juziss.localmediahub.data.BookChapterContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

@HiltViewModel
class TextReaderViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repo: MediaRepository,
    private val store: RecentActivityStore,
    private val localBookRepo: LocalBookRepository,
    private val downloadsStore: DownloadsStore,
    private val readingTimeStore: ReadingTimeStore,
) : ViewModel() {

    private var isLocalMode = false
    private var activeLocalPath: String? = null
    private var bookmarksJob: Job? = null

    private val chapterCache = object : LinkedHashMap<String, BookChapterContent>(50, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookChapterContent>?): Boolean {
            return size > 100
        }
    }

    fun clearChapterCache() {
        synchronized(chapterCache) {
            chapterCache.clear()
        }
    }

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _currentIndex = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _chapterBlocks = MutableStateFlow<List<Block>>(emptyList())

    /**
     * Wall time of the last scroll-mode list RESET caused by explicit chapter
     * navigation (resetScroll). Real-device finding: the collapse drops
     * firstVisibleItemIndex to 0, which the "prepend previous chapter near
     * list top" effect immediately treats as backward reading — re-loading
     * the chapter the user just LEFT and snapping the view back to its old
     * position. The prepend path is suppressed for a short window after
     * every navigation reset.
     */
    private var lastScrollChaptersResetAtMs = 0L

    companion object {
        /** How long backward-prepending stays suppressed after navigation. */
        private const val SCROLL_PRELOAD_SUPPRESS_MS = 1_500L

        fun pickEffectiveProgress(
            local: BookProgress?,
            serverLastReadAt: Long?,
            serverChapter: Int,
            serverPara: Int,
            path: String,
        ): BookProgress? {
            if (serverLastReadAt == null || serverLastReadAt <= 0L) return local
            val server = BookProgress(path, serverChapter, serverPara, 0, serverLastReadAt)
            if (local == null || serverLastReadAt > local.lastReadAt) return server
            return local
        }

        /**
         * Preserves the pending mid-chapter `blockIndex` and `scrollOffsetPx`
         * when `loadChapter` loads the exact chapter scheduled for resume,
         * preventing `loadChapter` from clobbering saved mid-chapter progress
         * with `(0, 0)` before the UI scroll restoration effect runs.
         */
        internal fun resolveInitialChapterProgress(
            bookPath: String,
            chapterIndex: Int,
            pendingResume: BookProgress?,
            nowMs: Long,
        ): BookProgress {
            val resumeMatch = pendingResume?.takeIf { it.chapterIndex == chapterIndex }
            return BookProgress(
                path = bookPath,
                chapterIndex = chapterIndex,
                blockIndex = resumeMatch?.blockIndex ?: 0,
                scrollOffsetPx = resumeMatch?.scrollOffsetPx ?: 0,
                lastReadAt = nowMs,
            )
        }
    }
    val chapterBlocks: StateFlow<List<Block>> = _chapterBlocks.asStateFlow()

    private val _scrollChapters = MutableStateFlow<List<com.juziss.localmediahub.data.ScrollModeChapter>>(emptyList())
    val scrollChapters: StateFlow<List<com.juziss.localmediahub.data.ScrollModeChapter>> = _scrollChapters.asStateFlow()

    private val _isScrollLoadingMore = MutableStateFlow(false)
    val isScrollLoadingMore: StateFlow<Boolean> = _isScrollLoadingMore.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val isBleDegraded: StateFlow<Boolean> = repo.isBleDegraded

    val bleDegradedEvents: kotlinx.coroutines.flow.SharedFlow<Unit> = repo.bleDegradedEvents

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _readerSettings = MutableStateFlow(ReaderSettings())
    val readerSettings: StateFlow<ReaderSettings> = _readerSettings.asStateFlow()

    private val _chromeVisible = MutableStateFlow(true)
    val chromeVisible: StateFlow<Boolean> = _chromeVisible.asStateFlow()

    private val _isAutoScrolling = MutableStateFlow(false)
    val isAutoScrolling: StateFlow<Boolean> = _isAutoScrolling.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks.asStateFlow()

    private val _bookmarkToast = MutableStateFlow<String?>(null)
    val bookmarkToast: StateFlow<String?> = _bookmarkToast.asStateFlow()

    private val _pendingResume = MutableStateFlow<BookProgress?>(null)
    val pendingResume: StateFlow<BookProgress?> = _pendingResume.asStateFlow()

    fun consumePendingResume() {
        _pendingResume.value = null
    }

    init {
        viewModelScope.launch {
            store.readerSettingsFlow.collect { incoming ->
                val previous = _readerSettings.value
                _readerSettings.value = incoming
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
                    _error.value = r.userText(appContext)
                    _isLoading.value = false
                }
                NetworkResult.Loading -> Unit
            }
        }
    }

    private suspend fun processBookLoaded(b: Book, path: String) {
        lastPushKey = ""
        _book.value = b
        if (b.format == "unsupported") {
            _error.value = appContext.getString(R.string.reader_unsupported_format)
            _isLoading.value = false
            return
        }
        store.readerSettingsFlow.first()
        val saved = store.getBookProgress(b.path)
        val lastValid = b.chapters.lastIndex.coerceAtLeast(0)
        val idx = saved?.chapterIndex?.coerceIn(0, lastValid) ?: 0
        if (saved != null && (idx > 0 || saved.blockIndex > 0 || saved.scrollOffsetPx > 0)) {
            _pendingResume.value = saved.copy(chapterIndex = idx)
        }
        loadChapter(idx)
        _chromeVisible.value = !_readerSettings.value.immersiveMode
        viewModelScope.launch {
            val srvState = withTimeoutOrNull(2_000) {
                (repo.getReadingState(b.path) as? NetworkResult.Success)?.data?.state
            } ?: return@launch
            val effective = pickEffectiveProgress(
                saved, srvState.lastReadAt,
                srvState.chapterIndex, srvState.paraIndex, b.path,
            ) ?: return@launch
            if (effective !== saved) {
                store.saveBookProgress(effective)
                val correctedIdx = effective.chapterIndex.coerceIn(0, lastValid)
                _pendingResume.value = effective.copy(chapterIndex = correctedIdx)
                if (correctedIdx != idx) loadChapter(correctedIdx)
            }
        }
    }

    private suspend fun fetchBookChapter(
        path: String,
        index: Int,
        onAppend: ((List<com.juziss.localmediahub.data.Block>) -> Unit)? = null,
    ): NetworkResult<BookChapterContent> {
        val cacheKey = "$path:$index"
        synchronized(chapterCache) {
            chapterCache[cacheKey]?.let { return NetworkResult.Success(it) }
        }
        val b = _book.value
        val localPath = activeLocalPath
        if (isLocalMode || localPath != null) {
            val targetPath = localPath ?: path
            if (b != null) {
                val localRes = localBookRepo.getLocalBookChapter(targetPath, b, index)
                if (localRes is NetworkResult.Success<BookChapterContent>) {
                    synchronized(chapterCache) { chapterCache[cacheKey] = localRes.data }
                    return localRes
                } else if (isLocalMode) {
                    return localRes
                }
            }
        }
        val r = repo.getBookChapter(path, index, onAppend = onAppend, appendScope = viewModelScope)
        if (r is NetworkResult.Success<BookChapterContent>) {
            synchronized(chapterCache) { chapterCache[cacheKey] = r.data }
            return r
        }
        if (r is NetworkResult.Error && localPath != null && b != null) {
            val localRes = localBookRepo.getLocalBookChapter(localPath, b, index)
            if (localRes is NetworkResult.Success<BookChapterContent>) {
                synchronized(chapterCache) { chapterCache[cacheKey] = localRes.data }
                return localRes
            }
        }
        return r
    }

    private fun appendChapterBlocks(index: Int, more: List<com.juziss.localmediahub.data.Block>) {
        if (_currentIndex.value != index || more.isEmpty()) return
        _chapterBlocks.value = _chapterBlocks.value + more
        _scrollChapters.value = _scrollChapters.value.map { ch ->
            if (ch.chapterIndex == index) {
                ch.copy(blocks = ch.blocks + more)
            } else {
                ch
            }
        }
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
        when (val r = fetchBookChapter(b.path, index) { more ->
            appendChapterBlocks(index, more)
        }) {
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
                    if (resetScroll) {
                        lastScrollChaptersResetAtMs = System.currentTimeMillis()
                    }
                } else {
                    val existing = _scrollChapters.value.find { it.chapterIndex == index }
                    if (existing == null) {
                        _scrollChapters.value = (_scrollChapters.value + newCh).sortedBy { it.chapterIndex }
                    }
                }
                store.saveBookProgress(
                    resolveInitialChapterProgress(
                        bookPath = b.path,
                        chapterIndex = index,
                        pendingResume = _pendingResume.value,
                        nowMs = System.currentTimeMillis(),
                    )
                )
                _isAutoScrolling.value = false
                success = true
            }
            is NetworkResult.Error -> _error.value = r.userText(appContext)
            NetworkResult.Loading -> Unit
        }
        _isLoading.value = false
        return success
    }

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

    suspend fun loadPreviousChapterForScroll(): Int {
        if (System.currentTimeMillis() - lastScrollChaptersResetAtMs <
            SCROLL_PRELOAD_SUPPRESS_MS
        ) {
            return 0
        }
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

    fun updateCurrentIndex(index: Int) {
        val b = _book.value ?: return
        if (index in b.chapters.indices && _currentIndex.value != index) {
            _currentIndex.value = index
            viewModelScope.launch {
                store.saveBookProgress(
                    BookProgress(
                        path = b.path,
                        chapterIndex = index,
                        blockIndex = 0,
                        scrollOffsetPx = 0,
                        lastReadAt = System.currentTimeMillis(),
                    )
                )
            }
        }
    }

    fun nextChapter() {
        val b = _book.value ?: return
        if (_currentIndex.value < b.chapters.lastIndex) {
            viewModelScope.launch {
                loadChapter(_currentIndex.value + 1, resetScroll = true)
            }
        }
    }

    fun prevChapter() {
        if (_currentIndex.value > 0) {
            viewModelScope.launch {
                loadChapter(_currentIndex.value - 1, resetScroll = true)
            }
        }
    }

    private var lastPushKey = ""
    private val MAX_REPORT_DELTA_SECONDS = 3600L
    private val readingTimer = ReadingSessionTimer()

    fun startReadingSession() { readingTimer.start() }

    fun stopReadingSession() {
        readingTimer.stop()
        val b = _book.value ?: return
        val d = readingTimer.takeDelta()
        if (d > 0) viewModelScope.launch { readingTimeStore.addPending(b.path, d) }
    }

    fun persistScrollProgress(
        chapterIndex: Int,
        blockIndex: Int,
        scrollOffsetPx: Int,
        chapterBlockCount: Int = 0,
        atChapterEnd: Boolean = false,
    ) {
        val b = _book.value ?: return
        viewModelScope.launch {
            val total = b.chapters.size.coerceAtLeast(1)
            val percent = ReadingMath.percent(chapterIndex, blockIndex, chapterBlockCount, total)
            val finished = ReadingMath.isFinished(chapterIndex, total, atChapterEnd)
            val now = System.currentTimeMillis()
            store.saveBookProgress(
                BookProgress(
                    path = b.path,
                    chapterIndex = chapterIndex,
                    blockIndex = blockIndex.coerceAtLeast(0),
                    scrollOffsetPx = scrollOffsetPx.coerceAtLeast(0),
                    lastReadAt = now,
                )
            )
            val key = "$chapterIndex:$blockIndex:$finished"
            val liveDelta = readingTimer.takeDelta()
            if (liveDelta > 0) readingTimeStore.addPending(b.path, liveDelta)
            val delta = readingTimeStore.takePendingUpto(b.path, MAX_REPORT_DELTA_SECONDS)
            if (key != lastPushKey || delta > 0) {
                lastPushKey = key
                val result = repo.reportReadingState(b.path, chapterIndex, blockIndex, percent, finished, now, delta)
                if (result !is NetworkResult.Success && delta > 0) {
                    readingTimeStore.addPending(b.path, delta)
                }
            }
        }
    }

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
        if (oldImmersive != settings.immersiveMode) {
            _chromeVisible.value = !settings.immersiveMode
        }
    }

    fun toggleChrome() {
        if (_readerSettings.value.immersiveMode) {
            _chromeVisible.value = !_chromeVisible.value
        }
    }

    fun showChrome() {
        _chromeVisible.value = true
    }

    fun hideChrome() {
        if (_readerSettings.value.immersiveMode) {
            _chromeVisible.value = false
        }
    }

    fun exitImmersiveMode() {
        _chromeVisible.value = true
        if (_readerSettings.value.immersiveMode) {
            updateSettings(_readerSettings.value.copy(immersiveMode = false))
        }
    }

    fun toggleAutoScroll() {
        _isAutoScrolling.value = !_isAutoScrolling.value
    }

    fun stopAutoScroll() {
        _isAutoScrolling.value = false
    }

    /** Reloads the bookmark list for [path] from DataStore into [_bookmarks], cancelling any prior collector. */
    fun loadBookmarksFor(path: String) {
        bookmarksJob?.cancel()
        bookmarksJob = viewModelScope.launch {
            store.getBookmarksFlow(path).collect { _bookmarks.value = it }
        }
    }

    fun addBookmarkFromParagraph(blockIndex: Int, chapterIndex: Int = _currentIndex.value): Boolean {
        val b = _book.value ?: return false
        val blocks = if (_readerSettings.value.readingMode == com.juziss.localmediahub.data.ReadingMode.SCROLL) {
            _scrollChapters.value.find { it.chapterIndex == chapterIndex }?.blocks ?: emptyList()
        } else {
            _chapterBlocks.value
        }
        if (blockIndex !in blocks.indices) return false
        val block = blocks[blockIndex]
        if (block.type != "text") return false
        val preview = block.value?.take(30) ?: ""
        val bm = Bookmark(
            bookPath = b.path,
            chapterIndex = chapterIndex,
            paragraphIndex = blockIndex,
            preview = preview,
            createdAt = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            val added = store.addBookmark(bm)
            if (!added) _bookmarkToast.value = appContext.getString(R.string.reader_bookmark_exists)
        }
        return true
    }

    fun consumeBookmarkToast() {
        _bookmarkToast.value = null
    }

    fun deleteBookmark(bm: Bookmark) {
        viewModelScope.launch {
            store.deleteBookmark(bm)
        }
    }
}

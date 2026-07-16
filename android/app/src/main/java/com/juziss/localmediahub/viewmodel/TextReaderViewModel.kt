package com.juziss.localmediahub.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.juziss.localmediahub.data.Book
import com.juziss.localmediahub.data.BookProgress
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.network.NetworkResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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

    private val _chapterText = MutableStateFlow("")
    val chapterText: StateFlow<String> = _chapterText.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

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
                    _chapterText.value = r.data.content
                    store.saveBookProgress(
                        BookProgress(
                            path = b.path,
                            chapterIndex = index,
                            scrollOffsetPx = 0,
                            lastReadAt = System.currentTimeMillis(),
                        )
                    )
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
}

package com.juziss.localmediahub.viewmodel

import android.content.Context
import com.juziss.localmediahub.R
import com.juziss.localmediahub.data.Block
import com.juziss.localmediahub.data.Book
import com.juziss.localmediahub.data.BookChapter
import com.juziss.localmediahub.data.BookProgress
import com.juziss.localmediahub.data.BookChapterContent
import com.juziss.localmediahub.data.MediaRepository
import com.juziss.localmediahub.data.RecentActivityStore
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import com.juziss.localmediahub.data.DownloadsStore
import com.juziss.localmediahub.data.LocalBookRepository
import com.juziss.localmediahub.network.NetworkResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextReaderViewModelReaderTest {

    private val dispatcher = StandardTestDispatcher()
    private val localBookRepo = LocalBookRepository()
    private val downloadsStore = mockk<DownloadsStore>(relaxed = true)
    // The ViewModel resolves localized error/bookmark strings through a
    // Context; a mock returns the same literals the assertions expect.
    private val appContext: Context = mockk(relaxed = true) {
        every { getString(R.string.reader_bookmark_exists) } returns "已存在书签"
        every { getString(R.string.reader_unsupported_format) } returns "暂不支持该格式"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { downloadsStore.downloadedFiles } returns flowOf(emptyList())
    }

    private fun createVm(repo: MediaRepository, store: RecentActivityStore) =
        TextReaderViewModel(appContext, repo, store, localBookRepo, downloadsStore)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun fakeBook(path: String = "/b.txt", format: String = "txt") = Book(
        path = path,
        format = format,
        title = "Test",
        charset = null,
        chapters = listOf(BookChapter(0, "C0"), BookChapter(1, "C1")),
        modTime = "2026-01-01T00:00:00Z",
    )

    @Test
    fun updateSettings_updates_state_and_persists() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.getBookProgress(any()) } returns null
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C0", listOf(Block(type = "text", value = "body"))))

        val vm = createVm(repo, store)
        // Drain the init { store.readerSettingsFlow.collect { ... } } so its
        // single default-ReaderSettings emission lands before updateSettings
        // sets the new value (otherwise the collector overwrites the update).
        dispatcher.scheduler.advanceUntilIdle()
        val updated = ReaderSettings(fontSizeSp = 20, theme = ReaderTheme.NIGHT)
        vm.updateSettings(updated)
        // updateSettings persists via viewModelScope.launch; run the scheduler
        // so saveReaderSettings actually executes before coVerify checks it.
        dispatcher.scheduler.advanceUntilIdle()

        coVerify { store.saveReaderSettings(updated) }
        assertEquals(updated, vm.readerSettings.value)
    }

    @Test
    fun toggleAutoScroll_flips_state() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        val vm = createVm(repo, store)
        assertFalse(vm.isAutoScrolling.value)
        vm.toggleAutoScroll()
        assertTrue(vm.isAutoScrolling.value)
        vm.toggleAutoScroll()
        assertFalse(vm.isAutoScrolling.value)
    }

    @Test
    fun stopAutoScroll_sets_state_false() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        val vm = createVm(repo, store)
        vm.toggleAutoScroll()
        vm.stopAutoScroll()
        assertFalse(vm.isAutoScrolling.value)
    }

    @Test
    fun addBookmarkFromParagraph_delegates_to_store_and_returns_true() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.addBookmark(any()) } returns true
        coEvery { store.getBookmarksFlow(any()) } returns flowOf(emptyList())
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(
                BookChapterContent("C0", listOf(Block(type = "text", value = "preview")))
            )
        val vm = createVm(repo, store)
        vm.loadBook("/b.txt") // sets _book
        dispatcher.scheduler.advanceUntilIdle()
        // _book now populated; bookmarks flow is per-path, must be reloaded
        vm.loadBookmarksFor("/b.txt")
        val ok = vm.addBookmarkFromParagraph(0) // preview extracted from block.value
        assertTrue(ok)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify {
            store.addBookmark(match {
                it.bookPath == "/b.txt" && it.paragraphIndex == 0 && it.preview == "preview"
            })
        }
        // Success path stays silent — no toast emitted.
        assertEquals(null, vm.bookmarkToast.value)
    }

    @Test
    fun addBookmarkFromParagraph_returns_false_on_duplicate() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.addBookmark(any()) } returns false
        coEvery { store.getBookmarksFlow(any()) } returns flowOf(emptyList())
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C0", listOf(Block(type = "text", value = "p"))))
        val vm = createVm(repo, store)
        vm.loadBook("/b.txt")
        dispatcher.scheduler.advanceUntilIdle()
        vm.loadBookmarksFor("/b.txt")
        vm.addBookmarkFromParagraph(0)
        // Duplicate detection now runs in viewModelScope; advance the
        // scheduler so the launched coroutine has a chance to populate
        // bookmarkToast before the assertion.
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("已存在书签", vm.bookmarkToast.value)
        // Consume clears the one-shot toast.
        vm.consumeBookmarkToast()
        assertEquals(null, vm.bookmarkToast.value)
    }

    @Test
    fun addBookmarkFromParagraph_returns_false_for_image_block() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.addBookmark(any()) } returns true
        coEvery { store.getBookmarksFlow(any()) } returns flowOf(emptyList())
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(
                BookChapterContent(
                    "C0",
                    listOf(Block(type = "image", src = "http://example.com/x.png")),
                )
            )
        val vm = createVm(repo, store)
        vm.loadBook("/b.txt")
        dispatcher.scheduler.advanceUntilIdle()
        vm.loadBookmarksFor("/b.txt")
        // Block 0 is an image — bookmarks are only allowed on text blocks.
        val ok = vm.addBookmarkFromParagraph(0)
        assertFalse(ok)
        dispatcher.scheduler.advanceUntilIdle()
        // No store call should have been attempted for an image block.
        coVerify(exactly = 0) { store.addBookmark(any()) }
    }

    // ---- Phase 5: 沉浸模式 chrome 可见性 --------------------------------

    @Test
    fun immersive_mode_hides_chrome_on_load() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.getBookProgress(any()) } returns null
        coEvery { store.readerSettingsFlow } returns
            flowOf(ReaderSettings(immersiveMode = true))
        val repo = mockk<MediaRepository>(relaxed = true)
        coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C0", listOf(Block(type = "text", value = "body"))))

        val vm = createVm(repo, store)
        dispatcher.scheduler.advanceUntilIdle()
        vm.loadBook("/b.txt")
        dispatcher.scheduler.advanceUntilIdle()
        // When immersiveMode is enabled, chrome is hidden upon loading the book.
        assertFalse(vm.chromeVisible.value)
    }

    @Test
    fun toggle_chrome_inverts_visibility_only_when_immersive_enabled() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings(immersiveMode = false))
        val repo = mockk<MediaRepository>(relaxed = true)
        val vm = createVm(repo, store)
        dispatcher.scheduler.advanceUntilIdle()
        // immersiveMode off → toggleChrome is a no-op.
        assertTrue(vm.chromeVisible.value)
        vm.toggleChrome()
        assertTrue(vm.chromeVisible.value)

        // immersiveMode on → updateSettings hides chrome, toggleChrome flips state.
        vm.updateSettings(ReaderSettings(immersiveMode = true))
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse(vm.chromeVisible.value)
        vm.toggleChrome()
        assertTrue(vm.chromeVisible.value)
        vm.toggleChrome()
        assertFalse(vm.chromeVisible.value)
    }

    @Test
    fun chapter_caching_avoids_duplicate_repo_calls() = runTest(dispatcher) {
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        val repo = mockk<MediaRepository>(relaxed = true)
        coEvery { repo.getBookInfo(any()) } returns NetworkResult.Success(fakeBook())
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C0", listOf(Block(type = "text", value = "body0")))) andThen
            NetworkResult.Success(BookChapterContent("C1", listOf(Block(type = "text", value = "body1"))))

        val vm = createVm(repo, store)
        vm.clearChapterCache()
        dispatcher.scheduler.advanceUntilIdle()

        // First load of chapter 0 calls repo
        vm.loadBook("/b.txt")
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repo.getBookChapter("/b.txt", 0, any(), any()) }

        // Switch to chapter 1
        vm.loadChapter(1)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repo.getBookChapter("/b.txt", 1, any(), any()) }

        // Switch back to chapter 0 -> served from chapterCache, repo NOT called again
        vm.loadChapter(0)
        dispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { repo.getBookChapter("/b.txt", 0, any(), any()) }
        assertEquals("body0", vm.chapterBlocks.value.firstOrNull()?.value)
    }

    // ---- Task 3: 进度 key 统一 + pendingResume --------------------------

    @Test
    fun `loadBook restores progress keyed by book path not request path`() = runTest(dispatcher) {
        // 服务端规范化后 Book.path 与请求 path 不同（如盘符大写）；恢复必须用 b.path 查询。
        // repo 取 relaxed：VM 构造时即读取 repo.isBleDegraded / bleDegradedEvents
        // 属性初始化器，strict mock 会在 createVm 阶段抛 no answer found。
        val repo = mockk<MediaRepository>(relaxed = true)
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { repo.getBookInfo("/raw/path/book.txt") } returns
            NetworkResult.Success(fakeBook(path = "/E:/Canonical/book.txt"))
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C1", listOf(Block(type = "text", value = "body"))))
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        coEvery { store.getBookProgress("/E:/Canonical/book.txt") } returns BookProgress(
            path = "/E:/Canonical/book.txt", chapterIndex = 1, blockIndex = 2,
            scrollOffsetPx = 30, lastReadAt = 1L,
        )
        val vm = createVm(repo, store)

        vm.loadBook("/raw/path/book.txt")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, vm.currentIndex.value)
        assertEquals(2, vm.pendingResume.value?.blockIndex)
        coVerify { store.getBookProgress("/E:/Canonical/book.txt") }
        coVerify(exactly = 0) { store.getBookProgress("/raw/path/book.txt") }
    }

    @Test
    fun `chapter zero with paragraph progress sets pendingResume`() = runTest(dispatcher) {
        val repo = mockk<MediaRepository>(relaxed = true)
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { repo.getBookInfo("/b.txt") } returns NetworkResult.Success(fakeBook(path = "/b.txt"))
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C0", listOf(Block(type = "text", value = "body"))))
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        coEvery { store.getBookProgress("/b.txt") } returns BookProgress(
            path = "/b.txt", chapterIndex = 0, blockIndex = 4,
            scrollOffsetPx = 20, lastReadAt = 1L,
        )
        val vm = createVm(repo, store)

        vm.loadBook("/b.txt")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.currentIndex.value)
        assertEquals(4, vm.pendingResume.value?.blockIndex)
    }

    @Test
    fun `chapter zero legacy record without paragraph info sets no pendingResume`() = runTest(dispatcher) {
        val repo = mockk<MediaRepository>(relaxed = true)
        val store = mockk<RecentActivityStore>(relaxed = true)
        coEvery { repo.getBookInfo("/b.txt") } returns NetworkResult.Success(fakeBook(path = "/b.txt"))
        coEvery { repo.getBookChapter(any(), any(), any(), any()) } returns
            NetworkResult.Success(BookChapterContent("C0", listOf(Block(type = "text", value = "body"))))
        coEvery { store.readerSettingsFlow } returns flowOf(ReaderSettings())
        coEvery { store.getBookProgress("/b.txt") } returns BookProgress(
            path = "/b.txt", chapterIndex = 0, blockIndex = 0,
            scrollOffsetPx = 0, lastReadAt = 1L,
        )
        val vm = createVm(repo, store)

        vm.loadBook("/b.txt")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, vm.currentIndex.value)
        assertEquals(null, vm.pendingResume.value)
    }

    @Test
    fun `consumePendingResume clears target`() = runTest(dispatcher) {
        val vm = createVm(mockk(relaxed = true), mockk(relaxed = true))
        vm.consumePendingResume()
        assertEquals(null, vm.pendingResume.value)
    }

    @Test
    fun pickEffectiveProgressPrefersNewerServer() {
        val local = BookProgress("/b", 1, 2, 0, 1000)
        val picked = TextReaderViewModel.pickEffectiveProgress(local, 2000L, 5, 3, "/b")
        assertEquals(5, picked!!.chapterIndex)
        assertEquals(3, picked.blockIndex)
        assertEquals(2000L, picked.lastReadAt)
        // 本地更新 → 保持本地
        assertSame(local, TextReaderViewModel.pickEffectiveProgress(local, 500L, 5, 3, "/b"))
        // 双空
        assertNull(TextReaderViewModel.pickEffectiveProgress(null, null, 0, 0, "/b"))
    }
}

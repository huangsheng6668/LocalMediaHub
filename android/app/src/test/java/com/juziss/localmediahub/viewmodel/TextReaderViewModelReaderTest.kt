package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.Block
import com.juziss.localmediahub.data.Book
import com.juziss.localmediahub.data.BookChapter
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextReaderViewModelReaderTest {

    private val dispatcher = StandardTestDispatcher()
    private val localBookRepo = LocalBookRepository()
    private val downloadsStore = mockk<DownloadsStore>(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { downloadsStore.downloadedFiles } returns flowOf(emptyList())
    }

    private fun createVm(repo: MediaRepository, store: RecentActivityStore) =
        TextReaderViewModel(repo, store, localBookRepo, downloadsStore)

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
        coEvery { repo.getBookChapter(any(), any()) } returns
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
        coEvery { repo.getBookChapter(any(), any()) } returns
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
        coEvery { repo.getBookChapter(any(), any()) } returns
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
        coEvery { repo.getBookChapter(any(), any()) } returns
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
        coEvery { repo.getBookChapter(any(), any()) } returns
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
}

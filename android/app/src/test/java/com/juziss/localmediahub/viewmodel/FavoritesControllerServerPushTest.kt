package com.juziss.localmediahub.viewmodel

import com.juziss.localmediahub.data.FavoriteEntry
import com.juziss.localmediahub.data.FavoritesStore
import com.juziss.localmediahub.data.Folder
import com.juziss.localmediahub.data.MediaFile
import com.juziss.localmediahub.data.MediaRepository
import io.mockk.CapturingSlot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * 收藏 toggle 必须同步推送到服务端（spec「用户点按收藏心形时：本地乐观更新 +
 * 异步推送服务端」）：新增推 POST /library/favorites，取消推 DELETE——否则
 * 「Android 取消收藏 → Web 心形变空心」验收失败，且下次启动的并集拉取会把
 * 服务端残留条目复活回本地。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesControllerServerPushTest {

    private val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val file = MediaFile(
        name = "a.txt", path = "/m/a.txt", relativePath = "a.txt",
        size = 1, modifiedTime = "2026-01-01", mediaType = "text", extension = ".txt",
    )
    private val folder = Folder(name = "comics", path = "/m/comics", relativePath = "comics")

    private lateinit var store: FavoritesStore
    private lateinit var repo: MediaRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        store = mockk(relaxed = true)
        coEvery { store.favorites } returns flowOf(emptySet())
        coEvery { store.favoriteFiles } returns flowOf(emptyList())
        coEvery { store.favoriteEntries } returns flowOf(emptyList())
        repo = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newController(): FavoritesController {
        val controller = FavoritesController(store, repo, BrowseSharedState())
        controller.startCollecting(kotlinx.coroutines.CoroutineScope(dispatcher))
        return controller
    }

    @Test
    fun toggleAddPushesServerFavorite() = runTest(dispatcher) {
        coEvery { store.toggleFavorite(file, false) } returns true
        val controller = newController()

        controller.toggleFavorite(file, false)
        dispatcher.scheduler.advanceUntilIdle()

        val body = slot<Map<String, Any?>>()
        coVerify(exactly = 1) { repo.pushServerFavorite(capture(body)) }
        coVerify(exactly = 0) { repo.removeServerFavorite(any()) }
        assert(body.captured["path"] == "/m/a.txt")
        assert(body.captured["is_dir"] == false)
    }

    @Test
    fun toggleRemoveSendsServerDelete() = runTest(dispatcher) {
        coEvery { store.toggleFavorite(file, false) } returns false
        val controller = newController()

        controller.toggleFavorite(file, false)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repo.removeServerFavorite("/m/a.txt") }
        coVerify(exactly = 0) { repo.pushServerFavorite(any()) }
    }

    @Test
    fun toggleFolderAddPushesFolderEntry() = runTest(dispatcher) {
        coEvery { store.toggleFavoriteFolder(folder, false) } returns true
        val controller = newController()

        controller.toggleFavoriteFolder(folder, false)
        dispatcher.scheduler.advanceUntilIdle()

        val body = CapturingSlot<Map<String, Any?>>()
        coVerify(exactly = 1) { repo.pushServerFavorite(capture(body)) }
        assert(body.captured["path"] == "/m/comics")
        assert(body.captured["is_dir"] == true)
        assert((body.captured["snapshot"] as? FavoriteEntry)?.folder != null)
    }

    @Test
    fun toggleFolderRemoveSendsServerDelete() = runTest(dispatcher) {
        coEvery { store.toggleFavoriteFolder(folder, false) } returns false
        val controller = newController()

        controller.toggleFavoriteFolder(folder, false)
        dispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repo.removeServerFavorite("/m/comics") }
    }
}

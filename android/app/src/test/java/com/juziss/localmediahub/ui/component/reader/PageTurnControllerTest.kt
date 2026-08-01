package com.juziss.localmediahub.ui.component.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnControllerTest {

    @Test
    fun turnTo_next_invokes_load_with_next_index_and_returns_target() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        var loadedIdx: Int? = null
        val target = controller.turnTo(
            direction = PageTurnDirection.NEXT,
            load = { idx -> loadedIdx = idx; true },
        )
        assertEquals(1, target)
        assertEquals(1, loadedIdx)
    }

    @Test
    fun turnTo_prev_at_first_returns_null_and_no_load() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        var loaded = false
        val target = controller.turnTo(
            direction = PageTurnDirection.PREV,
            load = { loaded = true; true },
        )
        assertNull(target)
        assertFalse(loaded)
    }

    @Test
    fun turnTo_next_at_last_returns_null() = runBlocking {
        val controller = PageTurnController(currentIdx = 2, chapterCount = 3)
        val target = controller.turnTo(PageTurnDirection.NEXT, load = { true })
        assertNull(target)
    }

    @Test
    fun turnTo_load_failure_returns_null() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        val target = controller.turnTo(PageTurnDirection.NEXT, load = { false })
        assertNull(target)
    }

    /** 真实并发：第一个 turnTo 的 load 挂起期间，第二个调用被 busy 互斥拒绝。 */
    @Test
    fun turnTo_rejects_second_call_while_busy() = runBlocking {
        val controller = PageTurnController(currentIdx = 0, chapterCount = 3)
        val loadStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = launch {
            controller.turnTo(PageTurnDirection.NEXT, load = {
                loadStarted.complete(Unit)
                release.await() // 挂起，保持 busy
                true
            })
        }
        loadStarted.await()
        val secondTarget = controller.turnTo(PageTurnDirection.NEXT, load = { true })
        assertNull(secondTarget) // busy 期间第二个被拒
        release.complete(Unit)
        first.join()
    }

    @Test
    fun turnTo_accepts_after_previous_completes() = runBlocking {
        var idx = 0
        val controller = PageTurnController(currentIdx = { idx }, chapterCount = { 3 })
        val first = controller.turnTo(PageTurnDirection.NEXT, load = { newIdx -> idx = newIdx; true })
        assertEquals(1, first)
        // Second NEXT reads idx=1 from the live lambda → target 2 (mirrors ViewModel updating idx after load).
        assertEquals(2, controller.turnTo(PageTurnDirection.NEXT, load = { newIdx -> idx = newIdx; true }))
    }
}

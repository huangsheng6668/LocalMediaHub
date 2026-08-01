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

    // ===== Task 12: 拖拽判定纯函数 =====

    @Test
    fun shouldDragTakeOver_horizontal_dominant_over_threshold() {
        assertTrue(shouldDragTakeOver(20f, 5f, 8f))
        assertTrue(shouldDragTakeOver(-20f, 5f, 8f))
    }

    @Test
    fun shouldDragTakeOff_vertical_dominant_returns_false() {
        assertFalse(shouldDragTakeOver(5f, 30f, 8f))
    }

    @Test
    fun shouldDragTakeOver_under_slop_returns_false() {
        assertFalse(shouldDragTakeOver(5f, 2f, 8f))
    }

    @Test
    fun shouldDragTakeOver_exact_slop_returns_false() {
        // 必须在触摸阈值**之上**才接管；等于阈值不算接管。
        assertFalse(shouldDragTakeOver(8f, 0f, 8f))
        assertTrue(shouldDragTakeOver(8.01f, 0f, 8f))
    }

    // ===== Task 12: 松手判定纯函数 =====

    @Test
    fun resolveDragOutcome_commit_on_25_percent() {
        assertEquals(DragOutcome.COMMIT, resolveDragOutcome(0.25f))
        assertEquals(DragOutcome.COMMIT, resolveDragOutcome(-0.25f))
        assertEquals(DragOutcome.COMMIT, resolveDragOutcome(0.5f))
        assertEquals(DragOutcome.COMMIT, resolveDragOutcome(-0.9f))
    }

    @Test
    fun resolveDragOutcome_revert_under_25_percent() {
        assertEquals(DragOutcome.REVERT, resolveDragOutcome(0.249f))
        assertEquals(DragOutcome.REVERT, resolveDragOutcome(-0.249f))
        assertEquals(DragOutcome.REVERT, resolveDragOutcome(0f))
        assertEquals(DragOutcome.REVERT, resolveDragOutcome(0.1f))
        assertEquals(DragOutcome.REVERT, resolveDragOutcome(-0.1f))
    }

    @Test
    fun resolveDragOutcome_symmetric() {
        // 正负对称：同绝对值阈值判定一致。
        for (r in listOf(0f, 0.1f, 0.24f, 0.25f, 0.5f, 1f)) {
            assertEquals(resolveDragOutcome(r), resolveDragOutcome(-r))
        }
    }
}

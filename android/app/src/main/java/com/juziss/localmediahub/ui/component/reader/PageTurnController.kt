package com.juziss.localmediahub.ui.component.reader

import kotlinx.coroutines.sync.Mutex

enum class PageTurnDirection { NEXT, PREV }

/**
 * 章级翻页控制器。CHAPTER 模式内容区通过 [turnTo] 发起带方向的翻页：
 * 校验目标章合法 → 调 [load] 加载 → 返回目标章 index（由 UI 层驱动动画）。
 *
 * 本类只负责"逻辑"（边界校验、加载编排、busy 互斥），**不持有动画状态**——
 * 动画由 UI 层用 Animatable.animateTo 驱动（时长按 pageTurnStyle 决定）。
 * 纯逻辑 + 协程，便于 Robolectric 单测。
 */
class PageTurnController(
    private val currentIdx: () -> Int,
    private val chapterCount: () -> Int,
) {
    // 便捷构造：直接传快照值（测试用）。
    constructor(currentIdx: Int, chapterCount: Int) : this({ currentIdx }, { chapterCount })

    private val mutex = Mutex()

    /** @return 成功 = 已加载的目标章 index；失败（越界/load 失败/并发被拒）= null */
    suspend fun turnTo(
        direction: PageTurnDirection,
        load: suspend (targetIdx: Int) -> Boolean,
    ): Int? {
        if (!mutex.tryLock()) return null
        try {
            val target = when (direction) {
                PageTurnDirection.NEXT -> currentIdx() + 1
                PageTurnDirection.PREV -> currentIdx() - 1
            }
            if (target < 0 || target >= chapterCount()) return null
            return if (load(target)) target else null
        } finally {
            mutex.unlock()
        }
    }
}

// ===== Task 12: 拖拽判定纯函数 =====

/** 拖动接管阈值（屏宽 25%），与 Web 端对齐。 */
const val DRAG_THRESHOLD = 0.25f

/** 松手判定结果：commit = 完成翻页，revert = 回弹。 */
enum class DragOutcome { COMMIT, REVERT }

/**
 * 判定水平拖动是否应由翻页接管：水平位移 > 垂直位移 且 > 触摸阈值。
 * 注意：|dx| 必须**严格大于** touchSlopPx（等于时不接管）。
 */
fun shouldDragTakeOver(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
    kotlin.math.abs(dx) > kotlin.math.abs(dy) && kotlin.math.abs(dx) > touchSlopPx

/**
 * 松手判定：|dxRatio| >= [DRAG_THRESHOLD]（0.25）→ COMMIT，否则 REVERT。
 * dxRatio 是拖动总位移与屏宽的比值（带符号，但判定仅用绝对值）。
 */
fun resolveDragOutcome(dxRatio: Float): DragOutcome {
    val abs = kotlin.math.abs(dxRatio)
    return if (abs < DRAG_THRESHOLD) DragOutcome.REVERT else DragOutcome.COMMIT
}
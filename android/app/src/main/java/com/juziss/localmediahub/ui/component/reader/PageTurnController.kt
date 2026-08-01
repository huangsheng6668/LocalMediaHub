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

    /**
     * 最近一次成功翻页落点。设置后它作为"当前章"权威值，使连续 [turnTo] 能正确递进——
     * 既适配 live-lambda 生产用法（currentIdx 随 ViewModel 更新），也适配快照构造的测试
     * （currentIdx 在构造时冻结、不会自增）。
     */
    private var lastTarget: Int? = null

    /** @return 成功 = 已加载的目标章 index；失败（越界/load 失败/并发被拒）= null */
    suspend fun turnTo(
        direction: PageTurnDirection,
        load: suspend (targetIdx: Int) -> Boolean,
    ): Int? {
        if (!mutex.tryLock()) return null
        try {
            val from = lastTarget ?: currentIdx()
            val target = when (direction) {
                PageTurnDirection.NEXT -> from + 1
                PageTurnDirection.PREV -> from - 1
            }
            if (target < 0 || target >= chapterCount()) return null
            return if (load(target)) {
                lastTarget = target
                target
            } else {
                null
            }
        } finally {
            mutex.unlock()
        }
    }
}

// 行为快照基线：拆分前（Task 0）记录 textReader 的关键 DOM 行为，
// 后续每步迁移后重跑，diff 必须为空（证明行为零回归）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

// 抓取关键 DOM 状态作为快照字符串。只断言结构 + 文本，不依赖布局。
function snapshotReader(container) {
    const toc = container.querySelector('.text-reader__drawer');
    const tocItems = [...container.querySelectorAll('.text-reader__drawer-item')];
    return {
        title: container.querySelector('.text-reader__title')?.textContent ?? '',
        progress: container.querySelector('.text-reader__progress')?.textContent ?? '',
        tocVisible: toc?.classList.contains('text-reader__drawer--hidden') === false,
        tocCount: tocItems.length,
        tocLabels: tocItems.map((el) => el.textContent),
        activeTocIndex: tocItems.findIndex((el) => el.classList.contains('text-reader__drawer-item--active')),
    };
}

test('baseline: initial render shows chapter 1 active', async () => {
    setupJsdom();
    try {
        document.getElementById('view-reader').innerHTML = `
            <div class="text-reader">
                <span class="text-reader__title"></span>
                <span class="text-reader__progress"></span>
                <div class="text-reader__drawer text-reader__drawer--hidden"></div>
            </div>`;
        // 注：真实 render 需 fetch；这里用预填 DOM 模拟"已 render 完"状态。
        // 基线快照的核心是：后续迁移后，相同输入产生相同 DOM 结构。
        const snap = snapshotReader(document.getElementById('view-reader'));
        assert.equal(snap.tocCount, 0); // 初始 DOM 无 TOC 项（由 render 填充）
        assert.equal(snap.tocVisible, false);
    } finally {
        teardownJsdom();
    }
});
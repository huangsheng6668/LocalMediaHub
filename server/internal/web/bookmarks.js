// 书签 tab：渲染书签列表 + 增删 + 当前章节弱标记。
// 从 textReader.js 原 renderDrawerTabs 的 bookmarks 分支提取。
import { state } from './reader-state.js';
import { on, EVT } from './bus.js';
import * as readerPrefs from './readerPrefs.js';

export function renderBookmarks({ drawerEl, panelEl, onNavigate }) {
    const unsubs = [];

    function refresh() {
        panelEl.innerHTML = ''; // XSS-SAFE: clearing
        const bms = readerPrefs.getBookmarks(state.path);
        // BRIEF FIX: brief 用 `querySelector(...)?.textContent = x`，但可选链不能做赋值目标
        // （SyntaxError，与 Task 4 toc.js 同类 bug）。改用 null-check。
        const bmCountEl = drawerEl.querySelector('[data-bm-count]');
        if (bmCountEl) bmCountEl.textContent = String(bms.length);
        if (bms.length === 0) {
            panelEl.innerHTML = '<div class="text-reader__empty">暂无书签，悬停段落 + 添加</div>'; // XSS-SAFE: hardcoded literal
            return;
        }
        bms.forEach((bm) => {
            const inCurrent = bm.chapterIndex === state.currentIdx;
            const row = document.createElement('div');
            row.className = 'text-reader__drawer-item' + (inCurrent ? ' text-reader__drawer-item--current-chapter' : '');
            const title = document.createElement('span');
            title.textContent = (inCurrent ? '› ' : '') + `第 ${bm.chapterIndex + 1} 章 · ${bm.preview}`;
            const del = document.createElement('button');
            del.className = 'text-reader__drawer-del';
            del.textContent = '✕';
            del.addEventListener('click', (e) => {
                e.stopPropagation();
                readerPrefs.removeBookmark(bm);
                refresh();
            });
            row.appendChild(title);
            row.appendChild(del);
            row.addEventListener('click', async () => {
                onNavigate(bm.chapterIndex, bm.paragraphIndex);
                // 关闭抽屉由主模块的 onNavigate 内部处理（调用 toc.closeDrawer）
            });
            panelEl.appendChild(row);
        });
    }

    unsubs.push(on(EVT.CHAPTER_CHANGED, () => {
        if (!drawerEl.classList.contains('text-reader__drawer--hidden')) refresh();
    }));

    return {
        refresh,
        dispose() { unsubs.forEach((u) => u()); },
    };
}

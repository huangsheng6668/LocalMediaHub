import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';
import { state, resetState } from './reader-state.js';
import { renderBookmarks } from './bookmarks.js';

// 注意：readerPrefs 在 node 环境用 localStorage，_snapshot-helpers.mjs 已 stub localStorage。
// 但 readerPrefs.js 是 ES module，import 时会读全局。测试用 localStorage 预填书签。
//
// DEVIATION FROM BRIEF (bug fix): readerPrefs.js 用 BOOKMARKS_PREFIX = 'book_bookmarks:'
// （不是 'bookmarks:'），且 removeBookmark 要求 bookmark.bookPath 存在才生效。
// 这里按 readerPrefs.js 的真实契约预填，否则 getBookmarks 永远返回 []、删除静默失败。
function seedBookmarks(path, bms) {
    localStorage.setItem('book_bookmarks:' + path, JSON.stringify(bms));
}

test('renderBookmarks lists seeded bookmarks with › prefix on current chapter', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.path = mockBook.path;
        state.currentIdx = 1;
        seedBookmarks(mockBook.path, [
            { bookPath: mockBook.path, chapterIndex: 0, paragraphIndex: 2, preview: 'A' },
            { bookPath: mockBook.path, chapterIndex: 1, paragraphIndex: 0, preview: 'B' },
        ]);
        const drawer = document.createElement('div');
        const panel = document.createElement('div');
        drawer.appendChild(panel);
        const bm = renderBookmarks({ drawerEl: drawer, panelEl: panel, onNavigate: () => {} });
        bm.refresh();
        const rows = panel.querySelectorAll('.text-reader__drawer-item');
        assert.equal(rows.length, 2);
        // 第二个书签 chapterIndex=1 === currentIdx=1 → 有 › 前缀 + current-chapter class
        assert.ok(rows[1].classList.contains('text-reader__drawer-item--current-chapter'));
        assert.ok(rows[1].querySelector('span').textContent.startsWith('›'));
        bm.dispose();
        // BRIEF FIX: brief 这里调 localStorage.clear()，但 _snapshot-helpers.mjs 的 stub
        // 没实现 clear()（只有 getItem/setItem/removeItem）→ 抛 TypeError。
        // 删除即可：setupJsdom 每次创建全新闭包 + 全新 store，跨测试无泄漏。
    } finally {
        teardownJsdom();
    }
});

test('renderBookmarks empty state', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.path = mockBook.path;
        const panel = document.createElement('div');
        const bm = renderBookmarks({ drawerEl: document.createElement('div'), panelEl: panel, onNavigate: () => {} });
        bm.refresh();
        assert.ok(panel.querySelector('.text-reader__empty'));
        bm.dispose();
    } finally {
        teardownJsdom();
    }
});

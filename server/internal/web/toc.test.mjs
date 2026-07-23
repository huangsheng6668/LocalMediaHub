import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';
import { state, setCurrentIdx, resetState } from './reader-state.js';
import { renderToc } from './toc.js';

function setupDrawer() {
    const drawer = document.createElement('div');
    drawer.className = 'text-reader__drawer text-reader__drawer--hidden';
    document.body.appendChild(drawer);
    return drawer;
}

test('renderToc builds one button per chapter with active on current', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.currentIdx = 1;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        const items = drawer.querySelectorAll('.text-reader__drawer-item');
        assert.equal(items.length, 3);
        assert.ok(items[1].classList.contains('text-reader__drawer-item--active'));
        toc.dispose();
    } finally {
        teardownJsdom();
    }
});

test('highlightCurrent moves active class on chapter change', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.currentIdx = 0;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        setCurrentIdx(2); // 触发 bus，toc 订阅应自更新
        const items = drawer.querySelectorAll('.text-reader__drawer-item');
        assert.ok(!items[0].classList.contains('text-reader__drawer-item--active'));
        assert.ok(items[2].classList.contains('text-reader__drawer-item--active'));
        toc.dispose();
    } finally {
        teardownJsdom();
    }
});

test('openDrawer unhides + registers outside click; closeDrawer cleans up', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        toc.openDrawer();
        assert.equal(drawer.classList.contains('text-reader__drawer--hidden'), false);
        toc.closeDrawer();
        assert.equal(drawer.classList.contains('text-reader__drawer--hidden'), true);
        toc.dispose();
    } finally {
        teardownJsdom();
    }
});

test('dispose unsubscribes from bus (no highlight update after dispose)', () => {
    setupJsdom();
    try {
        resetState();
        state.book = mockBook;
        state.currentIdx = 0;
        const drawer = setupDrawer();
        const toc = renderToc({ drawerEl: drawer, onNavigate: () => {} });
        toc.dispose();
        setCurrentIdx(2);
        const items = drawer.querySelectorAll('.text-reader__drawer-item');
        // dispose 后 toc 不再订阅，active 不应移动到 2
        assert.ok(!items[2].classList.contains('text-reader__drawer-item--active'));
    } finally {
        teardownJsdom();
    }
});

// 大目录增量渲染（spec 2026-10-30-web-large-dir-render）的 DOM 级验证：
// 首批同步注入、剩余分批 append、滚动锚点扩展首批、代际取消丢弃过期批次。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { state } from './state.js';
import { setDecorationsForTest } from './library.js';
import { rememberScroll } from './scrollMemory.js';

const FIRST_CHUNK = 120;

function makeFiles(n, dir = '/m/big') {
    return Array.from({ length: n }, (_, i) => ({
        path: `${dir}/f${i + 1}.jpg`,
        name: `f${i + 1}.jpg`,
        media_type: 'image',
        extension: '.jpg',
        size: 1000,
    }));
}

const cardCount = (list) => list.querySelectorAll('.media-card').length;

async function waitForCards(list, expected, timeoutMs = 5000) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
        if (cardCount(list) >= expected) return;
        await new Promise(r => setTimeout(r, 20));
    }
    throw new Error(`cards did not reach ${expected}: got ${cardCount(list)}`);
}

async function setupBrowserList() {
    setupJsdom();
    document.body.innerHTML = '<div class="view-container"><div id="browser-list"></div></div>';
    const { elements } = await import('./dom.js');
    elements.browserList = document.getElementById('browser-list');
    const { renderBrowserList } = await import('./browserView.js');
    setDecorationsForTest({ states: {}, favorites: [] });
    state.currentPath = '/m/big';
    state.currentFolders = [];
    state.currentFiles = [];
    state.favoritesOnly = false;
    state.statusFilter = null;
    return { elements, renderBrowserList };
}

function teardownBrowserList(elements, renderBrowserList) {
    state.currentFiles = [];
    state.currentFolders = [];
    renderBrowserList();
    setDecorationsForTest(null);
    teardownJsdom();
}

test('renderBrowserList: first chunk is synchronous, the rest appends asynchronously', async () => {
    const { elements, renderBrowserList } = await setupBrowserList();
    try {
        state.currentFiles = makeFiles(2000);
        renderBrowserList();

        assert.equal(cardCount(elements.browserList), FIRST_CHUNK, 'only the first chunk is in the DOM after render');

        await waitForCards(elements.browserList, 2000);
        assert.equal(cardCount(elements.browserList), 2000);
        assert.ok(elements.browserList.querySelector('.media-card[data-path="/m/big/f2000.jpg"]'), 'last card eventually appended');
    } finally {
        teardownBrowserList(elements, renderBrowserList);
    }
});

test('renderBrowserList: scroll-memory anchor extends the synchronous batch to cover it', async () => {
    const { elements, renderBrowserList } = await setupBrowserList();
    try {
        state.currentPath = '/m/anchored';
        state.currentFiles = makeFiles(2000, '/m/anchored');
        // f1500 位于第 1500 张（索引 1499），远超首批 120。
        rememberScroll('/m/anchored', { anchorPath: '/m/anchored/f1500.jpg', offset: 0 });

        renderBrowserList();

        assert.equal(cardCount(elements.browserList), 1500, 'initial batch stretched to include the anchor card');
        assert.ok(elements.browserList.querySelector('.media-card[data-path="/m/anchored/f1500.jpg"]'));
    } finally {
        teardownBrowserList(elements, renderBrowserList);
    }
});

test('renderBrowserList: a newer render cancels the previous chunk chain', async () => {
    const { elements, renderBrowserList } = await setupBrowserList();
    try {
        state.currentFiles = makeFiles(2000);
        renderBrowserList();
        assert.equal(cardCount(elements.browserList), FIRST_CHUNK);

        state.currentFiles = makeFiles(5, '/m/small');
        renderBrowserList();

        await new Promise(r => setTimeout(r, 200));
        assert.equal(cardCount(elements.browserList), 5, 'stale chunks were dropped by the generation guard');
    } finally {
        teardownBrowserList(elements, renderBrowserList);
    }
});

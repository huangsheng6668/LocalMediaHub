import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import {
    applyListFilters,
    badgeHtmlFor,
    computeReportPayload,
    runWithConcurrency,
    setDecorationsForTest,
    getDecorations,
    decorateBrowserList,
    toggleFavorite,
    markStatus,
    refreshDecorations,
    fetchState,
    reportState,
    migrateLocalProgress,
    openStatusMenu,
    closeStatusMenu,
} from './library.js';
import { state } from './state.js';

const decos = {
    states: {
        '/m/a.txt': { status: 'reading', percent: 42.5, last_read_at: 1 },
        '/m/b.txt': { status: 'finished', percent: 100, last_read_at: 2 },
        '/m/c.txt': { status: 'unread', percent: 0, last_read_at: 3 },
    },
    favorites: ['/m/b.txt', '/m/comics'],
};
const folders = [{ path: '/m/comics', name: 'comics' }, { path: '/m/other', name: 'other' }];
const files = [
    { path: '/m/a.txt', name: 'a', media_type: 'text' },
    { path: '/m/b.txt', name: 'b', media_type: 'text' },
    { path: '/m/c.txt', name: 'c', media_type: 'text' },
    { path: '/m/d.txt', name: 'd', media_type: 'text' }, // 无行 = 未读
    { path: '/m/v.mp4', name: 'v', media_type: 'video' },
];

test('applyListFilters: no filter passes through', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: null });
    assert.equal(r.folders.length, 2);
    assert.equal(r.files.length, 5);
});

test('applyListFilters: favoritesOnly matches files and folders by path', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: true, statusFilter: null });
    assert.deepEqual(r.folders.map(f => f.path), ['/m/comics']);
    assert.deepEqual(r.files.map(f => f.path), ['/m/b.txt']);
});

test('applyListFilters: statusFilter keeps only text cards matching, hides folders/videos', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: 'reading' });
    assert.equal(r.folders.length, 0);
    assert.deepEqual(r.files.map(f => f.path), ['/m/a.txt']);
    const u = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: 'unread' });
    assert.deepEqual(u.files.map(f => f.path).sort(), ['/m/c.txt', '/m/d.txt']);
});

test('applyListFilters: both filters intersect', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: true, statusFilter: 'finished' });
    assert.deepEqual(r.files.map(f => f.path), ['/m/b.txt']);
    assert.equal(r.folders.length, 0);
});

test('applyListFilters: empty decorations tolerated', () => {
    const r = applyListFilters(folders, files, null, { favoritesOnly: true, statusFilter: null });
    assert.equal(r.folders.length + r.files.length, 0);
});

test('badgeHtmlFor', () => {
    assert.equal(badgeHtmlFor('unread', 0), '');
    assert.equal(badgeHtmlFor(null, 0), '');
    assert.ok(badgeHtmlFor('reading', 42.5).includes('读到 42.5%'));
    assert.ok(badgeHtmlFor('reading', 0).includes('>读过<'));
    assert.ok(badgeHtmlFor('finished', 100).includes('已读完'));
    assert.ok(badgeHtmlFor('finished', 100).includes('card-badge--finished'));
});

test('computeReportPayload', () => {
    const p = computeReportPayload({ chapterIndex: 2, paraIndex: 5, chapterParaCount: 10, totalChapters: 5, atChapterEnd: false });
    assert.equal(p.percent, 50); // (2 + 0.5) / 5
    assert.equal(p.finished, false);
    const f = computeReportPayload({ chapterIndex: 4, paraIndex: 9, chapterParaCount: 10, totalChapters: 5, atChapterEnd: true });
    assert.equal(f.finished, true); // 末章 + 章尾
    assert.equal(f.percent, 100);   // clamp
    const zero = computeReportPayload({ chapterIndex: 0, paraIndex: 0, chapterParaCount: 0, totalChapters: 0, atChapterEnd: false });
    assert.equal(zero.percent, 0);  // max(1,..) 防除零
});

test('runWithConcurrency: limits concurrency and collects all results in order', async () => {
    let currentActive = 0;
    let maxActive = 0;
    const taskCount = 10;
    const limit = 3;

    const taskFactories = Array.from({ length: taskCount }, (_, i) => async () => {
        currentActive++;
        if (currentActive > maxActive) {
            maxActive = currentActive;
        }
        await new Promise(r => setTimeout(r, 20));
        currentActive--;
        return `result-${i}`;
    });

    const results = await runWithConcurrency(taskFactories, limit);

    assert.ok(maxActive <= limit, `maxActive (${maxActive}) should be <= limit (${limit})`);
    assert.ok(maxActive > 1, `maxActive (${maxActive}) should be > 1 to test concurrency`);
    assert.equal(results.length, taskCount);
    assert.deepEqual(results, Array.from({ length: taskCount }, (_, i) => `result-${i}`));
});

test('runWithConcurrency: handles empty task list', async () => {
    const results = await runWithConcurrency([], 3);
    assert.deepEqual(results, []);
});

test('runWithConcurrency: tolerates task rejections and continues', async () => {
    const taskFactories = [
        async () => 'ok-1',
        async () => { throw new Error('boom'); },
        async () => 'ok-3',
    ];
    const results = await runWithConcurrency(taskFactories, 2);
    assert.deepEqual(results, ['ok-1', undefined, 'ok-3']);
});

test('decorateBrowserList patches badge and heart state in place', () => {
    setupJsdom();
    try {
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="/m/a.txt" data-media-type="text">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="/m/a.txt"></button>
            </div>
            <div class="card-details"><div class="card-meta"></div></div>
          </div>
          <div class="media-card" data-path="/m/b.txt" data-media-type="text">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="/m/b.txt"></button>
            </div>
            <div class="card-details"><div class="card-meta"><span>已有徽章位</span></div></div>
          </div>
        </div>`;
        setDecorationsForTest({
            states: { '/m/a.txt': { status: 'reading', percent: 30, last_read_at: 1 } },
            favorites: ['/m/b.txt'],
        });
        decorateBrowserList(document.getElementById('browser-list'));
        const a = document.querySelector('[data-path="/m/a.txt"]');
        assert.ok(a.querySelector('.card-badge--reading'));
        assert.ok(!a.querySelector('.fav-btn.active'));
        const b = document.querySelector('[data-path="/m/b.txt"]');
        assert.ok(b.querySelector('.fav-btn.active'));
        // 重复 patch 幂等（不叠加徽章）
        decorateBrowserList(document.getElementById('browser-list'));
        assert.equal(a.querySelectorAll('.card-badge--reading').length, 1);
    } finally {
        teardownJsdom();
    }
});

test('toggleFavorite: optimistic update and API call on add/remove', async () => {
    setupJsdom();
    const calls = [];
    global.fetch = async (url, opts) => {
        calls.push({ url, method: opts?.method, body: opts?.body ? JSON.parse(opts.body) : null });
        return { ok: true, status: 200, json: async () => ({}) };
    };
    try {
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="/m/novel.txt" data-media-type="text">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="/m/novel.txt"></button>
            </div>
          </div>
        </div>`;
        setDecorationsForTest({ states: {}, favorites: [] });
        decorateBrowserList(document.getElementById('browser-list'));
        assert.ok(!document.querySelector('.fav-btn.active'));

        // Add favorite
        await toggleFavorite('/m/novel.txt', false, 'novel', 'text');
        assert.ok(document.querySelector('.fav-btn.active'));
        assert.equal(calls.length, 1);
        assert.equal(calls[0].method, 'POST');
        assert.deepEqual(calls[0].body, {
            path: '/m/novel.txt',
            is_dir: false,
            is_system: false,
            title: 'novel',
            media_type: 'text',
            snapshot: { title: 'novel' },
        });

        // Remove favorite
        await toggleFavorite('/m/novel.txt', false, 'novel', 'text');
        assert.ok(!document.querySelector('.fav-btn.active'));
        assert.equal(calls.length, 2);
        assert.equal(calls[1].method, 'DELETE');
        assert.ok(calls[1].url.includes('/api/v1/library/favorites?path='));

        // Rollback on failure
        global.fetch = async () => ({ ok: false, status: 500, json: async () => ({ error: 'Fail' }) });
        await toggleFavorite('/m/novel.txt', false, 'novel', 'text');
        // Should have rolled back to inactive
        assert.ok(!document.querySelector('.fav-btn.active'));
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('refreshDecorations: cache hit vs miss and trigger onFilterableChange', async () => {
    setupJsdom();
    let fetchCount = 0;
    global.fetch = async (url, opts) => {
        fetchCount++;
        return {
            ok: true,
            status: 200,
            json: async () => ({
                states: { '/m/book.txt': { status: 'finished', percent: 100 } },
                favorites: ['/m/book.txt'],
            }),
        };
    };
    try {
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="/m/book.txt" data-media-type="text">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="/m/book.txt"></button>
            </div>
            <div class="card-details"><div class="card-meta"></div></div>
          </div>
        </div>`;
        state.currentPath = '/m';
        state.currentFolders = [];
        state.currentFiles = [{ path: '/m/book.txt', media_type: 'text' }];
        state.favoritesOnly = false;
        state.statusFilter = null;

        let filterChanged = false;
        await refreshDecorations(() => { filterChanged = true; });
        assert.equal(fetchCount, 1);
        assert.equal(filterChanged, false);
        const card = document.querySelector('[data-path="/m/book.txt"]');
        assert.ok(card.querySelector('.fav-btn.active'));
        assert.ok(card.querySelector('.card-badge--finished'));

        // Calling again with same path and files count -> cache hit (no new fetch)
        await refreshDecorations(() => { filterChanged = true; });
        assert.equal(fetchCount, 1);

        // When filters are active and cache misses -> calls onFilterableChange
        state.currentPath = '/other';
        state.favoritesOnly = true;
        await refreshDecorations(() => { filterChanged = true; });
        assert.equal(fetchCount, 2);
        assert.equal(filterChanged, true);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('markStatus: sends status PUT and resets cache key for fresh decorations', async () => {
    setupJsdom();
    const calls = [];
    global.fetch = async (url, opts) => {
        calls.push({ url, method: opts?.method, body: opts?.body ? JSON.parse(opts.body) : null });
        if (url.includes('/status')) {
            return { ok: true, status: 200, json: async () => ({}) };
        }
        return {
            ok: true,
            status: 200,
            json: async () => ({
                states: { '/m/book.txt': { status: 'finished', percent: 100 } },
                favorites: [],
            }),
        };
    };
    try {
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="/m/book.txt" data-media-type="text">
            <div class="card-details"><div class="card-meta"></div></div>
          </div>
        </div>`;
        state.currentPath = '/m';
        state.currentFolders = [];
        state.currentFiles = [{ path: '/m/book.txt', media_type: 'text' }];

        await markStatus('/m/book.txt', 'finished');
        assert.equal(calls[0].method, 'PUT');
        assert.ok(calls[0].url.includes('/api/v1/library/states/status'));
        assert.deepEqual(calls[0].body, { path: '/m/book.txt', status: 'finished' });
        // The refreshDecorations call inside markStatus should have fetched decorations
        assert.ok(calls.some(c => c.url.includes('/api/v1/library/decorations')));
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('fetchState and reportState', async () => {
    const calls = [];
    global.fetch = async (url, opts) => {
        calls.push({ url, method: opts?.method, body: opts?.body ? JSON.parse(opts.body) : null });
        if (opts?.method === 'POST') {
            return { ok: true, status: 200, json: async () => ({}) };
        }
        return {
            ok: true,
            status: 200,
            json: async () => ({ state: { chapter_index: 3, percent: 50 } }),
        };
    };
    try {
        const res = await fetchState('/m/book.txt');
        assert.deepEqual(res, { state: { chapter_index: 3, percent: 50 } });

        reportState('/m/book.txt', { chapterIndex: 4, paraIndex: 1, percent: 60, finished: false, lastReadAt: 12345 });
        assert.equal(calls.length, 2);
        assert.equal(calls[1].method, 'POST');
        assert.deepEqual(calls[1].body, {
            path: '/m/book.txt',
            chapter_index: 4,
            para_index: 1,
            percent: 60,
            finished: false,
            last_read_at: 12345,
        });
    } finally {
        delete global.fetch;
    }
});

test('openStatusMenu and closeStatusMenu manipulate #card-status-menu', async () => {
    setupJsdom();
    const calls = [];
    global.fetch = async (url, opts) => {
        calls.push({ url, method: opts?.method, body: opts?.body ? JSON.parse(opts.body) : null });
        return { ok: true, status: 200, json: async () => ({}) };
    };
    try {
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="/m/test.txt">
            <button id="anchor-btn" data-action="status-menu" data-path="/m/test.txt">⋮</button>
          </div>
        </div>`;
        const anchor = document.getElementById('anchor-btn');
        openStatusMenu(anchor, '/m/test.txt');

        const menu = document.getElementById('card-status-menu');
        assert.ok(menu);
        assert.ok(menu.classList.contains('open'));
        const items = menu.querySelectorAll('.status-menu__item');
        assert.equal(items.length, 4);
        assert.equal(items[0].textContent, '标为已读完');
        assert.equal(items[1].textContent, '标为读过');
        assert.equal(items[2].textContent, '标为未读');
        assert.equal(items[3].textContent, '清除手动标记');

        // Click an item ('标为读过')
        items[1].click();
        assert.ok(!menu.classList.contains('open')); // Closes after selection
        assert.ok(calls.some(c => c.url.includes('/api/v1/library/states/status') && c.body.status === 'reading'));

        // Re-open and close with closeStatusMenu
        openStatusMenu(anchor, '/m/test.txt');
        assert.ok(menu.classList.contains('open'));
        closeStatusMenu();
        assert.ok(!menu.classList.contains('open'));

        // Re-open and close with Escape key
        openStatusMenu(anchor, '/m/test.txt');
        assert.ok(menu.classList.contains('open'));
        document.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }));
        assert.ok(!menu.classList.contains('open'));

        // Re-open and close with click outside
        openStatusMenu(anchor, '/m/test.txt');
        assert.ok(menu.classList.contains('open'));
        document.body.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
        assert.ok(!menu.classList.contains('open'));
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('browser filter chips: clicking toggles state and updates active attribute', async () => {
    setupJsdom();
    try {
        document.body.innerHTML = `
            <div class="filter-chips" id="browser-filter-chips">
                <button class="filter-chip" id="chip-favorites" data-active="false" title="只显示当前目录中已收藏的内容">只看收藏</button>
                <span class="filter-chip-sep"></span>
                <button class="filter-chip filter-chip--status" data-status="" data-active="false">全部</button>
                <button class="filter-chip filter-chip--status" data-status="unread" data-active="false">未读</button>
                <button class="filter-chip filter-chip--status" data-status="reading" data-active="false">读过</button>
                <button class="filter-chip filter-chip--status" data-status="finished" data-active="false">已读完</button>
            </div>
            <div id="browser-list"></div>
        `;

        const { elements } = await import('./dom.js');
        elements.browserList = document.getElementById('browser-list');
        const { setupBrowserListeners } = await import('./browserView.js');

        // Reset state
        state.favoritesOnly = false;
        state.statusFilter = null;
        state.currentFolders = [];
        state.currentFiles = [];

        setupBrowserListeners(elements);

        const chipsBox = document.getElementById('browser-filter-chips');
        const favChip = document.getElementById('chip-favorites');
        const allChip = chipsBox.querySelector('.filter-chip--status[data-status=""]');
        const unreadChip = chipsBox.querySelector('.filter-chip--status[data-status="unread"]');
        const readingChip = chipsBox.querySelector('.filter-chip--status[data-status="reading"]');

        // 1. Toggle favorites
        favChip.click();
        assert.equal(state.favoritesOnly, true);
        assert.equal(favChip.dataset.active, 'true');

        favChip.click();
        assert.equal(state.favoritesOnly, false);
        assert.equal(favChip.dataset.active, 'false');

        // 2. Click unread chip
        unreadChip.click();
        assert.equal(state.statusFilter, 'unread');
        assert.equal(unreadChip.dataset.active, 'true');
        assert.equal(allChip.dataset.active, 'false');
        assert.equal(readingChip.dataset.active, 'false');

        // 3. Click unread chip again to deselect -> returns to null
        unreadChip.click();
        assert.equal(state.statusFilter, null);
        assert.equal(unreadChip.dataset.active, 'false');
        assert.equal(allChip.dataset.active, 'true');

        // 4. Click reading chip
        readingChip.click();
        assert.equal(state.statusFilter, 'reading');
        assert.equal(readingChip.dataset.active, 'true');
        assert.equal(allChip.dataset.active, 'false');

        // 5. Click "全部" chip -> resets to null
        allChip.click();
        assert.equal(state.statusFilter, null);
        assert.equal(allChip.dataset.active, 'true');
        assert.equal(readingChip.dataset.active, 'false');

        // Clean up
        state.favoritesOnly = false;
        state.statusFilter = null;
    } finally {
        teardownJsdom();
    }
});

test('renderBrowserList: handles filtering and empty state message', async () => {
    setupJsdom();
    try {
        document.body.innerHTML = `
            <div id="browser-list"></div>
        `;

        const { elements } = await import('./dom.js');
        elements.browserList = document.getElementById('browser-list');
        const { renderBrowserList } = await import('./browserView.js');

        // Case 1: Truly empty directory (no filters)
        state.currentFolders = [];
        state.currentFiles = [];
        state.favoritesOnly = false;
        state.statusFilter = null;
        renderBrowserList();
        assert.ok(elements.browserList.innerHTML.includes('当前目录为空（无媒体文件）'));

        // Case 2: Empty due to favoritesOnly filter
        state.favoritesOnly = true;
        state.statusFilter = null;
        renderBrowserList();
        assert.ok(elements.browserList.innerHTML.includes('当前筛选下无匹配内容'));

        // Case 3: Empty due to status filter
        state.favoritesOnly = false;
        state.statusFilter = 'finished';
        renderBrowserList();
        assert.ok(elements.browserList.innerHTML.includes('当前筛选下无匹配内容'));

        // Case 4: Renders filtered items
        setDecorationsForTest({
            states: {
                '/m/reading.txt': { status: 'reading', percent: 50 },
                '/m/finished.txt': { status: 'finished', percent: 100 },
            },
            favorites: ['/m/reading.txt'],
        });
        state.currentFolders = [{ path: '/m/folder1', name: 'folder1' }];
        state.currentFiles = [
            { path: '/m/reading.txt', name: 'reading.txt', media_type: 'text', extension: '.txt', size: 200 },
            { path: '/m/finished.txt', name: 'finished.txt', media_type: 'text', extension: '.txt', size: 300 },
        ];

        // Filter: favorites only
        state.favoritesOnly = true;
        state.statusFilter = null;
        renderBrowserList();
        const favCards = elements.browserList.querySelectorAll('.media-card');
        assert.equal(favCards.length, 1);
        assert.equal(favCards[0].dataset.path, '/m/reading.txt');

        // Filter: reading status
        state.favoritesOnly = false;
        state.statusFilter = 'reading';
        renderBrowserList();
        const readingCards = elements.browserList.querySelectorAll('.media-card');
        assert.equal(readingCards.length, 1);
        assert.equal(readingCards[0].dataset.path, '/m/reading.txt');

        // Clean up
        state.favoritesOnly = false;
        state.statusFilter = null;
        state.currentFolders = [];
        state.currentFiles = [];
        setDecorationsForTest(null);
    } finally {
        teardownJsdom();
    }
});

test('migrateLocalProgress uploads book_progress entries once', async () => {
    setupJsdom();
    try {
        const posted = [];
        global.fetch = async (url, opts) => {
            if (String(url).includes('/api/v1/library/states') && opts && opts.method === 'POST') {
                posted.push(JSON.parse(opts.body));
            }
            return { ok: true, status: 200, json: async () => ({}) };
        };
        localStorage.setItem('book_progress:/m/a.txt', JSON.stringify({ chapterIndex: 1, paraIndex: 2, lastReadAt: 100 }));
        localStorage.setItem('book_progress:/m/b.txt', JSON.stringify({ chapterIndex: 3, paraIndex: 0, lastReadAt: 200 }));
        const { migrateLocalProgress } = await import('./library.js');
        await migrateLocalProgress();
        assert.equal(posted.length, 2);
        assert.equal(localStorage.getItem('library_migrated_v1'), '1');
        posted.length = 0;
        await migrateLocalProgress(); // 二次调用幂等
        assert.equal(posted.length, 0);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('decorateBrowserList keys folder heart off fav-btn data-path (original path form)', () => {
    setupJsdom();
    try {
        // Windows 真实形态：卡片 data-path 是斜杠形态（browse 导航历史行为），
        // 心形按钮 data-path 是原始反斜杠形态（与服务端 decorations 回显对齐）。
        document.body.innerHTML = `
        <div id="browser-list">
          <div class="media-card" data-path="E:/media/comics" data-media-type="folder">
            <div class="card-actions-overlay">
              <button class="card-action-btn fav-btn" data-action="fav-toggle" data-path="E:\\media\\comics"></button>
            </div>
          </div>
        </div>`;
        setDecorationsForTest({ states: {}, favorites: ['E:\\media\\comics'] });
        decorateBrowserList(document.getElementById('browser-list'));
        assert.ok(document.querySelector('.fav-btn.active'), 'heart lights up via button path form');
    } finally {
        teardownJsdom();
    }
});

test('renderBrowserList: folder fav-btn carries original backslash path', async () => {
    setupJsdom();
    try {
        document.body.innerHTML = '<div id="browser-list"></div>';
        const { elements } = await import('./dom.js');
        elements.browserList = document.getElementById('browser-list');
        const { renderBrowserList } = await import('./browserView.js');
        setDecorationsForTest({ states: {}, favorites: [] });
        state.currentFolders = [{ path: 'E:\\media\\comics', name: 'comics' }];
        state.currentFiles = [];
        state.favoritesOnly = false;
        state.statusFilter = null;
        renderBrowserList();
        const btn = elements.browserList.querySelector('.fav-btn');
        assert.ok(btn, 'folder card has heart button');
        assert.equal(btn.dataset.path, 'E:\\media\\comics');
        // 卡片本体保持斜杠形态（browse 导航历史行为不变）
        assert.equal(elements.browserList.querySelector('.media-card').dataset.path, 'E:/media/comics');
        state.currentFolders = [];
        setDecorationsForTest(null);
    } finally {
        teardownJsdom();
    }
});

test('toggleFavorite re-renders via onFilterableChange when favoritesOnly is active', async () => {
    setupJsdom();
    global.fetch = async () => ({ ok: true, status: 200, json: async () => ({}) });
    try {
        document.body.innerHTML = '<div id="browser-list"></div>';
        setDecorationsForTest({ states: {}, favorites: [] });
        state.favoritesOnly = true;
        state.statusFilter = null;
        let rerenders = 0;
        await toggleFavorite('/m/a.txt', false, 'a', 'text', () => { rerenders++; });
        assert.equal(rerenders, 1, 'list must re-render after toggle when favorites filter is active');
        state.favoritesOnly = false;
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('toggleFavorite does not re-render when no filter is active', async () => {
    setupJsdom();
    global.fetch = async () => ({ ok: true, status: 200, json: async () => ({}) });
    try {
        document.body.innerHTML = '<div id="browser-list"></div>';
        setDecorationsForTest({ states: {}, favorites: [] });
        state.favoritesOnly = false;
        state.statusFilter = null;
        let rerenders = 0;
        await toggleFavorite('/m/a.txt', false, 'a', 'text', () => { rerenders++; });
        assert.equal(rerenders, 0, 'no filter active: in-place patch only, no re-render');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('markStatus forwards onFilterableChange when status filter is active', async () => {
    setupJsdom();
    global.fetch = async (url) => {
        if (String(url).includes('/api/v1/library/decorations')) {
            return { ok: true, status: 200, json: async () => ({ states: {}, favorites: [] }) };
        }
        return { ok: true, status: 200, json: async () => ({}) };
    };
    try {
        document.body.innerHTML = '<div id="browser-list"></div>';
        setDecorationsForTest(null);
        state.currentPath = '/m';
        state.currentFolders = [];
        state.currentFiles = [];
        state.favoritesOnly = false;
        state.statusFilter = 'finished';
        let rerenders = 0;
        await markStatus('/m/a.txt', 'finished', () => { rerenders++; });
        assert.equal(rerenders, 1, 'list must re-render after status change when status filter is active');
        state.statusFilter = null;
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});



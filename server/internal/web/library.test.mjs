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

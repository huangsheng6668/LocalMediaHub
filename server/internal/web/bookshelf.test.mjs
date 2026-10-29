import { test } from 'node:test';
import assert from 'node:assert/strict';
import { coverGradientClass, relativeTime, formatReadDuration, filterBookshelf, render } from './bookshelf.js';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';

test('coverGradientClass: deterministic and in g1..g8', () => {
    const a = coverGradientClass('三体');
    assert.equal(a, coverGradientClass('三体'));
    assert.match(a, /^bookshelf-card__cover--g[1-8]$/);
    for (const t of ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i']) {
        assert.match(coverGradientClass(t), /^bookshelf-card__cover--g[1-8]$/);
    }
});

test('relativeTime boundaries', () => {
    const now = Date.now();
    assert.equal(relativeTime(now - 30 * 1000), '刚刚');
    assert.equal(relativeTime(now - 5 * 60 * 1000), '5 分钟前');
    assert.equal(relativeTime(now - 3 * 3600 * 1000), '3 小时前');
    assert.equal(relativeTime(now - 2 * 24 * 3600 * 1000), '2 天前');
    assert.equal(relativeTime(now - 40 * 24 * 3600 * 1000), '1 个月前');
    assert.equal(relativeTime(now - 400 * 24 * 3600 * 1000), '1 年前');
    assert.equal(relativeTime(0), '');
});

test('formatReadDuration renders minutes below an hour, hours above', () => {
    assert.equal(formatReadDuration(0), '');
    assert.equal(formatReadDuration(30), '');
    assert.equal(formatReadDuration(45 * 60), '45 分钟');
    assert.equal(formatReadDuration(90 * 60), '1.5 小时');
    assert.equal(formatReadDuration(3660), '1.0 小时');
});

// ── 书架搜索（2026-09-30）──────────────────────────────────────────────

const SHELF = [
    { path: 'D:/books/三体.epub', chapterIndex: 0 },
    { path: 'D:/books/三体Ⅱ·黑暗森林.epub', chapterIndex: 1 },
    { path: 'D:/novel/基地.txt', chapterIndex: 2 },
    { path: 'D:/novel/Snow Crash.txt', chapterIndex: 3 },
];

test('filterBookshelf: empty / whitespace / null query returns the same array', () => {
    // 同一引用（而非仅内容相等）——调用方的"未搜索"分支靠这个保持零分配。
    assert.equal(filterBookshelf(SHELF, ''), SHELF);
    assert.equal(filterBookshelf(SHELF, '   '), SHELF);
    assert.equal(filterBookshelf(SHELF, null), SHELF);
    assert.equal(filterBookshelf(SHELF, undefined), SHELF);
});

test('filterBookshelf: matches the display title (filename without extension)', () => {
    assert.deepEqual(filterBookshelf(SHELF, '三体').map(e => e.path), [
        'D:/books/三体.epub',
        'D:/books/三体Ⅱ·黑暗森林.epub',
    ]);
    assert.deepEqual(filterBookshelf(SHELF, '基地').map(e => e.path), ['D:/novel/基地.txt']);
});

test('filterBookshelf: matches the full path, so folder names work too', () => {
    assert.deepEqual(filterBookshelf(SHELF, 'novel').map(e => e.path), [
        'D:/novel/基地.txt',
        'D:/novel/Snow Crash.txt',
    ]);
});

test('filterBookshelf: case-insensitive on both title and path', () => {
    assert.deepEqual(filterBookshelf(SHELF, 'snow').map(e => e.path), ['D:/novel/Snow Crash.txt']);
    assert.deepEqual(filterBookshelf(SHELF, 'SNOW CRASH').map(e => e.path), ['D:/novel/Snow Crash.txt']);
    assert.deepEqual(filterBookshelf(SHELF, 'D:/NOVEL').map(e => e.path), [
        'D:/novel/基地.txt',
        'D:/novel/Snow Crash.txt',
    ]);
});

test('filterBookshelf: surrounding whitespace in the query is trimmed', () => {
    assert.deepEqual(filterBookshelf(SHELF, '  三体  ').map(e => e.path), [
        'D:/books/三体.epub',
        'D:/books/三体Ⅱ·黑暗森林.epub',
    ]);
});

test('filterBookshelf: no match yields an empty array (not the input)', () => {
    const out = filterBookshelf(SHELF, '不存在的书名');
    assert.deepEqual(out, []);
    assert.notEqual(out, SHELF);
});

test('filterBookshelf: tolerates malformed entries without throwing', () => {
    const messy = [null, {}, { path: null }, { path: 'D:/books/三体.epub' }];
    assert.deepEqual(filterBookshelf(messy, '三体').map(e => e && e.path), ['D:/books/三体.epub']);
    assert.equal(filterBookshelf([], '三体').length, 0);
});

// ── render() 集成：搜索框确实注入 DOM 且能过滤（jsdom）─────────────────

const DEBOUNCE_DRAIN_MS = 220; // > bookshelf.js SEARCH_DEBOUNCE_MS
const tick = (ms) => new Promise((r) => setTimeout(r, ms));

// 造一个装了 3 本书的 localStorage，并按 render() 的调用契约准备好全局环境。
// 返回 { container, window }；调用方负责在 finally 里 teardown。
function setupShelfRender() {
    const { window } = setupJsdom();
    // decorations 拉取是 fire-and-forget；给一个恒定的空响应，避免真发网络请求。
    global.fetch = async () => ({
        ok: true,
        status: 200,
        json: async () => ({ states: {}, favorites: [] }),
    });
    const seed = {
        'book_progress:D:/books/三体.epub': { chapterIndex: 0, lastReadAt: 3000 },
        'book_progress:D:/books/基地.epub': { chapterIndex: 1, lastReadAt: 2000 },
        'book_progress:D:/novel/Snow Crash.txt': { chapterIndex: 2, lastReadAt: 1000 },
        // 非书籍扩展名 —— loadAll() 应忽略，用来证明搜索只在书架条目上生效
        'book_progress:D:/media/clip.mp4': { chapterIndex: 0, lastReadAt: 500 },
    };
    for (const [k, v] of Object.entries(seed)) global.localStorage.setItem(k, JSON.stringify(v));
    const container = document.getElementById('view-reader');
    render(container);
    return { container, window };
}

test('render: injects a search box and reports the total count', async () => {
    const { container, window } = setupShelfRender();
    try {
        const input = container.querySelector('.bookshelf-search-input');
        assert.ok(input, '搜索框应被注入');
        assert.equal(input.getAttribute('aria-label'), '搜索书架');

        const grid = container.querySelector('.bookshelf-grid');
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 3, '只有 .txt/.epub 进书架');
        assert.equal(container.querySelector('.bookshelf-toolbar__count').textContent, '共 3 本');
        assert.equal(container.querySelector('.bookshelf-no-result').hidden, true);

        // 输入过滤（Enter 立即 flush，不等 debounce）
        input.value = '三体';
        input.dispatchEvent(new window.Event('input'));
        input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter' }));
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 1);
        assert.equal(container.querySelector('.bookshelf-toolbar__count').textContent, '找到 1 本');
        assert.equal(container.querySelector('.bookshelf-no-result').hidden, true);

        // 按路径匹配（文件夹名）
        input.value = 'novel';
        input.dispatchEvent(new window.Event('input'));
        input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter' }));
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 1);

        // 无命中：grid 隐藏 + 专用空状态出现（与"暂无阅读历史"区分）
        input.value = '不存在的书名';
        input.dispatchEvent(new window.Event('input'));
        input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Enter' }));
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 0);
        assert.equal(grid.hidden, true);
        const noResult = container.querySelector('.bookshelf-no-result');
        assert.equal(noResult.hidden, false);
        assert.match(noResult.textContent, /没有找到匹配的书籍/);
        assert.equal(container.querySelector('.bookshelf-toolbar__count').textContent, '');

        // Escape 清空并恢复全量
        input.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape' }));
        assert.equal(input.value, '');
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 3);
        assert.equal(grid.hidden, false);
        assert.equal(noResult.hidden, true);
    } finally {
        await tick(DEBOUNCE_DRAIN_MS); // 排空 debounce 定时器，否则回调会在 teardown 后触碰 document
        delete global.fetch;
        teardownJsdom();
    }
});

test('render: debounced input filters without needing Enter', async () => {
    const { container, window } = setupShelfRender();
    try {
        const input = container.querySelector('.bookshelf-search-input');
        const grid = container.querySelector('.bookshelf-grid');
        input.value = '基地';
        input.dispatchEvent(new window.Event('input'));
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 3, 'debounce 未到期前不重渲染');
        await tick(DEBOUNCE_DRAIN_MS);
        assert.equal(grid.querySelectorAll('.bookshelf-card').length, 1);
    } finally {
        await tick(DEBOUNCE_DRAIN_MS);
        delete global.fetch;
        teardownJsdom();
    }
});

test('render: empty shelf still shows the "暂无阅读历史" state, no search box', async () => {
    setupJsdom();
    try {
        const container = document.getElementById('view-reader');
        render(container);
        assert.equal(container.querySelector('.bookshelf-search-input'), null);
        assert.match(container.textContent, /暂无阅读历史/);
    } finally {
        teardownJsdom();
    }
});

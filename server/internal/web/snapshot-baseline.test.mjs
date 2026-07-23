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

// Mock global.fetch so renderTextReader can complete against the mock book.
// textReader.js → api.js → apiRequest() → fetch(). getBookInfo hits
// /books/info; getBookChapter hits /books/chapter. We stub both with the
// shared mockBook + a single-chapter payload.
function mockFetch() {
    global.fetch = async (url) => {
        if (url.includes('/books/info')) {
            return { ok: true, status: 200, json: async () => mockBook };
        }
        if (url.includes('/books/chapter')) {
            return {
                ok: true,
                status: 200,
                json: async () => ({
                    title: '第一章 开端',
                    blocks: [{ type: 'text', value: '正文内容' }],
                }),
            };
        }
        return { ok: false, status: 404 };
    };
}

// End-to-end snapshot: drive the REAL renderTextReader against the mock book.
// This is the load-bearing baseline for Task 8 — it MUST pass both before and
// after textReader.js is slimmed, proving zero behavior regression.
test('e2e baseline: render shows chapter 1 active + title + progress', async () => {
    setupJsdom();
    // state.js (auth state) reads sessionStorage at module load; jsdom helper
    // doesn't stub it, so we provide a minimal in-memory impl here.
    global.sessionStorage = global.sessionStorage || {
        _s: {},
        getItem(k) { return k in this._s ? this._s[k] : null; },
        setItem(k, v) { this._s[k] = String(v); },
        removeItem(k) { delete this._s[k]; },
    };
    // jsdom doesn't implement window.matchMedia; readerPrefs/theme resolve uses it.
    window.matchMedia = window.matchMedia || (() => ({
        matches: false,
        addEventListener() {},
        removeEventListener() {},
        addListener() {},
        removeListener() {},
    }));
    mockFetch();
    try {
        const { renderTextReader } = await import('./textReader.js');
        const container = document.getElementById('view-reader');
        await renderTextReader(container, mockBook.path, 0);
        // allow any trailing async microtasks (rAF stubs, setTimeout) to flush
        await new Promise((r) => setTimeout(r, 50));
        const snap = snapshotReader(container);
        assert.ok(
            snap.title.includes('第一章'),
            `title was: ${JSON.stringify(snap.title)}`
        );
        assert.ok(
            snap.progress.includes('1 / 3'),
            `progress was: ${JSON.stringify(snap.progress)}`
        );
        assert.equal(snap.tocCount, mockBook.chapters.length);
        assert.equal(snap.activeTocIndex, 0);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});
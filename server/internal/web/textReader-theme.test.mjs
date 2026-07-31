// 集成测试（Task 3）：textReader.js CUSTOM 主题解析 + letter-spacing CSS 变量。
// mock 模式照抄 snapshot-baseline.test.mjs（mockFetch/环境 stub），在 stub 装好
// 后才 import('./textReader.js')（模块在 import 时捕获全局）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

// 环境 stub 与 snapshot-baseline.test.mjs 一致：sessionStorage + matchMedia + fetch。
function installEnv() {
    global.sessionStorage = global.sessionStorage || {
        _s: {},
        getItem(k) { return k in this._s ? this._s[k] : null; },
        setItem(k, v) { this._s[k] = String(v); },
        removeItem(k) { delete this._s[k]; },
    };
    window.matchMedia = window.matchMedia || (() => ({
        matches: false,
        addEventListener() {},
        removeEventListener() {},
        addListener() {},
        removeListener() {},
    }));
    global.fetch = async (url) => {
        if (url.includes('/books/info')) {
            return { ok: true, status: 200, json: async () => mockBook };
        }
        if (url.includes('/books/chapter')) {
            return {
                ok: true, status: 200,
                json: async () => ({ title: '第一章 开端', blocks: [{ type: 'text', value: '正文内容' }] }),
            };
        }
        return { ok: false, status: 404 };
    };
}

function viewContainer() {
    let el = document.getElementById('view-reader');
    if (!el) { el = document.createElement('div'); el.id = 'view-reader'; document.body.appendChild(el); }
    return el;
}

test('CUSTOM theme injects custom colors into CSS vars (and border derives from muted)', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({
            theme: 'CUSTOM', customBg: '#112233', customFg: '#445566', customMuted: '#778899',
        }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        const root = document.documentElement;
        assert.equal(root.style.getPropertyValue('--reader-bg'), '#112233');
        assert.equal(root.style.getPropertyValue('--reader-fg'), '#445566');
        assert.equal(root.style.getPropertyValue('--reader-muted'), '#778899');
        assert.equal(root.style.getPropertyValue('--reader-border'), '#778899');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('CUSTOM with missing colors falls back to DAY palette (light system)', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'CUSTOM' }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        const root = document.documentElement;
        assert.equal(root.style.getPropertyValue('--reader-bg'), '#FAF8F3'); // DAY.bg
        assert.equal(root.style.getPropertyValue('--reader-fg'), '#2B2B2B'); // DAY.fg
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('letterSpacing setting injects --reader-letter-spacing', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('reader_settings', JSON.stringify({ letterSpacing: 0.25 }));
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));
        assert.equal(document.documentElement.style.getPropertyValue('--reader-letter-spacing'), '0.25em');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

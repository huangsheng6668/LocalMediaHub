// Task 4 集成测试：CHAPTER 模式下点击内容区右热区 → 经 pageTurnApi.turnTo('next')
// 推进到下一章。仿 snapshot-baseline.test.mjs 的 mock fetch + 环境 stub 模式。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';

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
    let call = 0;
    global.fetch = async (url) => {
        if (url.includes('/books/info')) {
            return { ok: true, status: 200, json: async () => mockBook };
        }
        if (url.includes('/books/chapter')) {
            // 交替章节标题，使"前进到下一章"可断言
            const idx = call++;
            const titles = ['第一章 开端', '第二章 发展', '第三章 结局'];
            return {
                ok: true,
                status: 200,
                json: async () => ({
                    title: titles[idx] || `第${idx + 1}章`,
                    blocks: [{ type: 'text', value: `正文内容 ${idx}` }],
                }),
            };
        }
        return { ok: false, status: 404 };
    };
}

function viewContainer() {
    let el = document.getElementById('view-reader');
    if (!el) {
        el = document.createElement('div');
        el.id = 'view-reader';
        document.body.appendChild(el);
    }
    return el;
}

// CHAPTER 模式（默认 NONE）下点击右 80% 热区 → 章节前进（走 pageTurn 路径）。
test('chapter mode: click right hotzone advances to next chapter', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem(
            'reader_settings',
            JSON.stringify({ readingMode: 'chapter', pageTurnStyle: 'NONE' })
        );
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), mockBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));

        const content = viewContainer().querySelector('.text-reader__content');
        // jsdom 无 layout，getBoundingClientRect 返回全 0；clientWidth 不可写。
        // 这里直接 stub getBoundingClientRect 让热区 ratio 计算有真实宽度。
        // clientX=700 / width=800 = 0.875 > 0.80 → 右热区 → turnTo('next')。
        content.getBoundingClientRect = () => ({ left: 0, top: 0, width: 800, height: 600, right: 800, bottom: 600 });
        const click = new window.MouseEvent('click', { bubbles: true, clientX: 700 });
        content.dispatchEvent(click);
        await new Promise((r) => setTimeout(r, 120)); // 等 turnTo 完成

        const title = viewContainer().querySelector('.text-reader__title').textContent;
        assert.ok(
            title.includes('第二章'),
            `expected advanced chapter title, got: ${title}`
        );
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

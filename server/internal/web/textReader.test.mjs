import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom, mockBook } from './_snapshot-helpers.mjs';
import { state } from './reader-state.js';

function installEnv({ book = mockBook, chapter = null } = {}) {
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
            return { ok: true, status: 200, json: async () => book };
        }
        if (url.includes('/books/chapter')) {
            const ch = chapter || {
                title: '第一章 开端',
                blocks: [
                    { type: 'text', value: '这是一段超过二十五个字符的默认正文内容，用于测试阅读器正常渲染逻辑。' },
                ],
            };
            return { ok: true, status: 200, json: async () => ch };
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

// ============================================================================
// 1. formatHeaderTitle pure helper tests
// ============================================================================

test('formatHeaderTitle: deduplicates when chapter.title === book.title', async () => {
    setupJsdom();
    try {
        installEnv();
        const { formatHeaderTitle } = await import('./textReader.js');
        assert.equal(formatHeaderTitle('斗破苍穹.txt', '斗破苍穹.txt'), '斗破苍穹.txt');
        assert.equal(formatHeaderTitle(' 凡人修仙传 ', '凡人修仙传'), '凡人修仙传');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('formatHeaderTitle: displays single title when chapter or book title is empty', async () => {
    setupJsdom();
    try {
        installEnv();
        const { formatHeaderTitle } = await import('./textReader.js');
        assert.equal(formatHeaderTitle('', '西游记'), '西游记');
        assert.equal(formatHeaderTitle('   ', '西游记'), '西游记');
        assert.equal(formatHeaderTitle('第一回', ''), '第一回');
        assert.equal(formatHeaderTitle('第一回', '   '), '第一回');
        assert.equal(formatHeaderTitle(null, '西游记'), '西游记');
        assert.equal(formatHeaderTitle('第一回', null), '第一回');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('formatHeaderTitle: combines titles when different', async () => {
    setupJsdom();
    try {
        installEnv();
        const { formatHeaderTitle } = await import('./textReader.js');
        assert.equal(formatHeaderTitle('第一回 灵根育孕源流出', '西游记'), '第一回 灵根育孕源流出 — 西游记');
        assert.equal(formatHeaderTitle('  第二回  ', '  西游记  '), '第二回 — 西游记');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

// ============================================================================
// 2. DOM integration tests: header title deduplication
// ============================================================================

test('renderTextReader: deduplicates header title in DOM when chapter.title === book.title', async () => {
    setupJsdom();
    try {
        const sameTitleBook = {
            title: '无分卷小说.txt',
            path: '/test/single.txt',
            format: 'txt',
            chapters: [{ title: '无分卷小说.txt', index: 0 }],
        };
        const sameTitleChapter = {
            title: '无分卷小说.txt',
            blocks: [
                { type: 'text', value: '这是一段超过二十五个字符的正常正文叙述段落，用于验证阅读器界面标题。' },
            ],
        };
        installEnv({ book: sameTitleBook, chapter: sameTitleChapter });
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), sameTitleBook.path, 0);
        await new Promise((r) => setTimeout(r, 50));

        const titleEl = document.querySelector('.text-reader__title');
        assert.ok(titleEl, 'Title element exists');
        assert.equal(titleEl.textContent, '无分卷小说.txt');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('renderTextReader: shows chapter — book title when chapter.title differs from book.title', async () => {
    setupJsdom();
    try {
        const book = {
            title: '三国演义',
            path: '/test/sanguo.txt',
            format: 'txt',
            chapters: [{ title: '第一回 宴桃园豪杰三结义', index: 0 }],
        };
        const chapter = {
            title: '第一回 宴桃园豪杰三结义',
            blocks: [
                { type: 'text', value: '滚滚长江东逝水，浪花淘尽英雄。是非成败转头空，青山依旧在，几度夕阳红。' },
            ],
        };
        installEnv({ book, chapter });
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), book.path, 0);
        await new Promise((r) => setTimeout(r, 50));

        const titleEl = document.querySelector('.text-reader__title');
        assert.ok(titleEl, 'Title element exists');
        assert.equal(titleEl.textContent, '第一回 宴桃园豪杰三结义 — 三国演义');
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

// ============================================================================
// 3. DOM integration tests: dropcap metadata and short lines exclusion
// ============================================================================

test('renderTextReader: dropcap skips metadata paragraphs and short lines (< 25 chars)', async () => {
    setupJsdom();
    try {
        const book = {
            title: '测试名著',
            path: '/test/book.txt',
            format: 'txt',
            chapters: [{ title: '第一章 序幕', index: 0 }],
        };
        const chapter = {
            title: '第一章 序幕',
            blocks: [
                // 1. 元数据段落（均应被排除）
                { type: 'text', value: '作者：著名作家张三' },
                { type: 'text', value: '书名：测试名著大全' },
                { type: 'text', value: '来 源：某某网络文学平台发布' },
                { type: 'text', value: '字数：一百二十万字整' },
                { type: 'text', value: '简介：这是一个跌宕起伏波澜壮阔的传奇冒险故事。' },
                { type: 'text', value: '编辑：王五' },
                { type: 'text', value: '翻译：李四' },
                { type: 'text', value: '出版：人民文学出版社二零二六年版' },
                { type: 'text', value: '内容简介：关于一段波澜壮阔的史诗级幻想世界冒险传说。' },
                // 2. 短行段落（< 25 字符，应被排除）
                { type: 'text', value: '短行第一行。' },
                { type: 'text', value: '这是只有二十四个字的短段落，长度未达标不能下沉。' }, // 24 chars
                // 3. 标点/引号开头段落（哪怕 >= 25 字符，也应排除以防放大标点）
                { type: 'text', value: '“这是带有中文双引号开头的正文对话段落，长度明显超过了二十五个字符限制。”' },
                { type: 'text', value: '《这是一段以书名号开头的引用说明文字，长度同样超过了二十五个字符限制。》' },
                // 4. 首个符合条件的叙述正文段落（>= 25 字符，无标点/元数据前缀）
                { type: 'text', value: '那是很久以前的一个清晨，晨曦穿透了薄雾洒在古老的大地上，少年背着行囊踏上了漫长的征途。' },
                // 5. 后续正文段落（不应重复应用 dropcap）
                { type: 'text', value: '沿着蜿蜒曲折的山路一直往前走，前方就是传说中危机四伏但充满机缘的迷雾森林。' },
            ],
        };
        installEnv({ book, chapter });
        const { renderTextReader } = await import('./textReader.js');
        await renderTextReader(viewContainer(), book.path, 0);
        await new Promise((r) => setTimeout(r, 50));

        const paragraphs = document.querySelectorAll('.text-reader__p');
        assert.equal(paragraphs.length, 15, 'All 15 blocks rendered as paragraphs');

        // Verify metadata lines do NOT have dropcap
        for (let i = 0; i < 9; i++) {
            assert.ok(
                !paragraphs[i].classList.contains('text-reader__p--dropcap'),
                `Metadata paragraph ${i} (${chapter.blocks[i].value}) should NOT have dropcap`
            );
        }

        // Verify short lines do NOT have dropcap
        assert.ok(!paragraphs[9].classList.contains('text-reader__p--dropcap'), 'Short line 9 should NOT have dropcap');
        assert.ok(!paragraphs[10].classList.contains('text-reader__p--dropcap'), 'Short line 10 should NOT have dropcap');

        // Verify punctuation-prefixed lines do NOT have dropcap
        assert.ok(!paragraphs[11].classList.contains('text-reader__p--dropcap'), 'Quote-prefixed line 11 should NOT have dropcap');
        assert.ok(!paragraphs[12].classList.contains('text-reader__p--dropcap'), 'Booktitle-prefixed line 12 should NOT have dropcap');

        // Verify the first qualifying narrative paragraph DOES have dropcap
        assert.ok(
            paragraphs[13].classList.contains('text-reader__p--dropcap'),
            'First qualifying narrative paragraph (idx 13) MUST have text-reader__p--dropcap'
        );

        // Verify subsequent narrative paragraphs do NOT have dropcap
        assert.ok(
            !paragraphs[14].classList.contains('text-reader__p--dropcap'),
            'Subsequent narrative paragraph (idx 14) should NOT have dropcap'
        );
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

// ============================================================================
// 4. resolveResume pure helper tests
// NOTE: textReader.js must be dynamically imported AFTER setupJsdom() — its
// import graph (api.js -> state.js) reads localStorage at module-eval time.
// ============================================================================

test('resolveResume: explicit chapter param wins over saved progress', async () => {
    setupJsdom();
    try {
        installEnv();
        const { resolveResume } = await import('./textReader.js');
        const r = resolveResume({
            chapterParam: '0',
            paraParam: null,
            saved: { chapterIndex: 2, paraIndex: 5 },
            chapterCount: 10,
        });
        assert.equal(r.startIdx, 0);
        assert.equal(r.resumePara, null); // URL 只指定章 → 章顶，不套用存档段落
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('resolveResume: URL para param applies', async () => {
    setupJsdom();
    try {
        installEnv();
        const { resolveResume } = await import('./textReader.js');
        const r = resolveResume({
            chapterParam: '1',
            paraParam: '3',
            saved: null,
            chapterCount: 10,
        });
        assert.equal(r.startIdx, 1);
        assert.equal(r.resumePara, 3);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('resolveResume: saved progress restores chapter and paragraph', async () => {
    setupJsdom();
    try {
        installEnv();
        const { resolveResume } = await import('./textReader.js');
        const r = resolveResume({
            chapterParam: null,
            paraParam: null,
            saved: { chapterIndex: 4, paraIndex: 7 },
            chapterCount: 10,
        });
        assert.equal(r.startIdx, 4);
        assert.equal(r.resumePara, 7);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('resolveResume: legacy payload without paraIndex → chapter only', async () => {
    setupJsdom();
    try {
        installEnv();
        const { resolveResume } = await import('./textReader.js');
        const r = resolveResume({
            chapterParam: null,
            paraParam: null,
            saved: { chapterIndex: 4 },
            chapterCount: 10,
        });
        assert.equal(r.startIdx, 4);
        assert.equal(r.resumePara, null);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

test('resolveResume: clamps out-of-range chapter', async () => {
    setupJsdom();
    try {
        installEnv();
        const { resolveResume } = await import('./textReader.js');
        const r = resolveResume({ chapterParam: null, paraParam: null, saved: { chapterIndex: 99 }, chapterCount: 3 });
        assert.equal(r.startIdx, 2);
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

// ============================================================================
// 5. integration: saved progress restores chapter via render
// ============================================================================

test('renderTextReader: reopens at saved chapter when no chapter param', async () => {
    setupJsdom();
    try {
        installEnv();
        localStorage.setItem('book_progress:/test/book.txt', JSON.stringify({ chapterIndex: 2, paraIndex: 0 }));
        const { renderTextReader } = await import('./textReader.js');
        const container = viewContainer();
        await renderTextReader(container, '/test/book.txt', null, null);
        assert.equal(state.currentIdx, 2);
        assert.ok(localStorage.getItem('book_progress:/test/book.txt').includes('"paraIndex"'));
        container._cleanupReader?.();
    } finally {
        delete global.fetch;
        teardownJsdom();
    }
});

// CSS token 一致性守护（2026-09-30）。
//
// 背景：--font-mono 曾被 dashboard.css（.path-chip）和 settings.css
// （.settings-static / textarea）引用 3 次，却从未在任何地方定义过 —— 三处
// 全部静默回落到 generic `monospace`。这类问题不报错、不影响任何现有测试、
// 只会在视觉上悄悄偏离设计系统，属于「人工走查才能发现」的缺陷。
//
// 本测试把它变成 CI 门禁：CSS 里出现的每个 var(--x) 都必须能在 CSS 中找到
// 定义（或是明确由 JS 注入的 --reader-* 系列）。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

const norm = (f) => f.replace(/\\/g, '/');
const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8');

// 注释剥离：注释里出现的 var() / --name: 都不参与判定。
const stripComments = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '');

function cssFiles() {
    return readdirSync(new URL('./css/', import.meta.url), { recursive: true, encoding: 'utf8' })
        .map(norm)
        .filter((f) => f.endsWith('.css'));
}

// JS 注入的变量：textReader.js 通过 element.style.setProperty 写入（阅读器
// 主题与字号），其定义不在 CSS 中，因此不参与本断言。
const JS_INJECTED = /^--reader-/;

test('every var(--x) referenced in CSS is defined somewhere in CSS', () => {
    const defined = new Set();
    const referenced = new Map(); // name -> Set<file>

    for (const f of cssFiles()) {
        const src = stripComments(read('./css/' + f));

        // 定义形式：行首 / 分号后 / 花括号后的 `--name:`（一行可定义多个）。
        for (const m of src.matchAll(/(?:^|[\s;{])(--[a-zA-Z0-9-]+)\s*:/g)) {
            defined.add(m[1]);
        }
        // 引用形式：var(--name …)，忽略 fallback 部分。
        for (const m of src.matchAll(/var\(\s*(--[a-zA-Z0-9-]+)/g)) {
            if (!referenced.has(m[1])) referenced.set(m[1], new Set());
            referenced.get(m[1]).add(f);
        }
    }

    assert.ok(cssFiles().length >= 10, `CSS 文件数异常 (${cssFiles().length}) — 检查目录`);
    assert.ok(referenced.size > 30, `扫描到的变量引用过少 (${referenced.size}) — 检查解析逻辑`);

    const missing = [...referenced.keys()]
        .filter((n) => !defined.has(n) && !JS_INJECTED.test(n))
        .sort();

    assert.deepEqual(
        missing,
        [],
        '以下 CSS 变量被引用但从未定义，会静默回落或整条声明失效：\n  ' +
            missing.map((n) => `${n}  ← ${[...referenced.get(n)].join(', ')}`).join('\n  '),
    );
});

test('themes.css defines the full chrome token set for :root', () => {
    // 这组 token 是所有主题的基线（各主题只覆盖色值，尺寸/字体继承 :root）。
    // 缺任何一个都会让非 day 主题出现「未定义变量」的静默降级。
    const src = stripComments(read('./css/themes.css'));
    const rootBlock = src.match(/:root\s*,\s*\[data-theme="day"\]\s*\{([\s\S]*?)\}/);
    assert.ok(rootBlock, 'themes.css 中未找到 `:root, [data-theme="day"]` 块 — 格式可能已变');

    const definedInRoot = new Set(
        [...rootBlock[1].matchAll(/(?:^|[\s;{])(--[a-zA-Z0-9-]+)\s*:/g)].map((m) => m[1]),
    );

    const REQUIRED = [
        '--surface-app', '--surface-card', '--surface-sidebar', '--surface-hover',
        '--text-primary', '--text-secondary', '--text-muted', '--text-on-accent',
        '--accent', '--accent-hover', '--accent-soft', '--accent-text',
        '--action-ink', '--action-ink-hover',
        '--border-soft', '--border-subtle',
        '--radius-sm', '--radius-md', '--radius-lg',
        '--space-1', '--space-2', '--space-3', '--space-4', '--space-5', '--space-6',
        '--shadow-sm', '--shadow-md',
        '--error', '--secondary',
        '--font-display', '--font-sans', '--font-mono',
    ];

    const missing = REQUIRED.filter((t) => !definedInRoot.has(t));
    assert.deepEqual(missing, [], `:root 缺少以下基线 token：${missing.join(', ')}`);
});

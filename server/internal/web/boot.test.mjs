// boot.js FOUC：主题初值必须读 reader_settings.theme（与 app.js 同源），
// 而非从未被写入过的 chrome_theme 键。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';

const SRC = readFileSync(new URL('./boot.js', import.meta.url), 'utf8');

function runBoot() { window.eval(SRC); }

test('boot: NIGHT 主题硬刷新不闪 day', () => {
    setupJsdom();
    try {
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'NIGHT' }));
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'night');
    } finally { teardownJsdom(); }
});

test('boot: NIGHT_BLACK 映射 night_black', () => {
    setupJsdom();
    try {
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'NIGHT_BLACK' }));
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'night_black');
    } finally { teardownJsdom(); }
});

test('boot: AUTO 按系统偏好解析', () => {
    setupJsdom();
    try {
        window.matchMedia = () => ({ matches: true }); // 系统 dark
        localStorage.setItem('reader_settings', JSON.stringify({ theme: 'AUTO' }));
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'night');
    } finally { teardownJsdom(); }
});

test('boot: 无存储/坏 JSON 回退 day（与 app.js 默认一致）', () => {
    setupJsdom();
    try {
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'day');
        localStorage.setItem('reader_settings', '{broken json');
        document.documentElement.dataset.theme = '';
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'day');
    } finally { teardownJsdom(); }
});

test('boot: 不再读 chrome_theme', () => {
    setupJsdom();
    try {
        localStorage.setItem('chrome_theme', 'night');
        runBoot();
        assert.equal(document.documentElement.dataset.theme, 'day'); // 忽略死键
    } finally { teardownJsdom(); }
});

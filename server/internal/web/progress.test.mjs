import { test } from 'node:test';
import assert from 'node:assert/strict';
import { detectActiveChapterOnScroll, computePercent } from './progress.js';

// mock section：模拟 getBoundingClientRect 返回的 {top, bottom}。
function mkSec(top, bottom, idx) {
    return { top, bottom, dataset: { chapterIndex: String(idx) } };
}

test('detectActive: single chapter → 0', () => {
    const sections = [mkSec(0, 800, 0)];
    assert.equal(detectActiveChapterOnScroll(sections, 0), 0);
});

test('detectActive: scrolled past chapter 0 into chapter 1', () => {
    // chapter0 顶部在容器顶 -200（已滚过），chapter1 顶部在 +50（刚露出）
    const sections = [mkSec(-200, -50, 0), mkSec(50, 600, 1)];
    assert.equal(detectActiveChapterOnScroll(sections, 0), 1);
});

test('detectActive: chapter boundary at exactly 120px threshold', () => {
    // chapter1 顶部恰在 containerTop+120（边界含，<= 120）
    const sections = [mkSec(-500, -100, 0), mkSec(120, 700, 1)];
    assert.equal(detectActiveChapterOnScroll(sections, 0), 1);
});

test('detectActive: first chapter still active when nothing scrolled past threshold', () => {
    const sections = [mkSec(0, 400, 0), mkSec(400, 800, 1)];
    // chapter0 top=0 (<=120 命中), chapter1 top=400 (>120 不命中)
    // 遍历后 activeIdx 被 chapter1 覆盖前，chapter0 已命中 → 返回最后一个命中的
    // 实现约定：返回顶部已滚过阈值的最靠后章节。chapter0 命中，chapter1 不命中 → 0
    assert.equal(detectActiveChapterOnScroll(sections, 0), 0);
});

test('detectActive: empty sections returns fallback', () => {
    assert.equal(detectActiveChapterOnScroll([], 0, 5), 5);
});

test('computePercent: clamps to [0, 100]', () => {
    assert.equal(computePercent(-1, 10), 0);
    assert.equal(computePercent(11, 10), 100);
    assert.equal(computePercent(5, 10), 50);
});

test('computePercent: zero max returns 0', () => {
    assert.equal(computePercent(5, 0), 0);
});

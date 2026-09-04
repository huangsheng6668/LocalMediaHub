import { test } from 'node:test';
import assert from 'node:assert/strict';
import { detectActiveChapterOnScroll, computePercent, firstVisibleParagraph } from './progress.js';

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

// mock paragraph：模拟 getBoundingClientRect 的 {top, bottom} + 章节/段落索引。
function mkPara(top, bottom, chapterIndex, paraIndex) {
    return { top, bottom, chapterIndex, paraIndex };
}

test('firstVisibleParagraph: returns first paragraph whose bottom crosses container top', () => {
    const paras = [
        mkPara(-100, -10, 0, 0),   // 已完全滚出
        mkPara(-10, 40, 0, 1),     // 部分可见 → 目标
        mkPara(40, 120, 0, 2),
    ];
    const hit = firstVisibleParagraph(paras, 0);
    assert.equal(hit.paraIndex, 1);
    assert.equal(hit.chapterIndex, 0);
});

test('firstVisibleParagraph: fully-visible first paragraph wins', () => {
    const paras = [mkPara(10, 80, 2, 3)];
    assert.equal(firstVisibleParagraph(paras, 0).paraIndex, 3);
});

test('firstVisibleParagraph: all scrolled past → last paragraph', () => {
    const paras = [mkPara(-200, -100, 0, 0), mkPara(-100, -20, 0, 4)];
    assert.equal(firstVisibleParagraph(paras, 0).paraIndex, 4);
});

test('firstVisibleParagraph: empty → null', () => {
    assert.equal(firstVisibleParagraph([], 0), null);
});

test('firstVisibleParagraph: honours nonzero container top', () => {
    const paras = [mkPara(50, 90, 1, 0), mkPara(90, 150, 1, 1)];
    // containerTop=100：第一段 bottom 90 < 100 已滚出，第二段部分可见
    assert.equal(firstVisibleParagraph(paras, 100).paraIndex, 1);
});

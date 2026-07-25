import { test } from 'node:test';
import assert from 'node:assert/strict';
import { progressToChapterIndex, chapterIndexToProgress } from './readerScrubber.js';

test('progressToChapterIndex: middle of 10 chapters → 5', () => {
    assert.equal(progressToChapterIndex(0.5, 10), 5);
});

test('progressToChapterIndex: rounds to nearest, not floor', () => {
    // spec: targetIdx = round(p * (chapterCount - 1))
    assert.equal(progressToChapterIndex(0.55, 10), 5);  // round(0.55*9)=round(4.95)=5
    assert.equal(progressToChapterIndex(0.06, 10), 1);  // round(0.06*9)=round(0.54)=1
});

test('progressToChapterIndex: clamps to [0, chapterCount-1]', () => {
    assert.equal(progressToChapterIndex(-0.5, 10), 0);
    assert.equal(progressToChapterIndex(1.5, 10), 9);
});

test('progressToChapterIndex: chapterCount <= 1 returns 0', () => {
    assert.equal(progressToChapterIndex(0.9, 1), 0);
    assert.equal(progressToChapterIndex(0.9, 0), 0);
});

test('chapterIndexToProgress: idx 5 of 10 → 5/9', () => {
    assert.equal(chapterIndexToProgress(5, 10), 5 / 9);
});

test('chapterIndexToProgress: clamps', () => {
    assert.equal(chapterIndexToProgress(-1, 10), 0);
    assert.equal(chapterIndexToProgress(20, 10), 1);
});

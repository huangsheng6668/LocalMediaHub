import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { renderPageTurn } from './pageTurn.js';

function makeSection(idx, text) {
    const s = document.createElement('section');
    s.className = 'text-reader__chapter-section';
    s.dataset.chapterIndex = String(idx);
    s.textContent = text;
    return s;
}

function setup(initialIdx = 0, count = 3, style = 'NONE') {
    setupJsdom();
    // pageTurn.js uses matchMedia for prefers-reduced-motion; stub it (no reduction).
    window.matchMedia = window.matchMedia || (() => ({
        matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }));
    const contentEl = document.createElement('div');
    contentEl.appendChild(makeSection(initialIdx, `chapter ${initialIdx}`));
    let currentIdx = initialIdx;
    const api = renderPageTurn({
        contentEl,
        getStyle: () => style,
        loadChapterSection: async (idx) => {
            currentIdx = idx;
            return makeSection(idx, `chapter ${idx}`);
        },
        getCurrentIdx: () => currentIdx,
        getChapterCount: () => count,
    });
    return { contentEl, api };
}

test('turnTo(next) in NONE swaps content immediately and returns true', async () => {
    const { contentEl, api } = setup(0, 3, 'NONE');
    const ok = await api.turnTo('next');
    assert.equal(ok, true);
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '1');
    api.dispose();
    teardownJsdom();
});

test('turnTo(next) at last chapter returns false and no-ops', async () => {
    const { contentEl, api } = setup(2, 3, 'NONE');
    const ok = await api.turnTo('next');
    assert.equal(ok, false);
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '2');
    api.dispose();
    teardownJsdom();
});

test('turnTo(prev) at first chapter returns false', async () => {
    const { contentEl, api } = setup(0, 3, 'NONE');
    const ok = await api.turnTo('prev');
    assert.equal(ok, false);
    api.dispose();
    teardownJsdom();
});

test('COVER turnTo(next) ends with new section visible', async () => {
    const { contentEl, api } = setup(0, 3, 'COVER');
    // CSS transitions won't actually animate in jsdom; pageTurn.js must invoke the
    // transitionend-or-fallback. The contract: after turnTo resolves, the new
    // section is the one in the DOM.
    await api.turnTo('next');
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '1');
    api.dispose();
    teardownJsdom();
});

test('invalid direction returns false and no-ops', async () => {
    const { contentEl, api } = setup(0, 3, 'NONE');
    const ok = await api.turnTo('sideways');
    assert.equal(ok, false);
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '0');
    api.dispose();
    teardownJsdom();
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { renderPageTurn, resolveDragOutcome } from './pageTurn.js';

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

test('SIMULATION turnTo(next) ends with new section visible', async () => {
    const { contentEl, api } = setup(0, 3, 'SIMULATION');
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

test('turnTo(next) requests the next chapter index via loadChapterSection', async () => {
    setupJsdom();
    window.matchMedia = window.matchMedia || (() => ({
        matches: false, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {},
    }));
    const contentEl = document.createElement('div');
    contentEl.appendChild(makeSection(1, 'one'));
    let requestedIdx = null;
    const api = renderPageTurn({
        contentEl,
        getStyle: () => 'NONE',
        loadChapterSection: async (idx) => { requestedIdx = idx; return makeSection(idx, `ch${idx}`); },
        getCurrentIdx: () => 1,
        getChapterCount: () => 5,
    });
    await api.turnTo('next');
    assert.equal(requestedIdx, 2);
    api.dispose();
    teardownJsdom();
});

// ===== Task 6: DRAG 翻页手势 — 纯函数阈值判定 =====
// 约定：dxRatio = (pointer.x − start.x) / width，带符号。
//   dxRatio<0（手指向左拖）= next 意图（中文从右往左翻）
//   dxRatio>0 = prev 意图；|dxRatio|>0.25 → commit，否则 revert。
test('resolveDragOutcome classifies by threshold and direction', () => {
    assert.equal(resolveDragOutcome(-0.30).action, 'commit');
    assert.equal(resolveDragOutcome(-0.30).direction, 'next');   // 向左拖 → next
    assert.equal(resolveDragOutcome(0.30).action, 'commit');
    assert.equal(resolveDragOutcome(0.30).direction, 'prev');    // 向右拖 → prev
    assert.equal(resolveDragOutcome(0.10).action, 'revert');
    assert.equal(resolveDragOutcome(-0.10).action, 'revert');
    assert.equal(resolveDragOutcome(0.10).direction, null);
});

test('resolveDragOutcome treats exactly threshold (0.25) as commit', () => {
    // 阈值边界：< threshold 才 revert；>= threshold（含 0.25）commit。
    assert.equal(resolveDragOutcome(0.25).action, 'commit');
    assert.equal(resolveDragOutcome(-0.25).action, 'commit');
    assert.equal(resolveDragOutcome(0.249).action, 'revert');
    assert.equal(resolveDragOutcome(0).action, 'revert');
    assert.equal(resolveDragOutcome(0).direction, null);
});

// ===== DRAG pointer 集成：通过 renderPageTurn 驱动手势 =====
// jsdom 不渲染真实过渡；pageTurn.js 靠 transitionend + setTimeout fallback
// 收尾，所以 pointerup 后等 COVER 时长即可读到终态 DOM。

// jsdom 没有 PointerEvent 构造器，但 pageTurn.js 的监听器只读 clientX/clientY，
// 所以用 window.MouseEvent 派发 'pointerdown/move/up' 事件即可驱动逻辑（事件
// type 决定哪个监听器被调用，与事件子类无关）。window 由 setupJsdom() 注入 global。
function pointer(el, type, x, y) {
    el.dispatchEvent(new window.MouseEvent(type, {
        bubbles: true, cancelable: true, composed: true,
        clientX: x, clientY: y,
    }));
}

test('DRAG: horizontal drag past threshold commits to next chapter', async () => {
    const { contentEl, api } = setup(0, 3, 'DRAG');
    // 给容器一个宽度，使 dxRatio = dx / width 有意义。
    Object.defineProperty(contentEl, 'clientWidth', { value: 1000, configurable: true });
    const rect = contentEl.getBoundingClientRect.bind(contentEl);
    contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, right: 1000, bottom: 500, width: 1000, height: 500 });
    void rect;
    // 起点 500，向左拖 300px（dxRatio = -0.30 → next，commit）。
    pointer(contentEl, 'pointerdown', 500, 10);
    pointer(contentEl, 'pointermove', 300, 10);   // |dx|=200 > |dy|=0，> 8px → 接管
    pointer(contentEl, 'pointermove', 200, 12);
    pointer(contentEl, 'pointerup', 200, 12);
    // 等 COVER 动画 fallback 收尾。
    await new Promise((r) => setTimeout(r, 360));
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '1');
    api.dispose();
    teardownJsdom();
});

test('DRAG: small drag below threshold reverts (old section stays)', async () => {
    const { contentEl, api } = setup(0, 3, 'DRAG');
    Object.defineProperty(contentEl, 'clientWidth', { value: 1000, configurable: true });
    contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, right: 1000, bottom: 500, width: 1000, height: 500 });
    // 起点 500，向左仅拖 100px（dxRatio = -0.10 → revert）。
    pointer(contentEl, 'pointerdown', 500, 10);
    pointer(contentEl, 'pointermove', 400, 10);
    pointer(contentEl, 'pointerup', 400, 10);
    await new Promise((r) => setTimeout(r, 360));
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '0');
    api.dispose();
    teardownJsdom();
});

test('DRAG: vertical-dominant move does not take over (no chapter change)', async () => {
    const { contentEl, api } = setup(0, 3, 'DRAG');
    Object.defineProperty(contentEl, 'clientWidth', { value: 1000, configurable: true });
    contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, right: 1000, bottom: 500, width: 1000, height: 500 });
    // 水平位移很小（10px）、垂直大（200px）→ 不接管，垂直滚动继续；松手无翻页。
    pointer(contentEl, 'pointerdown', 500, 10);
    pointer(contentEl, 'pointermove', 490, 210);
    pointer(contentEl, 'pointerup', 490, 210);
    await new Promise((r) => setTimeout(r, 360));
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '0');
    api.dispose();
    teardownJsdom();
});

test('DRAG: non-DRAG style ignores horizontal drag gesture', async () => {
    // COVER 样式下不应启用拖动手势；拖动后内容应保持原章。
    const { contentEl, api } = setup(0, 3, 'COVER');
    Object.defineProperty(contentEl, 'clientWidth', { value: 1000, configurable: true });
    contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, right: 1000, bottom: 500, width: 1000, height: 500 });
    pointer(contentEl, 'pointerdown', 500, 10);
    pointer(contentEl, 'pointermove', 200, 10);
    pointer(contentEl, 'pointerup', 200, 10);
    await new Promise((r) => setTimeout(r, 360));
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '0');
    api.dispose();
    teardownJsdom();
});

test('DRAG: dispose detaches pointer listeners (no drag handling after dispose)', async () => {
    const { contentEl, api } = setup(0, 3, 'DRAG');
    Object.defineProperty(contentEl, 'clientWidth', { value: 1000, configurable: true });
    contentEl.getBoundingClientRect = () => ({ left: 0, top: 0, right: 1000, bottom: 500, width: 1000, height: 500 });
    api.dispose();
    // dispose 后再发拖动事件应完全无副作用（不抛错、不翻页）。
    pointer(contentEl, 'pointerdown', 500, 10);
    pointer(contentEl, 'pointermove', 200, 10);
    pointer(contentEl, 'pointerup', 200, 10);
    await new Promise((r) => setTimeout(r, 360));
    assert.equal(contentEl.querySelector('.text-reader__chapter-section').dataset.chapterIndex, '0');
    teardownJsdom();
});

test('turnTo resets contentEl.scrollTop to 0 across styles', async () => {
    for (const style of ['NONE', 'COVER', 'SIMULATION']) {
        const { contentEl, api } = setup(0, 3, style);
        contentEl.scrollTop = 1500;
        await api.turnTo('next');
        assert.equal(contentEl.scrollTop, 0, `style ${style} should reset scrollTop to 0`);
        api.dispose();
        teardownJsdom();
    }
});

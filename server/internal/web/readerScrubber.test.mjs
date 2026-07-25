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

import { JSDOM } from 'jsdom';

function setupDom() {
    const dom = new JSDOM('<!DOCTYPE html><div id="host"></div>');
    global.document = dom.window.document;
    global.window = dom.window;
    // jsdom 25.0.1 lacks PointerEvent; polyfill for pointer-event dispatch in tests.
    if (!window.PointerEvent) {
        window.PointerEvent = class PointerEvent extends window.MouseEvent {
            constructor(type, init = {}) {
                super(type, init);
                if (init.pointerId !== undefined) {
                    Object.defineProperty(this, 'pointerId', { value: init.pointerId });
                }
            }
        };
    }
    return dom.window.document.getElementById('host');
}

import { renderScrubber } from './readerScrubber.js';

test('renderScrubber: builds DOM with track/thumb/label', () => {
    const host = setupDom();
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0.5,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: () => {}, onSeekEnd: () => {},
        formatLabel: (p) => `第${p}章`,
    });
    assert.ok(host.querySelector('.text-reader__scrubber'));
    assert.ok(host.querySelector('.text-reader__scrubber-track'));
    assert.ok(host.querySelector('.text-reader__scrubber-thumb'));
    assert.ok(host.querySelector('.text-reader__scrubber-label'));
    api.dispose();
});

// 拖动事件统一 dispatch 到 root(整个 28px 命中区),而非 4px 的 track。
// 真实用户按下时命中 thumb 或 root 空白区,products 的 pointerdown listener
// 必须在 root 上才能捕获——历史上 listener 误挂在 track 上导致只能点不能拖。
function setupScrubberHost(host) {
    const track = host.querySelector('.text-reader__scrubber-track');
    Object.defineProperty(track, 'getBoundingClientRect', {
        value: () => ({ left: 0, width: 200, right: 200, top: 0, bottom: 10, height: 10 }),
        configurable: true,
    });
    return host.querySelector('.text-reader__scrubber');
}

test('renderScrubber: pointerdown+move+up fires onSeekStart/onSeek/onSeekEnd', () => {
    const host = setupDom();
    const calls = { start: 0, seek: [], end: [] };
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => { calls.start++; },
        onSeek: (p) => { calls.seek.push(p); },
        onSeekEnd: (p) => { calls.end.push(p); },
        formatLabel: () => '',
    });
    const root = setupScrubberHost(host);
    const dispatch = (type, x) => {
        root.dispatchEvent(new window.PointerEvent(type, { clientX: x, bubbles: true }));
    };
    dispatch('pointerdown', 100);  // 50%
    dispatch('pointermove', 150);  // 75%
    dispatch('pointerup', 180);    // 90%
    assert.equal(calls.start, 1);
    assert.deepEqual(calls.seek, [0.5, 0.75]);
    assert.deepEqual(calls.end, [0.9]);
    api.dispose();
});

test('renderScrubber: progress clamped to [0,1] on drag', () => {
    const host = setupDom();
    const seekVals = [];
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: (p) => seekVals.push(p), onSeekEnd: () => {},
        formatLabel: () => '',
    });
    const root = setupScrubberHost(host);
    const dispatch = (type, x) => root.dispatchEvent(new window.PointerEvent(type, { clientX: x, bubbles: true }));
    dispatch('pointerdown', -50);   // <0 → 0
    dispatch('pointermove', 999);   // >width → 1
    assert.deepEqual(seekVals, [0, 1]);
    api.dispose();
});

// 回归测试:pointerdown 落在 thumb 子元素(冒泡到 root)也必须启动拖动。
// 这正是真实浏览器的失败场景——用户按下 14px 的 thumb 而非 4px 的 track。
test('renderScrubber: pointerdown on thumb child still initiates drag', () => {
    const host = setupDom();
    const calls = { start: 0, seek: [], end: [] };
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => { calls.start++; },
        onSeek: (p) => { calls.seek.push(p); },
        onSeekEnd: (p) => { calls.end.push(p); },
        formatLabel: () => '',
    });
    const root = setupScrubberHost(host);
    const thumb = host.querySelector('.text-reader__scrubber-thumb');
    // pointerdown 派发到 thumb(bubbles:true 冒泡到 root)
    thumb.dispatchEvent(new window.PointerEvent('pointerdown', { clientX: 100, bubbles: true }));
    root.dispatchEvent(new window.PointerEvent('pointermove', { clientX: 150, bubbles: true }));
    root.dispatchEvent(new window.PointerEvent('pointerup', { clientX: 180, bubbles: true }));
    assert.equal(calls.start, 1, 'onSeekStart must fire when pressing thumb');
    assert.deepEqual(calls.seek, [0.5, 0.75]);
    assert.deepEqual(calls.end, [0.9]);
    api.dispose();
});

test('renderScrubber: update() syncs thumb to external progress', () => {
    const host = setupDom();
    let prog = 0.2;
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => prog,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: () => {}, onSeekEnd: () => {},
        formatLabel: () => '',
    });
    api.update();
    const thumb = host.querySelector('.text-reader__scrubber-thumb');
    assert.equal(thumb.style.left, '20%');
    prog = 0.8;
    api.update();
    assert.equal(thumb.style.left, '80%');
    api.dispose();
});

test('renderScrubber: dispose removes listeners + clears host', () => {
    const host = setupDom();
    const api = renderScrubber({
        containerEl: host,
        getProgress: () => 0,
        getChapterCount: () => 10,
        onSeekStart: () => {}, onSeek: () => {}, onSeekEnd: () => {},
        formatLabel: () => '',
    });
    api.dispose();
    assert.equal(host.children.length, 0);
});

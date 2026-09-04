import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { state } from './state.js';
import {
    captureScrollAnchor,
    restoreScrollTop,
    rememberScroll,
    recallScroll,
    clearScrollMemory,
    initScrollMemory,
    restoreScrollMemory,
} from './scrollMemory.js';

test('captureScrollAnchor picks first card whose bottom passes container top', () => {
    const cards = [
        { path: '/a', top: -200, bottom: -50 },  // 完全滚出视口上方
        { path: '/b', top: -50, bottom: 120 },   // 部分可见 ← 锚点
        { path: '/c', top: 120, bottom: 300 },
    ];
    const anchor = captureScrollAnchor(cards, 0);
    assert.deepEqual(anchor, { anchorPath: '/b', offset: -50 });
});

test('captureScrollAnchor returns null when all cards above viewport', () => {
    assert.equal(captureScrollAnchor([{ path: '/a', top: -300, bottom: -100 }], 0), null);
});

test('captureScrollAnchor returns null when cards array is empty', () => {
    assert.equal(captureScrollAnchor([], 0), null);
});

test('restoreScrollTop computes target scrollTop', () => {
    // el 距容器内容顶 800，期望视口内位于 offset=-50：scrollTop = 800 - (-50) = 850
    assert.equal(restoreScrollTop({ offsetTop: 800 }, { scrollTop: 0 }, -50), 850);
});

test('remember/recall/clear roundtrip', () => {
    rememberScroll('/m/dir', { anchorPath: '/b', offset: -50 });
    assert.deepEqual(recallScroll('/m/dir'), { anchorPath: '/b', offset: -50 });
    clearScrollMemory('/m/dir');
    assert.equal(recallScroll('/m/dir'), null);
});

test('clearScrollMemory clears all entries when called without args', () => {
    rememberScroll('/dir1', { anchorPath: '/a', offset: 0 });
    rememberScroll('/dir2', { anchorPath: '/b', offset: 10 });
    clearScrollMemory();
    assert.equal(recallScroll('/dir1'), null);
    assert.equal(recallScroll('/dir2'), null);
});

test('restoreScrollMemory returns false if container is null or no memory', () => {
    setupJsdom();
    try {
        const container = document.createElement('div');
        assert.equal(restoreScrollMemory(null, '/unknown'), false);
        assert.equal(restoreScrollMemory(container, '/unknown'), false);
    } finally {
        teardownJsdom();
    }
});

test('restoreScrollMemory returns false if anchor card not found in container', () => {
    setupJsdom();
    try {
        clearScrollMemory();
        rememberScroll('/m/dir', { anchorPath: '/missing.txt', offset: 20 });
        const container = document.createElement('div');
        container.innerHTML = '<div class="media-card" data-path="/other.txt"></div>';
        assert.equal(restoreScrollMemory(container, '/m/dir'), false);
    } finally {
        teardownJsdom();
    }
});

test('restoreScrollMemory scrolls container and returns true when anchor card exists', () => {
    setupJsdom();
    try {
        clearScrollMemory();
        rememberScroll('/m/dir', { anchorPath: '/card-target.txt', offset: -40 });
        const container = document.createElement('div');
        container.scrollTop = 0;
        const card = document.createElement('div');
        card.className = 'media-card';
        card.setAttribute('data-path', '/card-target.txt');
        Object.defineProperty(card, 'offsetTop', { value: 500, configurable: true });
        container.appendChild(card);

        const restored = restoreScrollMemory(container, '/m/dir');
        assert.equal(restored, true);
        // container.scrollTop = el.offsetTop - offset = 500 - (-40) = 540
        assert.equal(container.scrollTop, 540);
    } finally {
        teardownJsdom();
    }
});

test('restoreScrollMemory handles special characters in anchorPath', () => {
    setupJsdom();
    try {
        clearScrollMemory();
        const weirdPath = '/m/dir/special"\' [test].txt';
        rememberScroll('/m/dir', { anchorPath: weirdPath, offset: 10 });
        const container = document.createElement('div');
        const card = document.createElement('div');
        card.className = 'media-card';
        card.setAttribute('data-path', weirdPath);
        Object.defineProperty(card, 'offsetTop', { value: 300, configurable: true });
        container.appendChild(card);

        const restored = restoreScrollMemory(container, '/m/dir');
        assert.equal(restored, true);
        assert.equal(container.scrollTop, 290);
    } finally {
        teardownJsdom();
    }
});

test('initScrollMemory captures anchor on container scroll event', async () => {
    setupJsdom();
    try {
        clearScrollMemory();
        state.currentPath = '/scroll/test';

        const container = document.createElement('div');
        container.getBoundingClientRect = () => ({ top: 100, bottom: 600 });

        const card1 = document.createElement('div');
        card1.className = 'media-card';
        card1.setAttribute('data-path', '/item-1');
        card1.getBoundingClientRect = () => ({ top: 0, bottom: 80 }); // above container top (100)

        const card2 = document.createElement('div');
        card2.className = 'media-card';
        card2.setAttribute('data-path', '/item-2');
        card2.getBoundingClientRect = () => ({ top: 80, bottom: 250 }); // cross container top (100)

        container.appendChild(card1);
        container.appendChild(card2);
        document.body.appendChild(container);

        initScrollMemory(container);

        // Fire scroll event
        container.dispatchEvent(new window.Event('scroll'));

        const recalled = recallScroll('/scroll/test');
        assert.ok(recalled);
        assert.equal(recalled.anchorPath, '/item-2');
        assert.equal(recalled.offset, 80 - 100); // -20
    } finally {
        teardownJsdom();
    }
});

test('initScrollMemory throttles scroll events within 200ms', () => {
    setupJsdom();
    try {
        clearScrollMemory();
        state.currentPath = '/scroll/throttle';

        const container = document.createElement('div');
        container.getBoundingClientRect = () => ({ top: 100, bottom: 600 });

        const card = document.createElement('div');
        card.className = 'media-card';
        card.setAttribute('data-path', '/item-1');
        card.getBoundingClientRect = () => ({ top: 50, bottom: 150 });

        container.appendChild(card);
        document.body.appendChild(container);

        initScrollMemory(container);

        // First scroll: captured
        container.dispatchEvent(new window.Event('scroll'));
        assert.equal(recallScroll('/scroll/throttle')?.anchorPath, '/item-1');

        // Immediately update card and change path
        state.currentPath = '/scroll/throttle-2';
        card.setAttribute('data-path', '/item-changed');

        // Second scroll within 200ms: throttled/ignored
        container.dispatchEvent(new window.Event('scroll'));
        assert.equal(recallScroll('/scroll/throttle-2'), null);
    } finally {
        teardownJsdom();
    }
});


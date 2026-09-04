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

test('restoreScrollTop computes viewport-rect-based target independent of offsetParent', () => {
    // elTopInContainer = el.rectTop - container.rectTop（当前锚点在视口内的偏移）；
    // 目标 scrollTop = 当前滚动 + 视口内偏移 - 捕获 offset。
    // 与 offsetParent/offsetTop 无关（滚动容器链无定位祖先时 offsetTop 相对 body，会偏差）。
    assert.equal(restoreScrollTop(200, 50, -40), 290);
    assert.equal(restoreScrollTop(100, 0, 0), 100);
    assert.equal(restoreScrollTop(0, 300, 30), 270);
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

test('restoreScrollMemory scrolls container via rect delta and returns true when anchor card exists', () => {
    setupJsdom();
    try {
        clearScrollMemory();
        rememberScroll('/m/dir', { anchorPath: '/card-target.txt', offset: -40 });
        const container = document.createElement('div');
        container.scrollTop = 50;
        container.getBoundingClientRect = () => ({ top: 100, bottom: 700 });
        const card = document.createElement('div');
        card.className = 'media-card';
        card.setAttribute('data-path', '/card-target.txt');
        card.getBoundingClientRect = () => ({ top: 300, bottom: 480 });
        container.appendChild(card);

        const restored = restoreScrollMemory(container, '/m/dir');
        assert.equal(restored, true);
        // scrollTop = 50 + (300-100) - (-40) = 290
        assert.equal(container.scrollTop, 290);
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
        container.scrollTop = 20;
        container.getBoundingClientRect = () => ({ top: 100, bottom: 700 });
        const card = document.createElement('div');
        card.className = 'media-card';
        card.setAttribute('data-path', weirdPath);
        card.getBoundingClientRect = () => ({ top: 350, bottom: 500 });
        container.appendChild(card);

        const restored = restoreScrollMemory(container, '/m/dir');
        assert.equal(restored, true);
        // scrollTop = 20 + (350-100) - 10 = 260
        assert.equal(container.scrollTop, 260);
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


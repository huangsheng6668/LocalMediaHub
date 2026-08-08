import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';
import { saveProgress, loadProgress, clearProgress, isCompleted } from './videoProgress.js';

// ---- isCompleted: 纯函数，无需 DOM ----
test('isCompleted: ratio >= 0.95 → true', () => {
    assert.equal(isCompleted(95, 100), true);
    assert.equal(isCompleted(99, 100), true);
    assert.equal(isCompleted(100, 100), true);
});
test('isCompleted: ratio < 0.95 → false', () => {
    assert.equal(isCompleted(94.9, 100), false);
    assert.equal(isCompleted(0, 100), false);
});
test('isCompleted: durationMs <= 0 → false', () => {
    assert.equal(isCompleted(100, 0), false);
    assert.equal(isCompleted(100, -1), false);
});

// ---- localStorage 函数：需 jsdom 暴露 global.localStorage ----
let dom;
beforeEach(() => {
    dom = new JSDOM('<!DOCTYPE html>', { url: 'http://localhost/' });
    global.window = dom.window;
    global.localStorage = dom.window.localStorage;
});
afterEach(() => {
    delete global.localStorage;
    delete global.window;
});

test('saveProgress/loadProgress: 往返', () => {
    saveProgress('movies/a.mkv', { positionMs: 5000, durationMs: 10000 });
    const p = loadProgress('movies/a.mkv');
    assert.ok(p);
    assert.equal(p.positionMs, 5000);
    assert.equal(p.durationMs, 10000);
});

test('loadProgress: 无记录 → null', () => {
    assert.equal(loadProgress('nope.mp4'), null);
});

test('loadProgress: 不同文件 key 隔离', () => {
    saveProgress('a.mp4', { positionMs: 1, durationMs: 10 });
    saveProgress('b.mp4', { positionMs: 2, durationMs: 20 });
    assert.equal(loadProgress('a.mp4').positionMs, 1);
    assert.equal(loadProgress('b.mp4').positionMs, 2);
});

test('clearProgress: 删除后 load → null', () => {
    saveProgress('c.mp4', { positionMs: 9, durationMs: 10 });
    clearProgress('c.mp4');
    assert.equal(loadProgress('c.mp4'), null);
});

test('saveProgress: 写入 video_progress: 前缀且含 updatedAt', () => {
    saveProgress('d.mp4', { positionMs: 5, durationMs: 10 });
    const raw = global.localStorage.getItem('video_progress:d.mp4');
    assert.ok(raw);
    const parsed = JSON.parse(raw);
    assert.equal(parsed.positionMs, 5);
    assert.equal(parsed.durationMs, 10);
    assert.equal(typeof parsed.updatedAt, 'number');
});

test('loadProgress: JSON 损坏 → null', () => {
    global.localStorage.setItem('video_progress:e.mp4', '{not json');
    assert.equal(loadProgress('e.mp4'), null);
});

test('loadProgress: 缺 positionMs 字段 → null（schema 校验）', () => {
    global.localStorage.setItem('video_progress:f.mp4', JSON.stringify({ durationMs: 10 }));
    assert.equal(loadProgress('f.mp4'), null);
});

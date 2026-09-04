import { test } from 'node:test';
import assert from 'node:assert/strict';
import { applyListFilters, badgeHtmlFor, computeReportPayload, runWithConcurrency } from './library.js';

const decos = {
    states: {
        '/m/a.txt': { status: 'reading', percent: 42.5, last_read_at: 1 },
        '/m/b.txt': { status: 'finished', percent: 100, last_read_at: 2 },
        '/m/c.txt': { status: 'unread', percent: 0, last_read_at: 3 },
    },
    favorites: ['/m/b.txt', '/m/comics'],
};
const folders = [{ path: '/m/comics', name: 'comics' }, { path: '/m/other', name: 'other' }];
const files = [
    { path: '/m/a.txt', name: 'a', media_type: 'text' },
    { path: '/m/b.txt', name: 'b', media_type: 'text' },
    { path: '/m/c.txt', name: 'c', media_type: 'text' },
    { path: '/m/d.txt', name: 'd', media_type: 'text' }, // 无行 = 未读
    { path: '/m/v.mp4', name: 'v', media_type: 'video' },
];

test('applyListFilters: no filter passes through', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: null });
    assert.equal(r.folders.length, 2);
    assert.equal(r.files.length, 5);
});

test('applyListFilters: favoritesOnly matches files and folders by path', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: true, statusFilter: null });
    assert.deepEqual(r.folders.map(f => f.path), ['/m/comics']);
    assert.deepEqual(r.files.map(f => f.path), ['/m/b.txt']);
});

test('applyListFilters: statusFilter keeps only text cards matching, hides folders/videos', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: 'reading' });
    assert.equal(r.folders.length, 0);
    assert.deepEqual(r.files.map(f => f.path), ['/m/a.txt']);
    const u = applyListFilters(folders, files, decos, { favoritesOnly: false, statusFilter: 'unread' });
    assert.deepEqual(u.files.map(f => f.path).sort(), ['/m/c.txt', '/m/d.txt']);
});

test('applyListFilters: both filters intersect', () => {
    const r = applyListFilters(folders, files, decos, { favoritesOnly: true, statusFilter: 'finished' });
    assert.deepEqual(r.files.map(f => f.path), ['/m/b.txt']);
    assert.equal(r.folders.length, 0);
});

test('applyListFilters: empty decorations tolerated', () => {
    const r = applyListFilters(folders, files, null, { favoritesOnly: true, statusFilter: null });
    assert.equal(r.folders.length + r.files.length, 0);
});

test('badgeHtmlFor', () => {
    assert.equal(badgeHtmlFor('unread', 0), '');
    assert.equal(badgeHtmlFor(null, 0), '');
    assert.ok(badgeHtmlFor('reading', 42.5).includes('读到 42.5%'));
    assert.ok(badgeHtmlFor('reading', 0).includes('>读过<'));
    assert.ok(badgeHtmlFor('finished', 100).includes('已读完'));
    assert.ok(badgeHtmlFor('finished', 100).includes('card-badge--finished'));
});

test('computeReportPayload', () => {
    const p = computeReportPayload({ chapterIndex: 2, paraIndex: 5, chapterParaCount: 10, totalChapters: 5, atChapterEnd: false });
    assert.equal(p.percent, 50); // (2 + 0.5) / 5
    assert.equal(p.finished, false);
    const f = computeReportPayload({ chapterIndex: 4, paraIndex: 9, chapterParaCount: 10, totalChapters: 5, atChapterEnd: true });
    assert.equal(f.finished, true); // 末章 + 章尾
    assert.equal(f.percent, 100);   // clamp
    const zero = computeReportPayload({ chapterIndex: 0, paraIndex: 0, chapterParaCount: 0, totalChapters: 0, atChapterEnd: false });
    assert.equal(zero.percent, 0);  // max(1,..) 防除零
});

test('runWithConcurrency: limits concurrency and collects all results in order', async () => {
    let currentActive = 0;
    let maxActive = 0;
    const taskCount = 10;
    const limit = 3;

    const taskFactories = Array.from({ length: taskCount }, (_, i) => async () => {
        currentActive++;
        if (currentActive > maxActive) {
            maxActive = currentActive;
        }
        await new Promise(r => setTimeout(r, 20));
        currentActive--;
        return `result-${i}`;
    });

    const results = await runWithConcurrency(taskFactories, limit);

    assert.ok(maxActive <= limit, `maxActive (${maxActive}) should be <= limit (${limit})`);
    assert.ok(maxActive > 1, `maxActive (${maxActive}) should be > 1 to test concurrency`);
    assert.equal(results.length, taskCount);
    assert.deepEqual(results, Array.from({ length: taskCount }, (_, i) => `result-${i}`));
});

test('runWithConcurrency: handles empty task list', async () => {
    const results = await runWithConcurrency([], 3);
    assert.deepEqual(results, []);
});

test('runWithConcurrency: tolerates task rejections and continues', async () => {
    const taskFactories = [
        async () => 'ok-1',
        async () => { throw new Error('boom'); },
        async () => 'ok-3',
    ];
    const results = await runWithConcurrency(taskFactories, 2);
    assert.deepEqual(results, ['ok-1', undefined, 'ok-3']);
});

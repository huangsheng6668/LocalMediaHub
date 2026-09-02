import { test } from 'node:test';
import assert from 'node:assert/strict';
import { coverGradientClass, relativeTime } from './bookshelf.js';

test('coverGradientClass: deterministic and in g1..g8', () => {
    const a = coverGradientClass('三体');
    assert.equal(a, coverGradientClass('三体'));
    assert.match(a, /^bookshelf-card__cover--g[1-8]$/);
    for (const t of ['a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i']) {
        assert.match(coverGradientClass(t), /^bookshelf-card__cover--g[1-8]$/);
    }
});

test('relativeTime boundaries', () => {
    const now = Date.now();
    assert.equal(relativeTime(now - 30 * 1000), '刚刚');
    assert.equal(relativeTime(now - 5 * 60 * 1000), '5 分钟前');
    assert.equal(relativeTime(now - 3 * 3600 * 1000), '3 小时前');
    assert.equal(relativeTime(now - 2 * 24 * 3600 * 1000), '2 天前');
    assert.equal(relativeTime(now - 40 * 24 * 3600 * 1000), '1 个月前');
    assert.equal(relativeTime(now - 400 * 24 * 3600 * 1000), '1 年前');
    assert.equal(relativeTime(0), '');
});

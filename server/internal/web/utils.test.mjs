import test from 'node:test';
import assert from 'node:assert/strict';
import { computeInitialRenderCount } from './utils.js';

test('computeInitialRenderCount renders the first chunk (or everything when smaller)', () => {
    assert.equal(computeInitialRenderCount(2000, 120, -1), 120);
    assert.equal(computeInitialRenderCount(50, 120, -1), 50);
    assert.equal(computeInitialRenderCount(120, 120, -1), 120);
    assert.equal(computeInitialRenderCount(0, 120, -1), 0);
});

test('computeInitialRenderCount extends to cover a scroll-memory anchor', () => {
    assert.equal(computeInitialRenderCount(2000, 120, 40), 120);    // inside first chunk
    assert.equal(computeInitialRenderCount(2000, 120, 700), 701);   // deeper anchor
    assert.equal(computeInitialRenderCount(2000, 120, 1999), 2000); // last item
    assert.equal(computeInitialRenderCount(2000, 120, 5000), 2000); // out of range clamps
    assert.equal(computeInitialRenderCount(2000, 120, -1), 120);    // no anchor
});

test('computeInitialRenderCount tolerates degenerate input', () => {
    assert.equal(computeInitialRenderCount(-5, 120, 10), 0);
    assert.equal(computeInitialRenderCount(NaN, 120, -1), 0);
    assert.equal(computeInitialRenderCount(100, 0, -1), 0);
    assert.equal(computeInitialRenderCount(100, -10, -1), 0);
    assert.equal(computeInitialRenderCount(100, 120, NaN), 100);
});

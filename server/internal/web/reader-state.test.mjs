import { test } from 'node:test';
import assert from 'node:assert/strict';
import { state, setCurrentIdx, resetState } from './reader-state.js';
import { on, EVT } from './bus.js';

test('setCurrentIdx updates state and emits chapter:changed', () => {
    resetState();
    let received;
    const unsub = on(EVT.CHAPTER_CHANGED, (p) => { received = p; });
    setCurrentIdx(3);
    assert.equal(state.currentIdx, 3);
    assert.deepEqual(received, { idx: 3 });
    unsub();
});

test('setCurrentIdx no-op when idx unchanged does not emit', () => {
    resetState();
    state.currentIdx = 2;
    let emitCount = 0;
    const unsub = on(EVT.CHAPTER_CHANGED, () => { emitCount++; });
    setCurrentIdx(2);
    assert.equal(emitCount, 0);
    unsub();
});

test('setCurrentIdx no-op for out-of-range negative', () => {
    resetState();
    state.currentIdx = 1;
    state.chapterCount = 5;
    setCurrentIdx(-1);
    assert.equal(state.currentIdx, 1); // unchanged
});

test('resetState restores defaults', () => {
    state.currentIdx = 9;
    state.book = { title: 'x' };
    resetState();
    assert.equal(state.currentIdx, 0);
    assert.equal(state.book, null);
    assert.equal(state.chapterCount, 0);
});

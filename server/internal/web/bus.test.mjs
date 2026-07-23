import { test } from 'node:test';
import assert from 'node:assert/strict';
import { on, emit, off, EVT } from './bus.js';

test('on/emit: handler receives payload', () => {
    let received;
    on(EVT.CHAPTER_CHANGED, (p) => { received = p; });
    emit(EVT.CHAPTER_CHANGED, { idx: 2 });
    assert.deepEqual(received, { idx: 2 });
});

test('on returns unsub that stops further calls', () => {
    let count = 0;
    const unsub = on(EVT.CHAPTER_CHANGED, () => { count++; });
    emit(EVT.CHAPTER_CHANGED, {});
    unsub();
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(count, 1);
});

test('multiple handlers all fire', () => {
    let a = 0, b = 0;
    on(EVT.CHAPTER_CHANGED, () => { a++; });
    on(EVT.CHAPTER_CHANGED, () => { b++; });
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(a, 1);
    assert.equal(b, 1);
});

test('emit with no subscribers does not throw', () => {
    assert.doesNotThrow(() => emit('unheard', {}));
});

test('handler exception does not block siblings', () => {
    let siblingCalled = false;
    on(EVT.CHAPTER_CHANGED, () => { throw new Error('boom'); });
    on(EVT.CHAPTER_CHANGED, () => { siblingCalled = true; });
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(siblingCalled, true);
});

test('off removes a specific handler', () => {
    let count = 0;
    const h = () => { count++; };
    on(EVT.CHAPTER_CHANGED, h);
    off(EVT.CHAPTER_CHANGED, h);
    emit(EVT.CHAPTER_CHANGED, {});
    assert.equal(count, 0);
});

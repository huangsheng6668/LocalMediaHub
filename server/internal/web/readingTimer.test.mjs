import test from 'node:test';
import assert from 'node:assert/strict';
import { createReadingTimer } from './readingTimer.js';

test('takeDelta accumulates between start/stop and resets', () => {
    let t = 1000;
    const timer = createReadingTimer(() => t);
    timer.start();
    t = 31000;
    assert.equal(timer.takeDelta(), 30);
    t = 61000;
    timer.stop();
    assert.equal(timer.takeDelta(), 30);
    assert.equal(timer.takeDelta(), 0);
});

test('takeDelta while running keeps the clock alive', () => {
    let t = 0;
    const timer = createReadingTimer(() => t);
    timer.start();
    t = 60000;
    assert.equal(timer.takeDelta(), 60);
    t = 90000;
    assert.equal(timer.takeDelta(), 30);
});

test('start/stop are idempotent', () => {
    let t = 0;
    const timer = createReadingTimer(() => t);
    timer.start();
    timer.start();
    t = 5000;
    timer.stop();
    timer.stop();
    assert.equal(timer.takeDelta(), 5);
});

test('isRunning reflects session state', () => {
    const timer = createReadingTimer(() => 0);
    assert.equal(timer.isRunning(), false);
    timer.start();
    assert.equal(timer.isRunning(), true);
    timer.stop();
    assert.equal(timer.isRunning(), false);
});

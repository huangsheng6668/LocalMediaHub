import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nextSpeed, wheelToVolume } from './videoHelpers.js';

const SPEEDS = [0.75, 1, 1.25, 1.5, 2, 3];

test('nextSpeed: 1 → 1.25', () => {
    assert.equal(nextSpeed(1, SPEEDS), 1.25);
});
test('nextSpeed: 末档 3 循环回首档 0.75', () => {
    assert.equal(nextSpeed(3, SPEEDS), 0.75);
});
test('nextSpeed: 0.75 → 1', () => {
    assert.equal(nextSpeed(0.75, SPEEDS), 1);
});
test('nextSpeed: 当前值不在档位 → 从 1x 的下一档开始（容错）', () => {
    // indexOf(1)=1, (1+1)%6=2 → SPEEDS[2]=1.25
    assert.equal(nextSpeed(1.7, SPEEDS), 1.25);
});

test('wheelToVolume: 向上滚（deltaY<0）→ +step', () => {
    assert.equal(wheelToVolume(0.5, -100, 0.05), 0.55);
});
test('wheelToVolume: 向下滚（deltaY>0）→ -step', () => {
    assert.equal(wheelToVolume(0.5, 100, 0.05), 0.45);
});
test('wheelToVolume: 上限钳制 1', () => {
    assert.equal(wheelToVolume(0.98, -100, 0.05), 1);
});
test('wheelToVolume: 下限钳制 0', () => {
    assert.equal(wheelToVolume(0.02, 100, 0.05), 0);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';

test('defaults include new typography fields', () => {
    const s = migrateV1toV2(null);
    assert.equal(s.letterSpacing, 0);
    assert.equal(s.customBg, null);
    assert.equal(s.customFg, null);
    assert.equal(s.customMuted, null);
});

test('migrate keeps valid letterSpacing and clamps out-of-range', () => {
    assert.equal(migrateV1toV2({ letterSpacing: 0.25 }).letterSpacing, 0.25);
    assert.equal(migrateV1toV2({ letterSpacing: 5 }).letterSpacing, 1);
    assert.equal(migrateV1toV2({ letterSpacing: -1 }).letterSpacing, 0);
    assert.equal(migrateV1toV2({ letterSpacing: 'x' }).letterSpacing, 0);
});

test('migrate keeps valid custom colors and drops invalid', () => {
    const s = migrateV1toV2({ customBg: '#ABCDEF', customFg: '#1a2b3c', customMuted: 'red' });
    assert.equal(s.customBg, '#ABCDEF');
    assert.equal(s.customFg, '#1a2b3c');
    assert.equal(s.customMuted, null);
});

test('migrate keeps CUSTOM theme', () => {
    assert.equal(migrateV1toV2({ theme: 'CUSTOM' }).theme, 'CUSTOM');
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';
import * as readerPrefs from './readerPrefs.js';

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

test('THEME_LABELS is frozen and aligned with THEME_PRESETS', () => {
    assert.ok(readerPrefs.THEME_LABELS, 'THEME_LABELS must be exported');
    assert.ok(Object.isFrozen(readerPrefs.THEME_LABELS));
    const presetKeys = Object.keys(readerPrefs.THEME_PRESETS);
    const labelKeys = Object.keys(readerPrefs.THEME_LABELS);
    assert.deepEqual([...labelKeys].sort(), [...presetKeys].sort());
    for (const v of Object.values(readerPrefs.THEME_LABELS)) {
        assert.equal(typeof v, 'string');
        assert.ok(v.length > 0);
    }
});

test('FONT_FAMILIES all include cross-platform emoji font fallback', () => {
    assert.ok(readerPrefs.EMOJI_FONT_FALLBACK, 'EMOJI_FONT_FALLBACK must be exported');
    assert.ok(readerPrefs.EMOJI_FONT_FALLBACK.includes('Segoe UI Emoji'));
    assert.ok(readerPrefs.EMOJI_FONT_FALLBACK.includes('Apple Color Emoji'));
    assert.ok(readerPrefs.EMOJI_FONT_FALLBACK.includes('Noto Color Emoji'));

    for (const [name, stack] of Object.entries(readerPrefs.FONT_FAMILIES)) {
        assert.ok(typeof stack === 'string', `${name} font family stack must be string`);
        assert.ok(stack.includes('Segoe UI Emoji'), `${name} must include Segoe UI Emoji`);
        assert.ok(stack.includes('Apple Color Emoji'), `${name} must include Apple Color Emoji`);
        assert.ok(stack.includes('Noto Color Emoji'), `${name} must include Noto Color Emoji`);
    }
});


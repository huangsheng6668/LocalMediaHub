import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrateV1toV2 } from './readerPrefs.js';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { state, resetState } from './reader-state.js';
import { renderSettings } from './reader-settings.js';
import { DEFAULT_SETTINGS } from './readerPrefs.js';

// readerPrefs.saveSettings calls window.dispatchEvent(new CustomEvent(...)).
// Node's global Event/CustomEvent are not jsdom-branded, so jsdom rejects them.
// Expose jsdom's constructors globally for the duration of each dialog test.
// （Polyfill 块逐字复制自 reader-settings.test.mjs。）
function setupDialog() {
    setupJsdom();
    global.Event = global.window.Event;
    global.CustomEvent = global.window.CustomEvent;
}
function teardownDialog() {
    delete global.Event;
    delete global.CustomEvent;
    teardownJsdom();
}

function mountDialog(settings) {
    resetState();
    state.settings = { ...DEFAULT_SETTINGS, ...settings };
    const container = document.createElement('div');
    document.body.appendChild(container);
    const api = renderSettings(container);
    return { container, api, dialog: container.querySelector('#reader-settings-dialog') };
}

const VALID = ['NONE', 'COVER', 'SIMULATION', 'DRAG'];

test('default pageTurnStyle is NONE', () => {
    assert.equal(migrateV1toV2(null).pageTurnStyle, 'NONE');
});

test('migrate keeps valid pageTurnStyle values', () => {
    for (const v of VALID) {
        assert.equal(migrateV1toV2({ pageTurnStyle: v }).pageTurnStyle, v);
    }
});

test('migrate drops invalid pageTurnStyle to NONE', () => {
    assert.equal(migrateV1toV2({ pageTurnStyle: 'BOGUS' }).pageTurnStyle, 'NONE');
    assert.equal(migrateV1toV2({ pageTurnStyle: 123 }).pageTurnStyle, 'NONE');
});

test('migrate preserves pageTurnStyle when other fields present', () => {
    const s = migrateV1toV2({ pageTurnStyle: 'DRAG', theme: 'NIGHT' });
    assert.equal(s.pageTurnStyle, 'DRAG');
    assert.equal(s.theme, 'NIGHT');
});

test('dialog renders 4 pageTurnStyle radios, CHAPTER-enabled', () => {
    setupDialog();
    try {
        const { api, dialog } = mountDialog({ readingMode: 'chapter' });
        for (const v of ['NONE', 'COVER', 'SIMULATION', 'DRAG']) {
            const r = dialog.querySelector(`input[name="pageTurnStyle"][value="${v}"]`);
            assert.ok(r, `missing radio for ${v}`);
            assert.equal(r.disabled, false, `${v} should be enabled in chapter mode`);
        }
        api.dispose();
    } finally {
        teardownDialog();
    }
});

test('pageTurnStyle radios disabled in scroll mode', () => {
    setupDialog();
    try {
        const { api, dialog } = mountDialog({ readingMode: 'scroll' });
        for (const v of ['NONE', 'COVER', 'SIMULATION', 'DRAG']) {
            const r = dialog.querySelector(`input[name="pageTurnStyle"][value="${v}"]`);
            assert.ok(r.disabled, `${v} should be disabled in scroll mode`);
        }
        api.dispose();
    } finally {
        teardownDialog();
    }
});

test('selecting COVER radio saves pageTurnStyle', () => {
    setupDialog();
    try {
        const { api, dialog } = mountDialog({ readingMode: 'chapter' });
        const radio = dialog.querySelector('input[name="pageTurnStyle"][value="COVER"]');
        radio.checked = true;
        radio.dispatchEvent(new Event('change', { bubbles: true }));
        const saved = JSON.parse(localStorage.getItem('reader_settings'));
        assert.equal(saved.pageTurnStyle, 'COVER');
        api.dispose();
    } finally {
        teardownDialog();
    }
});

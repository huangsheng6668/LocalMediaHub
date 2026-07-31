import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';
import { state, resetState } from './reader-state.js';
import { renderSettings } from './reader-settings.js';
import { DEFAULT_SETTINGS } from './readerPrefs.js';

// readerPrefs.saveSettings calls window.dispatchEvent(new CustomEvent(...)).
// Node's global Event/CustomEvent are not jsdom-branded, so jsdom rejects them.
// Expose jsdom's constructors globally for the duration of each test.
function setup() {
    setupJsdom();
    global.Event = global.window.Event;
    global.CustomEvent = global.window.CustomEvent;
}
function teardown() {
    delete global.Event;
    delete global.CustomEvent;
    teardownJsdom();
}

function mount(settings) {
    resetState();
    state.settings = { ...DEFAULT_SETTINGS, ...settings };
    // saveAndEmit persists from localStorage (readerPrefs.saveSettings merges
    // getSettings()), so seed localStorage with the same settings we put in
    // state — otherwise partial saves regress theme/custom fields to defaults.
    localStorage.setItem('reader_settings', JSON.stringify(state.settings));
    const container = document.createElement('div');
    document.body.appendChild(container);
    const api = renderSettings(container);
    return { container, api, dialog: container.querySelector('#reader-settings-dialog') };
}

test('dialog renders letterSpacing slider, new fonts and CUSTOM theme', () => {
    setup();
    try {
        const { api, dialog } = mount({});
        assert.ok(dialog.querySelector('input[name="letterSpacingSlider"]'));
        assert.ok(dialog.querySelector('input[name="fontFamily"][value="HEITI"]'));
        assert.ok(dialog.querySelector('input[name="fontFamily"][value="MONO"]'));
        assert.ok(dialog.querySelector('input[name="theme"][value="CUSTOM"]'));
        assert.equal(dialog.querySelector('.reader-settings__custom-colors').hidden, true);
        api.dispose();
    } finally {
        teardown();
    }
});

test('CUSTOM theme reveals color section; switching away hides it', () => {
    setup();
    try {
        const { api, dialog } = mount({ theme: 'CUSTOM' });
        const colors = dialog.querySelector('.reader-settings__custom-colors');
        assert.equal(colors.hidden, false);
        const dayRadio = dialog.querySelector('input[name="theme"][value="DAY"]');
        dayRadio.checked = true;
        dayRadio.dispatchEvent(new window.Event('change', { bubbles: true }));
        assert.equal(colors.hidden, true);
        api.dispose();
    } finally {
        teardown();
    }
});

test('letterSpacing slider saves float; customBg saves hex', () => {
    setup();
    try {
        const { api, dialog } = mount({ theme: 'CUSTOM' });
        const slider = dialog.querySelector('input[name="letterSpacingSlider"]');
        slider.value = '0.25';
        slider.dispatchEvent(new window.Event('change', { bubbles: true }));
        const bg = dialog.querySelector('input[name="customBg"]');
        bg.value = '#123456';
        bg.dispatchEvent(new window.Event('change', { bubbles: true }));
        const saved = JSON.parse(localStorage.getItem('reader_settings'));
        assert.equal(saved.letterSpacing, 0.25);
        assert.equal(saved.customBg, '#123456');
        assert.equal(saved.theme, 'CUSTOM');
        api.dispose();
    } finally {
        teardown();
    }
});

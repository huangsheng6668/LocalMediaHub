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

// Task 4: dialog 可访问名 — native <dialog> 需 aria-labelledby 指向标题元素。
test('settings dialog is labelled', () => {
    setup();
    try {
        const { api, dialog } = mount({});
        const labelledBy = dialog.getAttribute('aria-labelledby');
        assert.ok(labelledBy, 'dialog needs aria-labelledby');
        assert.ok(document.getElementById(labelledBy), 'aria-labelledby must point at the header');
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

// light dismiss：rAF（测试环境 stub 为 setTimeout 0）后监听才挂上。
const tick = () => new Promise((r) => setTimeout(r, 0));

test('click targeting the <dialog> itself (backdrop) closes open dialog', async () => {
    setup();
    try {
        const { api, dialog } = mount({});
        api.open();
        assert.equal(dialog.open, true);
        await tick();
        // showModal 环境下点击 ::backdrop 的事件 target 即 <dialog> 自身
        dialog.dispatchEvent(new window.Event('click', { bubbles: true }));
        assert.equal(dialog.open, false, 'backdrop-target click should close the dialog');
        api.dispose();
    } finally {
        teardown();
    }
});

test('click on a control inside the dialog keeps it open', async () => {
    setup();
    try {
        const { api, dialog } = mount({});
        api.open();
        await tick();
        const radio = dialog.querySelector('input[name="fontFamily"][value="SERIF"]');
        radio.dispatchEvent(new window.Event('click', { bubbles: true }));
        assert.equal(dialog.open, true, 'inside click must not close the dialog');
        api.dispose();
    } finally {
        teardown();
    }
});

test('click outside the dialog closes it (non-modal fallback)', async () => {
    setup();
    try {
        const { api, dialog } = mount({});
        api.open();
        await tick();
        const outside = document.querySelector('#view-reader');
        outside.dispatchEvent(new window.Event('click', { bubbles: true }));
        assert.equal(dialog.open, false, 'outside click should close the dialog');
        api.dispose();
    } finally {
        teardown();
    }
});

test('the click that opens the dialog does not immediately re-close it', async () => {
    setup();
    try {
        const { api, dialog } = mount({});
        const btn = document.createElement('button');
        btn.textContent = 'Aa';
        document.body.appendChild(btn);
        btn.addEventListener('click', () => api.open());
        btn.dispatchEvent(new window.Event('click', { bubbles: true }));
        assert.equal(dialog.open, true, 'dialog must survive the opening click');
        await tick(); // 监听在打开 click 完成后才挂上
        assert.equal(dialog.open, true, 'listener must not react to the opening click');
        // 用非打开按钮的外部元素：点打开按钮本身在 fallback 下会先关再开（open 总是执行）
        document.querySelector('#view-reader').dispatchEvent(new window.Event('click', { bubbles: true }));
        assert.equal(dialog.open, false, 'a later outside click closes');
        btn.remove();
        api.dispose();
    } finally {
        teardown();
    }
});

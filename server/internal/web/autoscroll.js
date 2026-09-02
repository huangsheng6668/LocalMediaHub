// 自动滚动面板：播放/暂停/调速 rAF 循环。
// 从 textReader.js 原自动滚动逻辑提取。
import { state } from './reader-state.js';
import * as readerPrefs from './readerPrefs.js';

export function renderAutoscroll({ panelEl, playBtn, minusBtn, plusBtn, speedValEl }) {
    let rafId = null;
    let running = false;

    function applySpeed() {
        const s = state.settings;
        if (s && speedValEl) speedValEl.textContent = String(s.autoScrollSpeed);
    }

    function loop() {
        if (!running) return;
        const s = state.settings;
        if (s && state.els && state.els.content) {
            const pxPerFrame = (s.autoScrollSpeed || 0) * 0.5;
            state.els.content.scrollTop += pxPerFrame;
        }
        rafId = requestAnimationFrame(loop);
    }

// Dual-span hidden-toggle icon (same pattern as videoPlayer.js setControlIcon):
// the button markup ships static <span data-icon="play|pause"> SVGs and this
// helper flips `hidden` — replaces the old ⏸/▶ text glyph writes.
function setPlayIcon(name) {
    if (!playBtn) return;
    playBtn.querySelectorAll('[data-icon]').forEach(el => {
        el.hidden = el.dataset.icon !== name;
    });
}

function start() {
    if (running) return;
    running = true;
    if (panelEl) panelEl.classList.remove('text-reader__autoscroll-panel--hidden');
    setPlayIcon('pause');
    rafId = requestAnimationFrame(loop);
}

function stop() {
    running = false;
    if (rafId !== null) cancelAnimationFrame(rafId);
    rafId = null;
    if (panelEl) panelEl.classList.add('text-reader__autoscroll-panel--hidden');
    setPlayIcon('play');
}

    function toggle() {
        running ? stop() : start();
    }

    if (playBtn) playBtn.addEventListener('click', toggle);
    if (minusBtn) minusBtn.addEventListener('click', () => adjustSpeed(-1));
    if (plusBtn) plusBtn.addEventListener('click', () => adjustSpeed(1));

    function adjustSpeed(delta) {
        const s = state.settings;
        if (!s) return;
        const next = Math.max(1, (s.autoScrollSpeed || 5) + delta);
        s.autoScrollSpeed = next;
        applySpeed();
        // Persist + broadcast so the settings dialog slider stays in sync and
        // the value survives a reload (previously it was memory-only).
        readerPrefs.saveSettings({ autoScrollSpeed: next });
    }

    return { start, stop, toggle, applySpeed, dispose: stop };
}

// 自动滚动面板：播放/暂停/调速 rAF 循环。
// 从 textReader.js 原自动滚动逻辑提取。
import { state } from './reader-state.js';

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

    function start() {
        if (running) return;
        running = true;
        if (panelEl) panelEl.classList.remove('text-reader__autoscroll-panel--hidden');
        if (playBtn) playBtn.textContent = '⏸';
        rafId = requestAnimationFrame(loop);
    }

    function stop() {
        running = false;
        if (rafId !== null) cancelAnimationFrame(rafId);
        rafId = null;
        if (panelEl) panelEl.classList.add('text-reader__autoscroll-panel--hidden');
        if (playBtn) playBtn.textContent = '▶';
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
        s.autoScrollSpeed = Math.max(1, (s.autoScrollSpeed || 5) + delta);
        applySpeed();
    }

    return { start, stop, toggle, applySpeed, dispose: stop };
}

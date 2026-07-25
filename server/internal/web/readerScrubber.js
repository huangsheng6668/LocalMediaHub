// 阅读器全书进度拖动条。两种模式、两端口径统一:
// progress ∈ [0,1] 表示全书进度;targetIdx = round(progress * (chapterCount-1))。
// 松手才跳转(onSeekEnd),拖动中(onSeek)只更新本地 thumb + label 预览。

// progress → 目标章节索引,四舍五入,clamp 到 [0, chapterCount-1]。
export function progressToChapterIndex(progress, chapterCount) {
    if (chapterCount <= 1) return 0;
    const denom = chapterCount - 1;
    const raw = Math.round(progress * denom);
    return Math.min(denom, Math.max(0, raw));
}

// 章节索引 → 全书进度,clamp 到 [0,1]。
export function chapterIndexToProgress(idx, chapterCount) {
    if (chapterCount <= 1) return 0;
    const denom = chapterCount - 1;
    return Math.min(1, Math.max(0, idx / denom));
}

// 追加到 readerScrubber.js 末尾

// 创建并挂载横向可拖动进度条。
// onSeek(p):拖动中,仅更新本地预览,不做加载。
// onSeekEnd(p):松手,执行跳转。
// 返回 { update(), dispose() }。
export function renderScrubber({
    containerEl, getProgress, getChapterCount,
    onSeekStart, onSeek, onSeekEnd, formatLabel,
}) {
    const root = document.createElement('div');
    root.className = 'text-reader__scrubber';
    // XSS-SAFE: 纯字面量骨架,label 文字通过 textContent 设置
    root.innerHTML = `
        <div class="text-reader__scrubber-track"></div>
        <div class="text-reader__scrubber-thumb"></div>
        <span class="text-reader__scrubber-label"></span>
    `;
    containerEl.appendChild(root);
    const track = root.querySelector('.text-reader__scrubber-track');
    const thumb = root.querySelector('.text-reader__scrubber-thumb');
    const label = root.querySelector('.text-reader__scrubber-label');

    let isDragging = false;
    let dragProgress = 0;

    function progressFromClientX(clientX) {
        const rect = track.getBoundingClientRect();
        if (rect.width <= 0) return 0;
        return Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    }

    function setThumb(progress) {
        thumb.style.left = `${Math.round(progress * 100)}%`;
    }

    function setLabel(progress, dragging) {
        const text = formatLabel ? formatLabel(progress, dragging) : '';
        if (typeof text === 'string') label.textContent = text;
    }

    function onPointerDown(e) {
        isDragging = true;
        dragProgress = progressFromClientX(e.clientX);
        try { root.setPointerCapture(e.pointerId); } catch (_) {}
        setThumb(dragProgress);
        setLabel(dragProgress, true);
        if (onSeekStart) onSeekStart();
        if (onSeek) onSeek(dragProgress);
        e.preventDefault();
    }
    function onPointerMove(e) {
        if (!isDragging) return;
        dragProgress = progressFromClientX(e.clientX);
        setThumb(dragProgress);
        setLabel(dragProgress, true);
        if (onSeek) onSeek(dragProgress);
    }
    function onPointerUp(e) {
        if (!isDragging) return;
        dragProgress = progressFromClientX(e.clientX);
        isDragging = false;
        try { root.releasePointerCapture(e.pointerId); } catch (_) {}
        if (onSeekEnd) onSeekEnd(dragProgress);
    }

    track.addEventListener('pointerdown', onPointerDown);
    root.addEventListener('pointermove', onPointerMove);
    root.addEventListener('pointerup', onPointerUp);
    root.addEventListener('pointercancel', onPointerUp);

    function update() {
        if (isDragging) return;  // 拖动中不覆盖本地 thumb
        const p = getProgress();
        setThumb(p);
        setLabel(p, false);
    }

    function dispose() {
        track.removeEventListener('pointerdown', onPointerDown);
        root.removeEventListener('pointermove', onPointerMove);
        root.removeEventListener('pointerup', onPointerUp);
        root.removeEventListener('pointercancel', onPointerUp);
        if (root.parentNode) root.parentNode.removeChild(root);
    }

    update();
    return { update, dispose };
}

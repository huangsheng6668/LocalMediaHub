// 进度计算 + 滚动模式章节推断。依赖 state 单例。
// 从 textReader.js 原 updateProgressUI + onContentScroll 章节推断逻辑提取。
// 修复 3：章节推断阈值从 <=100 放宽到 <=120，提取为可测纯函数。
import { state } from './reader-state.js';

// 纯函数：根据各章节 section 的 bounding rect 推断当前活动章节。
// sections: [{ top, bottom, dataset: { chapterIndex } }]
// containerTop: 容器顶部 y 坐标
// fallbackIdx: 无命中时返回的回退值
// 约定：返回最后一个满足 top - containerTop <= 120 的章节（即"已滚到最下方的可见章节"）。
export function detectActiveChapterOnScroll(sections, containerTop, fallbackIdx) {
    let active = fallbackIdx;
    let hit = false;
    for (const sec of sections) {
        if (sec.top - containerTop <= 120) {
            const idx = parseInt(sec.dataset.chapterIndex, 10);
            if (!Number.isNaN(idx)) {
                active = idx;
                hit = true;
            }
        }
    }
    return hit ? active : fallbackIdx;
}

// 纯函数：计算百分比，clamp 到 [0, 100]。max<=0 时返回 0。
export function computePercent(value, max) {
    if (max <= 0) return 0;
    const pct = Math.round((value / max) * 100);
    return Math.min(100, Math.max(0, pct));
}

// 更新进度条 + 进度文本 UI。从 state 读 currentIdx/chapterCount/els。
// 分章模式：按内容区 scrollTop 算章内百分比。
// 滚动模式：按 currentIdx + 章内 fraction 算全书百分比。
export function updateProgressUI() {
    const { els, currentIdx, chapterCount, settings } = state;
    if (!els || !state.book || !state.book.chapters || chapterCount === 0) return;

    const isScrollMode = settings && settings.readingMode === 'scroll';
    let percent = 0;

    if (isScrollMode) {
        const activeSec = els.content.querySelector(
            `.text-reader__chapter-section[data-chapter-index="${currentIdx}"]`
        );
        let chapterFraction = 0;
        if (activeSec) {
            const rect = activeSec.getBoundingClientRect();
            const containerTop = els.content.getBoundingClientRect().top;
            const secHeight = Math.max(1, rect.height);
            const readTop = containerTop - rect.top;
            chapterFraction = Math.min(1, Math.max(0, readTop / secHeight));
        }
        const overallFraction = (currentIdx + chapterFraction) / chapterCount;
        percent = Math.min(100, Math.max(0, Math.round(overallFraction * 100)));
        els.progress.textContent = `全书进度 ${percent}% · 第 ${currentIdx + 1} / ${chapterCount} 章`;
    } else {
        const scrollTop = els.content.scrollTop;
        const maxScroll = Math.max(1, els.content.scrollHeight - els.content.clientHeight);
        percent = computePercent(scrollTop, maxScroll);
        els.progress.textContent = `第 ${currentIdx + 1} / ${chapterCount} 章 (${percent}%)`;
    }

    if (els.progressBar) {
        els.progressBar.style.width = `${percent}%`;
    }
}

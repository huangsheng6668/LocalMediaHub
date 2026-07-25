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

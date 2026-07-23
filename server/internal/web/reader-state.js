// 核心状态单例：textReader 各子模块共享的可变状态。
// 设计要点：
//   - 单例（模块级 const），各模块 import 同一实例。
//   - setCurrentIdx 是唯一修改 currentIdx 的入口，同时 emit chapter:changed，
//     让 toc/bookmarks/progress 订阅自更新（替代 _highlightCurrent hack）。
//   - resetState 在 renderTextReader 入口调用，避免上一本书状态泄漏。
//   - els / settings 字段在主模块 render 时填充，子模块读取时按需 null 检查。
import { emit, EVT } from './bus.js';

export const state = {
    currentIdx: 0,
    chapterCount: 0,
    book: null,
    els: null,       // { content, drawer, title, progress, progressBar, ... }
    settings: null,  // readerPrefs.getSettings() 的缓存
    path: null,
};

// 更新当前章节 index。idx 未变化或越界时 no-op（不 emit）。
// 主模块 loadChapter、progress.detectActiveChapterOnScroll、TOC 点击都走这里。
export function setCurrentIdx(idx) {
    if (idx === state.currentIdx) return;
    if (idx < 0 || (state.chapterCount > 0 && idx >= state.chapterCount)) return;
    state.currentIdx = idx;
    emit(EVT.CHAPTER_CHANGED, { idx });
}

// 重置为默认值。renderTextReader 入口 + cleanup 调用。
export function resetState() {
    state.currentIdx = 0;
    state.chapterCount = 0;
    state.book = null;
    state.els = null;
    state.settings = null;
    state.path = null;
}

// TextReader orchestration module (Task 8 slim): renders #view-reader by
// wiring together the reader submodules (bus/state/progress/toc/bookmarks/
// autoscroll/settings). Owns ONLY: render entry, book fetch, loadChapter,
// scroll-mode chapter buffering, page-turn gestures, immersive state machine,
// and lifecycle cleanup. Mirrors the Android TextReaderViewModel contract.
import { getBookInfo, getBookChapter } from './api.js';
import { showToast } from './toast.js';
import * as readerPrefs from './readerPrefs.js';
import { on, EVT } from './bus.js';
import { state, setCurrentIdx, resetState } from './reader-state.js';
import { updateProgressUI, detectActiveChapterOnScroll, firstVisibleParagraph } from './progress.js';
import { renderToc } from './toc.js';
import { renderBookmarks } from './bookmarks.js';
import { renderAutoscroll } from './autoscroll.js';
import { renderSettings } from './reader-settings.js';
import { renderScrubber, progressToChapterIndex } from './readerScrubber.js';
import { renderPageTurn } from './pageTurn.js';

const STORAGE_PREFIX = 'book_progress:';

// Pure helper: click-region ratio for the content hotzone. Returns the
// fraction (0..1) of clientX across rect.width. Guarded against zero-width
// (jsdom returns all-zero rects with no layout). Exported for unit testing.
export function hotzoneRatio(clientX, rect) {
    const w = (rect && rect.width) || 0;
    if (w <= 0) return 0.5;
    const left = (rect && rect.left) || 0;
    return Math.min(1, Math.max(0, (clientX - left) / w));
}

// Pure helper: format reader header title, deduplicating when chapter title matches book title.
export function formatHeaderTitle(chapterTitle, bookTitle) {
    const chTitle = (chapterTitle || '').trim();
    const bkTitle = (bookTitle || '').trim();
    if (!chTitle || chTitle === bkTitle) {
        return bkTitle || chTitle;
    } else if (!bkTitle) {
        return chTitle;
    } else {
        return `${chTitle} — ${bkTitle}`;
    }
}

// Pure helper: decide which chapter/paragraph to open at. URL params win
// (TOC / bookmark deep links); otherwise the saved localStorage progress
// restores chapter + first-visible paragraph. Legacy payloads without
// paraIndex degrade to chapter-top. Exported for unit testing.
export function resolveResume({ chapterParam, paraParam, saved, chapterCount }) {
    const maxIdx = Math.max(0, chapterCount - 1);
    let startIdx = 0;
    let resumePara = null;
    if (chapterParam !== undefined && chapterParam !== null) {
        startIdx = clamp(parseInt(chapterParam, 10) || 0, 0, maxIdx);
        if (paraParam !== undefined && paraParam !== null) {
            resumePara = parseInt(paraParam, 10) || 0;
        }
    } else if (saved) {
        startIdx = clamp(saved.chapterIndex || 0, 0, maxIdx);
        const p = saved.paraIndex || 0;
        if (p > 0) resumePara = p;
    }
    return { startIdx, resumePara };
}

// Entry point invoked by router.js. Signature is FIXED: (container, path, chapterParam, paraParam).
export async function renderTextReader(container, path, chapterParam, paraParam) {
    // Run prior cleanup FIRST so leaks from a previous render release before innerHTML wipe.
    if (typeof container._cleanupReader === 'function') container._cleanupReader();
    resetState();

    if (!path) {
        container.innerHTML = '<div class="text-reader__error">缺少 path 参数</div>'; // XSS-SAFE: literal
        return;
    }

    // XSS-SAFE: pure-literal skeleton; book content rendered via textContent / DOM API
    container.innerHTML = `
        <div class="text-reader">
            <div class="text-reader__progress-track"><div class="text-reader__progress-bar"></div></div>
            <header class="text-reader__header">
                <button class="text-reader__back" type="button" aria-label="返回">←</button>
                <span class="text-reader__title">加载中...</span>
            </header>
            <div class="text-reader__content" tabindex="0">正在加载书籍信息...</div>
            <footer class="text-reader__footer">
                <button class="text-reader__prev" type="button">上一章</button>
                <span class="text-reader__progress">-</span>
                <button class="text-reader__next" type="button">下一章</button>
                <button class="text-reader__toc" type="button">目录</button>
            </footer>
        </div>
        <div class="text-reader__drawer text-reader__drawer--hidden" aria-hidden="true"></div>
        <div class="text-reader__autoscroll-panel text-reader__autoscroll-panel--hidden" id="autoscroll-panel">
            <button class="autoscroll-panel-btn" id="autoscroll-panel-play" aria-label="播放/暂停" title="播放/暂停"><span data-icon="pause"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="none" aria-hidden="true"><rect x="6" y="5" width="4" height="14"/><rect x="14" y="5" width="4" height="14"/></svg></span><span data-icon="play" hidden><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="none" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg></span></button>
            <button class="autoscroll-panel-btn" id="autoscroll-panel-minus" title="减速">-</button>
            <span class="autoscroll-panel-text">速度: <span id="autoscroll-val-speed">5</span></span>
            <button class="autoscroll-panel-btn" id="autoscroll-panel-plus" title="加速">+</button>
        </div>
    `;

    const els = bindEls(container);
    state.els = els;
    state.path = path;
    state.settings = readerPrefs.getSettings();

    // Scroll-mode buffering window (orchestration-local state).
    let minLoadedIdx = 0, maxLoadedIdx = 0, isLoadingChapter = false;

    // ===== Immersive-mode state machine (Phase 5). Toggles chrome visibility
    // via body dataset + fullscreen API — orchestrator-only concerns. =====
    let isImmersive = false;
    let immersiveEntryTimer = null;
    function enterImmersive() {
        isImmersive = true;
        document.body.dataset.readerImmersive = 'on';
        if (document.documentElement.requestFullscreen && !document.fullscreenElement) {
            document.documentElement.requestFullscreen().catch(() => {});
        }
        if (!readerPrefs.getSettings().immersiveMode) readerPrefs.saveSettings({ immersiveMode: true });
    }
    function exitImmersive() {
        if (!isImmersive && !document.body.dataset.readerImmersive) return;
        isImmersive = false;
        delete document.body.dataset.readerImmersive;
        if (document.fullscreenElement && document.exitFullscreen) document.exitFullscreen().catch(() => {});
        if (readerPrefs.getSettings().immersiveMode) readerPrefs.saveSettings({ immersiveMode: false });
    }
    function scheduleImmersiveEntry() {
        if (immersiveEntryTimer) clearTimeout(immersiveEntryTimer);
        if (readerPrefs.getSettings().immersiveMode) {
            exitImmersive();  // show chrome as visual anchor first
            immersiveEntryTimer = setTimeout(() => {
                if (readerPrefs.getSettings().immersiveMode) enterImmersive();
            }, 1500);
        } else {
            exitImmersive();
        }
    }
    const onFullscreenChange = () => { if (!document.fullscreenElement && isImmersive) exitImmersive(); };
    const onKeyDown = (e) => { if (e.key === 'Escape' && isImmersive) exitImmersive(); };
    document.addEventListener('fullscreenchange', onFullscreenChange);
    document.addEventListener('keydown', onKeyDown);

    // ===== Reflect settings onto CSS vars + body dataset. reader-settings.js
    // owns the dialog + persistence; this orchestrator owns visual reflection. =====
    function applySettingsToUI() {
        state.settings = readerPrefs.getSettings();
        const s = state.settings;
        const root = document.documentElement;
        let themeKey = s.theme;
        let theme;
        if (themeKey === 'AUTO') {
            themeKey = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
            theme = readerPrefs.THEME_PRESETS[themeKey];
        } else if (themeKey === 'CUSTOM') {
            const fb = readerPrefs.THEME_PRESETS[
                window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY'
            ];
            // 三色自定义；null 回退到系统深浅对应预设。chrome/border 从三色派生。
            theme = {
                bg: s.customBg || fb.bg,
                fg: s.customFg || fb.fg,
                chromeBg: s.customBg || fb.chromeBg,
                chromeFg: s.customFg || fb.chromeFg,
                muted: s.customMuted || fb.muted,
                border: s.customMuted || fb.border,
            };
        } else {
            theme = readerPrefs.THEME_PRESETS[themeKey];
        }
        const setVar = (k, v) => root.style.setProperty(k, v);
        setVar('--reader-bg', theme.bg);
        setVar('--reader-fg', theme.fg);
        setVar('--reader-chrome-bg', theme.chromeBg);
        setVar('--reader-chrome-fg', theme.chromeFg);
        setVar('--reader-muted', theme.muted);
        setVar('--reader-border', theme.border);
        setVar('--reader-font-size', s.fontSize + 'px');
        setVar('--reader-line-height', String(s.lineHeight));
        setVar('--reader-font-family', readerPrefs.FONT_FAMILIES[s.fontFamily] || readerPrefs.FONT_FAMILIES.SYSTEM);
        setVar('--reader-content-width', s.contentWidth + 'px');
        setVar('--reader-letter-spacing', s.letterSpacing + 'em');
        document.body.dataset.readerTheme = themeKey;
        if (els.autoscrollSpeedVal) els.autoscrollSpeedVal.textContent = s.autoScrollSpeed;
        updateParagraphClasses();
    }
    applySettingsToUI();
    scheduleImmersiveEntry();

    // firstLineIndent / paragraphSpacing 只在 renderBlocks 时写进 <p> className，
    // 设置变更后需手动刷新已渲染段落的 class，否则开关无反应。
    function updateParagraphClasses() {
        const s = readerPrefs.getSettings();
        const indent = s.firstLineIndent ? 'indent-on' : 'indent-off';
        const gap = s.paragraphSpacing ? 'gap-on' : 'gap-off';
        els.content.querySelectorAll('.text-reader__p').forEach(p => {
            const dropcap = p.classList.contains('text-reader__p--dropcap') ? 'text-reader__p--dropcap' : '';
            p.className = `text-reader__p ${indent} ${gap} ${dropcap}`.trim();
        });
    }

    function updateReadingModeUI(mode) {
        const hide = mode === 'scroll';
        els.prev.style.display = hide ? 'none' : '';
        els.next.style.display = hide ? 'none' : '';
    }
    updateReadingModeUI(state.settings.readingMode);

    // Bus listener: keep state.settings + CSS vars fresh and re-implement the
    // two Task-7 side effects that could not move into reader-settings.js
    // (immersiveMode toggle → enter/exitImmersive; readingMode change → reload).
    const unsubSettings = on(EVT.SETTINGS_CHANGED, ({ settings }) => {
        const prev = state.settings;
        state.settings = settings;
        applySettingsToUI();
        if (settings.readingMode !== prev.readingMode) {
            updateReadingModeUI(settings.readingMode);
            loadChapter(state.currentIdx, true);
        }
        if (settings.immersiveMode !== prev.immersiveMode) {
            settings.immersiveMode ? enterImmersive() : exitImmersive();
        }
    });
    // readerPrefs.subscribe fires for every preference mutation. Settings
    // changes already flow through the bus (SETTINGS_CHANGED -> unsubSettings)
    // and bookmark changes are handled by unsubBms — re-applying the full
    // settings pass here only duplicated work (a paragraph-class rewrite per
    // event). Keep the handler for any other pref types only.
    const unsubPrefs = readerPrefs.subscribe((e) => {
        if (e.detail?.type === 'settings' || e.detail?.type === 'bookmarks') return;
        applySettingsToUI();
    });
    const mediaDark = window.matchMedia('(prefers-color-scheme: dark)');
    const onSystemColorSchemeChange = () => {
        if (readerPrefs.getSettings().theme === 'AUTO') applySettingsToUI();
    };
    mediaDark.addEventListener('change', onSystemColorSchemeChange);

    // Early-registered subscriptions must be torn down if the book fetch
    // fails below — otherwise repeated failures leak document-level
    // listeners and bus subscriptions (the full _cleanupReader is only
    // assigned after the fetch succeeds).
    const cleanupEarlyListeners = () => {
        document.removeEventListener('fullscreenchange', onFullscreenChange);
        document.removeEventListener('keydown', onKeyDown);
        unsubSettings();
        unsubPrefs();
        mediaDark.removeEventListener('change', onSystemColorSchemeChange);
    };

    // ===== Fetch book info =====
    let book;
    try {
        book = await getBookInfo(path);
    } catch (e) {
        cleanupEarlyListeners();
        els.title.textContent = '加载失败';
        els.content.textContent = '无法加载书籍信息: ' + e.message;
        showToast('加载书籍失败: ' + e.message, 'error');
        return;
    }
    state.book = book;
    state.chapterCount = (book.chapters || []).length;
    const chapterCount = state.chapterCount;

    const savedProgress = loadProgress(path);
    const { startIdx, resumePara } = resolveResume({
        chapterParam, paraParam, saved: savedProgress, chapterCount,
    });
    minLoadedIdx = startIdx;
    maxLoadedIdx = startIdx;
    setCurrentIdx(startIdx);

    // ===== Unified navigate handler. TOC: onNavigate(idx); bookmarks:
    // onNavigate(chapterIndex, paragraphIndex). paraIdx optional. =====
    async function onNavigate(chapterIdx, paraIdx) {
        const s = readerPrefs.getSettings();
        if (s.readingMode === 'scroll') {
            let sec = els.content.querySelector(`.text-reader__chapter-section[data-chapter-index="${chapterIdx}"]`);
            if (!sec) {
                await loadChapter(chapterIdx, true);
                sec = els.content.querySelector(`.text-reader__chapter-section[data-chapter-index="${chapterIdx}"]`);
            }
            if (sec && paraIdx === undefined) sec.scrollIntoView({ behavior: 'smooth' });
            else if (paraIdx !== undefined) scrollToParagraph(paraIdx, chapterIdx);
        } else {
            if (state.currentIdx !== chapterIdx) await loadChapter(chapterIdx);
            if (paraIdx !== undefined) scrollToParagraph(paraIdx);
        }
        tocApi.closeDrawer();
    }

    // ===== Wire submodules. toc.js renders tabs + its own panel; bookmarks
    // gets a separate panel we create here so the two never share DOM. Tab
    // clicks swap panel visibility + refresh bookmarks. =====
    const tocApi = renderToc({ drawerEl: els.drawer, onNavigate });
    const bookmarksPanel = document.createElement('div');
    bookmarksPanel.className = 'text-reader__tab-panel text-reader__tab-panel--hidden';
    els.drawer.appendChild(bookmarksPanel);
    const bookmarksApi = renderBookmarks({
        drawerEl: els.drawer,
        panelEl: bookmarksPanel,
        onNavigate,
    });
    const tocPanel = els.drawer.querySelector('.text-reader__tab-panel');
    const bookmarksTabBtn = els.drawer.querySelector('.text-reader__tab[data-tab="bookmarks"]');
    const tocTabBtn = els.drawer.querySelector('.text-reader__tab[data-tab="toc"]');
    bookmarksTabBtn?.addEventListener('click', () => {
        tocPanel?.classList.add('text-reader__tab-panel--hidden');
        bookmarksPanel.classList.remove('text-reader__tab-panel--hidden');
        bookmarksApi.refresh();
    });
    tocTabBtn?.addEventListener('click', () => {
        bookmarksPanel.classList.add('text-reader__tab-panel--hidden');
        tocPanel?.classList.remove('text-reader__tab-panel--hidden');
    });

    const autoscrollApi = renderAutoscroll({
        panelEl: els.autoscrollPanel,
        playBtn: els.autoscrollPlay,
        minusBtn: els.autoscrollMinus,
        plusBtn: els.autoscrollPlus,
        speedValEl: els.autoscrollSpeedVal,
    });

    // ===== 进度拖动条 =====
    //   分章模式:thumb = 章内进度,拖动实时滚动本章(不跳章),到顶/底即停。
    //   滚动模式:thumb = 全书进度,松手才跳章(避免频繁触发章节惰性加载)。
    const scrubberApi = renderScrubber({
        containerEl: els.progress,   // 原 .text-reader__progress span 作为宿主
        getProgress: () => {
            const isScroll = readerPrefs.getSettings().readingMode === 'scroll';
            if (isScroll) {
                const cc = state.chapterCount || 1;
                const activeSec = els.content.querySelector(
                    `.text-reader__chapter-section[data-chapter-index="${state.currentIdx}"]`
                );
                let frac = 0;
                if (activeSec) {
                    const rect = activeSec.getBoundingClientRect();
                    const containerTop = els.content.getBoundingClientRect().top;
                    frac = Math.min(1, Math.max(0, (containerTop - rect.top) / Math.max(1, rect.height)));
                }
                return Math.min(1, Math.max(0, (state.currentIdx + frac) / cc));
            }
            // 分章模式:章内滚动比例
            const maxScroll = Math.max(1, els.content.scrollHeight - els.content.clientHeight);
            return Math.min(1, Math.max(0, els.content.scrollTop / maxScroll));
        },
        getChapterCount: () => state.chapterCount || 1,
        onSeekStart: () => { if (autoscrollApi) autoscrollApi.stop(); },
        onSeek: (p) => {
            // 分章模式:拖动实时滚动本章内容
            if (readerPrefs.getSettings().readingMode !== 'scroll') {
                const maxScroll = Math.max(0, els.content.scrollHeight - els.content.clientHeight);
                els.content.scrollTop = p * maxScroll;
            }
            // 滚动模式:纯本地预览,renderScrubber 内部已更新 thumb/label
        },
        onSeekEnd: (p) => {
            // 分章模式已在 onSeek 实时滚动,无需跳章;滚动模式松手跳章
            if (readerPrefs.getSettings().readingMode !== 'scroll') return;
            const targetIdx = progressToChapterIndex(p, state.chapterCount || 1);
            onNavigate(targetIdx);
        },
        formatLabel: (p, dragging) => {
            const cc = state.chapterCount || 1;
            const isScroll = readerPrefs.getSettings().readingMode === 'scroll';
            const pct = Math.round(p * 100);
            if (isScroll) {
                const targetIdx = progressToChapterIndex(p, cc);
                if (dragging) return `将跳到第 ${targetIdx + 1} 章`;
                return `第 ${state.currentIdx + 1} / ${cc} 章 (${pct}%)`;
            }
            // 分章模式:章内百分比
            return `第 ${state.currentIdx + 1} / ${cc} 章 · 本章 ${pct}%`;
        },
    });

    const settingsApi = renderSettings(container);

    // Header action buttons: Aa opens settings, play/pause SVG toggles autoscroll.
    const settingsBtn = document.createElement('button');
    settingsBtn.className = 'text-reader__icon-btn';
    settingsBtn.type = 'button';
    settingsBtn.ariaLabel = '阅读设置';
    settingsBtn.textContent = 'Aa';
    settingsBtn.addEventListener('click', () => settingsApi.open());
    const scrollBtn = document.createElement('button');
    scrollBtn.className = 'text-reader__icon-btn';
    scrollBtn.type = 'button';
    scrollBtn.ariaLabel = '自动滚动';
    // XSS-SAFE: pure-literal dual-span icon markup (hidden-toggle pattern)
    scrollBtn.innerHTML = '<span data-icon="play"><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="none" aria-hidden="true"><path d="M8 5v14l11-7z"/></svg></span><span data-icon="pause" hidden><svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" stroke="none" aria-hidden="true"><rect x="6" y="5" width="4" height="14"/><rect x="14" y="5" width="4" height="14"/></svg></span>';
    scrollBtn.addEventListener('click', () => autoscrollApi.toggle());
    const headerRight = document.createElement('div');
    headerRight.className = 'text-reader__header-actions';
    headerRight.appendChild(settingsBtn);
    headerRight.appendChild(scrollBtn);
    els.header.appendChild(headerRight);

    // ===== Page-turn controller (CHAPTER mode). Holds the animation layer over
    // els.content; prev/next/hotzone/❖ in CHAPTER mode route through it. SCROLL
    // mode keeps its existing direct loadChapter behaviour. =====
    const pageTurnApi = renderPageTurn({
        contentEl: els.content,
        getStyle: () => readerPrefs.getSettings().pageTurnStyle,
        loadChapterSection: async (idx) => {
            const ch = await getBookChapter(path, idx);
            // Mirror the bookkeeping loadChapter does for CHAPTER mode: update
            // current idx / title / progress / scrubber / saved progress so a
            // page-turn produces the same side effects as an instant load.
            setCurrentIdx(idx);
            els.title.textContent = formatHeaderTitle(ch.title, book.title);
            const sec = renderBlocks(ch.blocks || blocksFromLegacyContent(ch.content), ch.title, idx);
            updateProgressUI();
            if (scrubberApi) scrubberApi.update();
            saveProgress(path, { chapterIndex: idx, paraIndex: 0, lastReadAt: Date.now() });
            return sec;
        },
        getCurrentIdx: () => state.currentIdx,
        getChapterCount: () => chapterCount,
    });

    // ===== Chrome button bindings =====
    els.back.addEventListener('click', () => {
        if (window.history.length > 1) window.history.back();
        else window.location.hash = '#/dashboard';
    });
    els.prev.addEventListener('click', () => {
        // SCROLL 模式保持现状（直接 loadChapter），仅 CHAPTER 模式走翻页控制器。
        if (readerPrefs.getSettings().readingMode === 'scroll') {
            if (state.currentIdx > 0) loadChapter(Math.max(0, state.currentIdx - 1));
            return;
        }
        pageTurnApi.turnTo('prev').then((ok) => { if (!ok) showToast('已经是第一章了', 'info'); });
    });
    els.next.addEventListener('click', () => {
        if (readerPrefs.getSettings().readingMode === 'scroll') {
            if (state.currentIdx < chapterCount - 1) loadChapter(Math.min(chapterCount - 1, state.currentIdx + 1));
            return;
        }
        pageTurnApi.turnTo('next').then((ok) => { if (!ok) showToast('已经是最后一章了', 'info'); });
    });
    els.toc.addEventListener('click', () => tocApi.toggleDrawer());

    function syncBookmarkCount() {
        tocApi.setBookmarkCount?.(readerPrefs.getBookmarks(path).length);
    }
    const unsubBms = readerPrefs.subscribe((e) => {
        if (e.detail?.type !== 'bookmarks') return;
        syncBookmarkCount();
        const activeTab = els.drawer.querySelector('.text-reader__tab--active')?.dataset.tab;
        if (activeTab === 'bookmarks') bookmarksApi.refresh();
    });

    // Page-turn gestures: left 20% prev, right 20% next. Cursor reflects hot
    // zone. rAF-throttled: the cursor is purely visual, and each update costs
    // a getBoundingClientRect, so more than once per frame is wasted layout.
    let cursorRafId = null;
    els.content.addEventListener('mousemove', (e) => {
        if (cursorRafId !== null) return;
        cursorRafId = requestAnimationFrame(() => {
            cursorRafId = null;
            if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) {
                els.content.style.cursor = 'default'; return;
            }
            if (readerPrefs.getSettings().readingMode === 'scroll') {
                els.content.style.cursor = 'default'; return;
            }
            const ratio = hotzoneRatio(e.clientX, els.content.getBoundingClientRect());
            els.content.style.cursor = (ratio < 0.20 || ratio > 0.80) ? 'pointer' : 'default';
        });
    });
    els.content.addEventListener('click', (e) => {
        if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) return;
        if (window.getSelection()?.toString().trim() !== '') return;
        if (readerPrefs.getSettings().readingMode === 'scroll') return;
        const ratio = hotzoneRatio(e.clientX, els.content.getBoundingClientRect());
        if (ratio < 0.20) {
            pageTurnApi.turnTo('prev').then((ok) => { if (!ok) showToast('已经是第一章了', 'info'); });
        } else if (ratio > 0.80) {
            pageTurnApi.turnTo('next').then((ok) => { if (!ok) showToast('已经是最后一章了', 'info'); });
        }
    });

    // Scroll-mode buffering + progress + active-chapter detection (修复 3 in progress.js).
    // rAF-throttled: scroll events fire far more often than frames, and the
    // detection walks EVERY chapter section with getBoundingClientRect — doing
    // that per event is layout thrash on long books.
    let scrollRafId = null;
    // 段级进度保存：滚动停止 800ms 后写入当前首个可见段落；关闭页面时立即 flush。
    let progressSaveTimer = null;
    function collectVisibleParagraphs() {
        const containerTop = els.content.getBoundingClientRect().top;
        const paragraphs = [];
        els.content.querySelectorAll('.text-reader__chapter-section').forEach(sec => {
            const chIdx = parseInt(sec.dataset.chapterIndex, 10);
            sec.querySelectorAll('.text-reader__p').forEach(p => {
                const r = p.getBoundingClientRect();
                const paraIdx = parseInt(p.dataset.paraIndex, 10);
                paragraphs.push({
                    top: r.top,
                    bottom: r.bottom,
                    chapterIndex: Number.isNaN(chIdx) ? state.currentIdx : chIdx,
                    paraIndex: Number.isNaN(paraIdx) ? 0 : paraIdx,
                });
            });
        });
        return { paragraphs, containerTop };
    }
    function persistVisibleProgress() {
        const { paragraphs, containerTop } = collectVisibleParagraphs();
        const vis = firstVisibleParagraph(paragraphs, containerTop);
        if (vis) {
            saveProgress(path, { chapterIndex: vis.chapterIndex, paraIndex: vis.paraIndex, lastReadAt: Date.now() });
        }
    }
    function scheduleProgressSave() {
        if (progressSaveTimer) clearTimeout(progressSaveTimer);
        progressSaveTimer = setTimeout(() => {
            progressSaveTimer = null;
            persistVisibleProgress();
        }, 800);
    }
    const onPageHide = () => {
        if (progressSaveTimer) {
            clearTimeout(progressSaveTimer);
            progressSaveTimer = null;
        }
        persistVisibleProgress();
    };
    const onVisibilityChangeSave = () => { if (document.hidden) onPageHide(); };
    window.addEventListener('pagehide', onPageHide);
    document.addEventListener('visibilitychange', onVisibilityChangeSave);
    function onContentScroll() {
        if (scrollRafId !== null) return;
        scrollRafId = requestAnimationFrame(() => {
            scrollRafId = null;
            handleContentScroll();
        });
    }
    function handleContentScroll() {
        const s = readerPrefs.getSettings();
        if (s.readingMode === 'scroll') {
            if (els.content.scrollTop + els.content.clientHeight >= els.content.scrollHeight - 300) {
                loadNextScrollChapter().then(() => checkAndFillScrollBuffer());
            } else if (els.content.scrollTop <= 300 && minLoadedIdx > 0) {
                loadPrevScrollChapter();
            }
            const sections = [...els.content.querySelectorAll('.text-reader__chapter-section')].map(sec => {
                const r = sec.getBoundingClientRect();
                return { top: r.top, bottom: r.bottom, dataset: { chapterIndex: sec.dataset.chapterIndex } };
            });
            const containerTop = els.content.getBoundingClientRect().top;
            const activeIdx = detectActiveChapterOnScroll(sections, containerTop, state.currentIdx);
            if (activeIdx !== state.currentIdx) {
                setCurrentIdx(activeIdx);
                const ct = (book.chapters && book.chapters[activeIdx]) ? book.chapters[activeIdx].title : '';
                els.title.textContent = formatHeaderTitle(ct, book.title);
                saveProgress(path, { chapterIndex: activeIdx, paraIndex: 0, lastReadAt: Date.now() });
            }
        }
        updateProgressUI();
        if (scrubberApi) scrubberApi.update();
        scheduleProgressSave();
    }
    els.content.addEventListener('scroll', onContentScroll);
    const onVisibilityChange = () => { if (document.hidden) autoscrollApi.stop(); };
    document.addEventListener('visibilitychange', onVisibilityChange);

    // ===== Cleanup =====
    container._cleanupReader = () => {
        if (scrollRafId !== null) cancelAnimationFrame(scrollRafId);
        if (cursorRafId !== null) cancelAnimationFrame(cursorRafId);
        if (progressSaveTimer) clearTimeout(progressSaveTimer);
        unsubSettings(); unsubPrefs(); unsubBms();
        tocApi.dispose(); bookmarksApi.dispose(); autoscrollApi.dispose(); settingsApi.dispose();
        scrubberApi.dispose();
        pageTurnApi.dispose();
        document.removeEventListener('visibilitychange', onVisibilityChange);
        document.removeEventListener('visibilitychange', onVisibilityChangeSave);
        window.removeEventListener('pagehide', onPageHide);
        document.removeEventListener('keydown', onKeyDown);
        document.removeEventListener('fullscreenchange', onFullscreenChange);
        els.content.removeEventListener('scroll', onContentScroll);
        mediaDark.removeEventListener('change', onSystemColorSchemeChange);
        delete document.body.dataset.readerTheme;
        if (immersiveEntryTimer) clearTimeout(immersiveEntryTimer);
        exitImmersive();
        resetState();
    };

    syncBookmarkCount();
    await loadChapter(startIdx, true);
    if (resumePara !== null) scrollToParagraph(resumePara, startIdx);

    // ===== Chapter rendering + scroll-mode buffering =====

    // renderBlocks builds one <section> per chapter. Kept in the orchestrator
    // because the per-paragraph "+" bookmark button needs `path` + chIdx.
    function renderBlocks(blocks, chapterTitle, chIdx) {
        const section = document.createElement('section');
        section.className = 'text-reader__chapter-section';
        section.dataset.chapterIndex = String(chIdx);
        if (chIdx > 0 && readerPrefs.getSettings().readingMode === 'scroll') {
            const hr = document.createElement('hr');
            hr.className = 'text-reader__chapter-divider';
            section.appendChild(hr);
        }
        if (chapterTitle) {
            const h = document.createElement('h2');
            h.className = 'text-reader__chapter-title';
            h.textContent = chapterTitle;
            section.appendChild(h);
        }
        const list = blocks || [];
        // 排除以标点/空白开头的段落：破折号、引号（中英文）、书名号等。
        // 否则 ::first-letter 会把引号/书名号放大，造成首字下沉错位。
        // 同时排除元数据行（作者/书名/来源等）及短行（< 25 字符）。
        const dropCapIdx = list.findIndex(b => b && b.type === 'text' &&
            typeof b.value === 'string' && b.value.trim().length >= 25 &&
            !/^[—…\-\s"“”‘’《〈（(【]/.test(b.value.trim()) &&
            !/^(作\s*者|书\s*名|来\s*源|字\s*数|简\s*介|编\s*辑|翻\s*译|出\s*版|内\s*容\s*简\s*介)[：:]/.test(b.value.trim()));
        list.forEach((block, idx) => {
            if (block && block.type === 'image') {
                const img = document.createElement('img');
                img.className = 'text-reader__image';
                img.loading = 'lazy';
                if (block.src) img.src = block.src;
                else img.alt = '[本图片无法显示]';
                section.appendChild(img);
                return;
            }
            const text = (block && typeof block.value === 'string') ? block.value : '';
            const p = document.createElement('p');
            p.textContent = text;
            p.dataset.blockIndex = String(idx);
            p.dataset.paraIndex = String(idx);
            const indent = readerPrefs.getSettings().firstLineIndent ? 'indent-on' : 'indent-off';
            const gap = readerPrefs.getSettings().paragraphSpacing ? 'gap-on' : 'gap-off';
            const dropcap = (idx === dropCapIdx) ? 'text-reader__p--dropcap' : '';
            p.className = `text-reader__p ${indent} ${gap} ${dropcap}`.trim();
            const btn = document.createElement('button');
            btn.className = 'text-reader__para-bookmark';
            btn.type = 'button';
            btn.textContent = '+';
            btn.title = '添加书签';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const ok = readerPrefs.addBookmark({
                    bookPath: path, chapterIndex: chIdx, paragraphIndex: idx,
                    preview: text.slice(0, 30), createdAt: Date.now(),
                });
                showToast(ok ? '已添加书签' : '已存在书签', ok ? 'success' : 'info');
                syncBookmarkCount();
            });
            p.appendChild(btn);
            section.appendChild(p);
        });
        const end = document.createElement('div');
        end.className = 'text-reader__chapter-end';
        end.textContent = '❖';
        end.title = '下一章';
        end.addEventListener('click', () => {
            const s = readerPrefs.getSettings();
            if (s.readingMode === 'scroll') {
                if (chIdx < chapterCount - 1) {
                    const nextSec = els.content.querySelector(`.text-reader__chapter-section[data-chapter-index="${chIdx + 1}"]`);
                    if (nextSec) nextSec.scrollIntoView({ behavior: 'smooth' });
                    else {
                        loadNextScrollChapter().then(() => {
                            const sec = els.content.querySelector(`.text-reader__chapter-section[data-chapter-index="${chIdx + 1}"]`);
                            if (sec) sec.scrollIntoView({ behavior: 'smooth' });
                        });
                    }
                } else showToast('已经是最后一章了', 'info');
            } else {
                pageTurnApi.turnTo('next').then((ok) => { if (!ok) showToast('已经是最后一章了', 'info'); });
            }
        });
        section.appendChild(end);
        return section;
    }

    async function loadChapter(idx, resetScroll = false) {
        if (isLoadingChapter || idx < 0 || idx >= chapterCount) return;
        isLoadingChapter = true;
        setCurrentIdx(idx);
        try {
            const chapter = await getBookChapter(path, idx);
            const chTitle = (chapter.title || '').trim();
            const bkTitle = (book.title || '').trim();
            if (!chTitle || chTitle === bkTitle) {
                els.title.textContent = bkTitle || chTitle;
            } else if (!bkTitle) {
                els.title.textContent = chTitle;
            } else {
                els.title.textContent = `${chTitle} — ${bkTitle}`;
            }
            const s = readerPrefs.getSettings();
            const sec = renderBlocks(chapter.blocks || blocksFromLegacyContent(chapter.content), chapter.title, idx);
            if (s.readingMode === 'scroll' && !resetScroll) {
                els.content.appendChild(sec);
                maxLoadedIdx = Math.max(maxLoadedIdx, idx);
                minLoadedIdx = Math.min(minLoadedIdx, idx);
            } else {
                els.content.innerHTML = ''; // XSS-SAFE: clearing
                els.content.appendChild(sec);
                els.content.scrollTop = 0;
                minLoadedIdx = idx; maxLoadedIdx = idx;
                els.content.classList.remove('text-reader__content--entering');
                void els.content.offsetWidth;
                els.content.classList.add('text-reader__content--entering');
            }
            updateProgressUI();
            if (scrubberApi) scrubberApi.update();
            saveProgress(path, { chapterIndex: idx, paraIndex: 0, lastReadAt: Date.now() });
        } catch (e) {
            els.content.textContent = '加载章节失败: ' + e.message;
            showToast('加载章节失败: ' + e.message, 'error');
        } finally {
            isLoadingChapter = false;
        }
        if (readerPrefs.getSettings().readingMode === 'scroll') {
            setTimeout(() => {
                checkAndFillScrollBuffer();
                if (minLoadedIdx > 0) loadPrevScrollChapter();
            }, 50);
        }
    }

    // Scroll-mode DOM windowing: chapters read and scrolled past accumulate
    // unboundedly above the viewport (memory + layout cost grow forever on a
    // long book). Trim those away, keeping a few chapters above the active
    // one so scrolling up a little does not immediately re-fetch. The bottom
    // needs no trimming: buffer-fill only loads until ~viewport+400px of
    // content exists, so loaded-below is naturally bounded (and trimming it
    // would fight the fill loop into load/remove churn).
    const SCROLL_WINDOW_BEFORE = 3;

    function trimScrollWindow() {
        if (readerPrefs.getSettings().readingMode !== 'scroll') return;
        const sections = [...els.content.querySelectorAll('.text-reader__chapter-section')];
        if (sections.length <= SCROLL_WINDOW_BEFORE + 2) return;

        const anchor = clamp(state.currentIdx, minLoadedIdx, maxLoadedIdx);
        const keepMin = Math.max(minLoadedIdx, anchor - SCROLL_WINDOW_BEFORE);
        if (keepMin <= minLoadedIdx) return; // nothing above the window to drop

        let removedTopHeight = 0;
        for (const sec of sections) {
            const idx = parseInt(sec.dataset.chapterIndex, 10);
            if (Number.isNaN(idx)) continue;
            if (idx < keepMin) {
                removedTopHeight += sec.offsetHeight;
                sec.remove();
            }
        }

        // Compensate for content removed above the viewport so the visible
        // text stays put. offsetHeight forces one layout — fine, this runs
        // once per chapter load, not per scroll event.
        if (removedTopHeight > 0) {
            els.content.scrollTop -= removedTopHeight;
        }

        // Recompute the contiguous loaded bounds from what remains.
        let newMin = Infinity, newMax = -Infinity;
        els.content.querySelectorAll('.text-reader__chapter-section').forEach(sec => {
            const idx = parseInt(sec.dataset.chapterIndex, 10);
            if (!Number.isNaN(idx)) {
                newMin = Math.min(newMin, idx);
                newMax = Math.max(newMax, idx);
            }
        });
        if (newMin !== Infinity) {
            minLoadedIdx = newMin;
            maxLoadedIdx = newMax;
        }
    }

    async function loadNextScrollChapter() {
        if (isLoadingChapter || maxLoadedIdx >= chapterCount - 1) return false;
        isLoadingChapter = true;
        const nextIdx = maxLoadedIdx + 1;
        try {
            const ch = await getBookChapter(path, nextIdx);
            els.content.appendChild(renderBlocks(ch.blocks || blocksFromLegacyContent(ch.content), ch.title, nextIdx));
            maxLoadedIdx = nextIdx;
            trimScrollWindow();
            return true;
        } catch (e) {
            showToast('加载下一章失败: ' + e.message, 'error');
            return false;
        } finally {
            isLoadingChapter = false;
        }
    }
    async function loadPrevScrollChapter() {
        if (isLoadingChapter || minLoadedIdx <= 0) return false;
        isLoadingChapter = true;
        const prevIdx = minLoadedIdx - 1;
        try {
            const ch = await getBookChapter(path, prevIdx);
            const sec = renderBlocks(ch.blocks || blocksFromLegacyContent(ch.content), ch.title, prevIdx);
            const oldH = els.content.scrollHeight;
            const oldTop = els.content.scrollTop;
            els.content.insertBefore(sec, els.content.firstChild);
            els.content.scrollTop = oldTop + (els.content.scrollHeight - oldH);
            minLoadedIdx = prevIdx;
            trimScrollWindow();
            return true;
        } catch (e) {
            showToast('加载上一章失败: ' + e.message, 'error');
            return false;
        } finally {
            isLoadingChapter = false;
        }
    }
    async function checkAndFillScrollBuffer() {
        if (readerPrefs.getSettings().readingMode !== 'scroll' || isLoadingChapter) return;
        if (maxLoadedIdx < chapterCount - 1 && els.content.scrollHeight - els.content.clientHeight < 400) {
            if (await loadNextScrollChapter()) setTimeout(checkAndFillScrollBuffer, 50);
        }
    }

    // scrollToParagraph retries for up to 1.5s for reflow so deep links land correctly.
    function scrollToParagraph(paraIdx, chIdx) {
        let attempts = 0;
        const maxAttempts = 15;
        function tryScroll() {
            let target = null;
            if (chIdx !== undefined && chIdx !== null) {
                const sec = els.content.querySelector(`.text-reader__chapter-section[data-chapter-index="${chIdx}"]`);
                if (sec) target = sec.querySelector(`p[data-para-index="${paraIdx}"]`);
            }
            if (!target) target = els.content.querySelector(`p[data-para-index="${paraIdx}"]`);
            if (target) {
                const targetY = Math.max(0, target.offsetTop - 16);
                els.content.scrollTop = targetY;
                if (Math.abs(els.content.scrollTop - targetY) > 5 && attempts < maxAttempts) {
                    attempts++; setTimeout(tryScroll, 100);
                }
            } else if (attempts < maxAttempts) {
                attempts++; setTimeout(tryScroll, 100);
            }
        }
        setTimeout(tryScroll, 50);
    }
}

// Per-book progress persistence (localStorage, best-effort).
function loadProgress(p) {
    try { return JSON.parse(localStorage.getItem(STORAGE_PREFIX + p) || 'null'); }
    catch (e) { return null; }
}
function saveProgress(p, prog) {
    try { localStorage.setItem(STORAGE_PREFIX + p, JSON.stringify(prog)); }
    catch (e) { /* Quota / private mode — ignore. */ }
}

function clamp(n, lo, hi) { return Math.max(lo, Math.min(hi, n)); }

// Converts an old-style chapter.content string into the block array shape.
function blocksFromLegacyContent(content) {
    return (content || '').split('\n\n').filter(p => p.trim()).map(p => ({ type: 'text', value: p }));
}

function bindEls(root) {
    return {
        back: root.querySelector('.text-reader__back'),
        header: root.querySelector('.text-reader__header'),
        title: root.querySelector('.text-reader__title'),
        content: root.querySelector('.text-reader__content'),
        prev: root.querySelector('.text-reader__prev'),
        next: root.querySelector('.text-reader__next'),
        toc: root.querySelector('.text-reader__toc'),
        progress: root.querySelector('.text-reader__progress'),
        progressBar: root.querySelector('.text-reader__progress-bar'),
        drawer: root.querySelector('.text-reader__drawer'),
        autoscrollPanel: root.querySelector('#autoscroll-panel'),
        autoscrollPlay: root.querySelector('#autoscroll-panel-play'),
        autoscrollMinus: root.querySelector('#autoscroll-panel-minus'),
        autoscrollPlus: root.querySelector('#autoscroll-panel-plus'),
        autoscrollSpeedVal: root.querySelector('#autoscroll-val-speed'),
    };
}

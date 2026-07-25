// TextReader orchestration module (Task 8 slim): renders #view-reader by
// wiring together the reader submodules (bus/state/progress/toc/bookmarks/
// autoscroll/settings). Owns ONLY: render entry, book fetch, loadChapter,
// scroll-mode chapter buffering, page-turn gestures, immersive state machine,
// and lifecycle cleanup. Mirrors the Android TextReaderViewModel contract.
import { getBookInfo, getBookChapter, getAuthToken } from './api.js';
import { showToast } from './toast.js';
import * as readerPrefs from './readerPrefs.js';
import { on, EVT } from './bus.js';
import { state, setCurrentIdx, resetState } from './reader-state.js';
import { updateProgressUI, detectActiveChapterOnScroll } from './progress.js';
import { renderToc } from './toc.js';
import { renderBookmarks } from './bookmarks.js';
import { renderAutoscroll } from './autoscroll.js';
import { renderSettings } from './reader-settings.js';
import { renderScrubber, progressToChapterIndex } from './readerScrubber.js';

const STORAGE_PREFIX = 'book_progress:';

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
            <div class="text-reader__progress-bar"></div>
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
            <button class="autoscroll-panel-btn" id="autoscroll-panel-play">⏸</button>
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
        if (themeKey === 'AUTO') {
            themeKey = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'NIGHT' : 'DAY';
        }
        const theme = readerPrefs.THEME_PRESETS[themeKey];
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
    // readerPrefs window event covers non-settings (bookmarks) changes; re-apply too.
    const unsubPrefs = readerPrefs.subscribe(() => applySettingsToUI());
    const mediaDark = window.matchMedia('(prefers-color-scheme: dark)');
    const onSystemColorSchemeChange = () => {
        if (readerPrefs.getSettings().theme === 'AUTO') applySettingsToUI();
    };
    mediaDark.addEventListener('change', onSystemColorSchemeChange);

    // ===== Fetch book info =====
    let book;
    try {
        book = await getBookInfo(path);
    } catch (e) {
        els.title.textContent = '加载失败';
        els.content.textContent = '无法加载书籍信息: ' + e.message;
        showToast('加载书籍失败: ' + e.message, 'error');
        return;
    }
    state.book = book;
    state.chapterCount = (book.chapters || []).length;
    const chapterCount = state.chapterCount;

    let startIdx = 0;
    if (chapterParam !== undefined && chapterParam !== null) {
        startIdx = clamp(parseInt(chapterParam, 10) || 0, 0, Math.max(0, chapterCount - 1));
    } else {
        const p = loadProgress(path);
        if (p) startIdx = clamp(p.chapterIndex || 0, 0, Math.max(0, chapterCount - 1));
    }
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

    // Header action buttons: Aa opens settings, ▶ toggles autoscroll.
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
    scrollBtn.textContent = '▶';
    scrollBtn.addEventListener('click', () => autoscrollApi.toggle());
    const headerRight = document.createElement('div');
    headerRight.className = 'text-reader__header-actions';
    headerRight.appendChild(settingsBtn);
    headerRight.appendChild(scrollBtn);
    els.header.appendChild(headerRight);

    // ===== Chrome button bindings =====
    els.back.addEventListener('click', () => {
        if (window.history.length > 1) window.history.back();
        else window.location.hash = '#/dashboard';
    });
    els.prev.addEventListener('click', () => {
        if (state.currentIdx > 0) loadChapter(Math.max(0, state.currentIdx - 1));
    });
    els.next.addEventListener('click', () => {
        if (state.currentIdx < chapterCount - 1) loadChapter(Math.min(chapterCount - 1, state.currentIdx + 1));
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

    // Page-turn gestures: left 20% prev, right 20% next. Cursor reflects hot zone.
    els.content.addEventListener('mousemove', (e) => {
        if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) {
            els.content.style.cursor = 'default'; return;
        }
        if (readerPrefs.getSettings().readingMode === 'scroll') {
            els.content.style.cursor = 'default'; return;
        }
        const rect = els.content.getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;
        els.content.style.cursor = (ratio < 0.20 || ratio > 0.80) ? 'pointer' : 'default';
    });
    els.content.addEventListener('click', (e) => {
        if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) return;
        if (window.getSelection()?.toString().trim() !== '') return;
        if (readerPrefs.getSettings().readingMode === 'scroll') return;
        const rect = els.content.getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;
        if (ratio < 0.20) {
            if (state.currentIdx > 0) loadChapter(state.currentIdx - 1);
            else showToast('已经是第一章了', 'info');
        } else if (ratio > 0.80) {
            if (state.currentIdx < chapterCount - 1) loadChapter(state.currentIdx + 1);
            else showToast('已经是最后一章了', 'info');
        }
    });

    // Scroll-mode buffering + progress + active-chapter detection (修复 3 in progress.js).
    function onContentScroll() {
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
                els.title.textContent = `${ct || ''} — ${book.title || ''}`;
                saveProgress(path, { chapterIndex: activeIdx, scrollOffset: els.content.scrollTop, lastReadAt: Date.now() });
            }
        }
        updateProgressUI();
        if (scrubberApi) scrubberApi.update();
    }
    els.content.addEventListener('scroll', onContentScroll);
    const onVisibilityChange = () => { if (document.hidden) autoscrollApi.stop(); };
    document.addEventListener('visibilitychange', onVisibilityChange);

    // ===== Cleanup =====
    container._cleanupReader = () => {
        unsubSettings(); unsubPrefs(); unsubBms();
        tocApi.dispose(); bookmarksApi.dispose(); autoscrollApi.dispose(); settingsApi.dispose();
        scrubberApi.dispose();
        document.removeEventListener('visibilitychange', onVisibilityChange);
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
    if (paraParam !== undefined && paraParam !== null) scrollToParagraph(parseInt(paraParam, 10), startIdx);

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
        const dropCapIdx = list.findIndex(b => b && b.type === 'text' &&
            typeof b.value === 'string' && b.value.trim().length >= 4 &&
            !/^[—…\-\s"“”‘’《〈（(【]/.test(b.value.trim()));
        list.forEach((block, idx) => {
            if (block && block.type === 'image') {
                const img = document.createElement('img');
                img.className = 'text-reader__image';
                img.loading = 'lazy';
                if (block.src) img.src = appendTokenQueryParam(block.src);
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
                    else loadNextScrollChapter();
                } else showToast('已经是最后一章了', 'info');
            } else {
                if (state.currentIdx < chapterCount - 1) loadChapter(state.currentIdx + 1);
                else showToast('已经是最后一章了', 'info');
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
            els.title.textContent = `${chapter.title || ''} — ${book.title || ''}`;
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
            saveProgress(path, { chapterIndex: idx, scrollOffset: els.content.scrollTop, lastReadAt: Date.now() });
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

    async function loadNextScrollChapter() {
        if (isLoadingChapter || maxLoadedIdx >= chapterCount - 1) return false;
        isLoadingChapter = true;
        const nextIdx = maxLoadedIdx + 1;
        try {
            const ch = await getBookChapter(path, nextIdx);
            els.content.appendChild(renderBlocks(ch.blocks || blocksFromLegacyContent(ch.content), ch.title, nextIdx));
            maxLoadedIdx = nextIdx;
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

// Adds ?token=... so <img> tags authenticate against /api/v1/books/image.
function appendTokenQueryParam(url) {
    const token = getAuthToken();
    if (!token) return url;
    return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
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

// TextReader feature module (Task 15): online novel reader for .txt / .epub
// files. Renders into #view-reader (a dedicated view-section managed by the
// router), fetches book info + chapter text from the server books API, and
// persists the last-read chapter index to localStorage so reloads resume.
//
// Mirrors the Android TextReaderViewModel contract: /api/v1/books/info and
// /api/v1/books/chapter. The unsupported formats (.mobi / .azw3) never reach
// this module — browserView intercepts them and shows a "暂不支持" toast.
import { getBookInfo, getBookChapter } from './api.js';
import { showToast } from './toast.js';
import * as readerPrefs from './readerPrefs.js';

const STORAGE_PREFIX = 'book_progress:';

// Entry point invoked by router.js when the hash is #/read?path=...
//
// The container is the #view-reader section element. We always start by
// clearing any previous render so re-navigating to a different book does not
// leak DOM or event listeners from the previous one.
export async function renderTextReader(container, path) {
    if (!path) {
        container.innerHTML = '<div class="text-reader__error">缺少 path 参数</div>';
        return;
    }

    container.innerHTML = `
        <div class="text-reader">
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
    `;

    const els = bindEls(container);

    let currentIdx = 0;
    let chapterCount = 0;
    let isLoadingChapter = false;

    // Fetch book info
    let book;
    try {
        book = await getBookInfo(path);
    } catch (e) {
        els.title.textContent = '加载失败';
        els.content.textContent = '无法加载书籍信息: ' + e.message;
        showToast('加载书籍失败: ' + e.message, 'error');
        return;
    }

    chapterCount = (book.chapters || []).length;
    const progress = loadProgress(path);
    const startIdx = progress
        ? clamp(progress.chapterIndex || 0, 0, Math.max(0, chapterCount - 1))
        : 0;

    // closeDrawer: shared helper used by TOC and bookmark tab handlers.
    function closeDrawer() {
        els.drawer.classList.add('text-reader__drawer--hidden');
        els.drawer.setAttribute('aria-hidden', 'true');
    }

    els.back.addEventListener('click', () => {
        // Prefer browser history; fall back to dashboard so the user is never
        // stranded if they deep-linked #/read directly.
        if (window.history.length > 1) window.history.back();
        else window.location.hash = '#/dashboard';
    });
    els.prev.addEventListener('click', () => {
        if (currentIdx > 0) loadChapter(Math.max(0, currentIdx - 1));
    });
    els.next.addEventListener('click', () => {
        if (currentIdx < chapterCount - 1) loadChapter(Math.min(chapterCount - 1, currentIdx + 1));
    });
    els.toc.addEventListener('click', () => toggleDrawer(book, els.drawer));
    els.drawer.addEventListener('chapter-select', (e) => {
        loadChapter(e.detail);
        closeDrawer();
    });

    // ===== Reader settings integration (Task 8) =====

    // 1. Build settings dialog (HTML5 <dialog>), append into container.
    const dialog = document.createElement('dialog');
    dialog.id = 'reader-settings-dialog';
    dialog.innerHTML = `
        <form method="dialog">
            <h3>阅读设置</h3>
            <fieldset>
                <legend>字体大小</legend>
                ${['SMALL','MEDIUM','LARGE','XLARGE'].map(v =>
                    `<label><input type="radio" name="fontSize" value="${v}"> ${ {SMALL:'小',MEDIUM:'中',LARGE:'大',XLARGE:'超大'}[v] }</label>`
                ).join('')}
            </fieldset>
            <fieldset>
                <legend>行距</legend>
                ${['COMPACT','STANDARD','LOOSE'].map(v =>
                    `<label><input type="radio" name="lineHeight" value="${v}"> ${ {COMPACT:'紧凑',STANDARD:'标准',LOOSE:'宽松'}[v] }</label>`
                ).join('')}
            </fieldset>
            <fieldset>
                <legend>主题</legend>
                ${['DAY','NIGHT','EYE_CARE'].map(v =>
                    `<label><input type="radio" name="theme" value="${v}"> ${ {DAY:'日间',NIGHT:'夜间',EYE_CARE:'护眼'}[v] }</label>`
                ).join('')}
            </fieldset>
            <fieldset>
                <legend>自动滚动速度</legend>
                <input type="range" name="autoScrollSpeed" min="1" max="10" value="5">
                <span data-bind="speedLabel">5</span>
            </fieldset>
            <menu>
                <button type="submit">关闭</button>
            </menu>
        </form>
    `;
    container.appendChild(dialog);

    // 2. Add Aa + play/pause buttons to header.
    const settingsBtn = document.createElement('button');
    settingsBtn.className = 'text-reader__icon-btn';
    settingsBtn.type = 'button';
    settingsBtn.ariaLabel = '阅读设置';
    settingsBtn.textContent = 'Aa';
    settingsBtn.addEventListener('click', () => dialog.showModal());

    const scrollBtn = document.createElement('button');
    scrollBtn.className = 'text-reader__icon-btn';
    scrollBtn.type = 'button';
    scrollBtn.ariaLabel = '自动滚动';
    scrollBtn.textContent = '▶';

    const headerRight = document.createElement('div');
    headerRight.className = 'text-reader__header-actions';
    headerRight.appendChild(settingsBtn);
    headerRight.appendChild(scrollBtn);
    els.header.appendChild(headerRight);

    // 3. Apply current settings (CSS vars + dialog controls).
    function applySettingsToUI() {
        const s = readerPrefs.getSettings();
        const root = document.documentElement;
        const theme = readerPrefs.THEME_PRESETS[s.theme];
        root.style.setProperty('--reader-bg', theme.bg);
        root.style.setProperty('--reader-fg', theme.fg);
        root.style.setProperty('--reader-font-size', readerPrefs.FONT_SIZES[s.fontSize] + 'px');
        root.style.setProperty('--reader-line-height', readerPrefs.LINE_HEIGHTS[s.lineHeight]);
        // Reflect into dialog controls
        dialog.querySelector(`input[name="fontSize"][value="${s.fontSize}"]`)?.checked = true;
        dialog.querySelector(`input[name="lineHeight"][value="${s.lineHeight}"]`)?.checked = true;
        dialog.querySelector(`input[name="theme"][value="${s.theme}"]`)?.checked = true;
        dialog.querySelector('input[name="autoScrollSpeed"]').value = s.autoScrollSpeed;
        dialog.querySelector('[data-bind="speedLabel"]').textContent = s.autoScrollSpeed;
    }
    applySettingsToUI();
    const unsubPrefs = readerPrefs.subscribe(() => applySettingsToUI());

    // 4. Settings change handlers — let the dialog's `change` event bubble.
    dialog.addEventListener('change', (e) => {
        const t = e.target;
        if (t.name === 'autoScrollSpeed') {
            readerPrefs.saveSettings({ autoScrollSpeed: parseInt(t.value, 10) });
        } else if (t.name) {
            readerPrefs.saveSettings({ [t.name]: t.value });
        }
    });

    // 5. Auto-scroll via rAF + float truncation fix.
    let isScrolling = false;
    let currentScrollTop = 0;
    let scrollRafId = null;
    function scrollLoop() {
        if (!isScrolling) return;
        const speed = readerPrefs.getSettings().autoScrollSpeed;
        const pxPerFrame = speed * 0.5;
        currentScrollTop += pxPerFrame;
        els.content.scrollTop = currentScrollTop;
        // Re-sync if browser clamped (e.g. reached bottom).
        if (Math.abs(els.content.scrollTop - currentScrollTop) > 1) {
            currentScrollTop = els.content.scrollTop;
        }
        scrollRafId = requestAnimationFrame(scrollLoop);
    }
    scrollBtn.addEventListener('click', () => {
        isScrolling = !isScrolling;
        scrollBtn.textContent = isScrolling ? '⏸' : '▶';
        if (isScrolling) {
            currentScrollTop = els.content.scrollTop;
            scrollRafId = requestAnimationFrame(scrollLoop);
        } else if (scrollRafId !== null) {
            cancelAnimationFrame(scrollRafId);
            scrollRafId = null;
        }
    });
    document.addEventListener('visibilitychange', () => {
        if (document.hidden && isScrolling) {
            isScrolling = false;
            scrollBtn.textContent = '▶';
            if (scrollRafId !== null) { cancelAnimationFrame(scrollRafId); scrollRafId = null; }
        }
    });

    // 6. Render chapter text as <p> elements (replaces textContent-on-container).
    // Keeps XSS safety (each <p> set via textContent) and enables per-paragraph
    // hover bookmark button.
    function renderParagraphs(content) {
        const paras = (content || '').split('\n\n').filter(p => p.trim());
        els.content.innerHTML = '';
        paras.forEach((text, idx) => {
            const p = document.createElement('p');
            p.textContent = text;  // XSS safe
            p.dataset.paraIndex = idx;
            // Hover bookmark button
            const btn = document.createElement('button');
            btn.className = 'text-reader__para-bookmark';
            btn.type = 'button';
            btn.textContent = '+';
            btn.title = '添加书签';
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                const ok = readerPrefs.addBookmark({
                    bookPath: path,
                    chapterIndex: currentIdx,
                    paragraphIndex: idx,
                    preview: text.slice(0, 30),
                    createdAt: Date.now(),
                });
                showToast(ok ? '已添加书签' : '已存在书签', ok ? 'success' : 'info');
            });
            p.appendChild(btn);
            els.content.appendChild(p);
        });
    }

    // 7. TOC Tab (目录 / 书签).
    function renderDrawerTabs() {
        const tabs = document.createElement('div');
        tabs.className = 'text-reader__tabs';
        tabs.innerHTML = `
            <button class="text-reader__tab text-reader__tab--active" data-tab="toc">目录</button>
            <button class="text-reader__tab" data-tab="bookmarks">书签 (<span data-bm-count>0</span>)</button>
        `;
        const panel = document.createElement('div');
        panel.className = 'text-reader__tab-panel';
        els.drawer.innerHTML = '';
        els.drawer.appendChild(tabs);
        els.drawer.appendChild(panel);
        function refresh(tab) {
            panel.innerHTML = '';
            if (tab === 'toc') {
                (book.chapters || []).forEach((ch, i) => {
                    const btn = document.createElement('button');
                    btn.className = 'text-reader__drawer-item';
                    btn.textContent = ch.title || `第 ${i + 1} 章`;
                    btn.addEventListener('click', () => {
                        loadChapter(i);
                        closeDrawer();
                    });
                    panel.appendChild(btn);
                });
            } else {
                const bms = readerPrefs.getBookmarks(path);
                tabs.querySelector('[data-bm-count]').textContent = bms.length;
                if (bms.length === 0) {
                    panel.innerHTML = '<div class="text-reader__empty">暂无书签，悬停段落 + 添加</div>';
                    return;
                }
                bms.forEach(bm => {
                    const row = document.createElement('div');
                    row.className = 'text-reader__drawer-item';
                    const title = document.createElement('span');
                    title.textContent = `第 ${bm.chapterIndex + 1} 章 · ${bm.preview}`;
                    const del = document.createElement('button');
                    del.className = 'text-reader__drawer-del';
                    del.textContent = '✕';
                    del.addEventListener('click', (e) => {
                        e.stopPropagation();
                        readerPrefs.removeBookmark(bm);
                        renderDrawer.refresh('bookmarks');
                    });
                    row.appendChild(title);
                    row.appendChild(del);
                    row.addEventListener('click', () => {
                        loadChapter(bm.chapterIndex).then(() => {
                            const target = els.content.querySelector(`p[data-para-index="${bm.paragraphIndex}"]`);
                            target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
                            closeDrawer();
                        });
                    });
                    panel.appendChild(row);
                });
            }
        }
        tabs.querySelectorAll('.text-reader__tab').forEach(btn => {
            btn.addEventListener('click', () => {
                tabs.querySelectorAll('.text-reader__tab').forEach(b => b.classList.remove('text-reader__tab--active'));
                btn.classList.add('text-reader__tab--active');
                refresh(btn.dataset.tab);
            });
        });
        renderDrawer.refresh = refresh;
        refresh('toc');
    }

    // 8. Re-render bookmarks tab when prefs change.
    const unsubBms = readerPrefs.subscribe((e) => {
        if (e.detail?.type === 'bookmarks' && renderDrawer.refresh) {
            const activeTab = els.drawer.querySelector('.text-reader__tab--active')?.dataset.tab;
            renderDrawer.refresh(activeTab || 'toc');
        }
    });

    // Cleanup on re-render: container.innerHTML gets cleared next time, so we
    // stash unsubscribers + rAF cancellation on the container node.
    container._cleanupReader = () => {
        unsubPrefs();
        unsubBms();
        if (scrollRafId !== null) cancelAnimationFrame(scrollRafId);
    };

    renderDrawerTabs();
    await loadChapter(startIdx);

    // loadChapter: fetch one chapter by zero-based index and update the view.
    // Guarded by isLoadingChapter so rapid prev/next clicks do not race and
    // render a stale chapter over a newer one.
    async function loadChapter(idx) {
        if (isLoadingChapter) return;
        if (idx < 0 || idx >= chapterCount) return;
        isLoadingChapter = true;
        currentIdx = idx;
        try {
            const chapter = await getBookChapter(path, idx);
            els.title.textContent = `${chapter.title || ''} — ${book.title || ''}`;
            // Per-paragraph rendering preserves XSS safety and enables the
            // hover-to-add bookmark button (each <p> uses textContent).
            renderParagraphs(chapter.content || '');
            els.progress.textContent = `第 ${idx + 1} / ${chapterCount} 章`;
            els.content.scrollTop = 0;
            saveProgress(path, { chapterIndex: idx, scrollOffset: 0, lastReadAt: Date.now() });
        } catch (e) {
            els.content.textContent = '加载章节失败: ' + e.message;
            showToast('加载章节失败: ' + e.message, 'error');
        } finally {
            isLoadingChapter = false;
        }
    }
}

// localStorage-backed progress persistence. Keyed by absolute path so multiple
// books each remember their own chapter. Failures (private mode, quota) are
// swallowed — progress saving is best-effort, never fatal.
function loadProgress(path) {
    try {
        return JSON.parse(localStorage.getItem(STORAGE_PREFIX + path) || 'null');
    } catch (e) {
        return null;
    }
}

function saveProgress(path, p) {
    try {
        localStorage.setItem(STORAGE_PREFIX + path, JSON.stringify(p));
    } catch (e) {
        // Quota / private mode — silently ignore.
    }
}

function clamp(n, lo, hi) {
    return Math.max(lo, Math.min(hi, n));
}

// Cache DOM references once per render so we don't query on every chapter load.
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
        drawer: root.querySelector('.text-reader__drawer'),
    };
}

// Lazy-populate the TOC drawer on first open so we don't build N <a> tags up
// front for very-large books. Subsequent opens just toggle visibility.
function toggleDrawer(book, drawerEl) {
    const willOpen = drawerEl.classList.contains('text-reader__drawer--hidden');
    drawerEl.classList.toggle('text-reader__drawer--hidden');
    drawerEl.setAttribute('aria-hidden', String(!willOpen));

    if (!drawerEl.dataset.populated) {
        drawerEl.innerHTML = '<h3>目录</h3>';
        (book.chapters || []).forEach((ch, i) => {
            const a = document.createElement('a');
            a.href = '#';
            a.className = 'text-reader__toc-item';
            a.textContent = ch.title || `第 ${i + 1} 章`;
            a.addEventListener('click', (e) => {
                e.preventDefault();
                drawerEl.dispatchEvent(new CustomEvent('chapter-select', { detail: i }));
            });
            drawerEl.appendChild(a);
        });
        drawerEl.dataset.populated = '1';
    }
}

// TextReader feature module (Task 15): online novel reader for .txt / .epub
// files. Renders into #view-reader (a dedicated view-section managed by the
// router), fetches book info + chapter text from the server books API, and
// persists the last-read chapter index to localStorage so reloads resume.
//
// Mirrors the Android TextReaderViewModel contract: /api/v1/books/info and
// /api/v1/books/chapter. The unsupported formats (.mobi / .azw3) never reach
// this module — browserView intercepts them and shows a "暂不支持" toast.
import { getBookInfo, getBookChapter, getAuthToken } from './api.js';
import { showToast } from './toast.js';
import * as readerPrefs from './readerPrefs.js';

const STORAGE_PREFIX = 'book_progress:';

// Entry point invoked by router.js when the hash is #/read?path=...
//
// The container is the #view-reader section element. We always start by
// clearing any previous render so re-navigating to a different book does not
// leak DOM or event listeners from the previous one.
export async function renderTextReader(container, path, chapterParam, paraParam) {
    // Invoke prior cleanup FIRST so listener/rAF leaks from a previous render
    // (if any) are released before we wipe innerHTML below.
    if (typeof container._cleanupReader === 'function') container._cleanupReader();

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
        
        <!-- Floating Autoscroll Control Panel -->
        <div class="text-reader__autoscroll-panel text-reader__autoscroll-panel--hidden" id="autoscroll-panel">
            <button class="autoscroll-panel-btn" id="autoscroll-panel-play">⏸</button>
            <button class="autoscroll-panel-btn" id="autoscroll-panel-minus" title="减速">-</button>
            <span class="autoscroll-panel-text">速度: <span id="autoscroll-val-speed">5</span></span>
            <button class="autoscroll-panel-btn" id="autoscroll-panel-plus" title="加速">+</button>
        </div>
    `;

    const els = bindEls(container);

    function scrollToParagraph(paraIdx) {
        let attempts = 0;
        const maxAttempts = 15; // retry up to 1.5 seconds for complete layout reflow
        function tryScroll() {
            const target = els.content.querySelector(`p[data-para-index="${paraIdx}"]`);
            if (target) {
                const targetY = Math.max(0, target.offsetTop - 16);
                els.content.scrollTop = targetY;
                // Double check if we actually scrolled close to targetY (unless content height is too small)
                if (Math.abs(els.content.scrollTop - targetY) > 5 && attempts < maxAttempts) {
                    attempts++;
                    setTimeout(tryScroll, 100);
                }
            } else if (attempts < maxAttempts) {
                attempts++;
                setTimeout(tryScroll, 100);
            }
        }
        setTimeout(tryScroll, 50);
    }

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
    
    let startIdx = 0;
    if (chapterParam !== undefined && chapterParam !== null) {
        startIdx = clamp(parseInt(chapterParam, 10) || 0, 0, Math.max(0, chapterCount - 1));
    } else {
        const progress = loadProgress(path);
        if (progress) {
            startIdx = clamp(progress.chapterIndex || 0, 0, Math.max(0, chapterCount - 1));
        }
    }

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

    // Left 20% / right 20% = prev/next chapter; middle 60% = toggle immersive
    // (Phase 5). Middle hot-zone only toggles chrome when the user has enabled
    // immersiveMode in settings — otherwise middle clicks are inert so legacy
    // users do not see accidental chrome hide/show.
    els.content.addEventListener('mousemove', (e) => {
        if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) {
            els.content.style.cursor = 'default';
            return;
        }
        const rect = els.content.getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;
        if (ratio < 0.20 || ratio > 0.80) {
            els.content.style.cursor = 'pointer';  // 翻章热区
        } else if (readerPrefs.getSettings().immersiveMode) {
            els.content.style.cursor = 'pointer';  // 可切换沉浸
        } else {
            els.content.style.cursor = 'default';
        }
    });

    els.content.addEventListener('click', (e) => {
        if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) return;
        if (window.getSelection() && window.getSelection().toString().trim() !== '') return;

        const rect = els.content.getBoundingClientRect();
        const ratio = (e.clientX - rect.left) / rect.width;

        if (ratio < 0.20) {
            if (currentIdx > 0) {
                loadChapter(currentIdx - 1);
            } else {
                showToast('已经是第一章了', 'info');
            }
        } else if (ratio > 0.80) {
            if (currentIdx < chapterCount - 1) {
                loadChapter(currentIdx + 1);
            } else {
                showToast('已经是最后一章了', 'info');
            }
        } else {
            // 中区域：仅在用户启用沉浸模式时切换
            if (readerPrefs.getSettings().immersiveMode) {
                if (isImmersive) exitImmersive(); else enterImmersive();
            }
        }
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
            <header class="reader-settings__header">
                <h3>阅读设置</h3>
                <button type="submit" class="reader-settings__close" aria-label="关闭">×</button>
            </header>
            <div class="reader-settings__body">

                <section class="reader-settings__group">
                    <h4>外观</h4>

                    <div class="reader-settings__row">
                        <span>字体</span>
                        <div class="reader-settings__font-row">
                            ${['SYSTEM','SERIF','KAITI'].map(v =>
                                `<label><input type="radio" name="fontFamily" value="${v}"> ${ {SYSTEM:'无衬线',SERIF:'宋体',KAITI:'楷体'}[v] }</label>`
                            ).join('')}
                        </div>
                    </div>

                    <div class="reader-settings__theme-grid">
                        ${[
                            ['DAY','日间·纸白'],['DAY_BRIGHT','日间·亮白'],['EYE_CARE','护眼·米黄'],
                            ['PARCHMENT','羊皮纸'],['NIGHT','夜间·深空'],['NIGHT_BLACK','夜间·纯黑'],
                            ['AUTO','跟随系统'],
                        ].map(([v,label]) =>
                            `<label class="reader-settings__theme-opt">
                                <input type="radio" name="theme" value="${v}">
                                <span class="reader-settings__theme-swatch" data-theme="${v}"></span>
                                <span class="reader-settings__theme-label">${label}</span>
                            </label>`
                        ).join('')}
                    </div>
                </section>

                <section class="reader-settings__group">
                    <h4>字号与行距</h4>
                    <label class="reader-settings__slider-row">
                        <span>字号</span>
                        <input type="range" name="fontSizeSlider" min="12" max="28" step="1" value="16">
                        <output data-bind="fontSizeLabel">16 px</output>
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>行距</span>
                        <input type="range" name="lineHeightSlider" min="1.3" max="2.5" step="0.1" value="1.8">
                        <output data-bind="lineHeightLabel">1.8</output>
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>宽度</span>
                        <input type="range" name="contentWidthSlider" min="600" max="900" step="10" value="720">
                        <output data-bind="contentWidthLabel">720 px</output>
                    </label>
                </section>

                <section class="reader-settings__group">
                    <h4>段落</h4>
                    <label class="reader-settings__toggle-row">
                        <span>首行缩进</span>
                        <input type="checkbox" name="firstLineIndent" checked>
                    </label>
                    <label class="reader-settings__toggle-row">
                        <span>段间距</span>
                        <input type="checkbox" name="paragraphSpacing">
                    </label>
                </section>

                <section class="reader-settings__group">
                    <h4>行为</h4>
                    <label class="reader-settings__toggle-row">
                        <span>沉浸模式</span>
                        <input type="checkbox" name="immersiveMode">
                    </label>
                    <label class="reader-settings__slider-row">
                        <span>自动滚动速度</span>
                        <input type="range" name="autoScrollSpeed" min="1" max="10" value="5">
                        <output data-bind="speedLabel">5</output>
                    </label>
                </section>

            </div>
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
    // Phase 3: settings are V2-shape (numeric fontSize/lineHeight/contentWidth,
    // boolean firstLineIndent/paragraphSpacing, fontFamily enum). The dialog
    // exposes continuous sliders + paragraph toggles + 3 font radio options.
    function applySettingsToUI() {
        const s = readerPrefs.getSettings();
        const root = document.documentElement;

        // AUTO resolves to DAY/NIGHT based on prefers-color-scheme
        let themeKey = s.theme;
        if (themeKey === 'AUTO') {
            const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
            themeKey = isDark ? 'NIGHT' : 'DAY';
        }
        const theme = readerPrefs.THEME_PRESETS[themeKey];

        // Body + chrome CSS variables
        root.style.setProperty('--reader-bg', theme.bg);
        root.style.setProperty('--reader-fg', theme.fg);
        root.style.setProperty('--reader-chrome-bg', theme.chromeBg);
        root.style.setProperty('--reader-chrome-fg', theme.chromeFg);
        root.style.setProperty('--reader-muted', theme.muted);
        root.style.setProperty('--reader-border', theme.border);
        root.style.setProperty('--reader-font-size', s.fontSize + 'px');
        root.style.setProperty('--reader-line-height', String(s.lineHeight));
        root.style.setProperty('--reader-font-family', readerPrefs.FONT_FAMILIES[s.fontFamily] || readerPrefs.FONT_FAMILIES.SYSTEM);
        root.style.setProperty('--reader-content-width', s.contentWidth + 'px');

        // Overall App-variable override driven by data-reader-theme attribute
        document.body.dataset.readerTheme = themeKey;

        // Reflect into dialog controls (V2 sliders + font radio + toggles).
        const ffInput = dialog.querySelector(`input[name="fontFamily"][value="${s.fontFamily}"]`);
        if (ffInput) ffInput.checked = true;
        const fontSizeSlider = dialog.querySelector('input[name="fontSizeSlider"]');
        if (fontSizeSlider) {
            fontSizeSlider.value = s.fontSize;
            const fsLabel = dialog.querySelector('[data-bind="fontSizeLabel"]');
            if (fsLabel) fsLabel.textContent = s.fontSize + ' px';
        }
        const lhSlider = dialog.querySelector('input[name="lineHeightSlider"]');
        if (lhSlider) {
            lhSlider.value = s.lineHeight;
            const lhLabel = dialog.querySelector('[data-bind="lineHeightLabel"]');
            if (lhLabel) lhLabel.textContent = s.lineHeight.toFixed(1);
        }
        const cwSlider = dialog.querySelector('input[name="contentWidthSlider"]');
        if (cwSlider) {
            cwSlider.value = s.contentWidth;
            const cwLabel = dialog.querySelector('[data-bind="contentWidthLabel"]');
            if (cwLabel) cwLabel.textContent = s.contentWidth + ' px';
        }
        const indentToggle = dialog.querySelector('input[name="firstLineIndent"]');
        if (indentToggle) indentToggle.checked = s.firstLineIndent;
        const gapToggle = dialog.querySelector('input[name="paragraphSpacing"]');
        if (gapToggle) gapToggle.checked = s.paragraphSpacing;
        const immersiveToggle = dialog.querySelector('input[name="immersiveMode"]');
        if (immersiveToggle) immersiveToggle.checked = s.immersiveMode;
        const themeInput = dialog.querySelector(`input[name="theme"][value="${s.theme}"]`);
        if (themeInput) themeInput.checked = true;
        const speedSlider = dialog.querySelector('input[name="autoScrollSpeed"]');
        if (speedSlider) speedSlider.value = s.autoScrollSpeed;
        const speedLabel = dialog.querySelector('[data-bind="speedLabel"]');
        if (speedLabel) speedLabel.textContent = s.autoScrollSpeed;
        if (els.autoscrollSpeedVal) {
            els.autoscrollSpeedVal.textContent = s.autoScrollSpeed;
        }
    }
    applySettingsToUI();
    const unsubPrefs = readerPrefs.subscribe(() => applySettingsToUI());

    // === Phase 5: 沉浸模式状态机 ===
    // isImmersive tracks whether chrome is currently hidden. The body's
    // data-reader-immersive attribute drives the CSS slide/fade transitions
    // (see style.css). scheduleImmersiveEntry() shows chrome for 1.5s on book
    // load (a visual anchor) before hiding — only if immersiveMode is on.
    let isImmersive = false;
    function enterImmersive() {
        isImmersive = true;
        document.body.dataset.readerImmersive = 'on';
    }
    function exitImmersive() {
        isImmersive = false;
        delete document.body.dataset.readerImmersive;
    }

    let immersiveEntryTimer = null;
    function scheduleImmersiveEntry() {
        if (immersiveEntryTimer) clearTimeout(immersiveEntryTimer);
        if (readerPrefs.getSettings().immersiveMode) {
            exitImmersive();  // 先显示栏作为视觉锚点
            immersiveEntryTimer = setTimeout(() => {
                if (readerPrefs.getSettings().immersiveMode) enterImmersive();
            }, 1500);
        } else {
            exitImmersive();
        }
    }
    scheduleImmersiveEntry();

    // Esc 退出沉浸（键盘可达性 + 误触恢复路径）。
    function onKeyDown(e) {
        if (e.key === 'Escape' && isImmersive) {
            exitImmersive();
        }
    }
    document.addEventListener('keydown', onKeyDown);

    // AUTO follow-system: re-resolve when OS dark/light changes
    const mediaDark = window.matchMedia('(prefers-color-scheme: dark)');
    function onSystemColorSchemeChange() {
        if (readerPrefs.getSettings().theme === 'AUTO') applySettingsToUI();
    }
    mediaDark.addEventListener('change', onSystemColorSchemeChange);

    // 4. Settings change handlers — let the dialog's `change` event bubble.
    dialog.addEventListener('change', (e) => {
        const t = e.target;
        if (t.name === 'fontSizeSlider') {
            readerPrefs.saveSettings({ fontSize: parseInt(t.value, 10) });
        } else if (t.name === 'lineHeightSlider') {
            readerPrefs.saveSettings({ lineHeight: parseFloat(t.value) });
        } else if (t.name === 'contentWidthSlider') {
            readerPrefs.saveSettings({ contentWidth: parseInt(t.value, 10) });
        } else if (t.name === 'firstLineIndent' || t.name === 'paragraphSpacing' || t.name === 'immersiveMode') {
            readerPrefs.saveSettings({ [t.name]: t.checked });
        } else if (t.name === 'fontFamily') {
            readerPrefs.saveSettings({ fontFamily: t.value });
        } else if (t.name === 'autoScrollSpeed') {
            readerPrefs.saveSettings({ autoScrollSpeed: parseInt(t.value, 10) });
        } else if (t.name) {
            readerPrefs.saveSettings({ [t.name]: t.value });
        }
    });

    // 5. Auto-scroll via rAF
    let isScrolling = false;
    let currentScrollTop = 0;
    let scrollRafId = null;
    let autoNextChapterTimer = null;

    function startAutoScroll() {
        currentScrollTop = els.content.scrollTop;
        scrollLoop();
    }

    function stopAutoScroll() {
        isScrolling = false;
        scrollBtn.textContent = '▶';
        if (els.autoscrollPlay) els.autoscrollPlay.textContent = '▶';
        if (scrollRafId !== null) {
            cancelAnimationFrame(scrollRafId);
            scrollRafId = null;
        }
        if (autoNextChapterTimer) {
            clearTimeout(autoNextChapterTimer);
            autoNextChapterTimer = null;
        }
    }

    function scrollLoop() {
        if (!isScrolling) return;

        const speed = readerPrefs.getSettings().autoScrollSpeed;
        const pxPerFrame = speed * 0.15; // Smooth, readable slower scroll speeds
        currentScrollTop += pxPerFrame;
        els.content.scrollTop = currentScrollTop;

        // Re-sync if browser clamped
        if (Math.abs(els.content.scrollTop - currentScrollTop) > 1) {
            currentScrollTop = els.content.scrollTop;
        }

        // Check if reached bottom of current chapter
        if (els.content.scrollTop + els.content.clientHeight >= els.content.scrollHeight - 5) {
            stopAutoScroll();
            showToast('已到达本章底部，即将载入下一章...', 'info');
            autoNextChapterTimer = setTimeout(() => {
                if (currentIdx < chapterCount - 1) {
                    loadChapter(currentIdx + 1);
                    currentScrollTop = 0;
                    els.content.scrollTop = 0;
                    isScrolling = true;
                    scrollBtn.textContent = '⏸';
                    if (els.autoscrollPlay) els.autoscrollPlay.textContent = '⏸';
                    startAutoScroll();
                } else {
                    showToast('已读完本书最后一章', 'success');
                }
            }, 2000);
            return;
        }

        scrollRafId = requestAnimationFrame(scrollLoop);
    }

    scrollBtn.addEventListener('click', () => {
        isScrolling = !isScrolling;
        scrollBtn.textContent = isScrolling ? '⏸' : '▶';
        if (els.autoscrollPanel) {
            els.autoscrollPanel.classList.toggle('text-reader__autoscroll-panel--hidden', !isScrolling);
            if (els.autoscrollPlay) els.autoscrollPlay.textContent = isScrolling ? '⏸' : '▶';
        }
        if (isScrolling) {
            startAutoScroll();
        } else {
            stopAutoScroll();
        }
    });

    if (els.autoscrollPlay) {
        els.autoscrollPlay.addEventListener('click', () => {
            isScrolling = !isScrolling;
            scrollBtn.textContent = isScrolling ? '⏸' : '▶';
            els.autoscrollPlay.textContent = isScrolling ? '⏸' : '▶';
            if (isScrolling) {
                startAutoScroll();
            } else {
                stopAutoScroll();
            }
        });
    }

    if (els.autoscrollMinus) {
        els.autoscrollMinus.addEventListener('click', () => {
            const s = readerPrefs.getSettings();
            const nextSpeed = Math.max(1, s.autoScrollSpeed - 1);
            readerPrefs.saveSettings({ autoScrollSpeed: nextSpeed });
        });
    }

    if (els.autoscrollPlus) {
        els.autoscrollPlus.addEventListener('click', () => {
            const s = readerPrefs.getSettings();
            const nextSpeed = Math.min(10, s.autoScrollSpeed + 1);
            readerPrefs.saveSettings({ autoScrollSpeed: nextSpeed });
        });
    }

    function onVisibilityChange() {
        if (document.hidden && isScrolling) {
            stopAutoScroll();
            if (els.autoscrollPanel) {
                els.autoscrollPanel.classList.add('text-reader__autoscroll-panel--hidden');
            }
        }
    }
    document.addEventListener('visibilitychange', onVisibilityChange);

    // 6. Render chapter content block-by-block. Each block is either:
    //   - {type:'text',  value:string}  → <p> with textContent (XSS safe) +
    //                                     hover-to-add-bookmark button.
    //   - {type:'image', src:string}    → <img loading='lazy'> with the token
    //                                     appended as a query param (browsers
    //                                     cannot set Authorization headers on
    //                                     <img src> requests). A missing src
    //                                     renders an alt-text placeholder.
    //
    // Block index replaces the old paragraphIndex so C-phase bookmark scroll
    // (p[data-para-index]) still works — we set both dataset attributes.
    function renderBlocks(blocks) {
        els.content.innerHTML = '';
        (blocks || []).forEach((block, idx) => {
            if (block && block.type === 'image') {
                const img = document.createElement('img');
                img.className = 'text-reader__image';
                img.loading = 'lazy';
                if (block.src) {
                    // src is server-controlled (already URL-encoded by the
                    // service) and points at our own /api/v1/books/image —
                    // safe to assign directly.
                    img.src = appendTokenQueryParam(block.src);
                } else {
                    img.alt = '[本图片无法显示]';
                }
                els.content.appendChild(img);
                return;
            }
            // Default / text block (also tolerates unknown types by treating
            // their .value as text so a future server addition never injects
            // untrusted HTML).
            const text = (block && typeof block.value === 'string') ? block.value : '';
            const p = document.createElement('p');
            p.textContent = text;  // XSS safe
            p.dataset.blockIndex = String(idx);
            p.dataset.paraIndex = String(idx);  // C-phase bookmark scroll compat
            // Phase 3: per-paragraph indent/gap classes driven by V2 toggles.
            // Class names map 1:1 to CSS rules in style.css.
            const indent = readerPrefs.getSettings().firstLineIndent ? 'indent-on' : 'indent-off';
            const gap = readerPrefs.getSettings().paragraphSpacing ? 'gap-on' : 'gap-off';
            p.className = `text-reader__p ${indent} ${gap}`;
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
                        drawer.refresh('bookmarks');
                    });
                    row.appendChild(title);
                    row.appendChild(del);
                    row.addEventListener('click', async () => {
                        if (currentIdx !== bm.chapterIndex) {
                            await loadChapter(bm.chapterIndex);
                        }
                        scrollToParagraph(bm.paragraphIndex);
                        closeDrawer();
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
        refresh('toc');
        return { refresh };
    }

    // 8. Re-render bookmarks tab when prefs change.
    const drawer = renderDrawerTabs();
    const unsubBms = readerPrefs.subscribe((e) => {
        if (e.detail?.type === 'bookmarks') {
            const activeTab = els.drawer.querySelector('.text-reader__tab--active')?.dataset.tab;
            drawer.refresh(activeTab || 'toc');
        }
    });

    // Cleanup on re-render: container.innerHTML gets cleared next time, so we
    // stash subscribers + rAF cancellation on the container node.
    container._cleanupReader = () => {
        unsubPrefs();
        unsubBms();
        document.removeEventListener('visibilitychange', onVisibilityChange);
        document.removeEventListener('keydown', onKeyDown);
        mediaDark.removeEventListener('change', onSystemColorSchemeChange);
        delete document.body.dataset.readerTheme;
        if (immersiveEntryTimer) clearTimeout(immersiveEntryTimer);
        exitImmersive();
        if (scrollRafId !== null) cancelAnimationFrame(scrollRafId);
        if (autoNextChapterTimer) clearTimeout(autoNextChapterTimer);
    };

    await loadChapter(startIdx);

    // If an initial paragraph bookmark parameter is specified in the URL, scroll to it
    if (paraParam !== undefined && paraParam !== null) {
        scrollToParagraph(parseInt(paraParam, 10));
    }

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
            // Per-block rendering preserves XSS safety (each <p> uses
            // textContent) and renders inline <img> for image blocks. Falls
            // back to splitting legacy chapter.content on blank lines when the
            // server response does not yet include blocks.
            renderBlocks(chapter.blocks || blocksFromLegacyContent(chapter.content));
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

// blocksFromLegacyContent converts an old-style chapter.content string into
// the new block array shape so this client keeps working even if a chapter
// payload lacks the (newer) `blocks` field. Each blank-line-separated
// paragraph becomes a {type:'text'} block.
function blocksFromLegacyContent(content) {
    return (content || '')
        .split('\n\n')
        .filter(p => p.trim())
        .map(p => ({ type: 'text', value: p }));
}

// appendTokenQueryParam adds ?token=... (or &token=...) to a URL so that
// <img> tags — which cannot set Authorization headers — can authenticate
// against /api/v1/books/image. Returns the URL unchanged when no token is
// configured (open-auth-mode servers).
function appendTokenQueryParam(url) {
    const token = getAuthToken();
    if (!token) return url;
    const sep = url.includes('?') ? '&' : '?';
    return url + sep + 'token=' + encodeURIComponent(token);
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
        autoscrollPanel: root.querySelector('#autoscroll-panel'),
        autoscrollPlay: root.querySelector('#autoscroll-panel-play'),
        autoscrollMinus: root.querySelector('#autoscroll-panel-minus'),
        autoscrollPlus: root.querySelector('#autoscroll-panel-plus'),
        autoscrollSpeedVal: root.querySelector('#autoscroll-val-speed'),
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

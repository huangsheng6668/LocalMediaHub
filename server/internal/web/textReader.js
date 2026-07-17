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
        // Auto-close drawer on selection for small-screen usability.
        els.drawer.classList.add('text-reader__drawer--hidden');
        els.drawer.setAttribute('aria-hidden', 'true');
    });

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
            // textContent (not innerHTML) prevents any XSS from chapter text —
            // matches the security note in the brief and the Android client.
            els.title.textContent = `${chapter.title || ''} — ${book.title || ''}`;
            els.content.textContent = chapter.content || '';
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

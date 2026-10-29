// Bookshelf feature module (Task 16): surfaces every persisted reading-progress
// entry from localStorage as a clickable card. Two entry points:
//
//   render(container)       — full-page grid used by the #/bookshelf route.
//   renderSection(container) — dashboard-embedded preview that auto-hides
//                              when no progress entries exist.
//
// Storage keys mirror textReader.js: `book_progress:<path>` JSON, payload
// shape { chapterIndex, paraIndex, lastReadAt }. Sorting is lastReadAt desc
// so the most-recently-read book shows up first.
// Reading duration ("已读 X") is patched in asynchronously from server
// decorations (spec 2026-09-08); offline/failure degrades to progress-only meta.
//
// Search (2026-09-30): the full-page route carries a debounced search box that
// filters the in-memory localStorage list locally (filterBookshelf) — see the
// "search" section in render() below. The dashboard embed stays search-free.
import { fetchDecorationsFor } from './library.js';

const PREFIX = 'book_progress:';

// Deterministic gradient pick: same title → same cover color, always one of
// g1..g8. Gradients live in CSS classes (CSP-safe, no inline styles).
export function coverGradientClass(title) {
    let h = 0;
    const s = String(title || '');
    for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) >>> 0;
    return `bookshelf-card__cover--g${(h % 8) + 1}`;
}

export function relativeTime(ts) {
    if (!ts) return '';
    const diff = Date.now() - ts;
    const m = Math.floor(diff / 60000);
    if (m < 1) return '刚刚';
    if (m < 60) return `${m} 分钟前`;
    const h = Math.floor(m / 60);
    if (h < 24) return `${h} 小时前`;
    const d = Math.floor(h / 24);
    if (d < 30) return `${d} 天前`;
    const mo = Math.floor(d / 30);
    if (mo < 12) return `${mo} 个月前`;
    return `${Math.floor(mo / 12)} 年前`;
}

// 阅读累计秒数 → 展示文案（spec 2026-09-08）；<60s 返回空串（不值得展示）。
export function formatReadDuration(sec) {
    if (!sec || sec < 60) return '';
    if (sec < 3600) return `${Math.floor(sec / 60)} 分钟`;
    return `${(sec / 3600).toFixed(1)} 小时`;
}

// Scan localStorage for book_progress:* entries whose path ends in .txt/.epub
// (defensive: legacy keys or unrelated entries should be ignored), parse each
// payload, then sort by lastReadAt descending.
function loadAll() {
    const out = [];
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key || !key.startsWith(PREFIX)) continue;
        const path = key.slice(PREFIX.length);
        const ext = (path.split('.').pop() || '').toLowerCase();
        if (ext !== 'txt' && ext !== 'epub') continue;
        try {
            const parsed = JSON.parse(localStorage.getItem(key));
            if (!parsed || typeof parsed !== 'object') continue;
            out.push(Object.assign({ path }, parsed));
        } catch (e) {
            // Corrupted entry — skip rather than crash the whole shelf.
        }
    }
    out.sort((a, b) => (b.lastReadAt || 0) - (a.lastReadAt || 0));
    return out;
}

function baseName(path) {
    const tail = path.split(/[\\/]/).pop() || '';
    return tail.replace(/\.[^.]+$/, '');
}

// Bookshelf search filter. Matches the display title (baseName, i.e. the
// filename without extension) case-insensitively, and also the full path so a
// user who remembers the folder rather than the filename still finds the book.
//
// An empty/whitespace query returns the input array unchanged (same reference)
// so the caller's "no active search" branch stays allocation-free.
export function filterBookshelf(list, query) {
    const q = String(query == null ? '' : query).trim().toLowerCase();
    if (!q) return list;
    return list.filter((entry) => {
        const path = String((entry && entry.path) || '');
        return baseName(path).toLowerCase().includes(q) || path.toLowerCase().includes(q);
    });
}

function renderCard(entry) {
    const card = document.createElement('div');
    card.className = 'bookshelf-card';
    card.dataset.path = entry.path;
    const title = baseName(entry.path);
    const meta = `第 ${(entry.chapterIndex || 0) + 1} 章 · ${relativeTime(entry.lastReadAt)}`;
    // XSS-SAFE: pure-literal template; user data (title/meta) is set via textContent below
    card.innerHTML = `
        <div class="bookshelf-card__cover ${coverGradientClass(title)}">
            <span class="bookshelf-card__cover-title"></span>
        </div>
        <div class="bookshelf-card__meta">
            <div class="bookshelf-card__title"></div>
            <div class="bookshelf-card__progress"></div>
        </div>
    `;
    card.querySelector('.bookshelf-card__cover-title').textContent = title.slice(0, 8);
    card.querySelector('.bookshelf-card__title').textContent = title;
    card.querySelector('.bookshelf-card__progress').textContent = meta;
    // 键盘可达性：卡片是 div，需要 role + tabindex 才能被 Tab 到达并激活
    // （此前书架只能靠鼠标点击）。aria-label 用 textContent 写入的同一标题，
    // 保证读屏播报与视觉一致。
    card.setAttribute('role', 'button');
    card.setAttribute('tabindex', '0');
    card.setAttribute('aria-label', `继续阅读 ${title}`);
    const open = () => {
        location.hash = '#/read?path=' + encodeURIComponent(entry.path);
    };
    card.addEventListener('click', open);
    card.addEventListener('keydown', (e) => {
        if (e.key !== 'Enter' && e.key !== ' ') return;
        e.preventDefault(); // Space 的默认行为是滚动页面
        open();
    });
    return card;
}

// Async duration patch: fills "已读 X" into rendered cards from server
// decorations; silent no-op offline (progress-only meta stays).
function patchDurations(grid, paths) {
    fetchDecorationsFor(paths)
        .then(d => applyDurations(grid, d && d.states))
        .catch(() => { /* 服务端不可达：退回纯进度 meta */ });
}

// applyDurations writes cached decorations onto whatever cards are currently in
// the grid. Split out from patchDurations so the searchable bookshelf can fetch
// decorations ONCE and re-apply them after every re-render — otherwise each
// keystroke in the search box would fire its own request.
function applyDurations(grid, states) {
    if (!states) return;
    grid.querySelectorAll('.bookshelf-card').forEach(card => {
        const badge = states[card.dataset.path];
        const dur = formatReadDuration(badge && badge.read_seconds);
        if (!dur) return;
        const el = card.querySelector('.bookshelf-card__progress');
        if (el && !el.textContent.includes('已读')) {
            el.textContent = `${el.textContent} · 已读 ${dur}`;
        }
    });
}

// Search-box debounce: re-rendering the grid on every keystroke is wasted DOM
// work once the shelf holds more than a few dozen books. 120ms is short enough
// to still feel instant while coalescing a fast typist's burst.
const SEARCH_DEBOUNCE_MS = 120;

function debounce(fn, wait) {
    let timer = 0;
    return (...args) => {
        clearTimeout(timer);
        timer = setTimeout(() => fn(...args), wait);
    };
}

// Full-page render for the #/bookshelf route. Always replaces container
// contents so re-navigation does not stack cards.
export function render(container) {
    container.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
    const list = loadAll();
    if (list.length === 0) {
        // XSS-SAFE: pure literal markup
        container.innerHTML = `
            <div class="empty-state">
                <div class="empty-state__icon">
                    <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
                </div>
                <h3 class="empty-state__title">暂无阅读历史</h3>
                <p class="empty-state__desc">在媒体共享库中打开小说或书籍后，将自动在此记录阅读进度。</p>
                <a href="#/browser" class="btn btn-primary empty-state__action">前往媒体库</a>
            </div>
        `;
        return;
    }

    // Toolbar. The shelf is derived entirely from localStorage, so search is a
    // purely local filter over the already-loaded `list` — no /api/v1/search
    // round-trip, unlike the browser view (browserView.js triggerBrowserSearch).
    const toolbar = document.createElement('div');
    toolbar.className = 'bookshelf-toolbar';
    // XSS-SAFE: pure-literal markup; the count is written via textContent below
    toolbar.innerHTML = `
        <div class="search-box">
            <input type="text" class="bookshelf-search-input" placeholder="搜索书名或路径..." aria-label="搜索书架">
            <button class="btn btn-search" type="button" aria-label="搜索"><svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/></svg></button>
        </div>
        <div class="bookshelf-toolbar__count" role="status" aria-live="polite"></div>
    `;

    const grid = document.createElement('div');
    grid.className = 'bookshelf-grid';

    // Distinct from the "暂无阅读历史" state above: here the shelf has entries,
    // the active query just matches none of them.
    const noResult = document.createElement('div');
    noResult.className = 'empty-state bookshelf-no-result';
    noResult.hidden = true;
    // XSS-SAFE: pure literal markup
    noResult.innerHTML = `
        <div class="empty-state__icon">
            <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m21 21-4.3-4.3"/><path d="m8.6 8.6 4.8 4.8"/><path d="m13.4 8.6-4.8 4.8"/></svg>
        </div>
        <h3 class="empty-state__title">没有找到匹配的书籍</h3>
        <p class="empty-state__desc">试试更换关键词，或清空搜索框查看全部阅读历史。</p>
    `;

    container.append(toolbar, grid, noResult);

    const input = toolbar.querySelector('.bookshelf-search-input');
    const btn = toolbar.querySelector('.btn-search');
    const count = toolbar.querySelector('.bookshelf-toolbar__count');

    // Decorations are fetched once (bottom of this function) and re-applied on
    // every re-render, so typing never fires a server request.
    let durations = null;

    const paint = () => {
        const query = input.value;
        const filtered = filterBookshelf(list, query);
        grid.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
        filtered.forEach(entry => grid.appendChild(renderCard(entry)));
        const searching = String(query || '').trim() !== '';
        grid.hidden = filtered.length === 0;
        noResult.hidden = filtered.length !== 0;
        count.textContent = searching
            ? (filtered.length ? `找到 ${filtered.length} 本` : '')
            : `共 ${list.length} 本`;
        applyDurations(grid, durations);
    };

    const paintDebounced = debounce(paint, SEARCH_DEBOUNCE_MS);
    input.addEventListener('input', paintDebounced);
    input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
            e.preventDefault();
            paint(); // flush immediately, skip the debounce window
        } else if (e.key === 'Escape') {
            input.value = '';
            paint();
        }
    });
    if (btn) btn.addEventListener('click', paint);

    paint();

    fetchDecorationsFor(list.map(e => e.path))
        .then(d => {
            durations = d && d.states;
            applyDurations(grid, durations);
        })
        .catch(() => { /* 服务端不可达：退回纯进度 meta */ });
}

// Dashboard embed. Renders nothing (and clears the host) when the shelf is
// empty so the dashboard stays clean for users who never opened a book.
export function renderSection(container) {
    container.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
    const list = loadAll();
    if (list.length === 0) return;

    const section = document.createElement('section');
    section.className = 'widget-card bookshelf-section';
    // XSS-SAFE: pure-literal template; cards appended below set their own textContent
    section.innerHTML = `
        <h2>我的书架</h2>
        <div class="bookshelf-grid"></div>
    `;
    const grid = section.querySelector('.bookshelf-grid');
    const shown = list.slice(0, 6);
    shown.forEach(entry => grid.appendChild(renderCard(entry)));
    container.appendChild(section);
    patchDurations(grid, shown.map(e => e.path));
}

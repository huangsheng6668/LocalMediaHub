// Bookshelf feature module (Task 16): surfaces every persisted reading-progress
// entry from localStorage as a clickable card. Two entry points:
//
//   render(container)       — full-page grid used by the #/bookshelf route.
//   renderSection(container) — dashboard-embedded preview that auto-hides
//                              when no progress entries exist.
//
// Storage keys mirror textReader.js: `book_progress:<path>` JSON, payload
// shape { chapterIndex, scrollOffset, lastReadAt }. Sorting is lastReadAt desc
// so the most-recently-read book shows up first.
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

function renderCard(entry) {
    const card = document.createElement('div');
    card.className = 'bookshelf-card';
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
    card.addEventListener('click', () => {
        location.hash = '#/read?path=' + encodeURIComponent(entry.path);
    });
    return card;
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
    const grid = document.createElement('div');
    grid.className = 'bookshelf-grid';
    list.forEach(entry => grid.appendChild(renderCard(entry)));
    container.appendChild(grid);
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
    list.slice(0, 6).forEach(entry => grid.appendChild(renderCard(entry)));
    container.appendChild(section);
}

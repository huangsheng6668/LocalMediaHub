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
    const chapterText = `第 ${(entry.chapterIndex || 0) + 1} 章`;
    // XSS-SAFE: pure-literal template; user data (title/progress) is set via textContent below
    card.innerHTML = `
        <div class="bookshelf-card__icon">📄</div>
        <div class="bookshelf-card__title"></div>
        <div class="bookshelf-card__progress"></div>
    `;
    card.querySelector('.bookshelf-card__title').textContent = baseName(entry.path);
    card.querySelector('.bookshelf-card__progress').textContent = chapterText;
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
        container.innerHTML = '<div class="text-reader__error">暂无阅读历史</div>'; // XSS-SAFE: hardcoded literal
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

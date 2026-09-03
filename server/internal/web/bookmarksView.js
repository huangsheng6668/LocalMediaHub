import { state } from './state.js';
import { elements } from './dom.js';
import { showToast } from './toast.js';

function loadAllBookmarks() {
    const out = [];
    const PREFIX = 'book_bookmarks:';
    for (let i = 0; i < localStorage.length; i++) {
        const key = localStorage.key(i);
        if (!key || !key.startsWith(PREFIX)) continue;
        try {
            const list = JSON.parse(localStorage.getItem(key));
            if (Array.isArray(list)) {
                list.forEach(bm => {
                    out.push(bm);
                });
            }
        } catch (e) {
            // ignore
        }
    }
    out.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    return out;
}

function loadAllBookmarksForBook(path) {
    const raw = localStorage.getItem('book_bookmarks:' + path);
    if (!raw) return [];
    try {
        const list = JSON.parse(raw);
        return Array.isArray(list) ? list : [];
    } catch (_) {
        return [];
    }
}

function baseName(path) {
    const tail = path.split(/[\\/]/).pop() || '';
    return tail.replace(/\.[^.]+$/, '');
}

export function renderBookmarks() {
    const listEl = elements.bookmarksManagerList;
    if (!listEl) return;
    
    listEl.innerHTML = ''; // XSS-SAFE: clearing, no dynamic content
    const bookmarks = loadAllBookmarks();

    if (bookmarks.length === 0) {
        // XSS-SAFE: pure-literal template, no interpolation
        listEl.innerHTML = `
            <div class="empty-state bookmarks-empty-state">
                <div class="empty-state__icon">
                    <svg viewBox="0 0 24 24" width="32" height="32" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
                </div>
                <h3 class="empty-state__title">暂无书签记录</h3>
                <p class="empty-state__desc">在媒体共享库中阅读小说时，在段落右侧悬浮并点击 “+” 即可添加书签。</p>
                <a href="#/browser" class="btn btn-secondary empty-state__action">前往媒体库浏览</a>
            </div>
        `;
        return;
    }
    
    const container = document.createElement('div');
    container.style.display = 'flex';
    container.style.flexDirection = 'column';
    container.style.gap = '14px';
    
    bookmarks.forEach(bm => {
        const row = document.createElement('div');
        row.className = 'bookmarks-manager-item';
        row.style.display = 'flex';
        row.style.justifyContent = 'space-between';
        row.style.alignItems = 'center';
        row.style.padding = '16px 20px';
        row.style.backgroundColor = 'var(--surface-card)';
        row.style.border = '1px solid var(--border-subtle)';
        row.style.borderRadius = 'var(--radius-md)';
        row.style.cursor = 'pointer';
        row.style.transition = 'all 0.2s ease';
        row.style.boxShadow = 'var(--shadow-md)';

        row.addEventListener('mouseenter', () => {
            row.style.borderColor = 'var(--accent)';
            row.style.backgroundColor = 'var(--surface-hover)';
            row.style.transform = 'translateY(-2px)';
        });
        row.addEventListener('mouseleave', () => {
            row.style.borderColor = 'var(--border-subtle)';
            row.style.backgroundColor = 'var(--surface-card)';
            row.style.transform = 'none';
        });
        
        const info = document.createElement('div');
        info.style.display = 'flex';
        info.style.flexDirection = 'column';
        info.style.gap = '4px';
        
        const bookTitle = document.createElement('span');
        bookTitle.style.fontSize = '14px';
        bookTitle.style.fontWeight = 'bold';
        bookTitle.style.color = 'var(--text-primary)';
        bookTitle.textContent = baseName(bm.bookPath);
        
        const preview = document.createElement('span');
        preview.style.fontSize = '12px';
        preview.style.color = 'var(--text-muted)';
        preview.textContent = `第 ${bm.chapterIndex + 1} 章 · ${bm.preview}`;
        
        info.appendChild(bookTitle);
        info.appendChild(preview);
        
        const delBtn = document.createElement('button');
        delBtn.style.background = 'transparent';
        delBtn.style.border = 'none';
        delBtn.style.color = 'var(--text-muted)';
        delBtn.style.cursor = 'pointer';
        delBtn.style.fontSize = '16px';
        delBtn.style.padding = '6px 10px';
        delBtn.style.transition = 'color 0.2s';
        delBtn.textContent = '✕';
        delBtn.title = '删除书签';
        
        delBtn.addEventListener('mouseenter', () => delBtn.style.color = 'var(--error)');
        delBtn.addEventListener('mouseleave', () => delBtn.style.color = 'var(--text-muted)');
        
        delBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const key = 'book_bookmarks:' + bm.bookPath;
            const next = loadAllBookmarksForBook(bm.bookPath).filter(b => !(b.chapterIndex === bm.chapterIndex && b.paragraphIndex === bm.paragraphIndex));
            if (next.length === 0) {
                localStorage.removeItem(key);
            } else {
                localStorage.setItem(key, JSON.stringify(next));
            }
            showToast('书签已删除', 'success');
            renderBookmarks();
        });
        
        row.addEventListener('click', () => {
            location.hash = `#/read?path=${encodeURIComponent(bm.bookPath)}&chapter=${bm.chapterIndex}&para=${bm.paragraphIndex}`;
        });
        
        row.appendChild(info);
        row.appendChild(delBtn);
        container.appendChild(row);
    });
    
    listEl.appendChild(container);
}

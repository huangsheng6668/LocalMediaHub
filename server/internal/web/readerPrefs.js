// Reader preferences + bookmarks (localStorage-backed) for the Web client.
// Mirrors the Android RecentActivityStore.readerSettings / bookBookmarks APIs.
//
// Settings are global (apply to all books); bookmarks are per-book (keyed
// by bookPath). Persists to localStorage and dispatches a custom event so
// subscribers (textReader.js) can react without polling.

const SETTINGS_KEY = 'reader_settings';
const BOOKMARKS_PREFIX = 'book_bookmarks:';

export const THEME_PRESETS = {
    DAY: { bg: '#FFFFFF', fg: '#212121' },
    NIGHT: { bg: '#121212', fg: '#E0E0E0' },
    EYE_CARE: { bg: '#F4ECD8', fg: '#5B4636' },
};

export const FONT_SIZES = { SMALL: 14, MEDIUM: 16, LARGE: 18, XLARGE: 20 };
export const LINE_HEIGHTS = { COMPACT: '1.4', STANDARD: '1.8', LOOSE: '2.2' };

export const DEFAULT_SETTINGS = {
    fontSize: 'MEDIUM',
    lineHeight: 'STANDARD',
    theme: 'DAY',
    autoScrollSpeed: 5,
};

const EVENT = 'reader-prefs-changed';

function safeParse(json, fallback) {
    if (!json) return fallback;
    try { return JSON.parse(json); } catch (_) { return fallback; }
}

export function getSettings() {
    const raw = localStorage.getItem(SETTINGS_KEY);
    const parsed = safeParse(raw, null);
    if (!parsed || typeof parsed !== 'object') return { ...DEFAULT_SETTINGS };
    return { ...DEFAULT_SETTINGS, ...parsed };
}

export function saveSettings(partial) {
    const merged = { ...getSettings(), ...partial };
    try {
        localStorage.setItem(SETTINGS_KEY, JSON.stringify(merged));
        window.dispatchEvent(new CustomEvent(EVENT, { detail: { type: 'settings' } }));
    } catch (e) {
        // Quota exceeded or other localStorage failure — warn, don't throw.
        console.warn('readerPrefs.saveSettings failed:', e);
    }
    return merged;
}

export function getBookmarks(path) {
    if (!path) return [];
    const raw = localStorage.getItem(BOOKMARKS_PREFIX + path);
    const list = safeParse(raw, []);
    return Array.isArray(list) ? list : [];
}

export function addBookmark(bookmark) {
    if (!bookmark || !bookmark.bookPath) return false;
    const list = getBookmarks(bookmark.bookPath);
    const exists = list.some(b =>
        b.chapterIndex === bookmark.chapterIndex &&
        b.paragraphIndex === bookmark.paragraphIndex
    );
    if (exists) return false;
    list.push({ ...bookmark });
    try {
        localStorage.setItem(BOOKMARKS_PREFIX + bookmark.bookPath, JSON.stringify(list));
        window.dispatchEvent(new CustomEvent(EVENT, {
            detail: { type: 'bookmarks', path: bookmark.bookPath },
        }));
    } catch (e) {
        console.warn('readerPrefs.addBookmark failed:', e);
        return false;
    }
    return true;
}

export function removeBookmark(bookmark) {
    if (!bookmark || !bookmark.bookPath) return;
    const list = getBookmarks(bookmark.bookPath);
    const next = list.filter(b =>
        !(b.chapterIndex === bookmark.chapterIndex &&
          b.paragraphIndex === bookmark.paragraphIndex)
    );
    try {
        if (next.length === 0) {
            localStorage.removeItem(BOOKMARKS_PREFIX + bookmark.bookPath);
        } else {
            localStorage.setItem(BOOKMARKS_PREFIX + bookmark.bookPath, JSON.stringify(next));
        }
        window.dispatchEvent(new CustomEvent(EVENT, {
            detail: { type: 'bookmarks', path: bookmark.bookPath },
        }));
    } catch (e) {
        console.warn('readerPrefs.removeBookmark failed:', e);
    }
}

export function subscribe(callback) {
    window.addEventListener(EVENT, callback);
    return () => window.removeEventListener(EVENT, callback);
}

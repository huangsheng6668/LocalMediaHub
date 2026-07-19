// Reader preferences + bookmarks (localStorage-backed) for the Web client.
// Mirrors the Android RecentActivityStore.readerSettings / bookBookmarks APIs.
//
// Settings are global (apply to all books); bookmarks are per-book (keyed
// by bookPath). Persists to localStorage and dispatches a custom event so
// subscribers (textReader.js) can react without polling.

const SETTINGS_KEY = 'reader_settings';
const BOOKMARKS_PREFIX = 'book_bookmarks:';

// V1->V2 迁移表（保留旧枚举值映射，仅供 migrateV1toV2 使用）
const V1_FONT_SIZE = { SMALL: 14, MEDIUM: 16, LARGE: 18, XLARGE: 20 };
const V1_LINE_HEIGHT = { COMPACT: 1.4, STANDARD: 1.8, LOOSE: 2.2 };

// 字体选项与 CSS font-family 映射；serif/kaiti 的实际字体文件由 Phase 3 引入
export const FONT_FAMILIES = {
    SYSTEM: '-apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif',
    SERIF: '"Noto Serif SC", "Songti SC", "SimSun", serif',
    KAITI: '"LXGW WenKai", "Kaiti SC", "STKaiti", cursive',
};

// 内容宽度滑块范围（px）。Android 在屏幕 dp 上有等价 clamp。
export const CONTENT_WIDTH_RANGE = { MIN: 600, MAX: 1400, STEP: 10 };

// 6 个主题预设（spec §1.1 表格逐字一致）。
// chromeBg/chromeFg/muted 用于顶/底栏/drawer/dialog 的局部主题覆盖。
export const THEME_PRESETS = {
    DAY:        { bg: '#FAF8F3', fg: '#2B2B2B', chromeBg: '#F2EFE7', chromeFg: '#3D3D3D', muted: '#7A7A78', border: '#E5E2D8' },
    DAY_BRIGHT: { bg: '#FFFFFF', fg: '#212121', chromeBg: '#F5F5F5', chromeFg: '#333333', muted: '#7A7A7A', border: '#E0E0E0' },
    EYE_CARE:   { bg: '#F4ECD8', fg: '#5B4636', chromeBg: '#EDE3CC', chromeFg: '#6B5644', muted: '#9C8870', border: '#D8CBAF' },
    PARCHMENT:  { bg: '#EFE6D2', fg: '#3D3327', chromeBg: '#E5D9BF', chromeFg: '#4D4034', muted: '#8C7E66', border: '#D3C7AB' },
    NIGHT:      { bg: '#1A1A1F', fg: '#C9C9CE', chromeBg: '#232328', chromeFg: '#B0B0B5', muted: '#84848A', border: '#2D2D33' },
    NIGHT_BLACK:{ bg: '#000000', fg: '#BFBFBF', chromeBg: '#0A0A0A', chromeFg: '#A8A8A8', muted: '#787878', border: '#1C1C1C' },
    // AUTO 不是预设颜色，而是"跟随系统"标记。getSettings 调用方解析为 DAY/NIGHT。
    AUTO:       null,
};

export const FONT_SIZE_RANGE = { MIN: 12, MAX: 28, STEP: 1 };
export const LINE_HEIGHT_RANGE = { MIN: 1.3, MAX: 2.5, STEP: 0.1 };

export const DEFAULT_SETTINGS = {
    fontFamily: 'SYSTEM',
    fontSize: 16,
    lineHeight: 1.8,
    contentWidth: 720,
    firstLineIndent: true,
    paragraphSpacing: false,
    theme: 'DAY',
    immersiveMode: false,
    autoScrollSpeed: 5,
    readingMode: 'chapter', // 'chapter' | 'scroll'
};

// migrateV1toV2: 接受任何形状（包括 null/undefined/坏字段），输出 V2 形状。
// 这是 Phase 1 的迁移真源。Android 的等价逻辑在 RecentActivityStore 里。
export function migrateV1toV2(old) {
    const out = { ...DEFAULT_SETTINGS };
    if (!old || typeof old !== 'object') return out;

    // fontSize: V1 是 'SMALL'/'MEDIUM'/... 字符串；V2 是 12-28 整数
    if (typeof old.fontSize === 'string' && V1_FONT_SIZE[old.fontSize] !== undefined) {
        out.fontSize = V1_FONT_SIZE[old.fontSize];
    } else if (typeof old.fontSize === 'number' && Number.isFinite(old.fontSize)) {
        out.fontSize = clampInt(old.fontSize, FONT_SIZE_RANGE.MIN, FONT_SIZE_RANGE.MAX);
    }

    // lineHeight: V1 是 'COMPACT'/... 字符串；V2 是 1.3-2.5 浮点
    if (typeof old.lineHeight === 'string' && V1_LINE_HEIGHT[old.lineHeight] !== undefined) {
        out.lineHeight = V1_LINE_HEIGHT[old.lineHeight];
    } else if (typeof old.lineHeight === 'number' && Number.isFinite(old.lineHeight)) {
        out.lineHeight = clampFloat(old.lineHeight, LINE_HEIGHT_RANGE.MIN, LINE_HEIGHT_RANGE.MAX);
    }

    if (typeof old.theme === 'string' && THEME_PRESETS.hasOwnProperty(old.theme)) {
        out.theme = old.theme;
    }

    if (typeof old.autoScrollSpeed === 'number') {
        out.autoScrollSpeed = clampInt(old.autoScrollSpeed, 1, 10);
    }

    // 新字段：仅当 old 已是 V2 形状时才有；V1 数据填默认
    if (typeof old.fontFamily === 'string' && FONT_FAMILIES[old.fontFamily]) {
        out.fontFamily = old.fontFamily;
    }
    if (typeof old.contentWidth === 'number') {
        out.contentWidth = clampInt(old.contentWidth, CONTENT_WIDTH_RANGE.MIN, CONTENT_WIDTH_RANGE.MAX);
    }
    if (typeof old.firstLineIndent === 'boolean') out.firstLineIndent = old.firstLineIndent;
    if (typeof old.paragraphSpacing === 'boolean') out.paragraphSpacing = old.paragraphSpacing;
    if (typeof old.immersiveMode === 'boolean') out.immersiveMode = old.immersiveMode;
    if (old.readingMode === 'chapter' || old.readingMode === 'scroll') {
        out.readingMode = old.readingMode;
    }

    return out;
}

function clampInt(n, lo, hi) { return Math.max(lo, Math.min(hi, Math.round(n))); }
function clampFloat(n, lo, hi) { return Math.max(lo, Math.min(hi, Math.round(n * 10) / 10)); }

const EVENT = 'reader-prefs-changed';

function safeParse(json, fallback) {
    if (!json) return fallback;
    try { return JSON.parse(json); } catch (_) { return fallback; }
}

export function getSettings() {
    let raw = null;
    try { raw = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null'); } catch (_) { raw = null; }
    return migrateV1toV2(raw);
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

// ── Chrome theme (web shell, decoupled from reader_settings.theme) ──
// 独立 key + 独立事件，避免触发 textReader.js 重绘。
const CHROME_THEME_KEY = 'chrome_theme';
const CHROME_THEME_EVENT = 'chrome-theme-changed';

export function getChromeTheme() {
    const v = localStorage.getItem(CHROME_THEME_KEY);
    return v === 'night' ? 'night' : 'day';
}

export function saveChromeTheme(theme) {
    const next = theme === 'night' ? 'night' : 'day';
    try {
        localStorage.setItem(CHROME_THEME_KEY, next);
        window.dispatchEvent(new CustomEvent(CHROME_THEME_EVENT, { detail: { theme: next } }));
    } catch (e) {
        console.warn('readerPrefs.saveChromeTheme failed:', e);
    }
    return next;
}

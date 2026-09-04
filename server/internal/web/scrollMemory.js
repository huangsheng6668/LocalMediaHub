// 锚点式滚动记忆：按目录路径记住"首个可见卡片 + 视口内偏移"，重渲染后按锚点卡片复位。
import { state } from './state.js';

const memory = new Map(); // dirPath -> { anchorPath, offset }

export function captureScrollAnchor(cards, containerTop = 0) {
    if (!Array.isArray(cards)) return null;
    for (const c of cards) {
        if (c && c.bottom > containerTop) {
            return { anchorPath: c.path, offset: Math.round(c.top - containerTop) };
        }
    }
    return null;
}

export function restoreScrollTop(el, container, offset = 0) {
    return (el ? el.offsetTop : 0) - (offset || 0);
}

export function rememberScroll(dirPath, anchor) {
    if (anchor) memory.set(dirPath, anchor);
}

export function recallScroll(dirPath) {
    return memory.get(dirPath) || null;
}

export function clearScrollMemory(dirPath) {
    if (dirPath) memory.delete(dirPath);
    else memory.clear();
}

let lastCapture = 0;
export function initScrollMemory(container) {
    if (!container) return;
    lastCapture = 0;
    container.addEventListener('scroll', () => {
        const now = Date.now();
        if (now - lastCapture < 200) return;
        lastCapture = now;
        const cards = [...container.querySelectorAll('.media-card[data-path]')].map(el => {
            const r = el.getBoundingClientRect();
            return { path: el.dataset.path, top: r.top, bottom: r.bottom };
        });
        const containerTop = container.getBoundingClientRect ? container.getBoundingClientRect().top : 0;
        const anchor = captureScrollAnchor(cards, containerTop);
        rememberScroll(state.currentPath, anchor);
    }, { passive: true });
}

export function restoreScrollMemory(container, dirPath) {
    const mem = recallScroll(dirPath);
    if (!mem || !container) return false;
    const escapeFn = (typeof CSS !== 'undefined' && CSS.escape)
        ? CSS.escape
        : ((typeof window !== 'undefined' && window.CSS && window.CSS.escape)
            ? window.CSS.escape
            : null);
    const escaped = escapeFn ? escapeFn(mem.anchorPath) : mem.anchorPath.replace(/"/g, '\\"');
    const el = container.querySelector(`.media-card[data-path="${escaped}"]`);
    if (!el) return false; // 锚点消失（排序/筛选/内容变化）→ 安全放弃
    container.scrollTop = restoreScrollTop(el, container, mem.offset);
    return true;
}

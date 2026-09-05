// 阅读状态 + 收藏（library）模块：纯函数 + API + 列表装饰。
// 注意：不得在模块求值期访问 localStorage（jsdom 测试约束）。

import { apiRequest } from './api.js';
import { state } from './state.js';
import { showToast } from './toast.js';

export function applyListFilters(folders, files, decorations, { favoritesOnly, statusFilter }) {
    const favSet = new Set(decorations ? decorations.favorites : []);
    const states = (decorations && decorations.states) || {};
    let outFolders = folders || [];
    let outFiles = files || [];
    if (statusFilter != null) {
        // 状态筛选：仅小说；文件夹与非文本一律隐藏
        outFolders = [];
        outFiles = outFiles.filter(f => {
            if (f.media_type !== 'text') return false;
            const badge = states[f.path];
            const status = badge ? badge.status : 'unread';
            return status === statusFilter;
        });
    }
    if (favoritesOnly) {
        outFolders = outFolders.filter(f => favSet.has(f.path));
        outFiles = outFiles.filter(f => favSet.has(f.path));
    }
    return { folders: outFolders, files: outFiles };
}

export function badgeHtmlFor(status, percent) {
    if (status === 'finished') {
        return '<span class="card-badge card-badge--finished">✓ 已读完</span>'; // XSS-SAFE: 静态常量
    }
    if (status === 'reading') {
        const label = percent > 0 ? `读到 ${percent}%` : '读过';
        return `<span class="card-badge card-badge--reading">${label}</span>`; // XSS-SAFE: label 为受控文案+数字
    }
    return '';
}

// percent = clamp(((chapterIndex + intra) / max(1,totalChapters)) * 100, 0, 100)，1 位小数
export function computeReportPayload({ chapterIndex, paraIndex, chapterParaCount, totalChapters, atChapterEnd }) {
    const intra = chapterParaCount > 0 ? Math.min(1, paraIndex / chapterParaCount) : 0;
    const total = Math.max(1, totalChapters);
    let percent = ((chapterIndex + intra) / total) * 100;
    percent = Math.min(100, Math.max(0, percent));
    percent = Math.round(percent * 10) / 10;
    const finished = totalChapters > 0 && chapterIndex === totalChapters - 1 && Boolean(atChapterEnd);
    if (finished) {
        percent = 100;
    }
    return { percent, finished };
}

export function runWithConcurrency(taskFactories, limit) {
    return new Promise((resolve) => {
        if (!taskFactories || taskFactories.length === 0) {
            return resolve([]);
        }
        const effectiveLimit = Math.max(1, limit || 1);
        let next = 0;
        let active = 0;
        const results = new Array(taskFactories.length);
        const launch = () => {
            if (next >= taskFactories.length && active === 0) {
                return resolve(results);
            }
            while (active < effectiveLimit && next < taskFactories.length) {
                const i = next++;
                active++;
                Promise.resolve()
                    .then(taskFactories[i])
                    .then((res) => {
                        results[i] = res;
                    })
                    .catch(() => {
                        results[i] = undefined;
                    })
                    .then(() => {
                        active--;
                        launch();
                    });
            }
        };
        launch();
    });
}

let decorations = null; // { states, favorites }
let decorationsKey = '';

export function setDecorationsForTest(d) {
    decorations = d;
    decorationsKey = 'test';
}

export function getDecorations() {
    return decorations;
}

function chunk(arr, n) {
    const out = [];
    for (let i = 0; i < arr.length; i += n) {
        out.push(arr.slice(i, i + n));
    }
    return out;
}

async function fetchDecorationsFor(paths) {
    const states = {};
    const favorites = [];
    for (const part of chunk(paths, 500)) {
        const res = await apiRequest(`${state.apiBase}/api/v1/library/decorations`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ paths: part }),
        });
        Object.assign(states, (res && res.states) || {});
        favorites.push(...((res && res.favorites) || []));
    }
    return { states, favorites };
}

// browserView 渲染后调用：缓存命中同步 patch；未命中异步拉取后 patch（若期间有筛选激活则触发回调重渲染）。
export async function refreshDecorations(onFilterableChange) {
    const paths = [
        ...(state.currentFolders || []).map(f => f.path),
        ...(state.currentFiles || []).map(f => f.path),
    ];
    const key = (state.currentPath || '') + '|' + paths.length;
    if (decorations && decorationsKey === key) {
        decorateBrowserList(document.getElementById('browser-list'));
        return;
    }
    try {
        const d = await fetchDecorationsFor(paths);
        decorations = d;
        decorationsKey = key;
        decorateBrowserList(document.getElementById('browser-list'));
        if (onFilterableChange && (state.favoritesOnly || state.statusFilter != null)) {
            onFilterableChange();
        }
    } catch (e) {
        /* 服务端不可达：徽章/心形降级缺席 */
    }
}

export function decorateBrowserList(container) {
    if (!container || !decorations) return;
    const favSet = new Set((decorations && decorations.favorites) || []);
    container.querySelectorAll('.media-card[data-path]').forEach(card => {
        const path = card.dataset.path;
        const favBtn = card.querySelector('.fav-btn');
        if (favBtn) {
            // 心形匹配键用按钮自身的 data-path（与服务端 decorations 回显的原始
            // 请求形态一致）；文件夹卡片本体是斜杠形态（browse 导航历史行为），
            // 用卡片形态匹配会让 Windows 反斜杠路径的目录心形永远点不亮。
            const favPath = favBtn.dataset.path || path;
            favBtn.classList.toggle('active', favSet.has(favPath));
        }
        if (card.dataset.mediaType === 'text') {
            const meta = card.querySelector('.card-meta');
            if (!meta) return;
            const old = meta.querySelector('.card-badge--reading, .card-badge--finished');
            if (old) old.remove();
            const badge = decorations.states ? decorations.states[path] : undefined;
            const html = badgeHtmlFor(badge ? badge.status : 'unread', badge ? badge.percent : 0);
            if (html) {
                meta.insertAdjacentHTML('beforeend', html); // XSS-SAFE: badgeHtmlFor 输出受控常量
            }
        }
    });
}

export async function toggleFavorite(path, isDir, title, mediaType, onFilterableChange) {
    const shouldRerender = () => onFilterableChange && (state.favoritesOnly || state.statusFilter != null);
    const favSet = new Set(decorations ? decorations.favorites : []);
    const wasFav = favSet.has(path);
    if (wasFav) favSet.delete(path); else favSet.add(path);
    decorations = { ...(decorations || {}), states: (decorations && decorations.states) || {}, favorites: [...favSet] };
    decorateBrowserList(document.getElementById('browser-list'));
    try {
        if (wasFav) {
            await apiRequest(`${state.apiBase}/api/v1/library/favorites?path=${encodeURIComponent(path)}`, { method: 'DELETE' });
        } else {
            await apiRequest(`${state.apiBase}/api/v1/library/favorites`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    path,
                    is_dir: Boolean(isDir),
                    is_system: Boolean(state.isSystemBrowse),
                    title: title || '',
                    media_type: mediaType || '',
                    snapshot: { title: title || '' },
                }),
            });
        }
        showToast(wasFav ? '已取消收藏' : '已收藏', 'success');
        // 筛选激活时列表成员集变化（如"只看收藏"下取消收藏），就地 patch 不够，需重排。
        if (shouldRerender()) onFilterableChange();
    } catch (e) {
        const rollback = new Set((decorations && decorations.favorites) || []);
        if (wasFav) rollback.add(path); else rollback.delete(path);
        decorations = { ...(decorations || {}), states: (decorations && decorations.states) || {}, favorites: [...rollback] };
        decorateBrowserList(document.getElementById('browser-list'));
        if (shouldRerender()) onFilterableChange();
        showToast(`收藏操作失败: ${e.message}`, 'error');
    }
}

export async function markStatus(path, status, onFilterableChange) {
    try {
        await apiRequest(`${state.apiBase}/api/v1/library/states/status`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path, status }),
        });
        decorationsKey = ''; // 强制下次 refresh 重新拉取
        await refreshDecorations(onFilterableChange);
    } catch (e) {
        showToast(`标记失败: ${e.message}`, 'error');
    }
}

export async function fetchState(path) {
    try {
        return await apiRequest(`${state.apiBase}/api/v1/library/states?path=${encodeURIComponent(path)}`);
    } catch (e) {
        return null;
    }
}

export function reportState(path, payload = {}) {
    const chapterIndex = payload.chapterIndex !== undefined ? payload.chapterIndex : payload.chapter_index;
    const paraIndex = payload.paraIndex !== undefined ? payload.paraIndex : payload.para_index;
    const percent = payload.percent;
    const finished = payload.finished;
    const lastReadAt = payload.lastReadAt !== undefined ? payload.lastReadAt : payload.last_read_at;
    return apiRequest(`${state.apiBase}/api/v1/library/states`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            path,
            chapter_index: chapterIndex,
            para_index: paraIndex,
            percent,
            finished,
            last_read_at: lastReadAt,
        }),
    }).catch(() => {}); // 静默：下次保存重试
}

export async function migrateLocalProgress() {
    try {
        if (typeof localStorage === 'undefined' || !localStorage) return;
        if (localStorage.getItem('library_migrated_v1') === '1') return;
    } catch (e) {
        return;
    }
    const entries = [];
    try {
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (!key || !key.startsWith('book_progress:')) continue;
            try {
                const prog = JSON.parse(localStorage.getItem(key));
                if (prog && typeof prog.lastReadAt === 'number') {
                    entries.push({ path: key.slice('book_progress:'.length), prog });
                }
            } catch (e) { /* 跳过坏条目 */ }
        }
    } catch (e) {
        return;
    }
    await runWithConcurrency(entries.map(({ path, prog }) => () => {
        // 服务端 lastReadAt 守卫保证陈旧/重复上报 no-op；percent 无法本地重构，传 0（下次阅读会更新）
        return reportState(path, {
            chapterIndex: prog.chapterIndex || 0,
            paraIndex: prog.paraIndex || 0,
            percent: 0,
            finished: false,
            lastReadAt: prog.lastReadAt,
        });
    }), 6);
    try {
        localStorage.setItem('library_migrated_v1', '1');
    } catch (e) {}
}

let currentMenuPath = null;
let currentMenuCallback = null;

function ensureStatusMenu() {
    if (typeof document === 'undefined' || !document.body) return null;
    let menu = document.getElementById('card-status-menu');
    if (!menu) {
        menu = document.createElement('div');
        menu.id = 'card-status-menu';
        const items = [
            { label: '标为已读完', status: 'finished' },
            { label: '标为读过', status: 'reading' },
            { label: '标为未读', status: 'unread' },
            { label: '清除手动标记', status: null },
        ];
        items.forEach(({ label, status }) => {
            const btn = document.createElement('button');
            btn.type = 'button';
            btn.className = 'status-menu__item';
            btn.textContent = label;
            btn.addEventListener('click', (e) => {
                e.stopPropagation();
                if (currentMenuPath) {
                    markStatus(currentMenuPath, status, currentMenuCallback || undefined);
                }
                closeStatusMenu();
            });
            menu.appendChild(btn);
        });
        document.body.appendChild(menu);

        document.addEventListener('click', (e) => {
            const m = document.getElementById('card-status-menu');
            if (!m || !m.classList.contains('open')) return;
            if (!m.contains(e.target) && !e.target.closest('[data-action="status-menu"]')) {
                closeStatusMenu();
            }
        }, true);

        document.addEventListener('keydown', (e) => {
            if (e.key === 'Escape') {
                closeStatusMenu();
            }
        });
    }
    return menu;
}

export function openStatusMenu(anchorEl, path, onFilterableChange) {
    const menu = ensureStatusMenu();
    if (!menu) return;
    currentMenuPath = path;
    currentMenuCallback = onFilterableChange || null;
    if (anchorEl && typeof anchorEl.getBoundingClientRect === 'function') {
        const rect = anchorEl.getBoundingClientRect();
        const top = (rect.bottom || 0) + 4;
        const left = rect.left || 0;
        menu.style.top = `${top}px`;
        menu.style.left = `${left}px`;
    }
    menu.classList.add('open');
}

export function closeStatusMenu() {
    if (typeof document === 'undefined') return;
    const menu = document.getElementById('card-status-menu');
    if (menu) {
        menu.classList.remove('open');
    }
    currentMenuPath = null;
    currentMenuCallback = null;
}


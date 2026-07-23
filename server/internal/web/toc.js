// 目录抽屉：渲染 TOC 列表 + 高亮当前章节 + 开关 + 外部点击关闭。
// 修复 1：订阅 EVT.CHAPTER_CHANGED 自更新高亮（替代 _highlightCurrent hack）。
// 修复 2：open/close/toggle/外部点击全在此模块，单一 drawerEl 引用，导出 dispose。
import { state, setCurrentIdx } from './reader-state.js';
import { on, EVT } from './bus.js';

// 渲染 TOC 到 drawerEl，返回控制器。
// onNavigate(idx) 由主模块提供，处理章节跳转（滚动模式 scrollIntoView 或分章模式 loadChapter）。
export function renderToc({ drawerEl, onNavigate }) {
    const unsubs = [];
    let rafId = null;
    let listenerAttached = false;

    function attachOutsideListener() {
        if (listenerAttached) return;
        document.addEventListener('click', onOutsideClick, true);
        listenerAttached = true;
    }

    function detachOutsideListener() {
        if (rafId !== null) {
            cancelAnimationFrame(rafId);
            rafId = null;
        }
        if (listenerAttached) {
            document.removeEventListener('click', onOutsideClick, true);
            listenerAttached = false;
        }
    }

    function renderList() {
        drawerEl.innerHTML = ''; // XSS-SAFE: clearing
        const tabs = document.createElement('div');
        tabs.className = 'text-reader__tabs';
        // XSS-SAFE: pure-literal template (no interpolation, no user data)
        tabs.innerHTML = `
            <button class="text-reader__tab text-reader__tab--active" data-tab="toc">目录</button>
            <button class="text-reader__tab" data-tab="bookmarks">书签 (<span data-bm-count>0</span>)</button>
        `;
        const panel = document.createElement('div');
        panel.className = 'text-reader__tab-panel';
        drawerEl.appendChild(tabs);
        drawerEl.appendChild(panel);

        (state.book?.chapters || []).forEach((ch, i) => {
            const btn = document.createElement('button');
            btn.dataset.chapterIndex = String(i);
            btn.className = 'text-reader__drawer-item' + (i === state.currentIdx ? ' text-reader__drawer-item--active' : '');
            if (i === state.currentIdx) btn.setAttribute('aria-current', 'true');
            btn.textContent = ch.title || `第 ${i + 1} 章`;
            btn.addEventListener('click', () => {
                onNavigate(i);
                closeDrawer();
            });
            panel.appendChild(btn);
        });

        // 书签 tab 占位（bookmarks 模块 Task 5 填充实际内容）
        tabs.querySelector('[data-tab="toc"]')?.addEventListener('click', () => {
            tabs.querySelectorAll('.text-reader__tab').forEach((b) => b.classList.remove('text-reader__tab--active'));
            tabs.querySelector('[data-tab="toc"]')?.classList.add('text-reader__tab--active');
        });

        highlightCurrent();
        return { tabs, panel };
    }

    function highlightCurrent() {
        const items = drawerEl.querySelectorAll('.text-reader__drawer-item');
        items.forEach((el) => {
            const wasActive = el.classList.contains('text-reader__drawer-item--active');
            const shouldBeActive = parseInt(el.dataset.chapterIndex, 10) === state.currentIdx;
            if (shouldBeActive === wasActive) return;
            el.classList.toggle('text-reader__drawer-item--active', shouldBeActive);
            if (shouldBeActive) {
                el.setAttribute('aria-current', 'true');
                el.scrollIntoView({ block: 'nearest' });
            } else {
                el.removeAttribute('aria-current');
            }
        });
    }

    function openDrawer() {
        drawerEl.classList.remove('text-reader__drawer--hidden');
        drawerEl.setAttribute('aria-hidden', 'false');
        highlightCurrent();
        rafId = requestAnimationFrame(() => {
            rafId = null;
            attachOutsideListener();
        });
    }

    function closeDrawer() {
        drawerEl.classList.add('text-reader__drawer--hidden');
        drawerEl.setAttribute('aria-hidden', 'true');
        detachOutsideListener();
    }

    function toggleDrawer() {
        if (drawerEl.classList.contains('text-reader__drawer--hidden')) openDrawer();
        else closeDrawer();
    }

    function onOutsideClick(e) {
        if (drawerEl.contains(e.target)) return;
        if (e.target.closest && e.target.closest('.text-reader__toc')) return;
        closeDrawer();
    }

    // 修复 1：订阅章节变化自更新高亮（无条件——即使抽屉隐藏也同步状态，
    // 这样下次 openDrawer 立即显示正确高亮，替代 _highlightCurrent hack）。
    unsubs.push(on(EVT.CHAPTER_CHANGED, () => {
        highlightCurrent();
    }));

    renderList();

    return {
        openDrawer,
        closeDrawer,
        toggleDrawer,
        highlightCurrent,
        // 书签模块（Task 5）会调用 setBookmarkCount 更新 tab 标签。
        setBookmarkCount(n) {
            const bm = drawerEl.querySelector('[data-bm-count]');
            if (bm) bm.textContent = String(n);
        },
        dispose() {
            unsubs.forEach((u) => u());
            detachOutsideListener();
        },
    };
}

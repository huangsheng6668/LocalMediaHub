// Page-turn controller for CHAPTER mode. Owns the animation layer over
// contentEl: on turnTo(direction) it loads the target chapter section via
// loadChapterSection(), then animates the swap per getStyle(). NONE swaps
// instantly. SIMULATION animates a clip-path curl on the old section.
// DRAG uses a pointer gesture (horizontal drag) to flip chapters; vertical
// movement still scrolls. prefers-reduced-motion degrades COVER/SIMULATION to
// NONE; DRAG keeps its gesture but snaps instantly per-step.
const ANIM_MS = { COVER: 280, SIMULATION: 400, DRAG: 280 };
const DRAG_THRESHOLD = 0.25; // 屏宽比例；|dxRatio| >= threshold → commit
const DRAG_TOUCH_SLOP = 8;   // px；|dx| 超过此值才可能接管水平拖动

function prefersReducedMotion() {
    try {
        return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (_) {
        return false;
    }
}

// 纯函数：根据拖动位移（屏宽比例，带符号）判定翻页动作。
//   dxRatio < 0（手指向左拖）= next 意图（中文从右往左翻）
//   dxRatio > 0（手指向右拖）= prev 意图
//   |dxRatio| >= DRAG_THRESHOLD → commit；否则 revert（direction 为 null）。
// 注：单参数。阈值边界 0.25 视为 commit（< 才 revert）。
export function resolveDragOutcome(dxRatio) {
    const abs = Math.abs(dxRatio);
    if (abs < DRAG_THRESHOLD) return { action: 'revert', direction: null };
    return { action: 'commit', direction: dxRatio < 0 ? 'next' : 'prev' };
}

export function renderPageTurn({ contentEl, getStyle, loadChapterSection, getCurrentIdx, getChapterCount }) {
    let busy = false;

    function resolveStyle() {
        const s = getStyle();
        if (s === 'NONE') return 'NONE';
        if (prefersReducedMotion() && s !== 'DRAG') return 'NONE';
        return s;
    }

    async function swapInstant(section) {
        contentEl.innerHTML = ''; // XSS-SAFE: clearing
        contentEl.appendChild(section);
    }

    // COVER: layer old + new, translate, then settle. Uses a transitionend
    // listener with a setTimeout fallback (jsdom won't fire transitionend).
    function animateCover(oldSection, newSection, direction) {
        return new Promise((resolve) => {
            const sign = direction === 'next' ? 1 : -1;
            newSection.classList.add('text-reader__page--incoming');
            newSection.style.transform = `translateX(${sign * 100}%)`;
            contentEl.appendChild(newSection);
            // force reflow so the initial transform sticks before transitioning
            void contentEl.offsetWidth;
            newSection.style.transition = `transform ${ANIM_MS.COVER}ms ease-out`;
            oldSection.style.transition = `transform ${ANIM_MS.COVER}ms ease-out, opacity ${ANIM_MS.COVER}ms ease-out`;
            newSection.style.transform = 'translateX(0)';
            oldSection.style.transform = `translateX(${-sign * 100}%)`;
            oldSection.style.opacity = '0';
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                contentEl.removeChild(oldSection);
                newSection.classList.remove('text-reader__page--incoming');
                newSection.style.transition = '';
                newSection.style.transform = '';
                resolve();
            };
            newSection.addEventListener('transitionend', finish, { once: true });
            setTimeout(finish, ANIM_MS.COVER + 60); // fallback
        });
    }

    // SIMULATION: 单页卷曲。顶层（旧章）用 clip-path polygon 沿贝塞尔采样
    // 点裁剪，随进度从右向左扫；阴影用伪元素渐变定位在裁剪边界。jsdom
    // 无真实渲染，靠 transitionend + setTimeout 回退收尾（与 COVER 同策略）。
    function animateSimulation(oldSection, newSection, direction) {
        return new Promise((resolve) => {
            const sign = direction === 'next' ? 1 : -1;
            newSection.style.transform = `translateX(${sign * 100}%)`;
            newSection.style.transition = `transform ${ANIM_MS.SIMULATION}ms ease-in-out`;
            contentEl.appendChild(newSection);
            void contentEl.offsetWidth;
            // 旧章卷曲：clip-path 从满屏收缩到 0
            oldSection.style.transition = `clip-path ${ANIM_MS.SIMULATION}ms ease-in-out`;
            oldSection.classList.add('text-reader__page--curling');
            oldSection.dataset.curlSign = String(sign);
            newSection.style.transform = 'translateX(0)';
            // CSS @keyframes 驱动 clip-path（见 style.css），这里仅触发 + 收尾
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                contentEl.removeChild(oldSection);
                newSection.style.transition = '';
                newSection.style.transform = '';
                resolve();
            };
            newSection.addEventListener('transitionend', finish, { once: true });
            setTimeout(finish, ANIM_MS.SIMULATION + 60);
        });
    }

    async function turnTo(direction) {
        if (busy) return false;
        if (direction !== 'next' && direction !== 'prev') return false;
        const idx = getCurrentIdx();
        const count = getChapterCount();
        const target = direction === 'next' ? idx + 1 : idx - 1;
        if (target < 0 || target >= count) return false;
        busy = true;
        try {
            const style = resolveStyle();
            const newSection = await loadChapterSection(target);
            const oldSection = contentEl.querySelector('.text-reader__chapter-section');
            if (style === 'NONE' || !oldSection) {
                await swapInstant(newSection);
            } else if (style === 'SIMULATION') {
                await animateSimulation(oldSection, newSection, direction);
            } else {
                // COVER 与 DRAG 共用 COVER 动画：
                //   - COVER：唯一的翻页入口。
                //   - DRAG：仅服务于"无手势"翻页（点击热区/物理按钮），
                //     即 DRAG 样式下点击热区仍走 COVER 式滑入。
                //     DRAG 的拖动手势由 pointer 事件单独处理（见 bindDragGesture）。
                await animateCover(oldSection, newSection, direction);
            }
            return true;
        } finally {
            busy = false;
        }
    }

    // ===== DRAG 手势：水平拖动翻页，垂直滚动共存 =====
    // 仅当 getStyle()==='DRAG' 时消费水平拖动；其余样式完全无视。
    // 始终绑定（在 contentEl 上），运行时按 getStyle() 判定是否接管。
    //
    // 接管条件（pointermove 内逐次判定，首次满足后锁定）：
    //   |dx| > |dy|  且  |dx| > DRAG_TOUCH_SLOP(8px)  且  当前 style === 'DRAG'
    // 接管后：
    //   - 异步预加载目标章（按 dx 符号：dx<0→next，dx>0→prev），
    //     目标章以 absolute 叠层方式放入 contentEl 并实时设 translateX。
    //   - preventDefault 仅在接管期间，避免同时触发滚动。
    // 松手：resolveDragOutcome(dx/width) → commit 滑到位 / revert 回弹归零并移除目标章。
    // Web DOM 天然支持 revert：拖动期间 oldSection 始终在 DOM 中，回弹只是动画归零。
    const drag = {
        active: false,        // 当前 pointer 序列进行中（pointerdown 已发生）
        tookOver: false,      // 已判定为水平拖动接管
        startX: 0, startY: 0,
        direction: null,      // 'next' | 'prev'（接管后按 dx 符号确定）
        targetSection: null,  // 已就位的目标章（异步加载完成后填充）
        loadPromise: null,    // 目标章加载 Promise（pointerup 时 await）
        loadToken: 0,         // 用于丢弃过期的异步加载结果
        oldSection: null,     // 接管瞬间的当前章（回弹时复位）
        width: 1,             // 接管瞬间的 contentEl 宽度（缓存，避免拖动中 reflow）
    };

    function resetDrag() {
        drag.active = false;
        drag.tookOver = false;
        drag.startX = 0;
        drag.startY = 0;
        drag.direction = null;
        drag.targetSection = null;
        drag.loadPromise = null;
        drag.oldSection = null;
        drag.width = 1;
    }

    function onPointerDown(e) {
        // 只响应主键 / 触摸 / 笔；忽略右键等。
        if (e.button != null && e.button !== 0) return;
        if (getStyle() !== 'DRAG') return; // 非 DRAG 不启动序列
        if (drag.active) return;
        drag.active = true;
        drag.tookOver = false;
        drag.startX = e.clientX;
        drag.startY = e.clientY;
        drag.direction = null;
        drag.targetSection = null;
        drag.loadPromise = null;
        drag.oldSection = null;
    }

    function onPointerMove(e) {
        if (!drag.active) return;
        const dx = e.clientX - drag.startX;
        const dy = e.clientY - drag.startY;
        if (!drag.tookOver) {
            // 首次判定接管：满足条件才进入拖动模式（仅判一次，之后 tookOver=true）。
            const isDragStyle = getStyle() === 'DRAG';
            const horizontalDominant = Math.abs(dx) > Math.abs(dy);
            const beyondSlop = Math.abs(dx) > DRAG_TOUCH_SLOP;
            if (!(isDragStyle && horizontalDominant && beyondSlop)) {
                return; // 未接管：垂直滚动等继续，不 preventDefault。
            }
            // 接管。先确定方向（按 dx 符号）与目标章边界。
            const direction = dx < 0 ? 'next' : 'prev';
            const idx = getCurrentIdx();
            const count = getChapterCount();
            const target = direction === 'next' ? idx + 1 : idx - 1;
            // 边界：无目标章可翻 → 不接管（让用户随意拖，松手自然 revert/无操作）。
            if (target < 0 || target >= count) {
                drag.active = false;
                return;
            }
            const oldSection = contentEl.querySelector('.text-reader__chapter-section');
            if (!oldSection) {
                resetDrag();
                return;
            }
            drag.tookOver = true;
            drag.direction = direction;
            drag.oldSection = oldSection;
            drag.width = contentEl.clientWidth || contentEl.getBoundingClientRect().width || 1;
            // 异步预加载目标章。loadPromise 只负责拿到 section（含可能的加载失败），
            // DOM 叠层/套用 translateX 由后续 pointermove（已就位时）或 pointerup
            // 的 commit/revert 处理——这样 resetDrag 不会让在途结果被丢弃。
            // loadToken 仅用于在快速来回拖时丢弃"前一个方向"的过期结果。
            const token = ++drag.loadToken;
            drag.loadPromise = (async () => {
                try {
                    const sec = await loadChapterSection(target);
                    if (token !== drag.loadToken) return null; // 已被更新的拖动覆盖
                    if (!sec) return null;
                    drag.targetSection = sec;
                    // 叠层：目标章绝对定位平移到"进入侧"，旧章保持在原位。
                    sec.classList.add('text-reader__page--incoming');
                    sec.style.position = 'absolute';
                    sec.style.inset = '0';
                    sec.style.transition = 'none';
                    const sign = direction === 'next' ? 1 : -1;
                    sec.style.transform = `translateX(${sign * 100}%)`;
                    contentEl.appendChild(sec);
                    // 立即套用当前 dx（拖动可能已继续）。
                    applyDragTransform(dx);
                    return sec;
                } catch (_) {
                    return null; // 加载失败：保持接管，松手时 revert
                }
            })();
        }
        // 已接管：实时平移，阻止滚动。
        if (drag.tookOver) {
            if (e.cancelable) e.preventDefault();
            applyDragTransform(dx);
        }
    }

    // 把当前 dx（屏宽比例）套用到旧章 + 目标章（若已就位）。
    // next（dx<0）：旧章向左推、目标章从右贴入；prev（dx>0）：镜像。
    function applyDragTransform(dx) {
        const w = drag.width || 1;
        const ratio = dx / w; // -1..1
        if (!drag.oldSection) return;
        if (drag.direction === 'next') {
            // dx<0：旧章跟随手指左移（最多 -100%），目标章从 +100% 跟进。
            drag.oldSection.style.transition = 'none';
            drag.oldSection.style.transform = `translateX(${Math.max(-100, ratio * 100)}%)`;
            if (drag.targetSection) {
                drag.targetSection.style.transform = `translateX(${(1 + ratio) * 100}%)`;
            }
        } else {
            // dx>0：旧章跟随手指右移，目标章从 -100% 跟进。
            drag.oldSection.style.transition = 'none';
            drag.oldSection.style.transform = `translateX(${Math.min(100, ratio * 100)}%)`;
            if (drag.targetSection) {
                drag.targetSection.style.transform = `translateX(${(-1 + ratio) * 100}%)`;
            }
        }
    }

    async function onPointerUp(e) {
        if (!drag.active) return;
        const wasTaken = drag.tookOver;
        const dx = e.clientX - drag.startX;
        const direction = drag.direction;
        const oldSection = drag.oldSection;
        const width = drag.width || 1;
        // 抓取在途加载 Promise（可能仍 pending）；松手时等它落地，再判定 commit/revert。
        const loadPromise = drag.loadPromise;
        // 终结本次序列。注意：这里**不** bump loadToken——onPointerUp 是终点，
        // 在途的 loadPromise 必须有机会落地（token 仅用于丢弃"中途换方向"的旧结果）。
        resetDrag();
        if (!wasTaken) return; // 未接管 → 视为 tap，交给现有 click 热区逻辑

        // 等目标章加载完（若已加载完则立即返回）。
        let targetSection = null;
        if (loadPromise) {
            try { targetSection = await loadPromise; } catch (_) { targetSection = null; }
        }

        if (busy) return;
        busy = true;
        try {
            const outcome = resolveDragOutcome(dx / width);
            const state = { direction, oldSection, targetSection, width };
            if (outcome.action === 'commit' && outcome.direction === direction) {
                await commitDrag(state);
            } else {
                await revertDrag(state);
            }
        } finally {
            busy = false;
        }
    }

    // commit：把目标章滑到 0、旧章滑出；目标章未就位则降级为即时替换。
    function commitDrag(state) {
        const { oldSection, targetSection, direction } = state;
        // 目标章还没加载完 → 直接即时替换（DRAG 的降级路径，等价 NONE）。
        if (!targetSection || !oldSection) {
            if (targetSection) {
                contentEl.innerHTML = ''; // XSS-SAFE: empty literal, clearing content
                targetSection.classList.remove('text-reader__page--incoming');
                targetSection.style.position = '';
                targetSection.style.inset = '';
                targetSection.style.transition = '';
                targetSection.style.transform = '';
                contentEl.appendChild(targetSection);
            }
            return Promise.resolve();
        }
        return new Promise((resolve) => {
            const sign = direction === 'next' ? 1 : -1;
            targetSection.style.transition = `transform ${ANIM_MS.DRAG}ms ease-out`;
            oldSection.style.transition = `transform ${ANIM_MS.DRAG}ms ease-out, opacity ${ANIM_MS.DRAG}ms ease-out`;
            void contentEl.offsetWidth; // 强制 reflow，确保 transition 生效
            targetSection.style.transform = 'translateX(0)';
            oldSection.style.transform = `translateX(${-sign * 100}%)`;
            oldSection.style.opacity = '0';
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                if (oldSection.parentNode === contentEl) contentEl.removeChild(oldSection);
                targetSection.classList.remove('text-reader__page--incoming');
                targetSection.style.position = '';
                targetSection.style.inset = '';
                targetSection.style.transition = '';
                targetSection.style.transform = '';
                resolve();
            };
            targetSection.addEventListener('transitionend', finish, { once: true });
            setTimeout(finish, ANIM_MS.DRAG + 60);
        });
    }

    // revert：旧章 + 目标章回弹归零；移除目标章。Web DOM 天然支持——
    // oldSection 全程未离场，回弹只是动画归零，无需重新加载。
    function revertDrag(state) {
        const { oldSection, targetSection } = state;
        if (!oldSection) return Promise.resolve();
        return new Promise((resolve) => {
            if (targetSection) {
                targetSection.style.transition = `transform ${ANIM_MS.DRAG}ms ease-out`;
            }
            oldSection.style.transition = `transform ${ANIM_MS.DRAG}ms ease-out, opacity ${ANIM_MS.DRAG}ms ease-out`;
            void contentEl.offsetWidth;
            oldSection.style.transform = 'translateX(0)';
            oldSection.style.opacity = '';
            if (targetSection) {
                // 目标章滑回它原来的进入侧。
                // 注：direction 在 revert 时与 outcome.direction 可能不同（小拖），
                // 用 state.direction 复原最自然。
                const sign = state.direction === 'next' ? 1 : -1;
                targetSection.style.transform = `translateX(${sign * 100}%)`;
            }
            let done = false;
            const finish = () => {
                if (done) return;
                done = true;
                if (targetSection && targetSection.parentNode === contentEl) {
                    contentEl.removeChild(targetSection);
                }
                oldSection.style.transition = '';
                oldSection.style.transform = '';
                oldSection.style.opacity = '';
                resolve();
            };
            oldSection.addEventListener('transitionend', finish, { once: true });
            setTimeout(finish, ANIM_MS.DRAG + 60);
        });
    }

    // 绑定 pointer 监听（始终绑定，运行时按 getStyle() 过滤）。
    contentEl.addEventListener('pointerdown', onPointerDown);
    contentEl.addEventListener('pointermove', onPointerMove);
    contentEl.addEventListener('pointerup', onPointerUp);
    // pointercancel / lost capture 视同松手，避免拖动卡死。
    contentEl.addEventListener('pointercancel', onPointerUp);

    function dispose() {
        contentEl.removeEventListener('pointerdown', onPointerDown);
        contentEl.removeEventListener('pointermove', onPointerMove);
        contentEl.removeEventListener('pointerup', onPointerUp);
        contentEl.removeEventListener('pointercancel', onPointerUp);
        // 使任何在途异步加载结果在落地时被丢弃。
        drag.loadToken++;
        resetDrag();
    }

    return { turnTo, dispose };
}

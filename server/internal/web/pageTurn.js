// Page-turn controller for CHAPTER mode. Owns the animation layer over
// contentEl: on turnTo(direction) it loads the target chapter section via
// loadChapterSection(), then animates the swap per getStyle(). NONE swaps
// instantly. SIMULATION animates a clip-path curl on the old section.
// DRAG still falls through to COVER until Task 6 wires up the gesture.
// prefers-reduced-motion degrades COVER/SIMULATION to NONE.
const ANIM_MS = { COVER: 280, SIMULATION: 400, DRAG: 280 };
const DRAG_THRESHOLD = 0.25; // 屏宽比例

function prefersReducedMotion() {
    try {
        return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    } catch (_) {
        return false;
    }
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
            } else if (style === 'COVER') {
                await animateCover(oldSection, newSection, direction);
            } else {
                // DRAG 仍回退，Task 6 接入手势
                await animateCover(oldSection, newSection, direction);
            }
            return true;
        } finally {
            busy = false;
        }
    }

    function dispose() {
        // DRAG pointer listeners would detach here (Task: when DRAG gesture added).
    }

    return { turnTo, dispose };
}

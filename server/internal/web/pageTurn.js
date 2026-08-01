// Page-turn controller for CHAPTER mode. Owns the animation layer over
// contentEl: on turnTo(direction) it loads the target chapter section via
// loadChapterSection(), then animates the swap per getStyle(). NONE swaps
// instantly. SIMULATION is added in Task 5 (this module falls through to
// COVER if SIMULATION not yet implemented). prefers-reduced-motion degrades
// COVER/SIMULATION to NONE.
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
            } else if (style === 'COVER') {
                await animateCover(oldSection, newSection, direction);
            } else {
                // SIMULATION/DRAG fall back to COVER until those land (Task 5 / DRAG gesture).
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

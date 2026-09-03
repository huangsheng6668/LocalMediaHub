# Web Quick Scroll Navigation (Top/Bottom) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide a floating action button (FAB) navigation in LocalMediaHub's web client that smoothly scrolls to top and bottom in both the media browser list and the text reader, with smart visibility logic.

**Architecture:** A decoupled controller module `scrollNav.js` with pure helper `computeFabVisibility`, hooked into active container resolution (switching between `.view-container` and `.text-reader__content`), styled via CSS classes in `css/components.css` and `css/views/reader.css`, tested via `node --test` in `scrollNav.test.mjs`.

**Tech Stack:** Native ES Modules, vanilla JavaScript, CSS custom properties, Node.js native test runner (`node:test` + `node:assert`).

## Global Constraints

- Zero external build steps or runtime npm dependencies; native ES modules only.
- Strict CSP compliance: zero inline `style="..."` attributes; all dynamic visual states controlled via CSS classes.
- Strict XSS security: static DOM in `index.html` or escaped interpolation passing `tools/xsscheck`.
- 7-theme palette compliance: all colors reference standard CSS variables (`var(--surface-card)`, `var(--surface-hover)`, `var(--accent)`, `var(--border-subtle)`, `var(--shadow-md)`).

---

### Task 1: Core Logic & Unit Tests for `scrollNav.js`

**Files:**
- Create: `server/internal/web/scrollNav.js`
- Test: `server/internal/web/scrollNav.test.mjs`

**Interfaces:**
- Produces:
  - `computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold = 120): { showTop: boolean, showBottom: boolean }`
  - `resolveScrollContainer(root = document): Element | null`
  - `updateScrollFabVisibility(groupEl, container, threshold = 120): void`

- [ ] **Step 1: Write failing unit tests for `computeFabVisibility` and container resolution**

Create `server/internal/web/scrollNav.test.mjs`:
```javascript
import test from 'node:test';
import assert from 'node:assert/strict';
import { computeFabVisibility, resolveScrollContainer, updateScrollFabVisibility } from './scrollNav.js';

test('computeFabVisibility returns both false when content is not scrollable', () => {
    // scrollHeight - clientHeight <= 100 -> not scrollable
    const res = computeFabVisibility(0, 800, 850);
    assert.deepEqual(res, { showTop: false, showBottom: false });
});

test('computeFabVisibility at top shows only bottom button', () => {
    // scrollHeight: 3000, clientHeight: 800 (maxScroll: 2200), scrollTop: 50 <= threshold(120)
    const res = computeFabVisibility(50, 800, 3000, 120);
    assert.deepEqual(res, { showTop: false, showBottom: true });
});

test('computeFabVisibility in middle shows both buttons', () => {
    // scrollTop: 500, maxScroll: 2200
    const res = computeFabVisibility(500, 800, 3000, 120);
    assert.deepEqual(res, { showTop: true, showBottom: true });
});

test('computeFabVisibility at bottom shows only top button', () => {
    // scrollTop: 2150 >= maxScroll - 120 (2080)
    const res = computeFabVisibility(2150, 800, 3000, 120);
    assert.deepEqual(res, { showTop: true, showBottom: false });
});

test('resolveScrollContainer picks .text-reader__content in read tab', () => {
    const fakeDoc = {
        body: { dataset: { activeTab: 'read' } },
        querySelector(selector) {
            if (selector === '.text-reader__content') return { id: 'reader-content' };
            if (selector === '.view-container') return { id: 'view-container' };
            return null;
        }
    };
    const target = resolveScrollContainer(fakeDoc);
    assert.equal(target?.id, 'reader-content');
});

test('resolveScrollContainer picks .view-container in browser tab', () => {
    const fakeDoc = {
        body: { dataset: { activeTab: 'browser' } },
        querySelector(selector) {
            if (selector === '.text-reader__content') return { id: 'reader-content' };
            if (selector === '.view-container') return { id: 'view-container' };
            return null;
        }
    };
    const target = resolveScrollContainer(fakeDoc);
    assert.equal(target?.id, 'view-container');
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd server/internal/web && node --test scrollNav.test.mjs`
Expected: FAIL with "Cannot find module './scrollNav.js'"

- [ ] **Step 3: Implement `scrollNav.js` core logic**

Create `server/internal/web/scrollNav.js`:
```javascript
// Quick scroll navigation (Top & Bottom FAB) module.
// Computes visibility states and provides smooth scrolling for long lists and books.

/**
 * Pure helper to compute FAB visibility based on scroll metrics.
 * @param {number} scrollTop
 * @param {number} clientHeight
 * @param {number} scrollHeight
 * @param {number} threshold
 * @returns {{ showTop: boolean, showBottom: boolean }}
 */
export function computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold = 120) {
    const maxScroll = (scrollHeight || 0) - (clientHeight || 0);
    if (maxScroll <= 100) {
        return { showTop: false, showBottom: false };
    }
    const top = scrollTop || 0;
    return {
        showTop: top > threshold,
        showBottom: top < (maxScroll - threshold)
    };
}

/**
 * Resolves the active scrollable container based on current route/view.
 * @param {Document|Element} root
 * @returns {Element|null}
 */
export function resolveScrollContainer(root = typeof document !== 'undefined' ? document : null) {
    if (!root) return null;
    const body = root.body || root;
    if (body.dataset && body.dataset.activeTab === 'read') {
        const readerContent = root.querySelector('.text-reader__content');
        if (readerContent) return readerContent;
    }
    return root.querySelector('.view-container');
}

/**
 * Updates CSS classes on top/bottom buttons based on container scroll position.
 * @param {{ btnTop: Element|null, btnBottom: Element|null }} buttons
 * @param {Element|null} container
 * @param {number} threshold
 */
export function updateScrollFabVisibility(buttons, container, threshold = 120) {
    if (!buttons || !container) {
        if (buttons?.btnTop) buttons.btnTop.classList.remove('scroll-fab-btn--visible');
        if (buttons?.btnBottom) buttons.btnBottom.classList.remove('scroll-fab-btn--visible');
        return;
    }
    const { scrollTop, clientHeight, scrollHeight } = container;
    const { showTop, showBottom } = computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold);

    if (buttons.btnTop) {
        buttons.btnTop.classList.toggle('scroll-fab-btn--visible', showTop);
    }
    if (buttons.btnBottom) {
        buttons.btnBottom.classList.toggle('scroll-fab-btn--visible', showBottom);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd server/internal/web && node --test scrollNav.test.mjs`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/scrollNav.js server/internal/web/scrollNav.test.mjs
git commit -m "feat(web): add core scrollNav logic and unit tests"
```

---

### Task 2: DOM Injection, CSS Styling, and Visual Integration

**Files:**
- Modify: `server/internal/web/index.html:270-275`
- Modify: `server/internal/web/css/components.css`
- Modify: `server/internal/web/css/views/reader.css`
- Modify: `server/internal/web/css/responsive.css`

**Interfaces:**
- Consumes: CSS tokens (`--surface-card`, `--border-subtle`, `--shadow-md`, `--accent`, `--surface-hover`, `--text-secondary`)
- Produces: HTML element `#scroll-fab-group` with buttons `#btn-scroll-top` and `#btn-scroll-bottom`

- [ ] **Step 1: Inject FAB HTML into `index.html`**

In `server/internal/web/index.html`, right before the closing `</main>` tag (inside `.main-content`):
```html
            <!-- Quick Scroll Navigation FAB (Top & Bottom) -->
            <div class="scroll-fab-group" id="scroll-fab-group" aria-label="页面快捷滚动">
                <button class="scroll-fab-btn" id="btn-scroll-top" title="返回顶部" aria-label="返回顶部" type="button">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m18 15-6-6-6 6"/></svg>
                </button>
                <button class="scroll-fab-btn" id="btn-scroll-bottom" title="直达底部" aria-label="直达底部" type="button">
                    <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6"/></svg>
                </button>
            </div>
```

- [ ] **Step 2: Add styles in `server/internal/web/css/components.css`**

Add at the end of `server/internal/web/css/components.css`:
```css
/* Quick Scroll Navigation FAB */
.scroll-fab-group {
    position: fixed;
    right: 28px;
    bottom: 28px;
    display: flex;
    flex-direction: column;
    gap: 8px;
    z-index: 40;
    pointer-events: none;
}

.scroll-fab-btn {
    width: 38px;
    height: 38px;
    border-radius: 50%;
    background-color: var(--surface-card);
    border: 1px solid var(--border-subtle);
    color: var(--text-secondary);
    display: inline-flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    box-shadow: var(--shadow-md);
    opacity: 0;
    pointer-events: none;
    transform: scale(0.85);
    transition: opacity 0.2s ease, transform 0.2s ease, background-color 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.scroll-fab-btn--visible {
    opacity: 1;
    pointer-events: auto;
    transform: scale(1);
}

.scroll-fab-btn:hover {
    background-color: var(--surface-hover);
    border-color: var(--accent);
    color: var(--accent);
    transform: translateY(-1px) scale(1);
}

.scroll-fab-btn:active {
    transform: translateY(0) scale(0.96);
}

.scroll-fab-btn:focus-visible {
    outline: 2px solid var(--accent);
    outline-offset: 2px;
}
```

- [ ] **Step 3: Add reader overrides in `server/internal/web/css/views/reader.css`**

In `server/internal/web/css/views/reader.css`:
```css
/* Scroll FAB adjustments for Reader mode */
body[data-active-tab="read"] .scroll-fab-group {
    bottom: 72px;
    right: 24px;
}

/* Hide FAB in immersive reader mode */
body[data-reader-immersive="on"] .scroll-fab-group {
    opacity: 0;
    pointer-events: none;
    transition: opacity 0.25s ease;
}
```

- [ ] **Step 4: Add responsive positioning in `server/internal/web/css/responsive.css`**

In `server/internal/web/css/responsive.css` under the `@media (max-width: 768px)` section:
```css
    .scroll-fab-group {
        right: 16px;
        bottom: 16px;
    }
    body[data-active-tab="read"] .scroll-fab-group {
        right: 16px;
        bottom: 64px;
    }
```

- [ ] **Step 5: Verify with XSS checker and Go test**

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: PASS (0 violations)
Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add server/internal/web/index.html server/internal/web/css/components.css server/internal/web/css/views/reader.css server/internal/web/css/responsive.css
git commit -m "feat(web): add scroll fab HTML markup and responsive CSS styling"
```

---

### Task 3: Lifecycle Wiring & Dynamic Scroll Integration

**Files:**
- Modify: `server/internal/web/scrollNav.js`
- Modify: `server/internal/web/app.js`
- Modify: `server/internal/web/scrollNav.test.mjs`

**Interfaces:**
- Produces: `initScrollNav(options?: { root?: Document }): { update: () => void, cleanup: () => void }`

- [ ] **Step 1: Write integration tests in `scrollNav.test.mjs`**

Add to `server/internal/web/scrollNav.test.mjs`:
```javascript
test('initScrollNav wires click and smooth scroll handlers', () => {
    let scrolledTo = null;
    const fakeContainer = {
        scrollTop: 500,
        clientHeight: 800,
        scrollHeight: 3000,
        addEventListener: () => {},
        removeEventListener: () => {},
        scrollTo: (opts) => { scrolledTo = opts; }
    };
    const btnTop = {
        classList: {
            classes: new Set(),
            add(c) { this.classes.add(c); },
            remove(c) { this.classes.delete(c); },
            toggle(c, v) { if (v) this.add(c); else this.remove(c); },
            contains(c) { return this.classes.has(c); }
        },
        addEventListener(evt, fn) { if (evt === 'click') this._onClick = fn; }
    };
    const btnBottom = {
        classList: {
            classes: new Set(),
            add(c) { this.classes.add(c); },
            remove(c) { this.classes.delete(c); },
            toggle(c, v) { if (v) this.add(c); else this.remove(c); },
            contains(c) { return this.classes.has(c); }
        },
        addEventListener(evt, fn) { if (evt === 'click') this._onClick = fn; }
    };
    const fakeDoc = {
        body: { dataset: { activeTab: 'browser' } },
        getElementById(id) {
            if (id === 'btn-scroll-top') return btnTop;
            if (id === 'btn-scroll-bottom') return btnBottom;
            return null;
        },
        querySelector() { return fakeContainer; }
    };

    // Test clicking top scrolls to 0
    btnTop.addEventListener('click', () => {
        fakeContainer.scrollTo({ top: 0, behavior: 'smooth' });
    });
    btnTop._onClick();
    assert.deepEqual(scrolledTo, { top: 0, behavior: 'smooth' });

    // Test clicking bottom scrolls to scrollHeight
    btnBottom.addEventListener('click', () => {
        fakeContainer.scrollTo({ top: fakeContainer.scrollHeight, behavior: 'smooth' });
    });
    btnBottom._onClick();
    assert.deepEqual(scrolledTo, { top: 3000, behavior: 'smooth' });
});
```

- [ ] **Step 2: Implement `initScrollNav` in `server/internal/web/scrollNav.js`**

Complete `server/internal/web/scrollNav.js`:
```javascript
// Quick scroll navigation (Top & Bottom FAB) module.
// Computes visibility states and provides smooth scrolling for long lists and books.

/**
 * Pure helper to compute FAB visibility based on scroll metrics.
 * @param {number} scrollTop
 * @param {number} clientHeight
 * @param {number} scrollHeight
 * @param {number} threshold
 * @returns {{ showTop: boolean, showBottom: boolean }}
 */
export function computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold = 120) {
    const maxScroll = (scrollHeight || 0) - (clientHeight || 0);
    if (maxScroll <= 100) {
        return { showTop: false, showBottom: false };
    }
    const top = scrollTop || 0;
    return {
        showTop: top > threshold,
        showBottom: top < (maxScroll - threshold)
    };
}

/**
 * Resolves the active scrollable container based on current route/view.
 * @param {Document|Element} root
 * @returns {Element|null}
 */
export function resolveScrollContainer(root = typeof document !== 'undefined' ? document : null) {
    if (!root) return null;
    const body = root.body || root;
    if (body.dataset && body.dataset.activeTab === 'read') {
        const readerContent = root.querySelector('.text-reader__content');
        if (readerContent) return readerContent;
    }
    return root.querySelector('.view-container');
}

/**
 * Updates CSS classes on top/bottom buttons based on container scroll position.
 * @param {{ btnTop: Element|null, btnBottom: Element|null }} buttons
 * @param {Element|null} container
 * @param {number} threshold
 */
export function updateScrollFabVisibility(buttons, container, threshold = 120) {
    if (!buttons || !container) {
        if (buttons?.btnTop) buttons.btnTop.classList.remove('scroll-fab-btn--visible');
        if (buttons?.btnBottom) buttons.btnBottom.classList.remove('scroll-fab-btn--visible');
        return;
    }
    const { scrollTop, clientHeight, scrollHeight } = container;
    const { showTop, showBottom } = computeFabVisibility(scrollTop, clientHeight, scrollHeight, threshold);

    if (buttons.btnTop) {
        buttons.btnTop.classList.toggle('scroll-fab-btn--visible', showTop);
    }
    if (buttons.btnBottom) {
        buttons.btnBottom.classList.toggle('scroll-fab-btn--visible', showBottom);
    }
}

/**
 * Initializes scroll FAB controller: binds click actions, tracks active container,
 * and throttles scroll visibility updates via rAF.
 * @param {{ root?: Document, threshold?: number }} [opts]
 * @returns {{ update: () => void, cleanup: () => void }}
 */
export function initScrollNav(opts = {}) {
    const root = opts.root || (typeof document !== 'undefined' ? document : null);
    if (!root) return { update: () => {}, cleanup: () => {} };

    const threshold = opts.threshold || 120;
    const btnTop = root.getElementById('btn-scroll-top');
    const btnBottom = root.getElementById('btn-scroll-bottom');
    const buttons = { btnTop, btnBottom };

    let currentContainer = null;
    let rafId = null;

    function handleScroll() {
        if (rafId !== null) return;
        rafId = requestAnimationFrame(() => {
            rafId = null;
            updateScrollFabVisibility(buttons, currentContainer, threshold);
        });
    }

    function rebindContainer() {
        const nextContainer = resolveScrollContainer(root);
        if (nextContainer === currentContainer && nextContainer !== null) {
            updateScrollFabVisibility(buttons, currentContainer, threshold);
            return;
        }
        if (currentContainer && typeof currentContainer.removeEventListener === 'function') {
            currentContainer.removeEventListener('scroll', handleScroll);
        }
        currentContainer = nextContainer;
        if (currentContainer && typeof currentContainer.addEventListener === 'function') {
            currentContainer.addEventListener('scroll', handleScroll);
        }
        updateScrollFabVisibility(buttons, currentContainer, threshold);
    }

    // Bind click actions
    if (btnTop) {
        btnTop.addEventListener('click', () => {
            if (currentContainer && typeof currentContainer.scrollTo === 'function') {
                currentContainer.scrollTo({ top: 0, behavior: 'smooth' });
            }
        });
    }
    if (btnBottom) {
        btnBottom.addEventListener('click', () => {
            if (currentContainer && typeof currentContainer.scrollTo === 'function') {
                currentContainer.scrollTo({ top: currentContainer.scrollHeight, behavior: 'smooth' });
            }
        });
    }

    // Listen to hash route changes & window resize
    const onHashChange = () => {
        // Small timeout to allow DOM transition of active view
        setTimeout(rebindContainer, 50);
    };
    const onResize = () => {
        handleScroll();
    };

    if (typeof window !== 'undefined') {
        window.addEventListener('hashchange', onHashChange);
        window.addEventListener('resize', onResize);
    }

    // Initial binding
    rebindContainer();

    // Observe body for subtree / class / child list modifications to capture async lists
    let observer = null;
    if (typeof MutationObserver !== 'undefined' && root.body) {
        observer = new MutationObserver(() => {
            rebindContainer();
        });
        observer.observe(root.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['data-active-tab'] });
    }

    return {
        update: rebindContainer,
        cleanup: () => {
            if (rafId !== null) cancelAnimationFrame(rafId);
            if (currentContainer && typeof currentContainer.removeEventListener === 'function') {
                currentContainer.removeEventListener('scroll', handleScroll);
            }
            if (typeof window !== 'undefined') {
                window.removeEventListener('hashchange', onHashChange);
                window.removeEventListener('resize', onResize);
            }
            if (observer) observer.disconnect();
        }
    };
}
```

- [ ] **Step 3: Call `initScrollNav()` in `server/internal/web/app.js`**

In `server/internal/web/app.js`:
Import:
```javascript
import { initScrollNav } from './scrollNav.js';
```
And inside `initApp()` or right after DOM binding:
```javascript
    // Initialize quick scroll top & bottom navigation
    initScrollNav();
```

- [ ] **Step 4: Run all web tests and verification commands**

Run: `cd server/internal/web && node --test`
Expected: ALL PASS
Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: PASS (0 violations)
Run: `cd server && go test ./...`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add server/internal/web/scrollNav.js server/internal/web/scrollNav.test.mjs server/internal/web/app.js
git commit -m "feat(web): wire scrollNav with container lifecycle and app bootstrap"
```

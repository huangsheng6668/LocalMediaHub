import test from 'node:test';
import assert from 'node:assert/strict';
import { computeFabVisibility, resolveScrollContainer, updateScrollFabVisibility, initScrollNav } from './scrollNav.js';

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

test('updateScrollFabVisibility toggles visible class based on metrics', () => {
    const classListTop = new Set();
    const classListBottom = new Set();
    const buttons = {
        btnTop: {
            classList: {
                toggle: (cls, val) => { val ? classListTop.add(cls) : classListTop.delete(cls); },
                remove: (cls) => { classListTop.delete(cls); }
            }
        },
        btnBottom: {
            classList: {
                toggle: (cls, val) => { val ? classListBottom.add(cls) : classListBottom.delete(cls); },
                remove: (cls) => { classListBottom.delete(cls); }
            }
        }
    };
    const container = { scrollTop: 500, clientHeight: 800, scrollHeight: 3000 };
    updateScrollFabVisibility(buttons, container, 120);
    assert.equal(classListTop.has('scroll-fab-btn--visible'), true);
    assert.equal(classListBottom.has('scroll-fab-btn--visible'), true);

    // Null container removes class
    updateScrollFabVisibility(buttons, null);
    assert.equal(classListTop.has('scroll-fab-btn--visible'), false);
    assert.equal(classListBottom.has('scroll-fab-btn--visible'), false);

    // Null buttons handled gracefully
    assert.doesNotThrow(() => updateScrollFabVisibility(null, container));
});

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

    const nav = initScrollNav({ root: fakeDoc });
    assert.equal(typeof nav.update, 'function');
    assert.equal(typeof nav.cleanup, 'function');

    // Test clicking top scrolls to 0
    btnTop._onClick();
    assert.deepEqual(scrolledTo, { top: 0, behavior: 'smooth' });

    // Test clicking bottom scrolls to scrollHeight
    btnBottom._onClick();
    assert.deepEqual(scrolledTo, { top: 3000, behavior: 'smooth' });

    nav.cleanup();
});

test('initScrollNav update re-binds container when tab changes', () => {
    let currentTab = 'browser';
    let browserScrolled = null;
    let readerScrolled = null;
    const browserContainer = {
        scrollTop: 100,
        clientHeight: 800,
        scrollHeight: 2000,
        addEventListener: () => {},
        removeEventListener: () => {},
        scrollTo: (opts) => { browserScrolled = opts; }
    };
    const readerContainer = {
        scrollTop: 300,
        clientHeight: 800,
        scrollHeight: 5000,
        addEventListener: () => {},
        removeEventListener: () => {},
        scrollTo: (opts) => { readerScrolled = opts; }
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
        get body() { return { dataset: { activeTab: currentTab } }; },
        getElementById(id) {
            if (id === 'btn-scroll-top') return btnTop;
            if (id === 'btn-scroll-bottom') return btnBottom;
            return null;
        },
        querySelector(selector) {
            if (selector === '.text-reader__content') return readerContainer;
            if (selector === '.view-container') return browserContainer;
            return null;
        }
    };

    const nav = initScrollNav({ root: fakeDoc });
    btnTop._onClick();
    assert.deepEqual(browserScrolled, { top: 0, behavior: 'smooth' });

    // Switch tab to read
    currentTab = 'read';
    nav.update();

    btnTop._onClick();
    assert.deepEqual(readerScrolled, { top: 0, behavior: 'smooth' });

    nav.cleanup();
});

test('initScrollNav handles missing DOM gracefully without error', () => {
    const nav1 = initScrollNav({ root: null });
    assert.equal(typeof nav1.update, 'function');
    assert.equal(typeof nav1.cleanup, 'function');
    assert.doesNotThrow(() => nav1.update());
    assert.doesNotThrow(() => nav1.cleanup());

    const nav2 = initScrollNav({ root: { getElementById: () => null, querySelector: () => null } });
    assert.doesNotThrow(() => nav2.update());
    assert.doesNotThrow(() => nav2.cleanup());
});

test('initScrollNav cleanup removes container listeners and click handlers', () => {
    let scrollListenerRemoved = false;
    let clickRemoved = 0;
    const fakeContainer = {
        scrollTop: 500,
        clientHeight: 800,
        scrollHeight: 3000,
        addEventListener: () => {},
        removeEventListener: (evt) => { if (evt === 'scroll') scrollListenerRemoved = true; },
        scrollTo: () => {}
    };
    const btnTop = {
        classList: {
            remove: () => {},
            toggle: () => {}
        },
        addEventListener: () => {},
        removeEventListener: (evt) => { if (evt === 'click') clickRemoved++; }
    };
    const btnBottom = {
        classList: {
            remove: () => {},
            toggle: () => {}
        },
        addEventListener: () => {},
        removeEventListener: (evt) => { if (evt === 'click') clickRemoved++; }
    };
    const fakeDoc = {
        body: { dataset: { activeTab: 'browser' } },
        getElementById(id) {
            if (id === 'btn-scroll-top') return btnTop;
            if (id === 'btn-scroll-bottom') return btnBottom;
            return null;
        },
        querySelector: () => fakeContainer
    };

    const nav = initScrollNav({ root: fakeDoc });
    nav.cleanup();

    assert.equal(scrollListenerRemoved, true);
    assert.equal(clickRemoved, 2);
});


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

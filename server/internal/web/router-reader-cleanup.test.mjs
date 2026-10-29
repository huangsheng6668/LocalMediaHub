import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body><div id="r"></div><div id="d"></div><div id="b"></div></body></html>', {
    url: 'http://localhost:8000/#/dashboard',
});
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.localStorage = dom.window.localStorage;
globalThis.sessionStorage = dom.window.sessionStorage;

const { handleRoute } = await import('./router.js');
const { render: renderBookshelf } = await import('./bookshelf.js');

test('handleRoute invokes viewReader._cleanupReader when leaving #/read', () => {
    const viewReader = document.getElementById('r');
    const viewDashboard = document.getElementById('d');
    let cleaned = 0;
    viewReader._cleanupReader = () => {
        cleaned += 1;
    };

    window.location.hash = '#/dashboard';
    handleRoute(
        { viewReader, viewDashboard },
        () => {},
        () => {},
        () => {},
        () => {},
        () => {}
    );

    assert.equal(cleaned, 1);
    assert.equal(viewReader._cleanupReader, null);
});

test('renderBookshelf invokes container._cleanupReader', () => {
    const container = document.getElementById('b');
    let cleaned = 0;
    container._cleanupReader = () => {
        cleaned += 1;
    };

    renderBookshelf(container);

    assert.equal(cleaned, 1);
    assert.equal(container._cleanupReader, null);
});

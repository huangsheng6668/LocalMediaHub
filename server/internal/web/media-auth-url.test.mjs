import { test } from 'node:test';
import assert from 'node:assert/strict';
import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost:8000/' });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.sessionStorage = dom.window.sessionStorage;

const { setAuthToken, withMediaAuthToken } = await import('./api.js');

test('withMediaAuthToken appends token only when token is set and url targets /api/v1/', () => {
    setAuthToken('');
    assert.equal(
        withMediaAuthToken('/api/v1/images/a.jpg/thumbnail'),
        '/api/v1/images/a.jpg/thumbnail'
    );

    setAuthToken('my secret+token');
    assert.equal(
        withMediaAuthToken('/api/v1/images/a.jpg/thumbnail'),
        '/api/v1/images/a.jpg/thumbnail?token=my%20secret%2Btoken'
    );
    assert.equal(
        withMediaAuthToken('/api/v1/system/thumbnail?path=D%3A%5Ca.jpg'),
        '/api/v1/system/thumbnail?path=D%3A%5Ca.jpg&token=my%20secret%2Btoken'
    );
    // Already signed or already carrying token -> untouched
    assert.equal(
        withMediaAuthToken('/api/v1/books/image?path=a.epub&manifest=i1&sig=abc'),
        '/api/v1/books/image?path=a.epub&manifest=i1&sig=abc'
    );
    // Non-API URL -> untouched
    assert.equal(
        withMediaAuthToken('https://example.com/pic.jpg'),
        'https://example.com/pic.jpg'
    );
    // Cross-origin with /api/v1/ -> untouched (does not leak token to third-party)
    assert.equal(
        withMediaAuthToken('https://evil.com/api/v1/steal'),
        'https://evil.com/api/v1/steal'
    );
    // Same-origin absolute URL -> token appended
    assert.equal(
        withMediaAuthToken('http://localhost:8000/api/v1/images/a.jpg/thumbnail'),
        'http://localhost:8000/api/v1/images/a.jpg/thumbnail?token=my%20secret%2Btoken'
    );
    // Non-string / falsy -> returns input unchanged
    assert.equal(withMediaAuthToken(null), null);
    assert.equal(withMediaAuthToken(''), '');
    assert.equal(withMediaAuthToken(undefined), undefined);

    setAuthToken('');
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';

// apiRequest reads the success body with res.json(). The fakes below therefore
// only need json(); they deliberately stay minimal so every existing test mock
// in the suite keeps working unchanged.
function fakeResponse(body, status = 200) {
    return {
        ok: status >= 200 && status < 300,
        status,
        json: async () => JSON.parse(body),
    };
}

// Runs fn with global.fetch replaced, always restoring the previous value.
async function withFetch(impl, fn) {
    const original = global.fetch;
    global.fetch = impl;
    try {
        return await fn();
    } finally {
        if (original) global.fetch = original; else delete global.fetch;
    }
}

test('getBookChapter caches results and avoids repeated network requests', async () => {
    setupJsdom();
    const { getBookChapter, clearChapterCache } = await import('./api.js');
    clearChapterCache();
    let fetchCount = 0;

    try {
        await withFetch(async () => {
            fetchCount++;
            return fakeResponse(JSON.stringify({ title: 'Chapter 0', blocks: [{ type: 'text', value: 'Hello' }] }));
        }, async () => {
            const c1 = await getBookChapter('/test.txt', 0);
            assert.equal(c1.title, 'Chapter 0');
            assert.equal(fetchCount, 1);

            // Second call with same path and index should hit cache, not fetch
            const c2 = await getBookChapter('/test.txt', 0);
            assert.equal(c2.title, 'Chapter 0');
            assert.equal(fetchCount, 1);

            // Different index should fetch
            await getBookChapter('/test.txt', 1);
            assert.equal(fetchCount, 2);

            // Clear cache should cause re-fetch
            clearChapterCache();
            await getBookChapter('/test.txt', 0);
            assert.equal(fetchCount, 3);
        });
    } finally {
        clearChapterCache();
        teardownJsdom();
    }
});

test('apiRequest: 204 No Content resolves to null without touching json()', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    try {
        // The tags API answers DELETE with 204. Before the guard, apiRequest
        // called res.json() here and the resulting SyntaxError surfaced to
        // callers as a spurious failure. json() must not be reached at all.
        let jsonCalls = 0;
        const out = await withFetch(
            async () => ({ ok: true, status: 204, json: async () => { jsonCalls++; throw new SyntaxError('Unexpected end of JSON input'); } }),
            () => apiRequest('/api/v1/tags/1', { method: 'DELETE' }),
        );
        assert.equal(out, null);
        assert.equal(jsonCalls, 0, 'json() must not be called for a 204');
    } finally {
        teardownJsdom();
    }
});

test('apiRequest: 205 Reset Content also resolves to null', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    try {
        const out = await withFetch(
            async () => ({ ok: true, status: 205, json: async () => { throw new SyntaxError('no body'); } }),
            () => apiRequest('/api/v1/x'),
        );
        assert.equal(out, null);
    } finally {
        teardownJsdom();
    }
});

test('apiRequest: parses a normal JSON body', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    try {
        const out = await withFetch(async () => fakeResponse(JSON.stringify({ a: 1, b: [2, 3] })),
            () => apiRequest('/api/v1/x'));
        assert.deepEqual(out, { a: 1, b: [2, 3] });
    } finally {
        teardownJsdom();
    }
});

test('apiRequest: injects the bearer token when one is set', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    const { setAuthToken } = await import('./state.js');
    try {
        let seen = null;
        setAuthToken('secret-token');
        await withFetch(async (_url, opts) => { seen = opts; return fakeResponse('{}'); },
            () => apiRequest('/api/v1/x'));
        assert.equal(seen.headers.Authorization, 'Bearer secret-token');
    } finally {
        setAuthToken('');
        teardownJsdom();
    }
});

test('apiRequest: 401 dispatches AUTH_REQUIRED and rejects', async () => {
    setupJsdom();
    const { apiRequest, AUTH_REQUIRED_EVENT } = await import('./api.js');
    try {
        let fired = 0;
        const onAuth = () => { fired++; };
        window.addEventListener(AUTH_REQUIRED_EVENT, onAuth);
        await assert.rejects(
            () => withFetch(async () => fakeResponse(JSON.stringify({ error: 'nope' }), 401), () => apiRequest('/api/v1/x')),
            /Authentication required/,
        );
        assert.equal(fired, 1, 'AUTH_REQUIRED_EVENT should fire exactly once');
        window.removeEventListener(AUTH_REQUIRED_EVENT, onAuth);
    } finally {
        teardownJsdom();
    }
});

test('apiRequest: an error status surfaces the server message', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    try {
        await assert.rejects(
            () => withFetch(async () => fakeResponse(JSON.stringify({ error: '路径不在允许范围内' }), 403), () => apiRequest('/api/v1/x')),
            /路径不在允许范围内/,
        );
    } finally {
        teardownJsdom();
    }
});

test('apiRequest: a stalled request times out instead of hanging forever', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    try {
        // A fetch that never settles on its own — only the abort ends it.
        const neverSettles = (_url, opts) => new Promise((_resolve, reject) => {
            opts.signal.addEventListener('abort', () => {
                const err = new Error('The operation was aborted.');
                err.name = 'AbortError';
                reject(err);
            });
        });
        await assert.rejects(
            () => withFetch(neverSettles, () => apiRequest('/api/v1/slow', { timeoutMs: 30 })),
            /请求超时/,
        );
    } finally {
        teardownJsdom();
    }
});

test('apiRequest: a caller-owned signal is not hijacked by the timeout', async () => {
    setupJsdom();
    const { apiRequest } = await import('./api.js');
    try {
        const controller = new AbortController();
        const neverSettles = (_url, opts) => new Promise((_resolve, reject) => {
            opts.signal.addEventListener('abort', () => {
                const err = new Error('The operation was aborted.');
                err.name = 'AbortError';
                reject(err);
            });
        });
        const p = withFetch(neverSettles, () => apiRequest('/api/v1/x', { signal: controller.signal, timeoutMs: 5000 }));
        controller.abort();
        // Must stay an AbortError so the caller's own cancellation handling works,
        // rather than being rewritten into the timeout message.
        await assert.rejects(p, (err) => {
            assert.equal(err.name, 'AbortError');
            assert.doesNotMatch(err.message, /请求超时/);
            return true;
        });
    } finally {
        teardownJsdom();
    }
});

test('escapeHtml is exported from utils.js, not api.js', async () => {
    const utils = await import('./utils.js');
    assert.equal(typeof utils.escapeHtml, 'function');
    assert.equal(utils.escapeHtml('<a href="x">&\'</a>'), '&lt;a href=&quot;x&quot;&gt;&amp;&#039;&lt;/a&gt;');
    // Guard the relocation: a stray re-export would let the duplication creep back.
    const api = await import('./api.js');
    assert.equal(api.escapeHtml, undefined, 'api.js must not re-export escapeHtml');
});

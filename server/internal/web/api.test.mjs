import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';

test('getBookChapter caches results and avoids repeated network requests', async () => {
    setupJsdom();
    const { getBookChapter, clearChapterCache } = await import('./api.js');
    clearChapterCache();
    let fetchCount = 0;
    const originalFetch = global.fetch;
    global.fetch = async (url) => {
        fetchCount++;
        return {
            ok: true,
            status: 200,
            json: async () => ({ title: 'Chapter 0', blocks: [{ type: 'text', value: 'Hello' }] }),
        };
    };

    try {
        const c1 = await getBookChapter('/test.txt', 0);
        assert.equal(c1.title, 'Chapter 0');
        assert.equal(fetchCount, 1);

        // Second call with same path and index should hit cache, not fetch
        const c2 = await getBookChapter('/test.txt', 0);
        assert.equal(c2.title, 'Chapter 0');
        assert.equal(fetchCount, 1);

        // Different index should fetch
        const c3 = await getBookChapter('/test.txt', 1);
        assert.equal(fetchCount, 2);

        // Clear cache should cause re-fetch
        clearChapterCache();
        const c4 = await getBookChapter('/test.txt', 0);
        assert.equal(fetchCount, 3);
    } finally {
        if (originalFetch) {
            global.fetch = originalFetch;
        } else {
            delete global.fetch;
        }
        clearChapterCache();
        teardownJsdom();
    }
});

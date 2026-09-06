import { test } from 'node:test';
import assert from 'node:assert/strict';
import { setupJsdom, teardownJsdom } from './_snapshot-helpers.mjs';

test('openMedia: routes text files to reader hash', async () => {
    setupJsdom();
    try {
        const { openMedia } = await import('./lightbox.js');

        // 1. Normal text file
        window.location.hash = '#/browser';
        openMedia({ path: '/m/novel.txt', name: 'novel.txt', media_type: 'text', extension: '.txt' });
        assert.equal(window.location.hash, '#/read?path=%2Fm%2Fnovel.txt');

        // 2. Normal epub file
        window.location.hash = '#/browser';
        openMedia({ path: '/m/book.epub', name: 'book.epub', media_type: 'text', extension: '.epub' });
        assert.equal(window.location.hash, '#/read?path=%2Fm%2Fbook.epub');

        // 3. Misclassified text file (media_type: 'video', but .txt extension)
        window.location.hash = '#/browser';
        openMedia({ path: '/m/misclassified.txt', name: 'misclassified.txt', media_type: 'video', extension: '.txt' });
        assert.equal(window.location.hash, '#/read?path=%2Fm%2Fmisclassified.txt');

        // 4. Misclassified text file without extension property, but .txt in path
        window.location.hash = '#/browser';
        openMedia({ path: '/m/other.txt', name: 'other.txt', media_type: 'video' });
        assert.equal(window.location.hash, '#/read?path=%2Fm%2Fother.txt');
    } finally {
        teardownJsdom();
    }
});

test('openMedia: handles unsupported text formats without opening video player', async () => {
    setupJsdom();
    try {
        const { openMedia } = await import('./lightbox.js');

        window.location.hash = '#/browser';
        // .mobi file with media_type: 'video' or 'text'
        openMedia({ path: '/m/book.mobi', name: 'book.mobi', media_type: 'video', extension: '.mobi' });
        // Must NOT change hash to video player or open video player modal
        assert.equal(window.location.hash, '#/browser');
    } finally {
        teardownJsdom();
    }
});

test('openVideoPlayer: guards against text files and redirects to reader', async () => {
    setupJsdom();
    try {
        const { openVideoPlayer } = await import('./videoPlayer.js');

        window.location.hash = '#/browser';
        await openVideoPlayer({ path: '/m/novel.txt', name: 'novel.txt', media_type: 'video', extension: '.txt' });
        assert.equal(window.location.hash, '#/read?path=%2Fm%2Fnovel.txt');

        window.location.hash = '#/browser';
        await openVideoPlayer({ path: '/m/book.epub', name: 'book.epub', media_type: 'text' });
        assert.equal(window.location.hash, '#/read?path=%2Fm%2Fbook.epub');

        window.location.hash = '#/browser';
        await openVideoPlayer({ path: '/m/book.mobi', name: 'book.mobi', media_type: 'text', extension: '.mobi' });
        assert.equal(window.location.hash, '#/browser');
    } finally {
        teardownJsdom();
    }
});


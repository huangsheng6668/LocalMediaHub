import test from 'node:test';
import assert from 'node:assert/strict';
import {
    needsTranscodeExt, buildHlsPlaylistUrl, resolveHlsStrategy, needsHlsRestart,
    shouldTryDirectPlay, buildDirectStreamUrl, buildTranscodeStreamUrl, isUnsupportedMediaError,
    shouldReanchorOnHlsError, HLS_AUTO_REANCHOR_COOLDOWN_MS,
} from './hlsCompat.js';

test('needsTranscodeExt matches container-unsupported extensions case-insensitively', () => {
    assert.equal(needsTranscodeExt('Movie.mkv'), true);
    assert.equal(needsTranscodeExt('clip.TS'), true);
    assert.equal(needsTranscodeExt('a.b.wmv'), true);
    assert.equal(needsTranscodeExt('video.mp4'), false);
    assert.equal(needsTranscodeExt('noext'), false);
});

test('buildHlsPlaylistUrl encodes the path', () => {
    assert.equal(
        buildHlsPlaylistUrl('http://192.168.1.2:8000', 'D:\\Media\\a b.mp4'),
        'http://192.168.1.2:8000/api/v1/media/hls/playlist?path=D%3A%5CMedia%5Ca%20b.mp4',
    );
});

test('buildHlsPlaylistUrl appends a floored start anchor when given (spec 2026-09-06)', () => {
    assert.equal(
        buildHlsPlaylistUrl('http://h:1', 'a.mp4', 7200.9),
        'http://h:1/api/v1/media/hls/playlist?path=a.mp4&start=7200',
    );
    // Zero / negative / non-finite anchors keep the legacy URL shape.
    assert.equal(buildHlsPlaylistUrl('http://h:1', 'a.mp4', 0).includes('start='), false);
    assert.equal(buildHlsPlaylistUrl('http://h:1', 'a.mp4', -5).includes('start='), false);
    assert.equal(buildHlsPlaylistUrl('http://h:1', 'a.mp4', NaN).includes('start='), false);
});

test('needsHlsRestart only fires outside the anchored seekable window', () => {
    // Inside the window (accounting for the anchor offset): native seek.
    assert.equal(needsHlsRestart(100, 0, 225), false);
    assert.equal(needsHlsRestart(2300, 2160, 225), false);   // anchored session, rel=140
    assert.equal(needsHlsRestart(2385, 2160, 225), false);   // exactly at edge, rel=225
    assert.equal(needsHlsRestart(0, 0, 225), false);
    // Beyond the edge or before the anchor: re-anchor needed.
    assert.equal(needsHlsRestart(7200, 0, 225), true);
    assert.equal(needsHlsRestart(2386, 2160, 225), true);    // rel=226 > 225
    assert.equal(needsHlsRestart(7200, 2160, 225), true);    // rel=5040 > 225
    assert.equal(needsHlsRestart(2000, 2160, 225), true);    // rel=-160 < -0.5
    // Unknown seekable range: near-anchor allows native, distant target triggers re-anchor.
    assert.equal(needsHlsRestart(1, 0, null), false);        // rel=1 <= 2 near anchor
    assert.equal(needsHlsRestart(7200, 0, null), true);       // rel=7200 > 2 distant target
    assert.equal(needsHlsRestart(7200, 0, Infinity), true);
});

test('resolveHlsStrategy prefers hls.js when MSE is available', () => {
    const fakeVideo = { canPlayType: () => 'probably' };
    const origHls = globalThis.window && globalThis.window.Hls;
    globalThis.window = globalThis.window || {};
    globalThis.window.Hls = { isSupported: () => true };
    assert.equal(resolveHlsStrategy(fakeVideo), 'hlsjs');
    globalThis.window.Hls = origHls;
});

test('resolveHlsStrategy falls back to native HLS when only canPlayType works', () => {
    const fakeVideo = { canPlayType: () => 'maybe' };
    assert.equal(resolveHlsStrategy(fakeVideo), 'native');
});

test('resolveHlsStrategy returns none in environments without MSE or native HLS', () => {
    // jsdom: no window.Hls, canPlayType returns empty string.
    const fakeVideo = { canPlayType: () => '' };
    assert.equal(resolveHlsStrategy(fakeVideo), 'none');
    assert.equal(resolveHlsStrategy(null), 'none');
});

test('shouldTryDirectPlay allows only browser-native containers', () => {
    assert.equal(shouldTryDirectPlay('movie.mp4'), true);
    assert.equal(shouldTryDirectPlay('movie.MOV'), true);
    assert.equal(shouldTryDirectPlay('movie.m4v'), true);
    assert.equal(shouldTryDirectPlay('movie.webm'), true);
    assert.equal(shouldTryDirectPlay('movie.mkv'), false);
    assert.equal(shouldTryDirectPlay('movie.ts'), false);
    assert.equal(shouldTryDirectPlay('no-extension'), false);
    assert.equal(shouldTryDirectPlay(''), false);
});

test('buildDirectStreamUrl encodes the path', () => {
    assert.equal(
        buildDirectStreamUrl('http://h:1', 'D:\\Media\\a b.mp4'),
        'http://h:1/api/v1/media/stream?path=D%3A%5CMedia%5Ca%20b.mp4'
    );
});

test('buildTranscodeStreamUrl adds transcode and floors the start anchor', () => {
    assert.equal(
        buildTranscodeStreamUrl('http://h:1', 'a.mkv', 0),
        'http://h:1/api/v1/media/stream?path=a.mkv&transcode=true'
    );
    assert.equal(
        buildTranscodeStreamUrl('http://h:1', 'a.mkv', 90.9),
        'http://h:1/api/v1/media/stream?path=a.mkv&transcode=true&start=90'
    );
});

test('isUnsupportedMediaError matches decode and src-not-supported only', () => {
    assert.equal(isUnsupportedMediaError(3), true);
    assert.equal(isUnsupportedMediaError(4), true);
    assert.equal(isUnsupportedMediaError(2), false);
    assert.equal(isUnsupportedMediaError(1), false);
    assert.equal(isUnsupportedMediaError(null), false);
});

test('shouldReanchorOnHlsError only reacts to a lost session (404 load errors)', () => {
    const now = 1_000_000;
    // Session reaped / segments deleted: playlist and fragment loads 404.
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 404, now, 0), true);
    assert.equal(shouldReanchorOnHlsError('levelLoadError', 404, now, 0), true);
    // Auth / capacity / server errors must not re-anchor (they mask token or
    // slot problems; hls.js retry + startLoad already covers them).
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 401, now, 0), false);
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 403, now, 0), false);
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 503, now, 0), false);
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 500, now, 0), false);
    // Network timeouts carry no status.
    assert.equal(shouldReanchorOnHlsError('fragLoadTimeOut', undefined, now, 0), false);
    assert.equal(shouldReanchorOnHlsError('manifestLoadError', 404, now, 0), false);
    assert.equal(shouldReanchorOnHlsError(undefined, 404, now, 0), false);
});

test('shouldReanchorOnHlsError enforces a cooldown between automatic re-anchors', () => {
    const now = 1_000_000;
    assert.equal(HLS_AUTO_REANCHOR_COOLDOWN_MS, 15000);
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 404, now, now - 5000), false);
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 404, now, now - 15000), true);
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 404, now, now - 60000), true);
    // Clock skew (last stamp in the future) stays in cooldown.
    assert.equal(shouldReanchorOnHlsError('fragLoadError', 404, now, now + 5000), false);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { needsTranscodeExt, buildHlsPlaylistUrl, resolveHlsStrategy } from './hlsCompat.js';

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

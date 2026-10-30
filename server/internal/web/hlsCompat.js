// Playback source compatibility helpers: HLS routing (spec
// 2026-09-03-hls-transcode-b2) and native direct-play selection (spec
// 2026-10-30-web-direct-play). Pure functions: no DOM side effects, fully
// unit-testable under jsdom.

export const HLS_MIME = 'application/vnd.apple.mpegurl';

// File extensions whose CONTAINERS browsers cannot demux natively even when
// the codec is fine — the web player auto-routes these to transcoded output.
const NEEDS_TRANSCODE_EXTS = ['.ts', '.mkv', '.avi', '.wmv', '.flv'];

export function needsTranscodeExt(fileName) {
    const dot = fileName.lastIndexOf('.');
    if (dot < 0) return false;
    return NEEDS_TRANSCODE_EXTS.includes(fileName.substring(dot).toLowerCase());
}

// Direct-play allowlist: containers browsers demux natively. These are served
// over HTTP Range by /api/v1/media/stream, so seeking is handled by the
// browser itself — no ffmpeg session, no re-encode. Everything else (mkv/ts/
// avi/...) keeps the HLS transcode route.
const DIRECT_PLAY_EXTS = ['.mp4', '.m4v', '.mov', '.webm'];

export function shouldTryDirectPlay(fileName) {
    if (!fileName) return false;
    const dot = fileName.lastIndexOf('.');
    if (dot < 0) return false;
    return DIRECT_PLAY_EXTS.includes(fileName.substring(dot).toLowerCase());
}

export function buildDirectStreamUrl(apiBase, path) {
    return `${apiBase}/api/v1/media/stream?path=${encodeURIComponent(path)}`;
}

// Legacy single-pipe transcode fallback, used only when neither hls.js nor
// native HLS exists. startSec anchors ffmpeg input-side, mirroring the HLS
// playlist's ?start= contract.
export function buildTranscodeStreamUrl(apiBase, path, startSec) {
    let url = `${apiBase}/api/v1/media/stream?path=${encodeURIComponent(path)}&transcode=true`;
    if (Number.isFinite(startSec) && startSec > 0) {
        url += `&start=${Math.floor(startSec)}`;
    }
    return url;
}

// 3 = MEDIA_ERR_DECODE, 4 = MEDIA_ERR_SRC_NOT_SUPPORTED. Network (2) and
// abort (1) must NOT trigger the transcode fallback.
export function isUnsupportedMediaError(code) {
    return code === 3 || code === 4;
}

export function buildHlsPlaylistUrl(apiBase, path, startSec) {
    let url = `${apiBase}/api/v1/media/hls/playlist?path=${encodeURIComponent(path)}`;
    // Seek anchor (spec 2026-09-06-hls-seek-restart): the server re-anchors
    // the transcode at this offset so positions not yet transcoded play
    // immediately instead of clamping to the live edge.
    if (Number.isFinite(startSec) && startSec > 0) {
        url += `&start=${Math.floor(startSec)}`;
    }
    return url;
}

export const HLS_SEEK_MARGIN = 0;

// needsHlsRestart reports whether a progress-bar drag to targetTime (on the
// video's ABSOLUTE timeline) can be served by the current session, or needs
// the transcode re-anchored at the target. offsetSec is the current
// session's start anchor; seekableEnd is video.seekable's end (or null when
// unknown).
export function needsHlsRestart(targetTime, offsetSec, seekableEnd) {
    const rel = targetTime - (offsetSec || 0);
    // Before the current anchor (with 0.5s tolerance): definitely needs restart.
    if (rel < -0.5) return true;
    // Unknown seekable range: if not close to the anchor, re-anchor to prevent
    // setting video.currentTime into an unbuffered void.
    if (seekableEnd == null || !isFinite(seekableEnd) || seekableEnd <= 0) {
        return rel > 2;
    }
    // Beyond the current seekable edge: needs restart.
    return rel > seekableEnd;
}

// Auto-reanchor cooldown: one automatic recovery attempt per 15s so a source
// whose sessions keep dying cannot spin ffmpeg in a tight loop.
export const HLS_AUTO_REANCHOR_COOLDOWN_MS = 15000;

// shouldReanchorOnHlsError decides whether a fatal hls.js error means "the
// transcoded session this playlist referenced is gone" (idle reaper, disk-cap
// eviction, server restart) rather than a problem a plain startLoad() can fix.
// Only 404 load errors qualify: auth (401/403), capacity (503) and generic
// server errors must not trigger a new ffmpeg session. A re-anchored request
// starts a fresh session at the current position instead of 404-looping on
// segments that no longer exist.
export function shouldReanchorOnHlsError(details, status, nowMs, lastAutoReanchorAt) {
    if (details !== 'fragLoadError' && details !== 'levelLoadError') return false;
    if (status !== 404) return false;
    if (!lastAutoReanchorAt) return true;
    return nowMs - lastAutoReanchorAt >= HLS_AUTO_REANCHOR_COOLDOWN_MS;
}

// resolveHlsStrategy picks the HLS playback route:
//   'hlsjs'  — hls.js over MSE (best: xhrSetup can inject auth headers on
//              every request, including segments)
//   'native' — browser-native HLS (Safari); segments cannot carry auth
//              headers, so token mode may 401 on segments (known edge,
//              documented in the spec)
//   'none'   — no HLS route available; caller falls back to the legacy
//              single-pipe fMP4 transcode path (zero regression)
export function resolveHlsStrategy(video) {
    if (typeof window !== 'undefined' &&
        window.Hls && typeof window.Hls.isSupported === 'function' &&
        window.Hls.isSupported()) {
        return 'hlsjs';
    }
    if (video && typeof video.canPlayType === 'function' && video.canPlayType(HLS_MIME)) {
        return 'native';
    }
    return 'none';
}

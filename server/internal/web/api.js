import { state, setAuthToken } from './state.js';

export { setAuthToken };

// Event dispatched on 401 to trigger the token-input modal in app.js.
export const AUTH_REQUIRED_EVENT = 'lmh:auth-required';

// Upper bound on a single API call. Without it a stalled server (or a dropped
// Wi-Fi link) leaves the UI waiting forever with no way to recover — the fetch
// promise simply never settles.
export const DEFAULT_TIMEOUT_MS = 30000;

export async function apiRequest(url, options = {}) {
    // Inject Authorization header if we have a token.
    const finalOptions = { ...options };
    if (state.authToken) {
        finalOptions.headers = {
            ...(finalOptions.headers || {}),
            'Authorization': `Bearer ${state.authToken}`,
        };
    }

    // Callers may override the budget (long scans) or pass their own signal to
    // cancel (e.g. a superseded search). An explicit signal wins: we must not
    // hijack a controller the caller owns.
    const timeoutMs = typeof finalOptions.timeoutMs === 'number' ? finalOptions.timeoutMs : DEFAULT_TIMEOUT_MS;
    delete finalOptions.timeoutMs;
    let timer = null;
    let timedOut = false;
    if (!finalOptions.signal && timeoutMs > 0) {
        const controller = new AbortController();
        finalOptions.signal = controller.signal;
        timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
    }

    try {
        const res = await fetch(url, finalOptions);

        if (res.status === 401) {
            // Trigger modal — app.js listens and re-prompts the user.
            // window.CustomEvent (not the bare global) so the event is built from
            // the same realm as the target it is dispatched on — under jsdom the
            // ambient CustomEvent is a different class and dispatchEvent rejects it.
            window.dispatchEvent(new window.CustomEvent(AUTH_REQUIRED_EVENT, { detail: { url } }));
            throw new Error('Authentication required');
        }

        if (!res.ok) {
            let errorMsg = `HTTP Error ${res.status}`;
            try {
                const errData = await res.json();
                if (errData && errData.error) errorMsg = errData.error;
            } catch (_) {}
            throw new Error(errorMsg);
        }

        // 204 No Content / 205 Reset Content carry no body by definition, and the
        // API does use them (tag deletion answers 204). Calling res.json() there
        // threw a SyntaxError, which callers saw as a spurious failure.
        if (res.status === 204 || res.status === 205) return null;

        return res.json();
    } catch (err) {
        // Only rewrite the abort we caused ourselves; a caller-initiated abort
        // must stay an AbortError so its own handling keeps working.
        if (timedOut && err && err.name === 'AbortError') {
            throw new Error(`请求超时（${timeoutMs / 1000}s）：${url}`);
        }
        throw err;
    } finally {
        if (timer !== null) clearTimeout(timer);
    }
}

// Book / text-reader helpers (Task 15): thin wrappers over the books API so
// textReader.js does not hard-code URLs. They use the shared apiRequest helper
// which injects the Authorization header and surfaces 401 via AUTH_REQUIRED.
export async function getBookInfo(path) {
    return apiRequest(`/api/v1/books/info?path=${encodeURIComponent(path)}`);
}

const CHAPTER_CACHE_MAX = 100;
const chapterCache = new Map();

export function clearChapterCache() {
    chapterCache.clear();
}

export async function getBookChapter(path, index) {
    const key = `${path}::${index}`;
    if (chapterCache.has(key)) {
        return chapterCache.get(key);
    }
    const data = await apiRequest(
        `/api/v1/books/chapter?path=${encodeURIComponent(path)}&index=${encodeURIComponent(index)}`
    );
    if (chapterCache.size >= CHAPTER_CACHE_MAX) {
        const oldestKey = chapterCache.keys().next().value;
        chapterCache.delete(oldestKey);
    }
    chapterCache.set(key, data);
    return data;
}

// getAuthToken exposes the current bearer token so non-fetch consumers (e.g.
// <img src=...> tags, which cannot set Authorization headers) can append it
// as a query param. Returns '' when no token is configured (open-auth mode).
export function getAuthToken() {
    return state.authToken || '';
}

export function withMediaAuthToken(url) {
    if (!url || typeof url !== 'string') return url;
    const token = getAuthToken();
    if (!token) return url;
    if (!url.includes('/api/v1/')) return url;
    if (/^https?:\/\//i.test(url) && typeof window !== 'undefined' && window.location?.origin) {
        if (!url.startsWith(window.location.origin + '/')) return url;
    }
    if (/[?&](token|sig)=/.test(url)) return url;
    return url + (url.includes('?') ? '&' : '?') + 'token=' + encodeURIComponent(token);
}

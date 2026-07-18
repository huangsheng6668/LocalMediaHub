import { state } from './state.js';

// Event dispatched on 401 to trigger the token-input modal in app.js.
export const AUTH_REQUIRED_EVENT = 'lmh:auth-required';

export async function apiRequest(url, options = {}) {
    // Inject Authorization header if we have a token.
    const finalOptions = { ...options };
    if (state.authToken) {
        finalOptions.headers = {
            ...(finalOptions.headers || {}),
            'Authorization': `Bearer ${state.authToken}`,
        };
    }

    const res = await fetch(url, finalOptions);

    if (res.status === 401) {
        // Trigger modal — app.js listens and re-prompts the user.
        window.dispatchEvent(new CustomEvent(AUTH_REQUIRED_EVENT, { detail: { url } }));
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
    return res.json();
}

// Book / text-reader helpers (Task 15): thin wrappers over the books API so
// textReader.js does not hard-code URLs. They use the shared apiRequest helper
// which injects the Authorization header and surfaces 401 via AUTH_REQUIRED.
export async function getBookInfo(path) {
    return apiRequest(`/api/v1/books/info?path=${encodeURIComponent(path)}`);
}

export async function getBookChapter(path, index) {
    return apiRequest(
        `/api/v1/books/chapter?path=${encodeURIComponent(path)}&index=${encodeURIComponent(index)}`
    );
}

// getAuthToken exposes the current bearer token so non-fetch consumers (e.g.
// <img src=...> tags, which cannot set Authorization headers) can append it
// as a query param. Returns '' when no token is configured (open-auth mode).
export function getAuthToken() {
    return state.authToken || '';
}

// XSS Prevention: Safe HTML escaping for dynamic content
export function escapeHtml(str) {
    if (str === null || str === undefined) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

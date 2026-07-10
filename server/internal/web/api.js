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

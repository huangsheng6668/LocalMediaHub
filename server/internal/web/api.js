// API Client wrapper and safety helpers
export async function apiRequest(url, options = {}) {
    const res = await fetch(url, options);
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

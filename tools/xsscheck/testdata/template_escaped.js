import { escapeHtml } from './api.js';

function renderGood(name) {
    element.innerHTML = `<a>${escapeHtml(name)}</a>`; // XSS-SAFE: name wrapped in escapeHtml() (also detectable by escapeHtml-call scan)
}

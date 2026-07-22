import { escapeHtml } from './api.js';

function renderClean(name, path) {
    const safeName = escapeHtml(name);
    const safePath = escapeHtml(path.replace(/\\/g, '/'));
    element.innerHTML = `<a href="${safePath}">${safeName}</a>`; // XSS-SAFE: safeName/safePath derived from escapeHtml() above
    element.innerHTML = '<div>static text</div>'; // XSS-SAFE: hardcoded literal
    element.innerHTML = "<span class='x'>also static</span>"; // XSS-SAFE: hardcoded literal
}

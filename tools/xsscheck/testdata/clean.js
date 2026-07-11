import { escapeHtml } from './api.js';

function renderClean(name, path) {
    const safeName = escapeHtml(name);
    const safePath = escapeHtml(path.replace(/\\/g, '/'));
    element.innerHTML = `<a href="${safePath}">${safeName}</a>`;
    element.innerHTML = '<div>static text</div>';
    element.innerHTML = "<span class='x'>also static</span>";
}

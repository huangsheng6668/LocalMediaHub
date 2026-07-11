import { escapeHtml } from './api.js';

function renderGood(name) {
    element.innerHTML = '<a>' + escapeHtml(name) + '</a>';
}

import { escapeHtml } from './api.js';

function renderMixed(name, path) {
    a.innerHTML = `<b>${escapeHtml(name)}</b>`;        // OK — escapeHtml + comment
    b.innerHTML = '<a href="' + path + '">link</a>';    // BAD: path not escaped, no justification
    c.innerHTML = `<div>${name}</div>`;                  // BAD: name not escaped, no justification
}

import { escapeHtml } from './api.js';

function renderMixed(name, path) {
    a.innerHTML = `<b>${escapeHtml(name)}</b>`;        // OK
    b.innerHTML = '<a href="' + path + '">link</a>';    // BAD: path not escaped
    c.innerHTML = `<div>${name}</div>`;                  // BAD: name not escaped
}

// Verifies multi-line sink expressions: the regex catches the FIRST line of a
// map() callback. The justification must be on the line of the assignment.
function render(items) {
    // XSS-SAFE: items are escapeHtml-wrapped inside the map body
    element.innerHTML = items.map(i => {
        return `<li>${escapeHtml(i)}</li>`;
    }).join('');
}

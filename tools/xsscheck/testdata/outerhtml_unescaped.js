// Verifies the lint now also scans .outerHTML = sinks.
function render(name) {
    element.outerHTML = `<a>${name}</a>`;
}

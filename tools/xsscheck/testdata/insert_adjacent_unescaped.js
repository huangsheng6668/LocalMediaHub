// Verifies the lint now also scans insertAdjacentHTML sinks.
function render(name) {
    element.insertAdjacentHTML('beforeend', `<a>${name}</a>`);
}

// Verifies the lint now also scans document.write sinks.
function render(name) {
    document.write('<a>' + name + '</a>');
}

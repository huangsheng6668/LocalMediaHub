// Verifies: a sink with a `// XSS-SAFE:` comment on the LINE ABOVE passes,
// even when the expression is a raw variable.
function render(name) {
    // XSS-SAFE: name is app-internal constant, not user input
    element.innerHTML = name;
}

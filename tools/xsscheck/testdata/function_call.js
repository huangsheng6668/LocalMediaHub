function renderList(items) {
    element.innerHTML = items.map(i => `<li>${escapeHtml(i)}</li>`).join(''); // XSS-SAFE: i wrapped in escapeHtml()
    element.innerHTML = renderStaticHtml(); // XSS-SAFE: renderStaticHtml returns trusted static markup
}

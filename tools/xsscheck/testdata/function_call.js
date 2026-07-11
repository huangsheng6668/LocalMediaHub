function renderList(items) {
    element.innerHTML = items.map(i => `<li>${escapeHtml(i)}</li>`).join('');
    element.innerHTML = renderStaticHtml();
}

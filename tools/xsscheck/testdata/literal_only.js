function renderStatic() {
    element.innerHTML = '正在读取目录结构...'; // XSS-SAFE: hardcoded literal
    other.innerHTML = "<div class='loading'>Loading</div>"; // XSS-SAFE: hardcoded literal
}

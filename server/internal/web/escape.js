// escape.js — HTML entity escaping for safe DOM insertion.
//
// Re-exports the canonical escapeHtml implementation from api.js so that new
// modules can import it from a focused, semantics-named module. Existing
// files keep `import { escapeHtml } from './api.js'` — both paths resolve to
// the same function.
//
// Round 32 S4: introduced alongside the xsscheck lint rule extension that
// requires every innerHTML/outerHTML/insertAdjacentHTML/document.write sink
// to either call escapeHtml(...) or carry a `// XSS-SAFE:` justification.
export { escapeHtml } from './api.js';

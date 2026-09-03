# Web UI 视觉体验系统化精细调优实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 针对通过视觉审查发现的 5 处破坏精致度的痛点（小说阅读器首字下沉 Bug 与标题冗余、媒体卡片 2 行截断与面包屑粘连、服务信息扫描路径逗号拼接、书架/书签简陋空状态、设置表单灰底），进行系统化样式与渲染调优。

**Architecture:** 
1. 在 `components.css` 中扩展基础组件：`.empty-state`、表单控件与通用规范；
2. 在各视图 CSS（`browser.css`、`dashboard.css`、`settings.css`）实施视觉规范补强；
3. 在 `textReader.js`、`settings.js`、`bookshelf.js`、`bookmarksView.js` 补齐微小渲染逻辑，保持与 `xsscheck` 及既有 `node --test` 完全兼容。

**Tech Stack:** 原生 ES Module、CSS 自定义属性（Variables）、Vanilla DOM API、node:test、tools/xsscheck

## Global Constraints
- 无构建步骤，延续零依赖与原生 ES module（保留全部既有类名契约）；
- 所有 `innerHTML` 插入必须附带 `// XSS-SAFE:` 注释并通过 `escapeHtml()` 转义，严防 XSS；
- 每次改动后运行 `cd server/internal/web && node --test` 与 `cd tools/xsscheck && go run . ../../server/internal/web`；
- Commit 风格必须遵循 Conventional Commits。

---

### Task 1: 共享组件基础规范完善 (`components.css`)

**Files:**
- Modify: `server/internal/web/css/components.css`

**Interfaces:**
- Produces: `.empty-state`, `.empty-state__icon`, `.empty-state__title`, `.empty-state__desc`, `.empty-state__action`, `.form-group input`, `.form-group textarea`

- [ ] **Step 1: 在 `components.css` 中增加 `.empty-state` 规范与表单通用输入框规范**

```css
/* Empty State Component */
.empty-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    padding: 64px 24px;
    text-align: center;
}

.empty-state__icon {
    display: grid;
    place-items: center;
    width: 64px;
    height: 64px;
    border-radius: 50%;
    background: var(--surface-hover);
    color: var(--text-muted);
    margin-bottom: 16px;
}

.empty-state__icon svg {
    width: 32px;
    height: 32px;
    opacity: 0.7;
}

.empty-state__title {
    margin: 0 0 8px;
    font-size: 16px;
    font-weight: 600;
    color: var(--text-primary);
}

.empty-state__desc {
    margin: 0 0 20px;
    font-size: 14px;
    color: var(--text-secondary);
    max-width: 380px;
    line-height: 1.5;
}

.empty-state__action {
    display: inline-flex;
    align-items: center;
    gap: 6px;
}

/* Global Form Controls */
.form-group textarea,
.form-group input:not([type="checkbox"]):not([type="radio"]) {
    width: 100%;
    background: var(--surface-card);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    color: var(--text-primary);
    font-family: inherit;
    font-size: 13px;
    padding: 10px 12px;
    transition: border-color .15s ease, box-shadow .15s ease;
    box-sizing: border-box;
}

.form-group textarea:focus,
.form-group input:not([type="checkbox"]):not([type="radio"]):focus {
    outline: none;
    border-color: var(--accent);
    box-shadow: 0 0 0 2px var(--accent-soft);
}
```

- [ ] **Step 2: 验证单元测试不受破坏**

Run: `cd server/internal/web && node --test`
Expected: 120 tests pass

- [ ] **Step 3: Commit**

```bash
git add server/internal/web/css/components.css
git commit -m "feat(web): add empty-state and form control primitives to components.css"
```

---

### Task 2: 小说阅读器首字下沉与顶栏查重优化 (`textReader.js`)

**Files:**
- Modify: `server/internal/web/textReader.js`
- Test: `server/internal/web/textReader.test.mjs`

**Interfaces:**
- Consumes: `getBookChapter`, `getBookInfo`
- Produces: 智能 Dropcap 判定与顶栏去重标题

- [ ] **Step 1: 在 `textReader.test.mjs` 中添加针对 dropcap 过滤与标题去重的测试用例**

```javascript
test('dropcap skips metadata lines and short lines', () => {
    // metadata or short lines should not receive text-reader__p--dropcap
});

test('renderTitle deduplicates chapterTitle when equal to bookTitle', () => {
    // chapterTitle === bookTitle displays only bookTitle
});
```

- [ ] **Step 2: 在 `textReader.js` 中更新首段 dropcap 筛选与标题渲染**

在 `textReader.js` 的 `renderBlocks` 中更新 `dropCapIdx`：
```javascript
const dropCapIdx = list.findIndex(b => b && b.type === 'text' &&
    typeof b.value === 'string' && b.value.trim().length >= 25 &&
    !/^[—…\-\s"“”‘’《〈（(【]/.test(b.value.trim()) &&
    !/^(作\s*者|书\s*名|来\s*源|字\s*数|简\s*介|编\s*辑|翻\s*译|出\s*版|内\s*容\s*简\s*介)[：:]/.test(b.value.trim()));
```

在 `textReader.js` 的 `loadChapter` 中更新顶栏标题：
```javascript
const chTitle = (chapter.title || '').trim();
const bkTitle = (book.title || '').trim();
if (!chTitle || chTitle === bkTitle) {
    els.title.textContent = bkTitle || chTitle;
} else if (!bkTitle) {
    els.title.textContent = chTitle;
} else {
    els.title.textContent = `${chTitle} — ${bkTitle}`;
}
```

- [ ] **Step 3: 运行自动化测试**

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/textReader.js server/internal/web/textReader.test.mjs
git commit -m "fix(reader): filter metadata in dropcap and deduplicate header title"
```

---

### Task 3: 媒体共享库卡片 2 行截断与面包屑间距 (`browser.css`)

**Files:**
- Modify: `server/internal/web/css/views/browser.css`

**Interfaces:**
- Produces: `.card-title` 2-line clamp, `.crumb::after` 左右边距, `.scroll-fab-btn` 毛玻璃与半透明

- [ ] **Step 1: 修改 `browser.css`**

1. 将 `.card-title` 从单行截断改为 2 行截断：
```css
.card-title {
    font-size: 13px;
    font-weight: 500;
    color: var(--text-primary);
    display: -webkit-box;
    -webkit-box-orient: vertical;
    -webkit-line-clamp: 2;
    line-clamp: 2;
    overflow: hidden;
    text-overflow: ellipsis;
    line-height: 1.4;
    height: 2.8em;
    word-break: break-all;
}
```

2. 修正面包屑分隔符边距：
```css
.crumb::after {
    content: "›";
    margin: 0 8px;
    color: var(--text-muted);
}
```

3. 优化滚动置底 FAB 样式：
```css
.scroll-fab-btn {
    opacity: 0.85;
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
    transition: opacity .15s ease, transform .15s ease, background-color .15s ease;
}
.scroll-fab-btn:hover {
    opacity: 1;
}
```

- [ ] **Step 2: 运行现有测试**

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/internal/web/css/views/browser.css
git commit -m "feat(web): 2-line clamp for media card titles and breadcrumb spacing fix"
```

---

### Task 4: 仪表盘服务信息扫描路径胶囊化 (`dashboard.css`, `settings.js`)

**Files:**
- Modify: `server/internal/web/css/views/dashboard.css`
- Modify: `server/internal/web/settings.js`

**Interfaces:**
- Consumes: `getFolderPaths()`
- Produces: `.path-chip-group`, `.path-chip`

- [ ] **Step 1: 在 `dashboard.css` 中增加胶囊标签样式**

```css
/* Path Chips for Service Information */
.path-chip-group {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
    margin-top: 2px;
}

.path-chip {
    display: inline-flex;
    align-items: center;
    font-family: var(--font-mono, monospace);
    font-size: 11px;
    padding: 2px 8px;
    border-radius: var(--radius-sm);
    background: var(--surface-hover);
    border: 1px solid var(--border-subtle);
    color: var(--text-secondary);
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
}
```

- [ ] **Step 2: 在 `settings.js` 中将路径渲染为安全胶囊**

```javascript
const paths = getFolderPaths();
if (paths.length > 0) {
    // XSS-SAFE: paths escaped via escapeHtml()
    elements.infoScanRoots.innerHTML = `<div class="path-chip-group">${paths.map(p => `<span class="path-chip" title="${escapeHtml(p)}">${escapeHtml(p)}</span>`).join('')}</div>`;
} else {
    elements.infoScanRoots.textContent = '全盘自动检测';
}
```

- [ ] **Step 3: 运行 XSS 扫描与测试**

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: OK: no unescaped innerHTML variables

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/css/views/dashboard.css server/internal/web/settings.js
git commit -m "feat(web): render dashboard scan paths as path chips"
```

---

### Task 5: 书架与书签管理空状态升级 (`bookshelf.js`, `bookmarksView.js`)

**Files:**
- Modify: `server/internal/web/bookshelf.js`
- Modify: `server/internal/web/bookmarksView.js`

**Interfaces:**
- Produces: 优雅居中 SVG + 标题 + 说明 + 引导操作按钮

- [ ] **Step 1: 在 `bookshelf.js` 中重塑空状态**

```javascript
if (list.length === 0) {
    // XSS-SAFE: pure literal markup
    container.innerHTML = `
        <div class="empty-state">
            <div class="empty-state__icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z"/></svg>
            </div>
            <h3 class="empty-state__title">暂无阅读历史</h3>
            <p class="empty-state__desc">在媒体共享库中打开小说或书籍后，将自动在此记录阅读进度。</p>
            <a href="#/browser" class="btn btn-primary empty-state__action">前往媒体库</a>
        </div>
    `;
    return;
}
```

- [ ] **Step 2: 在 `bookmarksView.js` 中重塑空状态**

```javascript
if (bookmarks.length === 0) {
    // XSS-SAFE: pure literal markup
    listEl.innerHTML = `
        <div class="empty-state bookmarks-empty-state">
            <div class="empty-state__icon">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"><path d="m19 21-7-5-7 5V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2z"/></svg>
            </div>
            <h3 class="empty-state__title">暂无书签记录</h3>
            <p class="empty-state__desc">在媒体共享库中阅读小说时，在段落右侧悬浮并点击 “+” 即可添加书签。</p>
            <a href="#/browser" class="btn btn-secondary empty-state__action">前往媒体库浏览</a>
        </div>
    `;
    return;
}
```

- [ ] **Step 3: 运行 XSS 检查与测试**

Run: `cd tools/xsscheck && go run . ../../server/internal/web`
Expected: OK: no unescaped innerHTML variables

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add server/internal/web/bookshelf.js server/internal/web/bookmarksView.js
git commit -m "feat(web): modernize empty states for bookshelf and bookmarks"
```

---

### Task 6: 系统设置表单输入框精致化 (`settings.css`)

**Files:**
- Modify: `server/internal/web/css/views/settings.css`

**Interfaces:**
- Produces: 现代中性灰阶背景、细边框与高质感只读/输入状态

- [ ] **Step 1: 修改 `settings.css`**

```css
.settings-static {
    background-color: var(--surface-hover);
    border: 1px solid var(--border-subtle);
    border-radius: var(--radius-md);
    padding: 8px 12px;
    font-family: var(--font-mono, monospace);
    font-size: 12px;
    color: var(--text-secondary);
}

.settings-card textarea {
    min-height: 120px;
    font-family: var(--font-mono, monospace);
    font-size: 13px;
    line-height: 1.5;
}
```

- [ ] **Step 2: 运行测试**

Run: `cd server/internal/web && node --test`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add server/internal/web/css/views/settings.css
git commit -m "style(settings): harmonize form inputs and static values with design tokens"
```

---

### Task 7: 真实视觉审查与效果验证

**Files:**
- Test script: `scratch/cdp_capture_all.mjs`

- [ ] **Step 1: 运行无头 Edge CDP 重新截取各模块优化后的效果图**
- [ ] **Step 2: 使用 `view_file` 视觉审查比对结果**
- [ ] **Step 3: 确认阅读器、卡片、仪表盘、空状态均达到高质感预期**

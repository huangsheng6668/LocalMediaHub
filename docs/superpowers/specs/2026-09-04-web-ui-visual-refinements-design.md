# Web UI 视觉体验系统化精细调优设计（2026-09-04）

## 背景与目标

通过无头浏览器与视觉模型对当前 LocalMediaHub Web 端（`http://localhost:8000`）在桌面端（1440×900）、移动端（390×844）和深色模式（Night 主题）下的真实运行画面进行全量视觉视检，发现若干破坏精致度与体验细节的视觉缺陷。

本设计目标是针对这 5 个关键视觉痛点进行系统化调优：
1. **小说阅读器**：修复首字下沉误伤元数据（“作”字巨型化 Bug），消除顶栏书名与章节名重复冗余。
2. **媒体共享库**：卡片标题升级为标准 2 行截断（2-Line Clamping），扩大长文件名可读性；修正面包屑分隔符粘连与置底悬浮按钮遮挡。
3. **仪表盘**：服务信息扫描路径升级为等宽标签胶囊（Path Chips）；优化统计卡视觉留白与最近媒体对齐。
4. **系统设置**：重塑表单输入控件（`<input>` / `<textarea>`），对齐现代中性灰阶规范与 focus-visible 光圈。
5. **全局空状态**：建立通用 `.empty-state` 规范（含轻量内联 SVG、主副标题与 CTA 跳转按钮），重构书架与书签管理空状态。

---

## 模块设计细则

### 1. 小说阅读器（Text Reader）

#### 1.1 首字下沉（Dropcap）误伤元数据修复
* **问题分析**：`css/views/reader.css` 中配置了 `.text-reader__p--dropcap::first-letter`。当小说首段包含 `作者：xxx`、`书名：xxx`、`来源：xxx` 或简短声明时，首字“作”会被放大占据 3 行高度，产生滑稽的版面破损。
* **修改方案**：
  * 在 `textReader.js` 生成段落 DOM 时，增加首段内容智能校验：
    ```javascript
    const isMetaOrShort = /^(作者|书名|来源|字数|简介|【|（|\(|\[)/.test(text.trim()) || text.trim().length < 30;
    if (idx === 0 && !isMetaOrShort) {
        p.classList.add('text-reader__p--dropcap');
    }
    ```
  * 仅当首段为真正叙述性正文且长度充足时赋予 dropcap，元数据段落渲染为常规自然段。

#### 1.2 顶栏标题查重与精简
* **问题分析**：单章小说或章节名同名时，顶栏显示 `交换母亲.txt — 交换母亲.txt`。
* **修改方案**：
  * 在 `textReader.js` 的 `renderTitle()` 中，对书名（剥离扩展名）与章节名进行 trim 比较：
    * 若章节名为空、或章节名等于书名，顶栏仅显示单份书名；
    * 若章节名不同，显示 `书名 — 章节名`。

---

### 2. 媒体共享库（Media Browser）

#### 2.1 媒体卡片标题 2 行截断（2-Line Clamping）
* **问题分析**：当前 `.media-card__title` 强制单行省略，仅能显示 12~14 个汉字，番号与剧集长文件名被腰斩。
* **修改方案**：
  * 在 `css/views/browser.css` 中重写 `.media-card__title`：
    ```css
    .media-card__title {
        display: -webkit-box;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
        line-clamp: 2;
        overflow: hidden;
        text-overflow: ellipsis;
        line-height: 1.35;
        height: 2.7em;
        word-break: break-all;
    }
    ```
  * 固定高度 `2.7em` 保证无论标题是 1 行还是 2 行，底部格式后缀与文件大小徽章均在同一水平线上，网格整齐统一。

#### 2.2 面包屑导航间距与排版
* **问题分析**：面包屑中路径显示为 `根目录 >H: >IDM_Download >Video`，字符粘连无间距。
* **修改方案**：
  * 在 `css/views/browser.css` 完善面包屑分隔符样式：
    ```css
    .breadcrumb-separator {
        margin: 0 8px;
        color: var(--text-muted);
        opacity: 0.6;
        user-select: none;
    }
    ```

#### 2.3 滚动置底按钮（Scroll FAB）微调
* **修改方案**：
  * 滚动按钮加入半透明背景模糊 `backdrop-filter: blur(8px)`，默认不透明度设为 `0.85`，hover 恢复 `1.0`；在触底时降低对比度，减少对卡片的视觉干扰。

---

### 3. 仪表盘（Dashboard）

#### 3.1 扫描路径胶囊化（Path Chips）
* **问题分析**：服务信息卡片中的 `系统扫描路径` 将多个路径用逗号串联直接折行，排版凌乱。
* **修改方案**：
  * 在 `dashboard.js` 中将扫描路径解析为数组，使用 `.path-chip` 标签组进行结构化渲染：
    ```html
    <!-- XSS-SAFE: path is escaped via escapeHtml() -->
    <div class="path-chip-group">
        ${paths.map(p => `<span class="path-chip" title="${escapeHtml(p)}">${escapeHtml(p)}</span>`).join('')}
    </div>
    ```
  * 在 `css/views/dashboard.css` 中定义 `.path-chip`：
    * `font-family: var(--font-mono, monospace);`
    * `font-size: 11px; padding: 2px 8px; border-radius: var(--radius-sm);`
    * `background: var(--surface-hover); border: 1px solid var(--border-subtle); color: var(--text-secondary);`
    * `max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;`

#### 3.2 统计卡视觉留白与最近媒体
* **修改方案**：
  * 统计卡微调内边距与图标比例，优化大屏下的留白感；
  * 最近媒体项单行文本对齐与文件大小对齐线规范化。

---

### 4. 系统设置（Settings）

#### 4.1 表单控件现代化重塑
* **问题分析**：`css/views/settings.css` 中媒体库目录 `<textarea>` 与后缀 `<input>` 使用了沉重的原生灰底。
* **修改方案**：
  * 统一表单输入框样式至 `components.css`：
    ```css
    .form-input, .form-textarea {
        width: 100%;
        background: var(--surface-card);
        border: 1px solid var(--border-subtle);
        border-radius: var(--radius-md);
        color: var(--text-primary);
        font-family: var(--font-mono, monospace);
        font-size: 13px;
        padding: 10px 12px;
        transition: border-color .15s ease, box-shadow .15s ease;
    }
    .form-input:focus, .form-textarea:focus {
        outline: none;
        border-color: var(--accent);
        box-shadow: 0 0 0 2px var(--accent-soft);
    }
    ```

---

### 5. 全局空状态（Empty State）系统

#### 5.1 通用 `.empty-state` 组件规范
* **在 `css/components.css` 中定义**：
  ```css
  .empty-state {
      display: flex;
      flex-direction: column;
      align-items: center;
      justify-content: center;
      padding: 64px 24px;
      text-align: center;
  }
  .empty-state__icon {
      width: 56px;
      height: 56px;
      color: var(--text-muted);
      opacity: 0.5;
      margin-bottom: 16px;
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
  }
  ```

#### 5.2 接入书架与书签管理
* **书架（`bookshelf.js`）**：
  * 当 `history.length === 0` 时，渲染内联图书 SVG 图标 + 标题“暂无阅读历史” + 说明“在媒体库中打开书籍后将自动在此记录” + [前往媒体共享库] 按钮（点击跳转至 `#/browser`）。
* **书签管理（`bookmarksView.js`）**：
  * 当无书签时，渲染内联书签 SVG 图标 + 标题“暂无书签记录” + 说明“阅读小说时在段落右侧点击加号即可添加书签” + [去阅读书籍] 按钮。

---

## 验证与测试方案

1. **静态扫描与安全检查**：
   - 运行 `tools/xsscheck`：`cd tools/xsscheck && go run . ../../server/internal/web`，确保新增的 innerHTML 模板包含 `// XSS-SAFE:` 注释与 `escapeHtml()` 调用。
2. **自动化单元测试**：
   - 运行前端单元测试：`cd server/internal/web && node --test`，确保所有既有 DOM 测试用例均通过。
3. **视觉截屏比对验证**：
   - 运行无头 Edge CDP 截图脚本重新拍摄 5 个视图：
     - 验证小说阅读器首段首字不再变形，顶栏标题单显；
     - 验证媒体卡片标题呈现清晰的 2 行截断，水平对齐规范；
     - 验证服务信息中的扫描路径以整洁胶囊呈现；
     - 验证书架与书签显示居中插画与引导按钮；
     - 验证设置表单与整体卡片风格协调融合。

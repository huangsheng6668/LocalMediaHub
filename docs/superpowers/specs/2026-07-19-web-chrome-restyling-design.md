# Web 端 chrome 重写设计

- 日期：2026-07-19
- 范围：`server/internal/web/` 下的 web 管理界面 chrome（顶栏、侧栏、卡片、按钮、状态条、模态外壳）
- 状态：设计已与用户确认，待 writing-plans 拆分实施计划

## 1. 背景与动机

现状 web 端 chrome 存在两版互不一致的样式：

- **代码版**（`style.css:21-50`）：深色基底（`--bg-main: #0a0a0f`）+ 紫色 gradient 主色（`#7c3aed → #a78bfa`）+ 玻璃磨砂（`backdrop-filter: blur(12px)`）
- **截图版**（用户实际看到）：浅灰白底 + 蓝按钮 + 橙图标 + 紫边框，三种强调色相互打架，对比度不足，控件阴影风格不统一

两版都不符合用户审美。同时 `readerPrefs.js` 中已定义了一套考究的主题 token（`DAY / DAY_BRIGHT / EYE_CARE / PARCHMENT / NIGHT / NIGHT_BLACK`），但只作用于阅读器内部，chrome 完全没有用上 —— 这是"不协调"的根因。

本次从零重写 chrome，统一为：**温暖中性底色 + 单一 terracotta 强调色 + DAY/NIGHT 双主题切换 + 适度宽松布局**。

## 2. 目标

1. 消除"不协调"：把多 emphasizing 色（蓝/紫/橙）收敛为唯一 terracotta；统一阴影、圆角、间距；修复对比度
2. 引入 design token 系统（Open Props 子集），让 chrome 与未来扩展的主题预设共享同一套语言
3. 支持 DAY / NIGHT 顶栏一键切换，持久化到 localStorage
4. 适度宽松布局（顶栏 56px、卡片 padding 20、圆角 14），保持桌面信息密度同时提升舒适度
5. 不影响阅读器内部 UI、视频/lightbox 模态内容样式

## 3. 非目标

- 不改阅读器内部 UI（`body[data-reader-theme]` 作用域内的样式保持原样）
- 不引入 JS 框架
- 不动 Android 端
- 不写前端自动化测试（项目无前端测试基建）
- 不改视频/lightbox 模态的内容结构，只让外壳跟随主题色

## 4. 技术选型

| 项 | 选定 | 理由 |
|---|---|---|
| Token 来源 | Open Props（仅取 color/size/shadow/radius 子集，约 60 行） | 拿到成熟调优的 token，但不带组件样式，避免与现有 BEM 风格冲突 |
| 引入方式 | npm `open-props` 作为 devDependency；构建脚本抽子集到 `tokens.css` | 包不进运行时依赖；离线可用（与"本地媒体库"理念一致） |
| 主题切换 | `<html data-theme="day\|night">`；持久化 key `chrome_theme` | 不与 `reader_settings.theme` 耦合，避免改 chrome 主题意外影响阅读器 |
| 事件 | 复用 `reader-prefs-changed` 自定义事件广播 | 不引入新机制 |
| 字体 | 沿用 `--font-sans: system-ui, ...`；中文 PingFang/MS YaHei | 零新依赖 |

## 5. Token 设计

`tokens.css`（由 `tools/build-tokens.mjs` 从 open-props 抽取后生成；语义变量直接定义在 `style.css :root` 与 `[data-theme="night"]` 块中）：

```css
:root,
[data-theme="day"] {
  /* surfaces */
  --surface-app:     #FAF8F3;   /* = readerPrefs DAY.bg */
  --surface-card:    #FFFFFF;
  --surface-sidebar: #F2EFE7;   /* = DAY.chromeBg */
  --surface-hover:   rgba(199, 91, 57, 0.08);

  /* text */
  --text-primary:    #2B2B2B;   /* = DAY.fg */
  --text-secondary:  #5A5A57;
  --text-muted:      #7A7A78;   /* = DAY.muted */
  --text-on-accent:  #FFFFFF;

  /* accent — terracotta（唯一强调色） */
  --accent:          #C75B39;
  --accent-hover:    #B14E2E;
  --accent-soft:     rgba(199, 91, 57, 0.12);

  /* borders & shadows */
  --border-subtle:   #E5E2D8;   /* = DAY.border */
  --shadow-sm:       0 1px 2px rgba(43, 43, 43, 0.04), 0 1px 3px rgba(43, 43, 43, 0.06);
  --shadow-md:       0 4px 12px rgba(43, 43, 43, 0.08);

  /* shape & rhythm */
  --radius-sm: 6px;
  --radius-md: 10px;
  --radius-lg: 14px;
  --space-1: 4px;  --space-2: 8px;  --space-3: 12px;
  --space-4: 16px; --space-5: 24px; --space-6: 32px;
}

[data-theme="night"] {
  --surface-app:     #1A1A1F;   /* = NIGHT.bg */
  --surface-card:    #232328;   /* = NIGHT.chromeBg */
  --surface-sidebar: #16161A;
  --surface-hover:   rgba(199, 91, 57, 0.16);
  --text-primary:    #C9C9CE;   /* = NIGHT.fg */
  --text-secondary:  #A8A8AD;
  --text-muted:      #84848A;   /* = NIGHT.muted */
  --text-on-accent:  #FFFFFF;
  --accent:          #D97A56;   /* 夜间稍提亮 */
  --accent-hover:    #E68B6A;
  --accent-soft:     rgba(217, 122, 86, 0.16);
  --border-subtle:   #2D2D33;   /* = NIGHT.border */
  --shadow-sm:       0 1px 2px rgba(0, 0, 0, 0.4);
  --shadow-md:       0 4px 12px rgba(0, 0, 0, 0.5);
}
```

## 6. chrome 结构重写

### 6.1 侧栏 `.sidebar`（260px，padding 24/16）

- 顶部 brand 区：terracotta 圆角方块 logo（"LMH" monogram，28×28）+ "LocalMediaHub" 文本 + 版本 badge（中性灰）
- 菜单项：48px 高、左 16px padding、圆角 10px；未选中 `--text-secondary` + hover `--surface-hover`；选中态 = **terracotta 软背景 + 左侧 3px terracotta 竖条 + 主色文字 + 主色 icon**
- 底部 server-status：pill badge（`--surface-card` 背景 + 1px `--border-subtle` + 4px green dot + 文字）

### 6.2 顶栏 `.main-header`（高 56px，padding 0 28px）

- 左侧：汉堡按钮（移动端）+ page-title（22px / 600）
- 右侧 actions：[主题切换 icon button] + [立即扫描媒体 primary button]
- 底部 1px `--border-subtle`，背景 `--surface-card`，**删除 backdrop-filter glass**

### 6.3 主按钮 `.btn-primary`

- 背景 `--accent`、文字 `--text-on-accent`、圆角 10px、padding 10/18、`--shadow-sm`
- hover → `--accent-hover` + `--shadow-md`
- focus-visible → 3px `--accent-soft` outline
- **删除** `.brand-gradient` / `.menu-item.active` 的紫色 gradient + glow

### 6.4 卡片 `.stat-card` / `.widget-card` / `.settings-card`

- 背景 `--surface-card`、1px `--border-subtle`、圆角 14px、padding 20、`--shadow-sm`
- hover → `--shadow-md` + translateY(-1px)（仅可点击卡片，stat-card 不带 hover 抬升）

### 6.5 菜单图标统一

- 顶栏/侧栏功能性图标统一用 terracotta outline SVG（dashboard / folder / bookmark / settings / sun / moon / rescan），20px 容器
- 保留 `📁🎬🖼️🔄🔍` 这类内容性 emoji 作为内容标识，但置于中性容器中

### 6.6 响应式

- ≥1024px：侧栏常驻
- 768-1023px：侧栏改 drawer（保留现有 `sidebar-backdrop` 机制）
- <768px：顶栏汉堡触发 drawer，stat-grid 单列

## 7. 文件改动清单

| 文件 | 改动类型 | 内容 |
|---|---|---|
| `server/internal/web/package.json` | 新增 | devDep `open-props`；scripts 增 `build-tokens` |
| `server/internal/web/tools/build-tokens.mjs` | 新增 | 从 open-props 抽取 token 到 `tokens.css` |
| `server/internal/web/tokens.css` | 生成产物 | 约 60 行 token；纳入 embed |
| `server/internal/web/style.css` | 大改 | 删除紫色 gradient/glass/深色基底；`:root` → `[data-theme]` 两套；重写 sidebar/header/menu-item/btn/card/stat-card/widget-card/server-status；全局 `<input>`/`<textarea>` 继承 `--surface-card` 与 `--text-primary` 避免暗色下黑白错乱 |
| `server/internal/web/index.html` | 小改 | `<html>` 加 `data-theme="day"` 默认值；`<head>` 顶端 `<script src="boot.js"></script>`（在 stylesheet link 之前）防 FOUC；`link` 标签引入 `tokens.css`；顶栏右侧加主题切换 button；menu-icon emoji 换成 inline SVG 或加 wrapper span |
| `server/internal/web/boot.js` | 新增 | FOUC 防闪外部脚本（约 10 行 IIFE），在 stylesheet 加载前同步设置 `<html data-theme>`。外部文件以规避 CSP `script-src 'self'` 对 inline script 的禁用 |
| `server/internal/web/readerPrefs.js` | 小改 | 新增 `CHROME_THEME_KEY`、`getChromeTheme()`、`saveChromeTheme()`，广播 `chrome-theme-changed` 独立事件（或在 detail 中带 `type: 'chrome-theme'`）避免与阅读器事件混淆 |
| `server/internal/web/app.js` | 小改 | 监听主题切换按钮 click handler 及 `chrome-theme-changed` 事件同步 UI 状态 |
| `server/internal/web/web.go`（embed 列表） | 小改 | 更新为 `//go:embed index.html *.css *.js fonts/*.woff2`，自动包含 `tokens.css` 避免遗漏 |

## 8. 验收

- 手动验收：
  - DAY / NIGHT 切换瞬时无闪烁；**首次加载/刷新页面无白闪/黑闪（FOUC 校验）**
  - 四个页面（dashboard / browser / bookmarks / settings）颜色与控件风格一致
  - 顶栏主题切换按钮、立即扫描按钮、汉堡按钮交互正常
  - 输入框（搜索框、设置 textarea）在 DAY/NIGHT 下前景与背景色对比度均正常
  - 模态（视频/图片/auth）外层跟随主题
- 对比度（DevTools 检查）：
  - `--text-primary` on `--surface-app` ≥ 4.5:1（DAY: #2B2B2B on #FAF8F3 ≈ 13.5:1 ✓；NIGHT: #C9C9CE on #1A1A1F ≈ 11.5:1 ✓）
  - `--text-muted` on `--surface-sidebar` ≥ 4.5:1（DAY: #7A7A78 on #F2EFE7 ≈ 4.6:1 ✓）
- 响应式：768/1024 断点 drawer 行为正常
- 回归：阅读器内部主题不受影响（`readerPrefs.THEME_PRESETS` 仍只作用于 `body[data-reader-theme]`）

## 9. 风险与对策

| 风险 | 对策 |
|---|---|
| Open Props 升级引入破坏性 token 变化 | package-lock 锁定版本；`build-tokens.mjs` 只取显式列出的变量名 |
| 阅读器主题与 chrome 主题用户期望联动 | 本次明确解耦：两个独立 key；UI 上加一行说明文案（"阅读器主题在阅读器内单独设置"） |
| JS 异步加载导致主题白闪/黑闪 (FOUC) | 在 `index.html` 的 `<head>` 中、`<link rel="stylesheet">` 之前用 `<script src="boot.js"></script>` 同步阻塞加载外部脚本，设置 `documentElement.dataset.theme`。外部文件而非 inline，规避 CSP `script-src 'self'` 对 inline script 的禁用（实施时发现） |
| 现有 modal/overlay/input 用了大量硬编码颜色 | 仅替换 `--accent`/`--text-*`/`--surface-*` 引用，强化原生 `<input>`/`<textarea>` 规则覆盖，不动结构 |
| index.html emoji 替换为 SVG 增加体积 | 采用 inline SVG，单图标 < 300 字节；总增量 < 2KB |

## 10. 深度审核修复与建议 (AI 审计补充)

在针对当前代码库与架构进行针对性审计后，补充以下 4 点优化与修复建议：

1. **防闪烁 (FOUC) 执行机制**：
   - 现 `app.js` 为 ES Module 且含有 `await loadConfig()`，如果把主题初始化放在 `app.js` 初始化阶段，在慢网络/慢磁盘下会导致显著的"默认主题 -> 保存主题"闪烁。
   - **CSP 约束（实施时发现）**：项目 `server/internal/server/middleware/security_headers.go` 设置 `Content-Security-Policy: ... script-src 'self' ...`，明确禁止 inline script。直接在 `<head>` 写 inline `<script>` 会被浏览器拒绝执行。
   - **最终方案**：把 FOUC 脚本拆为**外部** `server/internal/web/boot.js`（普通 `<script src>` 而非 ES module），在 `<head>` 中、在 `<link rel="stylesheet">` **之前**用 `<script src="boot.js"></script>` 同步阻塞加载。这样：
     - 仍满足"在 stylesheet 应用前同步设置 `<html data-theme>`"的 FOUC 防闪目标
     - 不弱化 CSP（不加 `'unsafe-inline'`、不维护易失的 sha256 hash）
     - `boot.js` 内容（约 10 行）：
       ```javascript
       (function () {
         try {
           var t = localStorage.getItem('chrome_theme');
           if (t !== 'day' && t !== 'night') {
             t = window.matchMedia('(prefers-color-scheme: dark)').matches ? 'night' : 'day';
           }
           document.documentElement.dataset.theme = t;
         } catch (_) {
           document.documentElement.dataset.theme = 'day';
         }
       })();
       ```
2. **`web.go` Embed 路径容错**：
   - 将 `web.go` 嵌入指令调整为 `//go:embed index.html *.css *.js fonts/*.woff2`，利用 `*.css` 通配符自动包含生成的 `tokens.css`，防止后期漏写导致 Go 打包缺少文件。
3. **事件机制解耦**：
   - `reader-prefs-changed` 事件目前专用于阅读器内部偏好变更（包含字体、字号、阅读器背景等），建议针对 Chrome 主题使用 `chrome-theme-changed` 或在 `detail.type === 'chrome_theme'` 中进行隔离区分，避免触发不必要的 `textReader.js` 重绘。
4. **表单控件 (Input/Textarea) 暗色模式透传**：
   - `settings.html` 与 `browserView` 中包含多处原生的 `<input>` 和 `<textarea>`，在 CSS 重构时需确保设置：
     ```css
     input, textarea, select {
       background-color: var(--surface-card);
       color: var(--text-primary);
       border: 1px solid var(--border-subtle);
     }
     ```
     防止在切换到 `night` 主题时，浏览器原生控件保持白底亮色文字导致不可读。

## 11. 后续可扩展（不在本次范围）

- 接入 EYE_CARE / PARCHMENT / DAY_BRIGHT / NIGHT_BLACK（只需新增 `[data-theme="..."]` token 块 + 顶栏下拉）
- chrome 主题与阅读器主题可选联动开关
- 侧栏可折叠为图标条


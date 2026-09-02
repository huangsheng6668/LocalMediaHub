# Web UI 架构重写设计 — 现代中性风（2026-09-02）

## 背景与目标

Web 管理界面（`server/internal/web/`）当前为暖纸色阅读向设计，全部样式集中在单文件
`style.css`（约 2985 行 / 76KB）。用户确认的目标：

1. **视觉焕新**：切换为现代中性风（Linear/Notion 系：中性灰阶 + 单品牌 accent）。
2. **架构重写**：拆分 style.css 为分层多文件，组件样式全部按新设计语言重写。
3. **痛点视图重点升级**：仪表盘、书架、阅读器 chrome。
4. **主题**：保留全部 7 套主题，全部按新风格重调。
5. **移动端**：PC 为主，仅做容错（保留现有 hamburger 抽屉机制，修明显破版即可）。

## 非目标

- 不改业务逻辑、路由、状态管理、存储格式（localStorage key 全部不变）。
- 不做移动端专属布局重构（无底部导航栏等）。
- 不引入构建步骤、CSS 框架或 npm 依赖（延续零构建 + 原生 ES module）。
- 不改阅读面板（阅读区）主题机制——阅读区主题与 app chrome 主题继续分离。
- 不改 server 路由与鉴权。

## 设计语言

### 色彩

中性灰阶表面 + 每主题一个 accent。**Token 变量名契约不变**（`--surface-app` /
`--surface-card` / `--surface-sidebar` / `--surface-hover` / `--text-primary` /
`--text-secondary` / `--text-muted` / `--text-on-accent` / `--accent` /
`--accent-hover` / `--accent-soft` / `--accent-text` / `--border-soft` /
`--border-subtle` / `--shadow-sm` / `--shadow-md` / `--radius-sm|md|lg` /
`--space-1..6` / `--error` / `--secondary` / `--font-sans` 等），重写的是取值。
旧别名变量（`--bg-main` / `--primary` / `--primary-light` / `--text-main` /
`--text-white` / `--border-color` / `--transition-smooth` / `--transition-quick`）
在组件重写过程中迁移到规范名后**删除**。

7 套调色板目标值（实施时可在对比度校验后微调，明度/彩度关系不变）：

| 主题 | app 底 | 卡片 | 侧栏 | 主文字 | 次文字 | accent | hover | 边框 |
|---|---|---|---|---|---|---|---|---|
| day | `#FAFAFA` | `#FFFFFF` | `#F4F4F5` | `#17181C` | `#52525B` | `#5E6AD2` | `#4E59C9` | `#E4E4E7` |
| day_bright | `#FFFFFF` | `#FFFFFF` | `#F8FAFC` | `#0F172A` | `#475569` | `#2563EB` | `#1D4ED8` | `#E2E8F0` |
| eye_care | `#F7F1E3` | `#FCF8EE` | `#EFE7D2` | `#3F3A2F` | `#6B6353` | `#A0713C` | `#8A5F30` | `#E3D9C2` |
| eye_care_green | `#EAF0E6` | `#F4F8F1` | `#DFE8DA` | `#253326` | `#4A5B4C` | `#4F7D5D` | `#40684C` | `#D3DFD0` |
| parchment | `#F3EBD9` | `#FAF4E6` | `#EBE1C8` | `#443C2C` | `#6E6450` | `#9C6B2F` | `#83571F` | `#E0D4B8` |
| night | `#141517` | `#1D1F23` | `#101113` | `#E8E9ED` | `#A6A8B0` | `#7B87E8` | `#8C97EE` | `#2A2C33` |
| night_black | `#000000` | `#101114` | `#0A0B0D` | `#E8E9ED` | `#A6A8B0` | `#7B87E8` | `#8C97EE` | `#26282E` |

辅助色：`--error: #DC2626`（夜间 `#F87171`）、`--secondary: #16A34A`（夜间
`#4ADE80`）。文字三级 + `--text-on-accent`。深色主题层次以边框为主、阴影克制。

### 形状 / 排版 / 交互 / 图标

- 圆角三档：`--radius-sm: 6px` / `--radius-md: 10px` / `--radius-lg: 14px`。
- 阴影三档低透明度（浅色 `rgba(23,24,28,…)`，深色用纯黑基底）。
- 标题 600 字重；统计数字 `font-variant-numeric: tabular-nums`；间距 4px 网格。
- hover 过渡 150ms ease；focus 统一 2px accent ring（`:focus-visible`）；
  新增动效一律尊重 `prefers-reduced-motion`。
- 界面 emoji 图标全部替换为内联 SVG（stroke `currentColor`，1.75px，
  18/20px 网格）：静态页面（index.html）与 JS 模板（dashboard.js /
  bookshelf.js / browserView.js 等）同步替换。

## 文件架构

```
server/internal/web/
  index.html          — <link> 顺序: base → themes → layout → components → views/*
  css/
    base.css          — @font-face、reset、排版、滚动条、focus ring、utility
    themes.css        — 7 套 [data-theme] 调色板 + body[data-reader-theme] 覆盖块
    layout.css        — sidebar / main-header / view-container / 响应式断点
    components.css    — btn / card / stat-card / input / select / modal /
                        toast / badge / empty-state / dropdown 等
    views/
      dashboard.css   browser.css   bookshelf.css  bookmarks.css
      settings.css    reader.css    video.css      lightbox.css
```

- `web.go` embed 改为 `//go:embed index.html css/*.css css/views/*.css *.js fonts/*.woff2`。
  静态服务走 `http.FileServer(http.FS(Assets))`，子目录自动可用，server 代码零改动。
- **删除** `tokens.css` 与 `tools/build-tokens.mjs`：`--size-*` / `--radius-drawn-*` /
  `--shadow-1..6` 等变量在 style.css 与全部 JS 中零引用，属死代码；index.html 移除其 `<link>`。
- **铁律：类名契约不变**。JS `querySelector` / `classList` 与全部 `node --test`
  依赖的类选择器一律保留；重写的是样式声明与文件组织。新增结构（书架卡书封区）
  走增量 DOM 改动并保留既有类名。

### style.css 拆分映射（逻辑对应）

| 现内容（style.css） | 去向 |
|---|---|
| `@font-face`（LXGW WenKai / Noto Serif SC） | `base.css` |
| `:root, [data-theme=…]` 7 套调色板 | `themes.css` |
| body / 排版 / 滚动条 / focus / 通用 utility | `base.css` |
| `.sidebar*` / `.main-header` / `.view-container` / 响应式 | `layout.css` |
| `.btn*` / `.widget-card` / `.stat-card` / `.modal*` / `.toast*` 等 | `components.css` |
| `.text-reader*` 全部（含 TOC / 书签 / 设置 / 进度 / 翻页动画 / 沉浸模式） | `views/reader.css` |
| `body[data-reader-theme]` 变量覆盖块 | `themes.css` |
| 浏览页 / 书架 / 书签 / 设置 / 视频 / 灯箱各段 | 对应 `views/*.css` |
| CSP-safe 替换段（原 inline style 的类） | 按归属拆入对应文件 |

## 主题系统（机制不动）

- `reader_settings.theme` → `applyGlobalAppTheme()` → `<html data-theme>`，
  `AUTO` 跟随系统；`boot.js` 防 FOUC——**全部保持现状**。
- 工作量在 `themes.css`：重写 7 套调色板取值 + `body[data-reader-theme]`
  覆盖块迁入（阅读区背景/文字仍由阅读主题独立控制）。
- 设置页主题 swatch 色值（`reader-settings__theme-swatch[data-theme=…]` 的
  硬编码 hex）与新调色板同步更新；swatch 用 CSS 类实现，守 CSP。

## 痛点视图升级

### 仪表盘

- 统计卡：SVG 图标容器 + 大数字（tabular-nums）+ 标签层级重排。
- 「最近打开的媒体」：行卡加视频缩略图（复用 `GET /api/v1/thumbnail?path=`，
  `loading="lazy"`，加载失败回退图标），文件名 + 大小排版层级化。
- 「服务信息」卡与最近媒体卡宽度配比平衡；书架嵌入区跟随新书架卡样式。

### 书架

- 卡片书封化：顶部书封区（按书名 hash 确定性选一组 CSS 渐变类——**渐变必须
  用类，禁 inline style**——叠加书名首字/短标题排版），底部书名 + 元信息行。
  元信息行显示「第 N 章 · x 天前」（localStorage 只有 `chapterIndex` 与
  `lastReadAt`，无总章数，故不画百分比进度条）；dashboard 嵌入区复用同一个
  `renderCard`，两处样式天然一致。
- hover 浮起（translateY + shadow）；空态文案样式统一 `empty-state`。

### 阅读器 chrome

- TOC 抽屉 / 书签面板 / 设置 dialog / 自动滚动面板 / 顶部进度条统一到新
  token 与圆角/阴影/焦点态；翻页动画类名与关键帧**原样保留**（测试依赖行为）。
- 阅读区（正文）背景/文字仍由阅读主题控制，不随本次改动变化。

### 其余视图

浏览页 / 书签管理 / 设置 / 视频播放器 / 灯箱跟随新 token 打磨（控件、间距、
焦点态、空态），无结构性改动。移动端仅校准现有断点下的明显破版。

## JS 改动清单

| 文件 | 改动 |
|---|---|
| `index.html` | emoji→SVG、新增 `<link>`、移除 tokens.css link、书架卡容器结构微调 |
| `dashboard.js` | 统计图标 / 最近媒体行模板（缩略图 + SVG）、保持 `// XSS-SAFE:` 纪律 |
| `bookshelf.js` | `renderCard` 书封结构（类名新增 `bookshelf-card__cover` 等，渐变 class 由书名 hash 决定） |
| `browserView.js` / `bookmarksView.js` 等 | 模板内 emoji→SVG |
| 业务逻辑 / 路由 / readerPrefs / 存储 | **零改动** |

## CSP 与安全合规

- 无 inline `<script>`、无 inline `style="..."`（渐变、缩略图占位背景全部走 CSS 类；
  动态数值样式继续走 CSSOM `el.style.prop =`）。
- 所有 `innerHTML` 使用保持 `// XSS-SAFE:` 注释或 `escapeHtml()`，`tools/xsscheck`
  必须通过。
- 缩略图 URL 继续走既有 `apiRequest`/鉴权约定，不引入新端点。

## 测试与验证

1. `cd server/internal/web && node --test` —— 现有测试不读 CSS 文件内容，
   类名契约不变即应全绿；书架卡新增类名不触碰既有断言。
2. `cd tools/xsscheck && go run . ../../server/internal/web` —— 必须通过。
3. `cd server && go build ./...` —— 验证新 embed 指令与资源路径。
4. 视觉验证：`--headless` 起 server，浏览器工具逐视图截图（仪表盘 / 浏览 /
   书架 / 阅读器 / 设置 / 视频 / 灯箱），day、night、night_black 三主题必查，
   其余主题抽验；检查对比度（正文 ≥ 4.5:1）与 focus 可见性。
5. Android / Rust 子系统不受影响，无需跑其测试。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 2985 行 CSS 拆分漏段/错序导致样式回归 | 拆分映射表逐段核对；`<link>` 顺序固定；拆完先跑视觉抽验再改组件 |
| 类名/变量重命名破坏 JS 或测试 | 铁律：不改选择器名；别名变量删除前全局 grep 确认零引用 |
| reader.css 迁移破坏翻页动画/沉浸模式 | 关键帧与类名原样搬运，`node --test`（pageTurn/theme 系列）守护 |
| embed 指令遗漏新目录 | `go build` + 启动后 `GET /css/base.css` 等冒烟请求 |
| 7 套主题对比度不足 | 调色板表为基准，视觉验证阶段逐主题核对文字对比度 |

## 实施顺序（每步一个 commit）

1. 拆分迁移：style.css → css/ 多文件 + web.go embed + 删 tokens.css（行为零变化）
2. themes.css：7 套调色板重写 + swatch 同步
3. components.css + base.css：组件按新语言重写，index.html emoji→SVG
4. 仪表盘 + 书架升级（含 JS 模板）
5. 阅读器 chrome + 其余视图收尾，全量视觉验证

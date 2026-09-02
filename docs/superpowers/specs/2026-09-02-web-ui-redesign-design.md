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

中性灰阶表面 + 每主题一个 accent。**保留**的规范变量名（`:root` 现有契约，重写取值不改名）：`--surface-app` /
`--surface-card` / `--surface-sidebar` / `--surface-hover` / `--text-primary` /
`--text-secondary` / `--text-muted` / `--text-on-accent` / `--accent` /
`--accent-hover` / `--accent-soft` / `--accent-text` / `--border-soft` /
`--border-subtle` / `--shadow-sm` / `--shadow-md` / `--radius-sm|md|lg` /
`--space-1..6` / `--error` / `--secondary` / `--font-sans`。
**删除**的旧别名变量完整清单（已逐一核实）：`--bg-main` / `--bg-card` /
`--bg-sidebar` / `--bg-elevated`（仅出现在 reader 覆盖块）/ `--primary` /
`--primary-light` / `--text-main` / `--text-white` / `--border-color` /
`--border-radius-lg`（含 fallback 写法）/ `--transition-smooth` /
`--transition-quick`（JS 无引用，组件改用新交互态规则）。删除前必须 grep
审计全部消费者，
已知 **JS 侧 CSSOM 引用 5 处**（bookmarksView.js：`var(--border-color)`×2、
`var(--primary)`、`var(--text-white)`，见 JS 改动清单）需先迁移到规范名。

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
    responsive.css    — 全部 @media 断点规则（**必须最后加载**：断点规则要
                        在层叠上压过视图规则；这也是它独立成文件而非并入
                        layout.css 的原因）
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

## 主题系统（机制不动 + 两处既有缺陷修复）

- `reader_settings.theme` → `applyGlobalAppTheme()` → `<html data-theme>`，
  `AUTO` 跟随系统——**机制保持现状**。
- 工作量在 `themes.css`：重写 7 套 chrome 调色板取值 + `body[data-reader-theme]`
  覆盖块迁入（阅读区背景/文字仍由阅读主题独立控制）。
- **阅读面板主题不在本次范围**：7 套阅读主题色值存于 `readerPrefs.js` 的
  `THEME_PRESETS`（JS 数据，经 `textReader.js` 的 `setVar('--reader-*')` 注入），
  保持原值。设置页 swatch 的硬编码 hex 表达的就是这些阅读主题背景色，
  **不随 chrome 调色板同步修改**；swatch 规则里 `var(--reader-border,
  var(--border-color))` / `var(--reader-fg, var(--text-main))` 的别名回退在
  别名迁移时改为规范名回退。

### 既有缺陷 1：boot.js 主题键不一致（FOUC 闪错主题）

`boot.js` 读 `chrome_theme` 键，但该键**没有任何代码写入**（`saveChromeTheme`
零调用方）——每次加载都回退系统偏好，忽略用户在设置里选的主题（选了 NIGHT
仍先渲染 day 再切换）。修复：`boot.js` 改为解析 `reader_settings` JSON 的
`theme` 字段（含 AUTO → matchMedia 解析 → 与 app.js 相同的 themeMap 落到
`data-theme`），boot.js 是非模块脚本需自带一份最小映射副本。`chrome_theme`
键与 `getChromeTheme` / `saveChromeTheme` 导出一并删除（已确认零消费）。

### 既有缺陷 2：header 日/夜切换按钮是死按钮

`#btn-theme-toggle` 无任何 click 监听，`updateThemeToggleIcon()` 在 app.js
定义后从未被调用。修复：接线——点击在 DAY ↔ NIGHT 间切换并
`readerPrefs.saveSettings({ theme })`（走统一 `reader-prefs-changed` 事件
管线，阅读器不重绘 chrome 主题不受影响），图标更新复用现成的
`updateThemeToggleIcon` 并在 `applyGlobalAppTheme` 内调用。

## 痛点视图升级

### 仪表盘

- 统计卡：SVG 图标容器 + 大数字（tabular-nums）+ 标签层级重排。
- 「最近打开的媒体」：行卡加视频缩略图。**复用 browserView.js 的既有 URL
  构造**：`/api/v1/videos/<encodeRoutePath(file.path)>/thumbnail`（dashboard
  最近列表本就是 videos-only，且 `/api/v1/videos/*` asset 路由是公开的、无需
  `?token=`——挂 auth 的是 `/api/v1/videos` 列表端点），`loading="lazy"` +
  `decoding="async"`，`onerror` 回退 SVG 图标（复用浏览页 capture-phase 错误
  委托模式）。`encodeRoutePath` 从 browserView.js 导出复用，勿复制实现。
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
- 阅读区（正文）背景/文字仍由阅读主题（`THEME_PRESETS` → `--reader-*` 变量）
  控制，不随本次改动变化。
- **别名迁移的隐蔽依赖**：现 `body[data-reader-theme]` 覆盖块重映射的目标
  全是旧别名（`--bg-card` / `--bg-elevated` / `--text-main` / `--text-white` /
  `--text-muted` / `--border-color`）。组件与 reader.css 改用规范名消费后，
  覆盖块必须**同步改为重映射规范名**（`--surface-card` / `--text-primary` /
  `--text-muted` / `--border-subtle` 等），否则阅读器内 chrome 主题接管会
  静默失效（drawer/dialog 回落全局主题）。`--reader-*` 注入端（textReader.js
  `setVar`）不动。

### 其余视图

浏览页 / 书签管理 / 设置 / 视频播放器 / 灯箱跟随新 token 打磨（控件、间距、
焦点态、空态），无结构性改动。移动端仅校准现有断点下的明显破版。

## JS 改动清单

| 文件 | 改动 |
|---|---|
| `index.html` | emoji→SVG、新增 `<link>`、移除 tokens.css link、书架卡容器结构微调 |
| `dashboard.js` | 统计图标 / 最近媒体行模板（缩略图 + SVG）、保持 `// XSS-SAFE:` 纪律 |
| `bookshelf.js` | `renderCard` 书封结构（类名新增 `bookshelf-card__cover` 等，渐变 class 由书名 hash 决定） |
| `bookmarksView.js` | ① 模板内 emoji→SVG；② 5 处 CSSOM 别名引用迁移到规范名（`var(--border-color)` → `var(--border-subtle)`、`var(--primary)` → `var(--accent)`、`var(--text-white)` → `var(--text-primary)`） |
| `browserView.js` | emoji→SVG（`encodeRoutePath` 已由 `utils.js` 导出，无需改动） |
| `boot.js` | 修复主题键不一致：改读 `reader_settings` JSON 的 `theme`（含 AUTO 解析 + 内置最小 themeMap 副本） |
| `app.js` | 接线 `#btn-theme-toggle`（DAY↔NIGHT，走 `saveSettings` 事件管线）；`applyGlobalAppTheme` 内调用 `updateThemeToggleIcon` |
| `readerPrefs.js` | 删除零消费的 `getChromeTheme` / `saveChromeTheme` 与 `chrome_theme` 键（boot.js 改造后无人引用） |
| `package.json` | 删除 `"build:tokens"` 脚本（随 tools/build-tokens.mjs 一起清理） |
| 业务逻辑 / 路由 / 存储格式 | **零改动** |

favicon 由 `favicon.go` 程序化生成并独立路由，不在 embed 清单内，本次不受影响。

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
   其余主题抽验；检查对比度（正文 ≥ 4.5:1）与 focus 可见性。主题专项：
   ① 设置选 NIGHT 后硬刷新无 FOUC 闪白；② header 日/夜按钮切换即时生效；
   ③ NIGHT 阅读主题下打开 TOC drawer 与设置 dialog，确认 chrome 主题接管
   未失效（排查别名重定向遗漏）。
5. Android / Rust 子系统不受影响，无需跑其测试。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| 2985 行 CSS 拆分漏段/错序导致样式回归 | 拆分映射表逐段核对；`<link>` 顺序固定；拆完先跑视觉抽验再改组件 |
| 类名/变量重命名破坏 JS 或测试 | 铁律：不改选择器名；别名变量删除前全局 grep 确认零引用（bookmarksView.js 5 处已知） |
| reader 覆盖块目标仍是旧别名，阅读器 chrome 主题接管静默失效 | 别名迁移时同步把覆盖块重映射目标改为规范名；视觉验证阶段在 NIGHT 阅读主题下打开 drawer/dialog 专项核对 |
| boot.js / 主题按钮改造引入回归 | 两处改动各自独立 commit；day/night/AUTO 三态下刷新验证无 FOUC；`node --test`（readerPrefs 系列）守护 |
| reader.css 迁移破坏翻页动画/沉浸模式 | 关键帧与类名原样搬运，`node --test`（pageTurn/theme 系列）守护 |
| embed 指令遗漏新目录 | `go build` + 启动后 `GET /css/base.css` 等冒烟请求 |
| 7 套主题对比度不足 | 调色板表为基准，视觉验证阶段逐主题核对文字对比度 |

## 实施顺序（每步一个 commit）

1. 拆分迁移：style.css → css/ 多文件 + web.go embed + 删 tokens.css 与
   build:tokens 脚本（行为零变化）
2. themes.css：7 套 chrome 调色板重写 + reader 覆盖块别名重定向 + 主题系统
   两处既有缺陷修复（boot.js 键不一致、header 切换按钮接线）
3. components.css + base.css：组件按新语言重写，index.html emoji→SVG，
   bookmarksView.js 别名 CSSOM 迁移，随后删除别名变量
4. 仪表盘 + 书架升级（含 JS 模板与缩略图）
5. 阅读器 chrome + 其余视图收尾，全量视觉验证

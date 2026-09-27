# Ink Editorial 重设计（Slate Ink & Paper）— 设计 spec

日期：2026-09-27
状态：已确认（用户在 superdesign 画布选定方向 01「Ink Editorial 墨色编辑风」）
设计稿：https://superdesign.dev/teams/e33a9f6e-f55d-4478-95ac-8f011b211172/projects/97eec087-6ce9-4622-a11a-47ab6aabe512
设计系统：`.superdesign/design-system.md` Part B（本 spec 的唯一视觉真值）

## 1. 目标与非目标

**目标**：把已确认的 Ink Editorial 视觉语言落进 web 与 Android 两端真实代码：
- 双端 chrome 主题重调为「Slate Ink & Paper」（暖灰纸面 + 墨黑主操作 + 苔绿进度/焦点点缀）
- 阅读器：幽灵 chrome（半透明 + 毛玻璃 + hairline）、serif 章题去装饰线、墨黑/苔绿进度体系、阅读主题预设重调
- 仪表盘去卡片化开放构图、浏览页胶囊筛选 + hairline 卡片、书架 shelf 构图

**非目标**：
- 不改任何交互逻辑/数据流（翻页、进度保存、同步、BLE、设置项集合全部保留）
- 不改主题架构：chrome 7 预设 + reader 9 选项（7 预设 + AUTO + CUSTOM）结构不变，仅重调色值
- 不引入新依赖、不加构建步骤

## 2. 硬约束（CSP / 平台）

1. **CSP `default-src 'self'`**：设计稿中的 Instrument Serif（Google Fonts CDN）**不可用**。
   Web 端 display serif 用已自托管嵌入的 **Noto Serif SC**（`/fonts/NotoSerifSC-Regular.woff2`，400）。
   Android 端 display serif 用 `FontFamily.Serif`（设备 Noto Serif CJK）。
2. 零 inline style；全部走 CSS 类（web）/ Compose token（Android）。
3. **跨端主题值对称**：web `readerPrefs.THEME_PRESETS` 与 Android `ReaderTheme` enum 的 hex 必须逐字一致（现有约定延续）。
4. 中文 UI 文案不变。

## 3. 设计 token（Part B 摘要，实现以此为准）

### 3.1 Chrome 主题（web `[data-theme]`，7 预设重调）

| Token | day | night | 说明 |
|---|---|---|---|
| --surface-app | #F7F7F5 | #0C0D10 | 暖灰纸 / 近黑 |
| --surface-card | #FFFFFF | #15161A | |
| --surface-sidebar | #F1F1EE | #0F1013 | |
| --text-primary | #0F172A | #E7E9EE | 深 slate |
| --text-secondary | #52575E | #9BA1AC | |
| --text-muted | #8A8F98 | #74787F | |
| --accent（苔绿） | #3D6B4F | #8FBF9F | 焦点/进度/live/在读 |
| --accent-hover | #2F5640 | #A3CDAF | |
| --accent-soft | rgba(61,107,79,.10) | rgba(143,191,159,.14) | |
| --accent-text | #2F5640 | #A3CDAF | AA 级小字 |
| **--action-ink（新增）** | #0A0A0A | #E7E9EE | 主操作按钮/活动导航条/滑块 thumb |
| --action-ink-hover | #26282E | #FFFFFF | |
| --border-soft/subtle | #EFEDE8 / #E5E4DF | #1F2128 / #22242B | |

day_bright：白化变体（surface-app #FFFFFF，accent #3D6B4F 不变）；eye_care/eye_care_green/parchment：保留各自纸色底，accent 统一换苔绿系（eye_care #4A6B52、green #33593C、parchment #4E6B44），text 用各自深棕/深绿。night_black：#000 底 + 同 night 的苔绿/ink 反色。

### 3.2 Reader 主题预设重调（双端同值）

| Theme | bg | fg | chromeBg | chromeFg | muted | border |
|---|---|---|---|---|---|---|
| DAY | #F6F4EE | #26282E | #EDEBE3 | #3A3C44 | #83858C | #E0DDD2 |
| DAY_BRIGHT | #FFFFFF | #212121 | #F5F5F5 | #333333 | #7A7A7A | #E0E0E0 |
| EYE_CARE | #F2EAD8 | #4A4034 | #E9DFC9 | #56493A | #9A8C74 | #D9CDB2 |
| EYE_CARE_GREEN | #C6D2C4 | #1E2A20 | #B9C7B6 | #22301F | #48584A | #A3B39F |
| PARCHMENT | #EFE6D2 | #3D3327 | #E5D9BF | #4D4034 | #8C7E66 | #D3C7AB |
| NIGHT | #111318 | #C6CAD2 | #191C22 | #B4B9C4 | #7E838D | #252832 |
| NIGHT_BLACK | #000000 | #B9BDC6 | #0A0B0D | #A7ABB5 | #74787F | #1B1D24 |

阅读默认值：fontSize 16→**17**，lineHeight 1.8→**1.9**（其余不变；迁移函数 clamp 范围兼容）。

### 3.3 组件语言

- **主按钮/活动导航**：ink 胶囊（9999px，#0A0A0A + 白字；暗色反转）；次按钮 hairline 胶囊
- **图标按钮**：36px ghost 圆（无边框静止，hover hairline）
- **卡片**：1px #E5E4DF hairline + radius 12px + 无/极浅阴影；hover hairline→ink
- **进度**：细线（2-4px）苔绿填充 + ink 圆 thumb
- **章节标题**：serif 400 居中 +8px，**去掉 40px 下划线装饰**（纯留白）
- **侧栏**：220px，serif 品牌字 + muted 副标（"局域网媒体中枢 · v0.2.0"），活动项 = ink 文字 + 2px ink 左条（无底色填充）
- **统计数字**：serif 40px 展示
- **动效**：入场 fade-rise 0.5s（dashboard/书架网格 60ms stagger），hover scale 1.02，其余维持现有时长

## 4. Web 实施面

| 文件 | 改动 |
|---|---|
| `css/themes.css` | 7 预设重调 + 新增 --action-ink 族；reader 子树覆盖块不变（沿用 --reader-*） |
| `css/layout.css` | 侧栏 220px/serif 品牌区/菜单静音化/活动 ink 条；header serif 标题 |
| `index.html` | sidebar-brand 结构改 wordmark+副标；**新增「书架」菜单项**（id=menu-bookshelf, #/bookshelf） |
| `router.js` | bookshelf 路由激活 menu-bookshelf |
| `css/components.css` | .btn-primary→--action-ink；FAB/toast token 顺应 |
| `css/views/reader.css` | 幽灵 chrome（半透明+blur+hairline）、icon-btn ghost 圆、footer 胶囊（下一章=ink 胶囊）、scrubber ink thumb/苔绿填充、章题去装饰线、TOC 活动=ink 条、设置 dialog swatch 值更新 |
| `readerPrefs.js` | THEME_PRESETS 重调 + 默认 17/1.9 |
| `textReader.js` | 仅 class/结构微调（footer 按钮次序、next 主按钮类）；无逻辑改动 |
| `css/views/dashboard.css` | 统计区开放构图（serif 数字/hairline 分隔）；continue-reading 条 |
| `css/views/browser.css` | 筛选胶囊（ink 活动）、卡片 hairline/radius12/hover ink、书封 serif |
| `css/views/bookshelf.css` | shelf 卡构图（书脊 inset 阴影、苔绿进度线、状态 chip） |
| 测试 | `textReader-theme.test.mjs` DAY hex；`readerPrefs.test.mjs` 结构断言应仍过；跑全量 `node --test` + xsscheck |

## 5. Android 实施面

| 文件 | 改动 |
|---|---|
| `ui/theme/Theme.kt` | Light/Dark/EyeCare/Green/Parchment/NightBlack 六 scheme 重调（primary→ink 系主操作、tertiary→苔绿） |
| `ui/theme/ColorTokens.kt` | OutlineSoft/PrimaryText 随调 |
| `data/ReaderSettings.kt` | ReaderTheme hex = §3.2（与 web 对称）；默认 17/1.9 |
| `ui/screen/TextReaderScreen.kt` | TopAppBar/BottomAppBar 幽灵化（透明 Surface+blur 近似：低 alpha surface + 无阴影）、进度条苔绿、下一章 ink 填充 Button、上一章/目录 ghost |
| `ui/component/reader/ReaderScrollbar.kt` | thumb→ink、track→苔绿 |
| `ui/screen/BookshelfScreen.kt` | 卡片 hairline、进度细线苔绿、筛选 ink 胶囊 |
| 测试 | `ThemeColorSchemeTest`、`ReaderSettingsMigrationTest`、`TextReaderScreenThemeTest` 相应断言更新；跑 `testDebugUnitTest` |

## 6. 阶段与提交切分（每 task 一 commit）

1. `feat(web): slate-ink chrome tokens and editorial sidebar (Ink Editorial Phase 1)` — themes.css/layout.css/index.html/router.js/components.css
2. `feat(reader): ink editorial reader chrome and retuned themes (Phase 2)` — reader.css/readerPrefs.js/textReader.js + 测试
3. `feat(web): dashboard browser bookshelf ink editorial restyle (Phase 3)` — 三个 views css
4. `feat(android): slate-ink material schemes and reader theme parity (Phase 4)` — Theme/ColorTokens/ReaderSettings + 测试
5. `feat(android): reader and bookshelf ink editorial chrome (Phase 5)` — TextReaderScreen/ReaderScrollbar/BookshelfScreen
6. `docs: sync handbook for ink editorial redesign` — AGENTS.md 模块地图措辞

## 7. 风险与回滚

- 色值重调会改变所有主题观感——用户已在画布确认方向；如需微调，token 单点改。
- Android Material3 primary 语义变化影响所有用 primary 的组件（FAB、filter chip 等）——Phase 4 提交后需人工过一遍主要屏。
- 回滚：每阶段独立 commit，revert 单阶段即可。

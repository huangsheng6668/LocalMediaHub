# UI 整体优化设计（Android + Web chrome）

- 日期：2026-07-26
- 范围：Android Compose 全屏 + Web chrome（阅读器/播放器全屏体验不在本次范围）
- 目标：保持温暖复古气质，精修对比度/层次/灰阶（P.a）；保持 HomeScreen 入口数量不变，靠间距与卡片样式提升呼吸感（L.b）；组件微观打磨与触控无障碍提升（T）
- 交付物：本 spec + HTML 静态对照预览（现状 vs 优化后）

## 1. 配色精修与 WCAG 可访问性

### 1.1 浅色（温暖复古 · terracotta 主 · 纸感）

主强调色由 teal 改为 terracotta；背景与卡片色差保持小（纸感），靠细描边 + 极微阴影区分卡片。
针对小字号文本标签，补充高对比度文本 token 以满足 WCAG AA 4.5:1 要求。

| Token | 现状 (Theme.kt) | 优化后 | 说明 |
|---|---|---|---|
| primary | `#135F65` teal | `#B96D1D` terracotta | 主强调色（图标、按钮底色、卡片高亮） |
| primary-text | 无（直接用 primary） | `#965410` terracotta 强对比 | 纸感背景上的文本标签（满足 WCAG AA 4.5:1） |
| secondary | `#B96D1D` | `#3E7A7E`（teal 降饱和） | teal 退为辅助 |
| tertiary | `#647A33` | `#647A33`（不变） | 书架/阅读相关，保持 |
| background | `#F6F1E8` | `#F4EEE2` | 微沉，衬纸感 |
| surface（卡片） | `#FFFBF7` | `#FBF6EC` | 与背景色差保持小 |
| surfaceVariant | `#E6E3DA` | `#EDE6D6` | 更暖的变体 |
| primaryContainer | `#DDF1F1` teal 底 | `#FBEBD8` terracotta 底 | HeroCard 渐变起始色 |
| secondaryContainer | `#FFE4C9` 琥珀 | `#D6EFF0` teal 底 | HeroCard 渐变终止色 / ContinueWatching 底色 |
| outline-soft（新增） | 无 | `#E2D9C6` | 卡片细描边（纸感关键） |
| outlineVariant | `#C7D0CC` | `#D4CCBA` | 暖化描边色（当前用于 HeroCard 0.25 alpha 边框） |
| shadow | Material 默认 elevation 2dp | `0 1px 2px rgba(60,40,20,.06)` | 极微阴影替代 elevation |
| error | `Color.Red (系统默认)` | `#C0392B`（不变） | 保持 |

### 1.2 暗色（冷暖并存 · 近纯黑带暖意）

主操作用暖琥珀，激活态用 teal；底色改为近纯黑带暖意。

| Token | 现状 (Theme.kt) | 优化后 | 说明 |
|---|---|---|---|
| background | `#0D1718` 冷青绿 | `#141210` 近纯黑带暖 | 偏暖底色 |
| surface | `#142022` | `#1E1A17` | 暖深棕卡片 |
| surfaceVariant | `#213235` | `#2A2420` | 暖化 |
| primary（主操作） | `#7AD4D2` teal | `#E8915A` 暖琥珀 | terracotta 提亮（与 `#141210` 对比度 7.8:1） |
| secondary（激活态） | `#FFBC6D` | `#6FB8BC` teal | 冷暖并存 |
| primaryContainer | `#1C3A3F` | `#3A2516` | 暖深棕 container |
| secondaryContainer | `#473017` | `#1A3335` | teal container |
| outline-soft（新增） | 无 | `#332B24` | 卡片细描边 |
| outlineVariant | `#314749` | `#3A3229` | 暖化描边 |

### 1.3 其他既有主题

`EYE_CARE_GREEN` / `EYE_CARE` / `PARCHMENT` / `NIGHT_BLACK` 本次配色不动，仅需：
- 为各主题补充 `outline-soft` token：
  - EYE_CARE: `#D9C8B2`（复用现有 `outline`）
  - EYE_CARE_GREEN: `#9BB098`（复用现有 `outline`）
  - PARCHMENT: `#D6CBAE`（复用现有 `outline`）
  - NIGHT_BLACK: `#222222`（复用现有 `outlineVariant`）
- 确保 terracotta primary 在上述主题中不生效（各主题保持自身 `primary`）

### 1.4 Web UI 色值协调

Web `style.css` 已有独立主题体系，`day` 主题 accent 为 `#C75B39`（比 Android 的 `#B96D1D` 偏红）。本次 Web 对齐方案：
- `--accent` 统一为 `#B96D1D`（与 Android primary 一致，差异可在后续微调）
- 新增 `--border-soft: #E2D9C6`（对应 Android `outline-soft`）
- 新增 `--accent-text: #965410`（对应 Android `primary-text`）
- `night` 主题 accent 对齐 `#E8915A`
- 其他 Web 主题（`day_bright` / `eye_care` / `parchment` 等）保持不动

## 2. 布局、呼吸感与多端响应式（HomeScreen）

现状值（来自 `HomeScreen.kt`）：
- `LazyColumn` 垂直 section 间距：`22.dp`
- `LazyRow` 横向卡片间距：`12.dp`
- 内容外边距：`start=20.dp, end=20.dp, top=12.dp, bottom=28.dp`
- 无 `WindowSizeClass` 响应式适配

优化目标：
- Section 间距：22dp → **28dp**
- 卡片之间横向间距：12dp → **14dp**
- 横向滚动区两端各加 **8dp** contentPadding，避免卡片贴边
- 内容外边距维持 20dp
- 卡片内边距统一为 **16dp**（现状不一致：HeroCard 20dp / LibraryCard 18dp / ContinueWatching 16dp）

**响应式网格适配（新增）**：
- Compact (<600dp 手机)：现有单列 LazyColumn + LazyRow 横滑
- Medium (600–840dp 折叠屏/小平板)：双列 LazyVerticalGrid，外边距扩大至 24dp
- Expanded (>840dp 平板/桌面)：3–4 列 Grid 布局，外边距扩大至 32dp
- 需引入 `WindowSizeClass`（当前代码库未使用）

## 3. 组件微观打磨与交互细节

现状值（来自 `HomeComponents.kt`）：

| 组件 | 现状圆角 | 现状宽度 | 现状 elevation |
|---|---|---|---|
| HeroCard | **24dp** | 全宽 | 2dp |
| LibraryCard | 16dp | 232dp | 2dp |
| ContinueWatchingCard | 16dp | 232dp | 2dp |
| RecentMediaCard | 16dp | 184dp | 2dp |
| BookshelfTile | 16dp | 140dp | 2dp |
| FavoritePreviewCard | 16dp | 184dp | 2dp |
| CollectionChip | 12dp (AssistChip) | 自适应 | 0dp |
| HeroCard 内部 Surface | 12dp | 自适应 | 0dp |

优化方案：
- **圆角规范**：HeroCard 24dp → **20dp**（稍收敛）；其余大卡保持 **16dp**；小卡/Chip/内部 Surface **12dp**；按钮 **12dp**
- **纸感卡片**：所有卡片 elevation 2dp → **0dp**（移除 Material 投影），改为叠加 `1px outline-soft` 描边 + 极微阴影 `Modifier.shadow(elevation = 1.dp, shape, ambientColor, spotColor)` 使用暖色调阴影
- **图标风格**：统一 Outline 风格（如 `Icons.Outlined.*`）；现状 HeroCard 混用了 Filled icons，激活态统一亮起 `primary`
- **排版字重**：SectionHeader title 字重 `FontWeight.Bold` → `FontWeight.SemiBold`（减轻沉重感，但 HeroCard 标题保持 Bold 作为层次最高元素）
- **按钮分级**：
  - 主按钮：`Button`（primary 实心底 + 白字），`shape = RoundedCornerShape(12.dp)`
  - 次按钮：`OutlinedButton`（surface 底 + outline-soft 描边），移除现有 `FilledTonalButton`
- **触控无障碍 (Accessibility)**：所有交互元素（Chip、图标按钮、动作项）保持最小 **48dp × 48dp** 触控热区
- **微交互反馈**：卡片与按钮在按压态（Pressed）添加 `scale(0.98)` 动画与 Ripple 反馈（利用已有 `NoRippleIndication` 机制做选择性 ripple）

## 4. 受影响的 Screen 与 Component 清单

本次 token 变更将影响以下文件（Compose 代码改动留作下一轮）：

| 文件 | 影响范围 |
|---|---|
| `ui/theme/Theme.kt` | 全部配色方案 token 更新 + 新增 outline-soft |
| `ui/screen/HomeScreen.kt` | section 间距、padding、响应式 |
| `ui/component/home/HomeComponents.kt` | 所有卡片圆角/阴影/描边、按钮样式、字重 |
| `ui/screen/BrowseScreen.kt` | TopAppBar 颜色跟随 theme 自动变，无需额外改动 |
| `ui/component/browse/*.kt` | 卡片圆角/阴影跟随 theme token |
| `server/internal/web/style.css` | accent / border-soft / accent-text / night accent |

## 5. HTML Mockup（spec 可视化附件）

单文件 `docs/ui-redesign/ui-redesign-preview.html`，全部 CSS 内联，无依赖。

结构与技术实现：
1. 顶部切换器：`[现状] [优化后] [左右对照]` 三态，基于 `.theme-cur` 与 `.theme-opt` 独立作用域实现隔离
2. Token 总览页：全套色板（含 container 色）/ 字号 / 圆角 / 阴影 / WCAG 对比度，现状 vs 优化后并排
3. HomeScreen 预览：模拟手机框，还原完整八段（Hero / Libraries / Collections / Continue / Bookshelf / Recent / Favorites / Downloads）
4. 卡片特写与按压态预览：LibraryCard / ContinueWatchingCard / RecentMediaCard 放大对比及 Hover/Pressed 效果
5. 暗色整机对照：完整暗色手机框（非片段），展示冷青→暖黑的差异

## 6. 不在本次范围

- 阅读器（TextReaderScreen）、播放器（VideoPlayerScreen）全屏体验
- 真正改 Compose 代码（留作下一轮，按本 spec 出 plan）

## 7. 验收

- HTML mockup 在浏览器中能切换三态（现状、优化后、左右对照），左右对照时左面板呈现旧版 Teal 风格，右面板呈现新版 Terracotta/纸感风格
- Token 总览页数值与本文 §1 完全一致
- 所有现状列数值与实际代码（`Theme.kt` / `HomeComponents.kt`）一致
- 用户确认优化后方向后，再进入 writing-plans 出 Compose 重构计划

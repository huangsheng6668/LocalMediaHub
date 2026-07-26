# UI 整体优化设计（Android + Web chrome）

- 日期：2026-07-26
- 范围：Android Compose 全屏 + Web chrome（阅读器/播放器全屏体验不在本次范围）
- 目标：保持温暖复古气质，精修对比度/层次/灰阶（P.a）；保持 HomeScreen 入口数量不变，靠间距与卡片样式提升呼吸感（L.b）；组件微观打磨（T）
- 交付物：本 spec + HTML 静态对照预览（现状 vs 优化后）

## 1. 配色精修

### 1.1 浅色（温暖复古 · terracotta 主 · 纸感）

主强调色由 teal 改为 terracotta；背景与卡片色差保持小（纸感），靠细描边 + 极微阴影区分卡片。

| Token | 现状 | 优化后 | 说明 |
|---|---|---|---|
| primary | `#135F65` teal | `#B96D1D` terracotta | 主强调色 |
| secondary | `#B96D1D` | `#3E7A7E`（teal 降饱和） | teal 退为辅助 |
| background | `#F6F1E8` | `#F4EEE2` | 微沉，衬纸感 |
| surface（卡片） | `#FFFBF7` | `#FBF6EC` | 与背景色差保持小 |
| outline-soft（新增） | 无 | `#E2D9C6` | 卡片细描边（纸感关键） |
| shadow | Material 默认 | `0 1px 2px rgba(60,40,20,.06)` | 极微阴影 |

### 1.2 暗色（冷暖并存 · 近纯黑带暖意）

主操作用暖琥珀，激活态用 teal；底色改为近纯黑带暖意。

| Token | 现状 | 优化后 | 说明 |
|---|---|---|---|
| background | `#0D1718` 冷青绿 | `#141210` 近纯黑带暖 | 偏暖 |
| surface | `#142022` | `#1E1A17` | 暖深棕 |
| primary（主操作） | `#7AD4D2` teal | `#E8915A` 暖琥珀 | terracotta 提亮 |
| secondary（激活态） | `#FFBC6D` | `#6FB8BC` teal | 冷暖并存 |
| outline-soft（新增） | 无 | `#332B24` | 卡片细描边 |

### 1.3 其他既有主题

`EYE_CARE_GREEN` / `EYE_CARE` / `PARCHMENT` / `NIGHT_BLACK` 本次不动，仅确保新增 `outline-soft` token 在各主题下有合理回退值。

## 2. 布局与呼吸感（HomeScreen，入口数量不变）

- Section 间距：22dp → 28dp
- 卡片内边距：统一为 16dp（现状不一致）
- 卡片之间横向间距：12dp → 14dp
- 横向滚动区两端各加 8dp 透明留白，避免卡片贴边
- 内容外边距维持 20dp

## 3. 组件微观打磨

- 卡片圆角统一：大卡 16dp / 小卡与 chip 12dp（现状混用）
- 所有卡片加 1px `outline-soft` 描边 + 极微阴影（纸感来源）
- 操作图标统一 outline 系（现状 Material filled 与 outline 混用）；激活态用 primary
- headline 字重：Bold → SemiBold（现状偏重）
- 按钮：主按钮 primary 实心 + 白字；次按钮 surface 底 + 描边（去掉 tonal 灰底）

## 4. HTML Mockup（spec 可视化附件）

单文件 `docs/ui-redesign/ui-redesign-preview.html`，全部 CSS 内联，无依赖。

结构：
1. 顶部切换器：`[现状] [优化后] [左右对照]` 三态
2. Token 总览页：全套色板 / 字号 / 圆角 / 阴影，现状 vs 优化后并排
3. HomeScreen 预览：模拟手机框，还原八段（Hero / Libraries / Collections / Continue / Bookshelf / Recent / Favorites / Downloads）
4. 卡片特写：LibraryCard / ContinueWatchingCard / RecentMediaCard 三种卡片放大对比

## 5. 不在本次范围

- 阅读器（TextReaderScreen）、播放器（VideoPlayerScreen）全屏体验
- 真正改 Compose 代码（留作下一轮，按本 spec 出 plan）
- Web chrome 实际代码改动（mockup 不含 Web chrome 一屏，仅方向与本 spec 对齐）

## 6. 验收

- HTML mockup 在浏览器中能切换三态，优化后配色与现状差异清晰可辨
- Token 总览页数值与本文 §1 完全一致
- 用户确认优化后方向后，再进入 writing-plans 出 Compose 重构计划

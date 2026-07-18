# 小说阅读 UI 重设计（Web + Android）

- 日期：2026-07-18
- 状态：已与用户对齐（brainstorming），待写实施 plan
- 作者：huangsheng6668（与 Claude 协作）
- 关联分支：`feat/epub-image-inline`

## 背景与目标

用户反馈：Web 端和 Android 端的小说阅读 UI 在「颜色、排版、整体氛围」三方面都不够舒适。
本期目标是在不破坏现有书签 / 章节进度 / 自动滚动 / epub 内联图片等既有能力的前提下，系统性提升阅读体验，并保持两端视觉风格统一（具体实现允许差异化）。

## 范围确认（用户已选）

- **A1** 调整既有三主题配色；**A2** 新增主题预设 + 跟随系统
- **B1** 字体可切换（含 Web 字体打包）；**B2** 段落缩进 / 段间距开关；**B3** 内容宽度可调
- **C1** 沉浸模式；**C2** 阅读主题覆盖顶/底栏；**C3** 翻页动效；**C4** 章节装饰

边界：
- 两端「视觉风格统一，实现各自合适」（Web 用 LXGW WenKai 真实楷体；Android 用系统字体回退）
- 字体资源允许引入（Web 可打包 woff2；Android 不打包，仅系统字体映射）
- 排除真正的「分页翻页引擎」重写（风险过高，对书签 / 进度 / 自动滚动破坏大）

## 数据形状（SettingsV2）

两端共用同一组语义字段；存储格式各自序列化。

```text
SettingsV2 = {
  fontFamily:        'SYSTEM' | 'SERIF' | 'KAITI'
  fontSize:          number   // 12–28，整数；默认 16
  lineHeight:        number   // 1.3–2.5，步长 0.1；默认 1.8
  contentWidth:      number   // Web 600–900 px / Android 360–720 dp；默认 Web 720 / Android 600
  firstLineIndent:   boolean  // 默认 true
  paragraphSpacing:  boolean  // 默认 false
  theme:             'DAY' | 'DAY_BRIGHT' | 'EYE_CARE' | 'PARCHMENT'
                   | 'NIGHT' | 'NIGHT_BLACK' | 'AUTO'
  immersiveMode:     boolean  // 默认 false
  autoScrollSpeed:   number   // 1–10（既有，保留）
}
```

> **审核修改说明**：移除了 `autoSystemTheme` 字段。原设计中 `theme: 'AUTO'` 已经表达了“跟随系统”语义，再加一个 `autoSystemTheme: boolean` 会产生两个控制点（`autoSystemTheme=true` 但 `theme='DAY'` 时行为不明确）。统一为：用户选择 `theme='AUTO'` 即启用跟随系统，选择其他主题即关闭。设置面板中“跟随系统”开关的 on/off 等价于将 `theme` 设为 `'AUTO'` / 恢复上次非 AUTO 主题。

迁移（V1 → V2）：
- `fontSize: 'SMALL' → 14, 'MEDIUM' → 16, 'LARGE' → 18, 'XLARGE' → 20`，其他值（含未定义）→ `16`

> **审核修改说明**：原文档 MEDIUM 映射为 17，但两端现有代码中 `MEDIUM` 的实际值是 `16`（Web `FONT_SIZES.MEDIUM = 16`，Android `ReaderFontSize.MEDIUM(16)`）。迁移必须保持与现有行为一致，否则老用户升级后字号会悄然变大。
- `lineHeight: 'COMPACT' → 1.4, 'STANDARD' → 1.8, 'LOOSE' → 2.2`，其他 → `1.8`
- `theme` 字段保留原值，`'DAY'/'NIGHT'/'EYE_CARE'` 直接兼容
- 缺失字段填默认；损坏 JSON 整体回退默认；新字段（fontFamily / contentWidth / 等）填默认

## §1 主题系统

### 1.1 预设（6 + AUTO）

| key | 名称 | bg | fg | chromeBg | chromeFg | muted | border |
|---|---|---|---|---|---|---|---|
| `DAY` | 日间·纸白 | `#FAF8F3` | `#2B2B2B` | `#F2EFE7` | `#3D3D3D` | `#7A7A78` | `#E5E2D8` |
| `DAY_BRIGHT` | 日间·亮白 | `#FFFFFF` | `#212121` | `#F5F5F5` | `#333333` | `#7A7A7A` | `#E0E0E0` |
| `EYE_CARE` | 护眼·米黄 | `#F4ECD8` | `#5B4636` | `#EDE3CC` | `#6B5644` | `#9C8870` | `#D8CBAF` |
| `PARCHMENT` | 羊皮纸 | `#EFE6D2` | `#3D3327` | `#E5D9BF` | `#4D4034` | `#8C7E66` | `#D3C7AB` |
| `NIGHT` | 夜间·深空 | `#1A1A1F` | `#C9C9CE` | `#232328` | `#B0B0B5` | `#84848A` | `#2D2D33` |
| `NIGHT_BLACK` | 夜间·纯黑 | `#000000` | `#BFBFBF` | `#0A0A0A` | `#A8A8A8` | `#787878` | `#1C1C1C` |
| `AUTO` | 跟随系统 | — | — | — | — | — | — |

`AUTO` 在两端解析：亮→`DAY`，暗→`NIGHT`，整套预设（含 chrome 字段）一起切换。

### 1.2 跟随系统实现

- Web：`window.matchMedia('(prefers-color-scheme: dark)')` 监听变化；`theme === 'AUTO'` 时执行解析路径（亮→`DAY`，暗→`NIGHT`）
- Android：`isSystemInDarkTheme()` Compose API；`theme == AUTO` 时主题组 6 个 `FilterChip` 全部禁用（灰显）
- 两端均需缓存上次非 AUTO 主题值，当用户关闭“跟随系统”时恢复到该值而非总是 fallback 到 `DAY`

### 1.3 应用范围

整个阅读器视图（顶栏 + 正文 + 底栏 + drawer + 设置面板 + 自动滚动面板）全部由当前 reader theme 驱动。退出阅读器后立即恢复 App/系统主题，Dashboard 等其他视图不受影响。

## §2 字体系统

### 2.1 Web 字体打包

引入两个开源字体到 `server/internal/web/fonts/`：

| 字体 | 风格 | 文件 | 大小（估） |
|---|---|---|---|
| LXGW WenKai（霞鹜文楷） | 楷体风手写感 | `LXGWWenKai-Regular.woff2` | ~7 MB |
| Noto Serif SC（思源宋体） | 传统宋体 | `NotoSerifSC-Regular.woff2` | ~8 MB |

- 由 Go HTTP 服务器静态托管为公开资源（不参与 `/api/v1/books/image` 鉴权）
- `@font-face` 声明 `font-display: swap`，先用系统字体渲染，加载完无缝替换
- 仅打包 Regular 字重；woff2 格式
- 文件名使用无空格命名，避免 URL 编码问题

> **审核补充：字体加载优化**
> - 本项目为局域网本地服务，15MB 字体在千兆内网下 < 200ms，可接受
> - 仍建议在阅读器 HTML 中对首选字体添加 `<link rel="preload" as="font" type="font/woff2" crossorigin>` 以尽早触发下载
> - 若未来考虑外网访问，可通过 `pyftsubset`（fonttools）对 CJK 字体做常用字子集化，将体积降至 2–3 MB

### 2.2 字体选项（3 档，两端枚举一致）

| key | Web CSS font-family | Android 映射 |
|---|---|---|
| `SYSTEM` | `-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif` | `FontFamily.Default` |
| `SERIF` | `"Noto Serif SC", "Songti SC", "SimSun", serif` | `FontFamily.Serif` |
| `KAITI` | `"LXGW WenKai", "Kaiti SC", "STKaiti", cursive` | 回退到 `FontFamily.Serif`（Android 多数 ROM 无独立楷体） |

> Android `KAITI` 选项在设置面板上标小字「（部分设备显示为宋体）」。

### 2.3 应用范围

正文段落跟随用户 `fontFamily`；章节大标题、首字下沉固定 serif（视觉锚点，不随用户切换）。

## §3 排版系统

### 3.1 字号

- 连续滑块：12–28，步长 1，默认 16
- Web：`--reader-font-size: 16px` → `.text-reader__content { font-size: var(--reader-font-size) }`
- Android：`TextStyle.fontSize = fontSize.sp`

### 3.2 行距

- 连续滑块：1.3–2.5，步长 0.1，默认 1.8
- Web：`--reader-line-height: 1.8` → CSS `line-height`
- Android：`TextStyle.lineHeight = (fontSize.sp × lineHeightMultiplier)`

### 3.3 内容宽度

- 连续滑块：Web 600–900 px（默认 720）；Android 360–720 dp（默认 600）
- Web：`--reader-content-width` → `.text-reader__content { max-width: var(--reader-content-width); margin: 0 auto }`
- Android：LazyColumn 包一层 `Box(Modifier.fillMaxWidth(), contentAlignment = Center)`，内层限宽
- **Android 设备适配规则**：滑块上界 `min(720, screenWidthDp - 32)`（两侧各留 16dp），用户设置值超过设备宽度时自动 clamp 到上界；竖屏/横屏切换时重新计算上界但不修改用户已保存的值

### 3.4 段落开关

| 选项 | 默认 | 关闭效果 | 开启效果 |
|---|---|---|---|
| `firstLineIndent` | true | `text-indent: 0` | `text-indent: 2em` |
| `paragraphSpacing` | false | `margin-bottom: 1.2em` | `margin-bottom: 1.6em` |

CSS class 组合：`<p class="text-reader__p indent-on gap-off">`，避免动态计算具体值。

### 3.5 V1 字段枚举的内部用途

`FONT_SIZES` / `LINE_HEIGHTS` 不再对外暴露，仅用于迁移函数初始化与既有测试 fixture；新代码使用 V2 数字字段。

## §4 沉浸模式

### 4.1 触发与退出

| 操作 | 行为 |
|---|---|
| 单击屏幕中区域（横向 20%–80%） | 切换沉浸开/关 |
| 单击屏幕左 20% | 上一章（不退出沉浸） |
| 单击屏幕右 20% | 下一章（不退出沉浸） |
| `Esc`（仅 Web） | 退出沉浸 |

> **审核修改说明**：将中区域从 25%–75%（50% 宽度）调整为 20%–80%（60% 宽度）。原比例下手机竖屏（~360dp）中区域仅 ~180dp，用户容易误触翻章热区。调整后中区域 ~216dp，翻章热区各 ~72dp，更合理。另外，现有代码中 Web 端已经是 25%/75% 分割点（`textReader.js` 中 `< 25%` = prev, `> 75%` = next），这个修改与现状差异很小，且新增的沉浸切换仅在中间区域触发，不会破坏现有翻章行为。

不实现「临时唤出栏」的滑动手势，避免双状态栏交互复杂度。`autoScroll` 在沉浸模式下仍可用。

### 4.2 视觉行为

沉浸开时：
- 顶栏 / 底栏：`opacity: 0; transform: translateY(-100% / +100%); pointer-events: none`，250ms `cubic-bezier(0.4, 0, 0.2, 1)`
- 正文上下 padding 32px → 24px，同步过渡
- Android：`TopAppBar / BottomAppBar` 用 `AnimatedVisibility` 收起，`Scaffold` padding 重算

### 4.3 进入书籍时的行为

即使 `immersiveMode=true`，加载书籍时先显示栏 1.5 秒（给用户视觉锚点），再自动隐藏。

### 4.4 光标规则（仅 Web）

- 中区域：沉浸 on 时 `pointer`（可切换）；off 时 `default`
- 左/右热区：始终 `pointer`（翻页）
- Android 无 hover 概念，不区分。

## §5 主题覆盖顶/底栏（Chrome Theming）

### 5.1 Web 策略

进入阅读器时给 `document.body` 设置 `data-reader-theme="<THEME>"` 属性；CSS 用属性选择器在阅读器子树内整体覆盖 App 变量：

```css
/* 审核修正：两个属性选择器在同一个 body 上，必须写成复合选择器（无空格） */
body[data-reader-theme="NIGHT"][data-active-tab="read"] .view-container,
body[data-reader-theme="NIGHT"] .text-reader,
body[data-reader-theme="NIGHT"] .text-reader__drawer,
body[data-reader-theme="NIGHT"] dialog#reader-settings-dialog {
    --bg-card: var(--reader-bg);
    --text-main: var(--reader-fg);
    --text-muted: var(--reader-muted);
    --border-color: var(--reader-border);
}
```

> **审核修改说明**：原写法 `body[...] body[...]` 是后代选择器，要求第二个 `body` 嵌套在第一个 `body` 内——HTML 中 `<body>` 不会嵌套，该选择器永远不会匹配任何元素。修正为 `body[data-reader-theme="NIGHT"][data-active-tab="read"]` 复合选择器（同一元素同时具有两个属性）。

顶栏 / 底栏显式使用 `var(--reader-chrome-bg)` / `var(--reader-chrome-fg)`，不再 fallback 到 `rgba(0,0,0,0.2)`。
退出 / cleanup 时 `delete document.body.dataset.readerTheme`。

### 5.2 Android 策略

`ReaderThemeWrapper` 升级为 `ReaderThemeScope`：

```kotlin
@Composable
fun ReaderThemeScope(theme: ReaderTheme, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme.copy(
        background = theme.bg,
        onBackground = theme.fg,
        surface = theme.chromeBg,
        onSurface = theme.chromeFg,
        surfaceVariant = theme.chromeBg,
        onSurfaceVariant = theme.muted,
    )
    CompositionLocalProvider(LocalContentColor provides theme.fg) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.background(theme.bg)) { content() }
        }
    }
}
```

`TextReaderScreen` 把 `ReaderThemeScope` 移到 `Scaffold` 外层，使 `TopAppBar / BottomAppBar / ModalDrawerSheet / ReaderSettingsSheet` 全部跟随。

### 5.3 `--primary` 强调色

不跟随 reader theme，保持 App 主题蓝。避免夜间蓝色按钮刺眼，也避免主色乱跳。

## §6 翻页动效与章节装饰

### 6.1 章节切换淡入

每次 `loadChapter` 完成后，正文从 `opacity: 0` + `translateY(8px)` 渐入到 `1 / 0`，120ms `ease-out`。

- Web：CSS keyframes + class toggle
- Android：`AnimatedContent` 包 `LazyColumn`，`fadeIn(tween(120)) togetherWith fadeOut(tween(0))`

不淡出旧章，避免「消失再出现」感。

### 6.2 章节大标题

正文顶部渲染章节大标题（独立于顶栏面包屑）：
- 居中
- 字号 = `fontSize + 6px`（跟随用户字号）
- 字重 600
- 固定 serif（不跟随用户字体）
- 上下间距 `2em 0 1.5em`
- 底部 40px 居中装饰线，颜色 `var(--reader-border)`

来源：服务端 chapter `title` 字段。

### 6.3 首字下沉（仅 Web）

仅章节首个 text block 应用：

```css
.text-reader__p--dropcap {
    text-indent: 0;  /* 下沉段不再缩进 */
}
.text-reader__p--dropcap::first-letter {
    font-size: 3.2em;
    font-family: var(--reader-font-serif, "Noto Serif SC", serif);
    font-weight: 600;
    float: left;
    line-height: 0.9;
    margin: 0.05em 0.12em 0 0;
    color: var(--reader-fg);
}
```

边界处理：
- 引号开头的段落：CSS 标准行为（引号与首字一起下沉），不额外处理
- 首段是图片 block：跳过，找第一个 text block 应用
- 段落不足 4 字：不应用
- 纯数字开头的段落（如“2024年…”）：正常应用，数字下沉视觉可接受
- 纯标点/特殊符号开头（如“——”、“……”）：不应用，跳到下一个 text block

Android 不实现（Compose 自定义 Layout 成本高，已知差异）。

### 6.4 章节结束符号

末段后追加 `<div class="text-reader__chapter-end" data-action="next">❖</div>`：
- 居中
- 颜色 `var(--reader-muted)`
- 点击 = 下一章

## §7 设置面板信息架构

### 7.1 分组（4 组）

```
阅读设置                                 [×]

┌─ 外观 ─────────────────────────┐
│ 跟随系统      [⚪─────]   off   │  toggle（on = theme 设为 AUTO，下面主题组禁用灰显）
│ 主题（6 选 1）                  │  2 行 × 3 列 radio + 圆色块预览
│ 字体（3 档）                    │  radio
└────────────────────────────────┘

┌─ 字号与行距 ───────────────────┐
│ 字号      [────●────]  16 px   │
│ 行距      [──●──────]  1.8     │
│ 宽度      [────●────]  720 px  │
└────────────────────────────────┘

┌─ 段落 ─────────────────────────┐
│ 首行缩进                  [● on]│
│ 段间距                    [○ off]│
└────────────────────────────────┘

┌─ 行为 ─────────────────────────┐
│ 沉浸模式                  [○ off]│
│ 自动滚动速度 [───●───]  5       │
└────────────────────────────────┘

                       [关闭]
```

### 7.2 交互细节

- 主题：6 radio 横向 2×3 网格，每项带圆色块预览（不含 AUTO，AUTO 通过“跟随系统”开关控制）
- `跟随系统` toggle on 时：`theme` 设为 `'AUTO'`，6 主题 radio 整组 disabled + 灰显
- `跟随系统` toggle off 时：`theme` 恢复为上次选择的非 AUTO 值（缓存在内存中）
- 滑块实时预览（不点应用），右侧实时数字标签
- 段落 / 沉浸 toggles：iOS 风格左右 toggle
- 关闭按钮：单个「关闭」，所有改动即时生效（无应用 / 取消）

### 7.3 不做

- 不做「预设配置」「恢复默认」「导入导出」「设置项搜索」

## §8 落地清单

### 8.1 Web 端（不新增文件）

| 文件 | 改动摘要 |
|---|---|
| `server/internal/web/readerPrefs.js` | `THEME_PRESETS` 扩展到 6 + AUTO，每项加 chrome 字段；删除 `FONT_SIZES`/`LINE_HEIGHTS` 对外导出（仅内部迁移用）；新增 `FONT_FAMILIES`、`CONTENT_WIDTH_RANGE`；`DEFAULT_SETTINGS` 升级到 V2（不含 `autoSystemTheme`）；新增 `migrateV1toV2`；缓存 `lastNonAutoTheme` |
| `server/internal/web/textReader.js` | `applySettingsToUI` 新增：`--reader-font-family`、`--reader-content-width`、`--reader-chrome-bg/fg`、`--reader-muted`；`document.body.dataset.readerTheme`；段落 indent/gap class 切换；首段 `text-reader__p--dropcap`（含标点开头跳过逻辑）；章节大标题渲染；章节末尾 `❖`；切章淡入；沉浸模式状态机（中区域 20%–80%）；cleanup 移除 `data-reader-theme` |
| `server/internal/web/style.css` | `body[data-reader-theme="..."][data-active-tab="read"]` 复合选择器覆盖；header/footer 显式 chrome 变量；`--reader-content-width` / `--reader-font-family`；段落 indent/gap class；首字下沉；章节大标题；章节结束符号；淡入 keyframes；沉浸模式栏隐藏；`.reader-settings__*` 设置面板样式 |
| `server/internal/web/fonts/` | 新增目录，放 2 个 woff2 文件（`LXGWWenKai-Regular.woff2`、`NotoSerifSC-Regular.woff2`） |

### 8.2 Android 端（新增 1 文件）

| 文件 | 改动摘要 |
|---|---|
| `data/ReaderSettings.kt` | `ReaderTheme` 加 chrome 字段、加 `DAY_BRIGHT/PARCHMENT/NIGHT_BLACK/AUTO`；删 `ReaderFontSize/ReaderLineHeight` enum，改 `fontSizeSp:Int / lineHeightMultiplier:Float`；加 `fontFamily / contentWidthDp / firstLineIndent / paragraphSpacing / immersiveMode`（无 `autoSystemTheme`，由 `theme=AUTO` 统一表达） |
| `data/RecentActivityStore.kt` | reader_settings 序列化升级 V2 + V1 兼容反序列化；缓存 `lastNonAutoTheme` 用于关闭跟随系统时恢复 |
| `ui/component/reader/ReaderThemeWrapper.kt` | 升级为 `ReaderThemeScope`（覆盖 MaterialTheme colorScheme、包整个 Scaffold、监听 AUTO） |
| `ui/screen/TextReaderScreen.kt` | `ReaderThemeScope` 移到 Scaffold 外层；沉浸模式状态机；中区域（20%–80%）点击切换沉浸；章节大标题作为 LazyColumn 首项；正文宽度 Box 居中；切章 AnimatedContent 淡入；末尾 ❖ item |
| `ui/component/reader/ReaderSettingsSheet.kt` | 完全重排：4 组（外观 / 字号与行距 / 段落 / 行为）；主题 6 FilterChip + 圆色块；字体 3 FilterChip；3 Slider；4 Switch（“跟随系统”toggle 控制 theme=AUTO，不是独立字段） |
| `viewmodel/TextReaderViewModel.kt` | 兼容 AUTO + 缓存 lastNonAutoTheme；`updateSettings` 透传 V2；进入书籍后 1.5 秒沉浸栏隐藏时序 |
| **新增** `ui/component/reader/ReaderFontFamily.kt` | enum：`SYSTEM/SERIF/KAITI`，提供 `toFontFamily()` 映射 |

### 8.3 PR 拆分（6 个，风险递增）

1. **数据层**：`ReaderSettings` V2 + 迁移 + 测试（Android）/ `readerPrefs` V2 + 迁移 + 预设完整字段（Web）。UI 暂时只读旧字段，行为不变。
2. **主题扩展**：6 + AUTO 主题、chrome 字段、Web `body[data-reader-theme]` 整体覆盖 / Android `ReaderThemeScope` 包 Scaffold。
3. **排版与字体**：连续滑块、首行缩进 / 段间距开关、Web 字体打包 + @font-face、`--reader-font-family` / `--reader-content-width`。
4. **设置面板重排**：Web `<dialog>` 重写 + Android `ReaderSettingsSheet` 重写。
5. **沉浸模式**：状态机 + 动画 + 中区域点击切换。
6. **章节装饰与动效**：章节大标题、首字下沉、章节结束符号、淡入动画。

### 8.4 风险与缓解

| 风险 | 缓解 |
|---|---|
| Web 字体文件大，首屏白屏 | `font-display: swap` + woff2 + `<link rel="preload">` |
| Android MaterialTheme 局部覆盖影响 ModalDrawerSheet 行为 | 实施时跑 `TextReaderScreenThemeTest` 全套，发现问题再 narrow |
| 老用户 V1 settings 读不到 V2 | `migrateV1toV2` + 单测覆盖；迁移失败 fallback DEFAULT |
| 沉浸模式中区域点击与现状冲突 | 现状中区域 click 无副作用，新行为是新增（不破坏） |
| 章节大标题与顶栏标题重复 | 视觉上是「面包屑 + 装饰性大标题」双层；若反馈冗余，后续可加开关 |
| `theme=AUTO` 关闭后不知恢复哪个主题 | 两端内存缓存 `lastNonAutoTheme`；初始化时若 `theme=AUTO` 则 fallback `DAY` |

## §9 测试策略

### 9.1 原则

- Android 沿用 JUnit + Compose UI 测试；V2 API 同步迁移测试，新功能加新测试
- Web 项目无单测基础设施，本期不新建；以手动验证清单 + 浏览器 devtools 为主
- 跨端一致性用一份共享行为矩阵作人工验收

### 9.2 Android 自动化测试

| 文件 | 改动 |
|---|---|
| `ReaderSettingsSheetTest.kt` | 改：构造 V2 实例；新：6 主题 FilterChip 渲染、"跟随系统"toggle 开启后 theme=AUTO 且主题组禁用、3 滑块实时改变 settings 值、4 toggles（跟随系统/首行缩进/段间距/沉浸）、字体 3 档切换 |
| `TextReaderScreenThemeTest.kt` | 改：6 主题各自渲染断言；新：AUTO 在 dark 时切 NIGHT、Scaffold 外层包 ReaderThemeScope 后 TopAppBar 颜色跟随 |
| `TextReaderViewModelReaderTest.kt` | 新：AUTO 解析为 DAY/NIGHT 的逻辑、沉浸 1.5 秒后栏隐藏时序、`updateSettings` 持久化 V2 形状 |
| `RecentActivityStoreReaderSettingsTest.kt` | 新：V1 → V2 迁移用例、V2 round-trip |
| **新增** `ReaderSettingsMigrationTest.kt` | 集中放 V1→V2 迁移边界用例（空、损坏 JSON、部分字段缺失、字段类型错误） |

### 9.3 Web 手动验证清单

**主题（§1, §5）**
- [ ] 切换 6 主题，正文 + 顶栏 + 底栏 + drawer + 设置 dialog 颜色全部跟随
- [ ] 开启跟随系统，改 OS 深浅模式，主题在 DAY/NIGHT 间自动切
- [ ] 关闭跟随系统，主题组可选
- [ ] 退出阅读器（返回 Dashboard），App 主题恢复正常（无残留）

**字体（§2）**
- [ ] SYSTEM/SERIF/KAITI 切换，正文字体立即变化
- [ ] 首次切到 SERIF/KAITI，先显示系统字体（无白屏），加载完无缝替换
- [ ] DevTools Network 看 woff2 文件被请求且命中缓存

**排版（§3）**
- [ ] 字号 12→28 滑动，正文实时变化，标签数字同步
- [ ] 行距 1.3→2.5 滑动，正文实时变化
- [ ] 宽度 600→900 滑动，正文最大宽度变化
- [ ] firstLineIndent off 时正文左对齐无缩进
- [ ] paragraphSpacing on 时段间距明显增大

**沉浸模式（§4）**
- [ ] off 时单击中区域 → on，顶/底栏滑出
- [ ] on 时单击中区域 → off，栏滑入
- [ ] on 时单击左 25% → 上一章（不退出沉浸）
- [ ] on 时单击右 25% → 下一章（不退出沉浸）
- [ ] on 时 Esc → 退出（Web）
- [ ] 进入书籍时即使 immersiveMode=true 也先显示栏 1.5 秒

**章节装饰（§6）**
- [ ] 章节顶部出现大标题（居中 + 装饰线）
- [ ] 第一段首字下沉（首字大 + 不缩进）
- [ ] 章节末尾出现 ❖，点击进入下一章
- [ ] 切章时正文有 120ms 淡入

**兼容性**
- [ ] localStorage 手动塞 V1 settings，刷新后变 V2 形状、行为正确

### 9.4 已知差异（接受，不视为 bug）

| 差异 | 原因 |
|---|---|
| Web 首字下沉有，Android 无 | Android Compose 自定义 Layout 成本高 |
| Web LXGW WenKai 真实楷体，Android KAITI 回退 serif | APK 不打包字体 |
| Web 中区域有 cursor 提示，Android 无 hover 概念 | 平台本质差异 |

### 9.5 不做

- 不为 Web 端新建单测框架
- 不做端到端视觉回归测试
- 不做 A/B 灰度
- 不做性能基准测试

## 后续步骤

本 spec 终点为「设计完成 + 用户复审通过」。下一步进入 `superpowers:writing-plans`，由该 skill 基于本 spec 生成实施 plan（任务拆解、依赖、TDD 步骤、review checkpoint）。

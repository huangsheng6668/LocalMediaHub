# 阅读器排版细节打磨设计文档（字间距 / 字体扩充 / 自定义主题色）

**日期**：2026-08-01
**范围**：Web（`server/internal/web/`）+ Android（`android/app/src/main/java/com/juziss/localmediahub/`）双端阅读器
**目标读者**：实施 agent、未来维护者
**状态**：已与用户确认，待实施

## 背景

用户体验了 PixivSource 书源项目（https://github.com/DowneyRem/PixivSource）——该项目的"好体验"实际来自其宿主 Legado（开源阅读 App）的阅读器本身。用户据此希望优化本项目的双端小说阅读体验，并选定四个方向分阶段落地：

1. **排版细节打磨**（本次）——字间距、字体扩充、自定义主题色
2. 翻页方式与动画（后续阶段）
3. 阅读交互细节（后续阶段）
4. 书架与进度管理（后续阶段）

用户确认的本次范围：**字间距调节 + 字体扩充 + 自定义主题色，Web + Android 双端同步**。

## 非目标（明确不做）

- 背景图调节（Android 模糊/透明度、Web 背景图）→ 留后续阶段
- 首字下沉 Android 补齐 → 留后续阶段
- 水平边距调节 → 留后续阶段
- 自定义字体文件导入 → 留后续阶段
- 跨端设置同步（设置存服务端）→ 不做
- 翻页动画、阅读交互细节、书架进度管理 → 后续阶段
- 不动 7 个既有主题预设的色值（除枚举新增外，THEME_PRESETS 零改动）

## 数据模型与字段规格

### 新增设置字段（双端完全一致，唯一事实来源）

| 字段 | 类型 | 默认 | 范围 | 语义 |
|------|------|------|------|------|
| `letterSpacing` | Float (em) | `0.0` | `0.0–1.0`，步进 `0.05` | 正文字间距。0 即当前行为 |
| `customBg` | String? (hex) | `null` | `#RRGGBB` | 自定义背景色 |
| `customFg` | String? (hex) | `null` | `#RRGGBB` | 自定义正文色 |
| `customMuted` | String? (hex) | `null` | `#RRGGBB` | 自定义次要色（分隔符/❖/进度文本等） |

`null` 表示"未设置过"。只有 `theme === CUSTOM` 时三色才参与渲染。

### 自定义主题模型（方案 A：独立主题）

- `theme` 枚举新增第 8 项 `CUSTOM`（label "自定义"）。其预设色为 Transparent 占位，与 AUTO 相同——由**渲染处解析**（Web `applySettingsToUI` / Android `ReaderThemeWrapper`）。
- 解析规则：
  - `AUTO` → 按系统深浅解析为 DAY/NIGHT（现有逻辑不变）
  - `CUSTOM` → 颜色取 `customBg/customFg/customMuted`；任一项为 `null` 时该项回退到系统深浅对应的 DAY/NIGHT 色
  - 其他 → 预设色（现有逻辑不变）
- 切换主题**不清空**三个 custom 值（随时可切回 CUSTOM 恢复）。
- 设置面板中 CUSTOM 的色板 swatch：三色渐变图标（类似 AUTO 的半亮半暗样式，改为 bg→fg 渐变）。
- Android 侧 `ReaderTheme.resolveAuto` 语义扩展：CUSTOM 的解析不进 `resolveAuto`（该函数保持 AUTO→DAY/NIGHT 不变），由 `ReaderThemeWrapper` 统一处理 CUSTOM 分支。

### 字体扩充方案

**Web 端**（零新增资源）：

- 现 `KAITI` 已映射本地字体 "LXGW WenKai"——设置面板标签从"楷体"改为"文楷"。
- 新增 `HEITI` "黑体"：字体栈 `'Heiti SC', 'Microsoft YaHei', sans-serif`。
- 新增 `MONO` "等宽"：字体栈 `monospace`。
- `FONT_FAMILIES` 两条新映射，`FONT_OPTIONS` 相应扩到 5 项。

**Android 端**：

- 打包**霞鹜文楷子集版 ttf** 进 `res/font/lxgw_wenkai.ttf`（LXGW WenKai GitHub release 提供子集版，约 4–6MB），让 `KAITI` 从"显示为宋体"变为真楷体，映射 `FontFamily(Font(R.font.lxgw_wenkai))`。
- 新增 `MONO` "等宽"：`FontFamily.Monospace`，零资源。
- **回退方案**：若实施第一步确认子集版 ttf 不可获取或体积仍过大（APK 膨胀不可接受），则 KAITI 保持现状（映射 Serif），仅落地 MONO 与 Web 端扩充。此决策在实施开始时确认，需明确告知用户。

### 字间距渲染

- Web：`.text-reader__p` 的 `letter-spacing` 由 CSS 变量 `--reader-letter-spacing` 注入（em 值，字号变化自动等比）。`style.css` 写 `letter-spacing: var(--reader-letter-spacing, 0em)`。
- Android：`ParagraphItem` 的 `TextStyle` 加 `letterSpacing = fontSize * settings.letterSpacing`（sp 值，按当前字号换算，避免 Compose em 引用问题）。

## Web 端改动点

| 文件 | 改动 |
|------|------|
| `readerPrefs.js` | 默认设置加 4 字段；`sanitizeSettings` 迁移：`letterSpacing` 须为 number 且 clamp 0–1，三色须匹配 `/^#[0-9a-fA-F]{6}$/` 否则丢弃；`FONT_FAMILIES` 加 `HEITI`/`MONO` |
| `reader-settings.js` | `THEME_OPTIONS` 加 `['CUSTOM', '自定义']`（三色渐变 swatch）；`FONT_OPTIONS` 加黑体/等宽，KAITI 标签改"文楷"；新增"字间距" slider 行（min 0 max 1 step 0.05，显示 ×.×× em）；新增"自定义颜色"区（theme=CUSTOM 时显示 3 个 `<input type="color">` + hex 文本）；`syncControlsFromSettings` / `onChange` 相应扩展 |
| `textReader.js` | `applySettingsToUI`：`theme === 'CUSTOM'` 分支用三色注入 CSS 变量（null 回退跟随系统深浅的 DAY/NIGHT）；新增 `setVar('--reader-letter-spacing', s.letterSpacing + 'em')` |
| `style.css` | `.text-reader__p` 应用 `letter-spacing: var(--reader-letter-spacing, 0em)` |

## Android 端改动点

| 文件 | 改动 |
|------|------|
| `ReaderSettings.kt` | 4 个新字段（默认 `0f`/`null`——Gson 反序列化旧数据缺字段回退到 0/null，恰好等于默认值，**无需迁移代码**）；`ReaderTheme.CUSTOM` 枚举（Transparent 占位 + label "自定义"） |
| `ReaderThemeWrapper.kt` | 解析 CUSTOM：三色取 settings，null 回退 DAY/NIGHT（跟随系统深浅）。**签名变化**：`ReaderThemeScope` 当前签名 `(theme, bgImageUri)`（ReaderThemeWrapper.kt:28），需新增参数接收 custom 三色（如 `customColors: CustomThemeColors?`，调用处 TextReaderScreen 传 settings 三色） |
| `ReaderFontFamily.kt` | 加 `MONO`；打包文楷后 `KAITI` 映射 `FontFamily(Font(R.font.lxgw_wenkai))`（按 1.3 回退方案决策） |
| `ReaderSettingsSheet.kt` | 字间距 slider（0–1.0 step 0.05）；字体 chips 自动含新枚举项；theme=CUSTOM 时显示"自定义颜色"区：三个色板网格（各 12 色预设）+ hex 文本输入 |
| `TextReaderScreen.kt` | `ParagraphItem`（约 931 行处）TextStyle 加 `letterSpacing`（按 fontSize 换算 sp） |
| 资源 | `res/font/lxgw_wenkai.ttf` + `res/font/lxgw_wenkai.xml`（fontFamily 声明）；实施第一步先确认子集版可获取、体积可接受 |
| ViewModel / RecentActivityStore | **零改动**（Gson 自动序列化新字段） |

## 测试计划

**Web**（node:test + jsdom，沿用现有 `*.test.mjs` 模式）：

- `readerPrefs`：新字段默认值；旧数据迁移（缺字段 → 默认）；非法值丢弃/clamp
- 设置面板：新控件存在性（快照模式）；CUSTOM 选中时自定义颜色区显隐联动
- `textReader` 集成：CUSTOM 主题下 CSS 变量注入正确；`--reader-letter-spacing` 注入

**Android**（Robolectric）：

- `ReaderSettingsSheetContent`：字间距 slider 存在与变更回调；CUSTOM 区显隐联动
- `ReaderTheme.CUSTOM` 解析单测：三色组合、null 回退
- 设置保存→读取回环：新字段保真

## 双端对齐校验清单（实施完成时人工核对）

- [ ] `letterSpacing` 默认 0、范围 0–1.0、步进 0.05，双端一致
- [ ] `customBg/customFg/customMuted` 默认 null、格式 `#RRGGBB`，双端一致
- [ ] CUSTOM 解析规则（null 回退 DAY/NIGHT）双端一致
- [ ] 字体选项：SYSTEM/SERIF/KAITI（文楷）/HEITI/MONO 双端语义一致
- [ ] 切换主题不清空 custom 三色，双端一致
- [ ] 旧数据（无新字段）在双端均以默认值工作，无崩溃、无设置丢失

## 风险与回退

1. **文楷 ttf 体积/获取**：子集版不可获取或过大 → 回退为仅 MONO（见字体方案），实施开始时确认并告知用户。
2. **Gson 旧数据**：缺字段回退 0/null 即默认值，无风险（已在字段表注明）。
3. **Web sanitize 竞态**：旧 localStorage 数据经 `sanitizeSettings` 补齐默认值，逻辑与现有字段一致，沿用现有测试模式覆盖。

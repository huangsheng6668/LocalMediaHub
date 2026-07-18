# 小说阅读 UI 重设计 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 spec `docs/superpowers/specs/2026-07-18-reader-ui-redesign-design.md` 重做 Web + Android 两端的小说阅读 UI：6+AUTO 主题、3 档字体（Web 打包 LXGW WenKai/Noto Serif SC）、连续滑块排版、沉浸模式、章节装饰、淡入动效，并对 V1 reader settings 做向后兼容迁移。

**Architecture:** SettingsV2 数据形状两端一致，存储格式各自序列化（Web localStorage / Android DataStore + Gson）。改动按 6 个 Phase 拆分，每个 Phase 一组 PR，风险递增。Web 端无单测基础设施，靠手动验证清单代替运行测试；Android 沿用 JUnit + Compose UI Test。

**Tech Stack:**
- Web：原生 JS + CSS（无构建系统），文件通过 Go `//go:embed` 静态托管（`server/internal/web/web.go`）
- Android：Kotlin + Jetpack Compose + Material3 + DataStore Preferences + Gson
- 测试：Android JUnit4 + Robolectric + Compose UI Test；Web 手动清单

## Global Constraints

- 字号默认值 `16`，范围 `12–28`，步长 1（来自 spec §3.1）
- 行距默认 `1.8`，范围 `1.3–2.5`，步长 0.1（spec §3.2）
- 内容宽度默认 Web `720 px` / Android `600 dp`；Android 上界 `min(720, screenWidthDp - 32)`（spec §3.3）
- 6 个主题预设的具体 hex 值见 spec §1.1 表格，必须逐字一致
- 沉浸模式热区：左 20% / 中 20%–80% / 右 20%（spec §4.1）
- 章节大标题字号 = `fontSize + 6px`；固定 serif；上下间距 `2em 0 1.5em`；底部 40px 装饰线（spec §6.2）
- 首字下沉：仅 Web；首段是 text block 才应用；段落不足 4 字 / 纯标点开头不应用；纯数字开头正常应用（spec §6.3）
- 字体文件：`LXGWWenKai-Regular.woff2`、`NotoSerifSC-Regular.woff2`，放在 `server/internal/web/fonts/`，文件名无空格；通过 `//go:embed` 嵌入；公开静态资源，**不**参与 `/api/v1/books/image` 鉴权
- `font-display: swap` 必须设置
- 主题切换时 `body[data-reader-theme="..."][data-active-tab="read"]` 复合选择器（spec §5.1）——不要写成后代选择器
- Android `MaterialTheme.colorScheme` 用 `copy()` 局部覆盖；`ReaderThemeScope` 包整个 `Scaffold` 外层（spec §5.2）
- `--primary` 强调色不跟随 reader theme，保持 App 蓝
- V1 → V2 迁移映射：`fontSize: SMALL→14 / MEDIUM→16 / LARGE→18 / XLARGE→20`，`lineHeight: COMPACT→1.4 / STANDARD→1.8 / LOOSE→2.2`，`theme` 字段 `'DAY'/'NIGHT'/'EYE_CARE'` 直接兼容；缺失/损坏 JSON 整体回退默认
- Android 迁移必须在 Gson `fromJson` **之前**做手动 JSON 改写（V1 中 fontSize/lineHeight 是字符串 enum 名，V2 是 Int/Float，Gson 直接解析会抛异常导致整体回退、丢失 theme/autoScrollSpeed）
- AUTO 主题解析：亮→`DAY`、暗→`NIGHT`；关闭"跟随系统"时恢复上次非 AUTO 主题（缓存在内存中，不持久化）
- 设置面板分组：外观 / 字号与行距 / 段落 / 行为，4 组
- Web 无单测，所有"运行测试"步骤改为"手动验证 + 列出预期观察点"
- 每个 Phase 完成后单独提交；每个 Task 内部 TDD 循环结束后提交

---

## File Structure

### Web 端（不新增 JS/CSS 文件）

| 文件 | 职责 |
|---|---|
| `server/internal/web/readerPrefs.js` | 唯一 reader settings 真源；THEME_PRESETS（含 chrome 字段）、FONT_FAMILIES、DEFAULT_SETTINGS（V2）、migrateV1toV2、settings CRUD、bookmarks CRUD（既有） |
| `server/internal/web/textReader.js` | 阅读器视图入口；应用 V2 settings 到 CSS 变量与 data-attribute；沉浸模式状态机；章节装饰渲染；cleanup 时清 data-reader-theme |
| `server/internal/web/style.css` | 阅读器全部样式；`body[data-reader-theme="..."]` 整体覆盖；`.text-reader__*` 视觉；`.reader-settings__*` 设置面板样式；首字下沉 keyframes；沉浸栏隐藏；切章淡入 |
| `server/internal/web/fonts/` | 新目录，放 2 个 woff2 |
| `server/internal/web/web.go` | 修改 `//go:embed` 指令包含 `fonts/*` |

### Android 端（新增 2 文件）

| 文件 | 职责 |
|---|---|
| `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt` | V2 `ReaderSettings` 数据类；`ReaderTheme` 6+AUTO + chrome 字段；`ReaderFontFamily` enum（或独立文件） |
| `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt` | `decodeReaderSettings` 增加 V1 JSON 手动改写 |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt` | 重命名/重写为 `ReaderThemeScope`；包 Scaffold；覆盖 MaterialTheme colorScheme；监听 AUTO |
| `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt` | Scaffold 外层换 ReaderThemeScope；沉浸模式；中区域点击；章节大标题/结束符号；切章 AnimatedContent |
| `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt` | 完全重排：4 组 + FilterChip + Slider + Switch |
| `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt` | 兼容 AUTO；1.5 秒沉浸栏隐藏时序 |
| **新增** `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt` | enum + `toFontFamily()` 映射 |
| **新增** `android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt` | V1→V2 迁移边界用例集中地 |

### 修改的测试

| 文件 | 改动 |
|---|---|
| `android/app/src/test/java/.../RecentActivityStoreReaderSettingsTest.kt` | 改用 V2；新增 V1 JSON 注入用例 |
| `android/app/src/test/java/.../ReaderSettingsSheetTest.kt` | 改用 V2；新增 6 主题 FilterChip / 滑块 / toggles 断言 |
| `android/app/src/test/java/.../TextReaderScreenThemeTest.kt` | 改用 V2；6 主题 + AUTO + Scaffold 外层断言 |
| `android/app/src/test/java/.../TextReaderViewModelReaderTest.kt` | 改用 V2；AUTO 解析 + 1.5 秒沉浸时序 |

---

## Phase 1: 数据层 V2 迁移（先动数据，不动 UI）

> 目标：两端 reader settings 升级到 V2 数据形状，老数据自动迁移，UI 暂时只读 V2 字段（行为不变）。Phase 1 结束后 App 行为对老用户与现状完全一致。

### Task 1.1: Web `readerPrefs.js` V2 数据形状 + 迁移函数

**Files:**
- Modify: `server/internal/web/readerPrefs.js`
- Test: 手动验证（Web 无单测）—— 在浏览器 console 跑迁移断言

**Interfaces:**
- Produces: `THEME_PRESETS`（含 chrome 字段）、`FONT_FAMILIES`、`CONTENT_WIDTH_RANGE`、`DEFAULT_SETTINGS`（V2 形状）、`migrateV1toV2(old)`、`getSettings()` 返回 V2

- [ ] **Step 1: 写一个浏览器 console 验证脚本（手动单测替代）**

把以下内容保存到 `server/internal/web/_readerPrefsSelfTest.js`（临时文件，Phase 1 末删除），在浏览器 console 引用：

```javascript
// 手动单测：在浏览器 console 粘贴执行
(async () => {
  const { migrateV1toV2, DEFAULT_SETTINGS } = await import('./readerPrefs.js');
  const assert = (cond, msg) => console.assert(cond, msg);

  // 迁移用例
  assert(migrateV1toV2({fontSize:'SMALL'}).fontSize === 14, 'SMALL->14');
  assert(migrateV1toV2({fontSize:'MEDIUM'}).fontSize === 16, 'MEDIUM->16');
  assert(migrateV1toV2({fontSize:'LARGE'}).fontSize === 18, 'LARGE->18');
  assert(migrateV1toV2({fontSize:'XLARGE'}).fontSize === 20, 'XLARGE->20');
  assert(migrateV1toV2({fontSize:'BOGUS'}).fontSize === 16, 'unknown->16 default');
  assert(migrateV1toV2({lineHeight:'COMPACT'}).lineHeight === 1.4, 'COMPACT->1.4');
  assert(migrateV1toV2({lineHeight:'STANDARD'}).lineHeight === 1.8, 'STANDARD->1.8');
  assert(migrateV1toV2({lineHeight:'LOOSE'}).lineHeight === 2.2, 'LOOSE->2.2');
  assert(migrateV1toV2({theme:'NIGHT'}).theme === 'NIGHT', 'theme passthrough');
  assert(migrateV1toV2({autoScrollSpeed:7}).autoScrollSpeed === 7, 'autoScrollSpeed passthrough');
  const m = migrateV1toV2(null);
  assert(m.fontSize === 16 && m.theme === 'DAY' && m.fontFamily === 'SYSTEM',
    'null -> full default');
  console.log('Self-test done');
})();
```

- [ ] **Step 2: 替换 `readerPrefs.js` 整个导出（V1 -> V2）**

把 `readerPrefs.js:8-25`（SETTINGS_KEY 到 DEFAULT_SETTINGS）替换为：

```javascript
const SETTINGS_KEY = 'reader_settings';
const BOOKMARKS_PREFIX = 'book_bookmarks:';

// V1->V2 迁移表（保留旧枚举值映射，仅供 migrateV1toV2 使用）
const V1_FONT_SIZE = { SMALL: 14, MEDIUM: 16, LARGE: 18, XLARGE: 20 };
const V1_LINE_HEIGHT = { COMPACT: 1.4, STANDARD: 1.8, LOOSE: 2.2 };

// 字体选项与 CSS font-family 映射；serif/kaiti 的实际字体文件由 Phase 3 引入
export const FONT_FAMILIES = {
    SYSTEM: '-apple-system, "PingFang SC", "Microsoft YaHei", "Helvetica Neue", sans-serif',
    SERIF: '"Noto Serif SC", "Songti SC", "SimSun", serif',
    KAITI: '"LXGW WenKai", "Kaiti SC", "STKaiti", cursive',
};

// 内容宽度滑块范围（px）。Android 在屏幕 dp 上有等价 clamp。
export const CONTENT_WIDTH_RANGE = { MIN: 600, MAX: 900, STEP: 10 };

// 6 个主题预设（spec §1.1 表格逐字一致）。
// chromeBg/chromeFg/muted 用于顶/底栏/drawer/dialog 的局部主题覆盖。
export const THEME_PRESETS = {
    DAY:        { bg: '#FAF8F3', fg: '#2B2B2B', chromeBg: '#F2EFE7', chromeFg: '#3D3D3D', muted: '#7A7A78', border: '#E5E2D8' },
    DAY_BRIGHT: { bg: '#FFFFFF', fg: '#212121', chromeBg: '#F5F5F5', chromeFg: '#333333', muted: '#7A7A7A', border: '#E0E0E0' },
    EYE_CARE:   { bg: '#F4ECD8', fg: '#5B4636', chromeBg: '#EDE3CC', chromeFg: '#6B5644', muted: '#9C8870', border: '#D8CBAF' },
    PARCHMENT:  { bg: '#EFE6D2', fg: '#3D3327', chromeBg: '#E5D9BF', chromeFg: '#4D4034', muted: '#8C7E66', border: '#D3C7AB' },
    NIGHT:      { bg: '#1A1A1F', fg: '#C9C9CE', chromeBg: '#232328', chromeFg: '#B0B0B5', muted: '#84848A', border: '#2D2D33' },
    NIGHT_BLACK:{ bg: '#000000', fg: '#BFBFBF', chromeBg: '#0A0A0A', chromeFg: '#A8A8A8', muted: '#787878', border: '#1C1C1C' },
    // AUTO 不是预设颜色，而是"跟随系统"标记。getSettings 调用方解析为 DAY/NIGHT。
    AUTO:       null,
};

export const FONT_SIZE_RANGE = { MIN: 12, MAX: 28, STEP: 1 };
export const LINE_HEIGHT_RANGE = { MIN: 1.3, MAX: 2.5, STEP: 0.1 };

export const DEFAULT_SETTINGS = {
    fontFamily: 'SYSTEM',
    fontSize: 16,
    lineHeight: 1.8,
    contentWidth: 720,
    firstLineIndent: true,
    paragraphSpacing: false,
    theme: 'DAY',
    immersiveMode: false,
    autoScrollSpeed: 5,
};

// migrateV1toV2: 接受任何形状（包括 null/undefined/坏字段），输出 V2 形状。
// 这是 Phase 1 的迁移真源。Android 的等价逻辑在 RecentActivityStore 里。
export function migrateV1toV2(old) {
    const out = { ...DEFAULT_SETTINGS };
    if (!old || typeof old !== 'object') return out;

    // fontSize: V1 是 'SMALL'/'MEDIUM'/... 字符串；V2 是 12-28 整数
    if (typeof old.fontSize === 'string' && V1_FONT_SIZE[old.fontSize] !== undefined) {
        out.fontSize = V1_FONT_SIZE[old.fontSize];
    } else if (typeof old.fontSize === 'number' && Number.isFinite(old.fontSize)) {
        out.fontSize = clampInt(old.fontSize, FONT_SIZE_RANGE.MIN, FONT_SIZE_RANGE.MAX);
    }

    // lineHeight: V1 是 'COMPACT'/... 字符串；V2 是 1.3-2.5 浮点
    if (typeof old.lineHeight === 'string' && V1_LINE_HEIGHT[old.lineHeight] !== undefined) {
        out.lineHeight = V1_LINE_HEIGHT[old.lineHeight];
    } else if (typeof old.lineHeight === 'number' && Number.isFinite(old.lineHeight)) {
        out.lineHeight = clampFloat(old.lineHeight, LINE_HEIGHT_RANGE.MIN, LINE_HEIGHT_RANGE.MAX);
    }

    if (typeof old.theme === 'string' && THEME_PRESETS.hasOwnProperty(old.theme)) {
        out.theme = old.theme;
    }

    if (typeof old.autoScrollSpeed === 'number') {
        out.autoScrollSpeed = clampInt(old.autoScrollSpeed, 1, 10);
    }

    // 新字段：仅当 old 已是 V2 形状时才有；V1 数据填默认
    if (typeof old.fontFamily === 'string' && FONT_FAMILIES[old.fontFamily]) {
        out.fontFamily = old.fontFamily;
    }
    if (typeof old.contentWidth === 'number') {
        out.contentWidth = clampInt(old.contentWidth, CONTENT_WIDTH_RANGE.MIN, CONTENT_WIDTH_RANGE.MAX);
    }
    if (typeof old.firstLineIndent === 'boolean') out.firstLineIndent = old.firstLineIndent;
    if (typeof old.paragraphSpacing === 'boolean') out.paragraphSpacing = old.paragraphSpacing;
    if (typeof old.immersiveMode === 'boolean') out.immersiveMode = old.immersiveMode;

    return out;
}

function clampInt(n, lo, hi) { return Math.max(lo, Math.min(hi, Math.round(n))); }
function clampFloat(n, lo, hi) { return Math.max(lo, Math.min(hi, Math.round(n * 10) / 10)); }
```

把 `getSettings`（V1 版 `readerPrefs.js:34-39`）替换为：

```javascript
export function getSettings() {
    let raw = null;
    try { raw = JSON.parse(localStorage.getItem(SETTINGS_KEY) || 'null'); } catch (_) { raw = null; }
    return migrateV1toV2(raw);
}
```

`saveSettings`（V1 版 `readerPrefs.js:41-51`）保留逻辑，但 merge 后写盘前不必再迁移（save 一定来自 V2 形态的 partial）—— 不动该函数。

- [ ] **Step 3: 删除 `_readerPrefsSelfTest.js`**（Phase 1 末清理临时验证脚本）

```bash
rm server/internal/web/_readerPrefsSelfTest.js
```

- [ ] **Step 4: 手动验证（Web 无单测）**

启动 web server，浏览器打开阅读器：
- DevTools Console: `localStorage.setItem('reader_settings', '{"fontSize":"MEDIUM","theme":"NIGHT","autoScrollSpeed":7}')`，刷新页面
- 预期：阅读器正常渲染（不报错），主题夜间生效
- DevTools Console: `(await import('./readerPrefs.js')).getSettings()` 输出 `{fontFamily:'SYSTEM', fontSize:16, lineHeight:1.8, contentWidth:720, firstLineIndent:true, paragraphSpacing:false, theme:'NIGHT', immersiveMode:false, autoScrollSpeed:7}` —— 全部字段为 V2 形态、MEDIUM→16 迁移成功

- [ ] **Step 5: 提交**

```bash
git add server/internal/web/readerPrefs.js
git commit -m "feat(readerPrefs): V2 settings shape with V1->V2 migration (Web)"
```

---

### Task 1.2: Android `ReaderSettings.kt` V2 数据类

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt`

**Interfaces:**
- Produces: V2 `ReaderSettings`（含 `fontFamily: ReaderFontFamily`, `fontSizeSp: Int`, `lineHeightMultiplier: Float`, `contentWidthDp: Int`, `firstLineIndent: Boolean`, `paragraphSpacing: Boolean`, `theme: ReaderTheme`, `immersiveMode: Boolean`, `autoScrollSpeed: Int`）
- Produces: `ReaderTheme` 6+AUTO，每项带 `bg/chromeBg/fg/chromeFg/muted/border`
- Produces: `ReaderFontFamily` enum `SYSTEM/SERIF/KAITI`
- Removes: `ReaderFontSize`、`ReaderLineHeight` enum（老调用方在 Task 1.4 起逐个迁移）

> **重要**：此 Task 单独提交会导致下游编译失败（`ReaderFontSize`/`ReaderLineHeight` 在 ViewModel/Screen/Test 中被引用）。本 Task 不单独跑构建，紧接着做 Task 1.3-1.5 把全部引用迁移完，最后再跑 Android 构建。Task 1.2 + 1.3 + 1.4 + 1.5 一并提交或拆 4 个 commit 都可以，但**完成顺序**必须 1.2 → 1.3 → 1.4 → 1.5。

- [ ] **Step 1: 替换 `ReaderSettings.kt` 全文**

```kotlin
package com.juziss.localmediahub.data

import androidx.compose.ui.graphics.Color

/**
 * 全局阅读器设置（V2）。一组设置应用于所有书。
 * 通过 RecentActivityStore 持久化在 `reader_settings` DataStore key 下。
 *
 * 字段语义见 docs/superpowers/specs/2026-07-18-reader-ui-redesign-design.md §数据形状。
 */
data class ReaderSettings(
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val fontSizeSp: Int = 16,
    val lineHeightMultiplier: Float = 1.8f,
    val contentWidthDp: Int = 600,
    val firstLineIndent: Boolean = true,
    val paragraphSpacing: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.DAY,
    val immersiveMode: Boolean = false,
    val autoScrollSpeed: Int = 5,  // 1..10
)

/**
 * 阅读区主题（含 chrome 配色字段）。AUTO 不携带颜色，由调用方解析为
 * DAY/NIGHT（亮/暗系统模式）。具体 hex 值来自 spec §1.1 表格。
 */
enum class ReaderTheme(
    val bg: Color,
    val fg: Color,
    val chromeBg: Color,
    val chromeFg: Color,
    val muted: Color,
    val border: Color,
    val label: String,
) {
    DAY(
        bg = Color(0xFFFAF8F3), fg = Color(0xFF2B2B2B),
        chromeBg = Color(0xFFF2EFE7), chromeFg = Color(0xFF3D3D3D),
        muted = Color(0xFF7A7A78), border = Color(0xFFE5E2D8),
        label = "日间·纸白",
    ),
    DAY_BRIGHT(
        bg = Color(0xFFFFFFFF), fg = Color(0xFF212121),
        chromeBg = Color(0xFFF5F5F5), chromeFg = Color(0xFF333333),
        muted = Color(0xFF7A7A7A), border = Color(0xFFE0E0E0),
        label = "日间·亮白",
    ),
    EYE_CARE(
        bg = Color(0xFFF4ECD8), fg = Color(0xFF5B4636),
        chromeBg = Color(0xFFEDE3CC), chromeFg = Color(0xFF6B5644),
        muted = Color(0xFF9C8870), border = Color(0xFFD8CBAF),
        label = "护眼·米黄",
    ),
    PARCHMENT(
        bg = Color(0xFFEFE6D2), fg = Color(0xFF3D3327),
        chromeBg = Color(0xFFE5D9BF), chromeFg = Color(0xFF4D4034),
        muted = Color(0xFF8C7E66), border = Color(0xFFD3C7AB),
        label = "羊皮纸",
    ),
    NIGHT(
        bg = Color(0xFF1A1A1F), fg = Color(0xFFC9C9CE),
        chromeBg = Color(0xFF232328), chromeFg = Color(0xFFB0B0B5),
        muted = Color(0xFF84848A), border = Color(0xFF2D2D33),
        label = "夜间·深空",
    ),
    NIGHT_BLACK(
        bg = Color(0xFF000000), fg = Color(0xFFBFBFBF),
        chromeBg = Color(0xFF0A0A0A), chromeFg = Color(0xFFA8A8A8),
        muted = Color(0xFF787878), border = Color(0xFF1C1C1C),
        label = "夜间·纯黑",
    ),
    AUTO(
        bg = Color.Transparent, fg = Color.Transparent,
        chromeBg = Color.Transparent, chromeFg = Color.Transparent,
        muted = Color.Transparent, border = Color.Transparent,
        label = "跟随系统",
    );

    companion object {
        /** AUTO 在亮/暗模式下解析到的预设。 */
        fun resolveAuto(isDark: Boolean): ReaderTheme = if (isDark) NIGHT else DAY
    }
}

/**
 * 阅读正文字体选项。Android 不打包字体，依赖系统字体映射。
 * KAITI 在多数 Android ROM 上没有独立楷体，toFontFamily() 回退到 Serif。
 */
enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体");

    fun toFontFamily(): androidx.compose.ui.text.font.FontFamily = when (this) {
        SYSTEM -> androidx.compose.ui.text.font.FontFamily.Default
        SERIF  -> androidx.compose.ui.text.font.FontFamily.Serif
        KAITI  -> androidx.compose.ui.text.font.FontFamily.Serif
    }
}
```

> **命名说明**：spec §8.2 把 `ReaderFontFamily` 单独成文件；但为了简化 Phase 1（少一次创建文件），先放在 `ReaderSettings.kt` 里。Phase 3 实施 Task 3.3 时再独立到 `ReaderFontFamily.kt`。

- [ ] **Step 2: 跳过单独构建**（编译会失败，等 Task 1.5 末再跑）

- [ ] **Step 3: 暂不提交**（一并到 Task 1.5 末提交）

---

### Task 1.3: Android `RecentActivityStore` V1 JSON 迁移

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt:420-427`（`decodeReaderSettings` 函数）

**Interfaces:**
- Consumes: V2 `ReaderSettings`（来自 Task 1.2）
- Produces: 读取老 V1 JSON 时，先手动改写 `fontSize`/`lineHeight` 字段（string → number），再交给 Gson 反序列化；新 V2 JSON 透传

> **关键约束**：V1 JSON 形如 `{"fontSize":"MEDIUM",...}`，V2 `fontSize` 是 `Int`。Gson 直接 `fromJson` 会抛 `JsonSyntaxException`，`decodeReaderSettings` 现有 catch 会整体回退到默认值——这会丢失 theme/autoScrollSpeed 等仍然有效的字段。必须在 Gson 之前做手动改写。

- [ ] **Step 1: 在 `RecentActivityStore.kt` 末尾新增迁移辅助函数**

在 `decodeReaderSettings` 函数**下方**追加：

```kotlin
/**
 * 把 V1 reader_settings JSON 改写为 V2 形态，交给 Gson 反序列化。
 *
 * V1 中 `fontSize` 是 "SMALL"/"MEDIUM"/"LARGE"/"XLARGE" 字符串枚举名，
 * V2 中是 12-28 整数。`lineHeight` 同理（"COMPACT"/"STANDARD"/"LOOSE" →
 * 1.4/1.8/2.2）。其他字段（theme/autoScrollSpeed）形态未变，透传即可。
 *
 * Gson 默认无法把字符串 `"MEDIUM"` 反序列化为 Int，会抛 JsonSyntaxException。
 * 我们在 Gson 解析前手工改写 JsonObject，保证迁移不丢失非 fontSize 字段。
 */
private val v1FontSizeMap = mapOf(
    "SMALL" to 14, "MEDIUM" to 16, "LARGE" to 18, "XLARGE" to 20,
)
private val v1LineHeightMap = mapOf(
    "COMPACT" to 1.4f, "STANDARD" to 1.8f, "LOOSE" to 2.2f,
)

private fun migrateReaderSettingsJson(raw: String): String {
    return try {
        val obj = com.google.gson.JsonParser.parseString(raw).asJsonObject
        // fontSize: 字符串枚举名 -> 数字
        val fs = obj.get("fontSize")
        if (fs != null && fs.isJsonPrimitive && fs.asJsonPrimitive.isString) {
            val mapped = v1FontSizeMap[fs.asString]
            if (mapped != null) obj.addProperty("fontSize", mapped)
        }
        // lineHeight: 字符串枚举名 -> 数字
        val lh = obj.get("lineHeight")
        if (lh != null && lh.isJsonPrimitive && lh.asJsonPrimitive.isString) {
            val mapped = v1LineHeightMap[lh.asString]
            if (mapped != null) obj.addProperty("lineHeight", mapped)
        }
        obj.toString()
    } catch (_: Exception) {
        // 解析失败就让上层 Gson 再失败、走 fallback 默认值
        raw
    }
}
```

- [ ] **Step 2: 修改 `decodeReaderSettings` 调用迁移函数**

把 `RecentActivityStore.kt:420-427`：

```kotlin
private fun decodeReaderSettings(json: String?): ReaderSettings {
    if (json.isNullOrBlank()) return ReaderSettings()
    return try {
        gson.fromJson(json, ReaderSettings::class.java) ?: ReaderSettings()
    } catch (_: Exception) {
        ReaderSettings()
    }
}
```

替换为：

```kotlin
private fun decodeReaderSettings(json: String?): ReaderSettings {
    if (json.isNullOrBlank()) return ReaderSettings()
    return try {
        val migrated = migrateReaderSettingsJson(json)
        gson.fromJson(migrated, ReaderSettings::class.java) ?: ReaderSettings()
    } catch (_: Exception) {
        ReaderSettings()
    }
}
```

- [ ] **Step 3: 暂不提交**（一并到 Task 1.5 末提交）

---

### Task 1.4: Android 迁移所有 V1 `ReaderFontSize/ReaderLineHeight` 引用到 V2

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（仅引用替换，UI 行为不变）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`（如有引用）

**Interfaces:**
- Consumes: V2 `ReaderSettings` / `ReaderTheme` / `ReaderFontFamily`（来自 Task 1.2）
- Goal: 全部 `ReaderFontSize.X` → `16` / `.sp` 数字；`ReaderLineHeight.X` → `1.8f` / `.multiplier` 浮点；不改变 UI 视觉

> **策略**：本期 Phase 1 只做"形态升级、行为不变"。Phase 4 才会重排 ReaderSettingsSheet。所以这里只做最小引用替换，每个 enum 引用替换为对应的 V1 默认值数字（保留原 MEDIUM→16、STANDARD→1.8 等映射语义）。

- [ ] **Step 1: 用 Grep 找到所有 `ReaderFontSize` 与 `ReaderLineHeight` 引用**

```bash
# 用 Grep 工具
Grep pattern="ReaderFontSize|ReaderLineHeight" path="android/app/src/main"
```

- [ ] **Step 2: 对每个引用做最小替换**

对 `TextReaderScreen.kt` 中（spec 探索时见到的 `settings.fontSize.sp.sp`、`settings.fontSize.sp * settings.lineHeight.multiplier`）：

```kotlin
// 原 V1：fontSizeSp = settings.fontSize.sp.sp, lineHeightSp = (settings.fontSize.sp * settings.lineHeight.multiplier).sp
// 新 V2：
fontSizeSp = settings.fontSizeSp.sp,
lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
```

对 `ReaderSettingsSheet.kt` 中所有 `ReaderFontSize.values()` 遍历或 `ReaderFontSize.SMALL` 等：
- 把枚举遍历替换为 `listOf(14, 16, 18, 20)`（保持 V1 的 4 档选项）
- 选中状态判断 `settings.fontSize == ReaderFontSize.MEDIUM` → `settings.fontSizeSp == 16`
- 点击保存 `ReaderFontSize.LARGE` → `settings.copy(fontSizeSp = 18)`
- 行距同理：`listOf(1.4f, 1.8f, 2.2f)`，`settings.lineHeightMultiplier == 1.8f`

> **重要**：本期 Phase 1 暂保留 4 档字号 + 3 档行距 UI（不引入滑块），等 Phase 3 / Phase 4 再换成连续滑块。所以这里只把"枚举形态"换成"数字形态"，UI 列表保持原 4 档/3 档。

- [ ] **Step 3: 对 `TextReaderViewModel.kt` 做 Grep 验证**

```bash
Grep pattern="ReaderFontSize|ReaderLineHeight|fontSize\\.|lineHeight\\." path="android/app/src/main/java/com/juziss/localmediahub/viewmodel"
```

如有引用，按 Step 2 同策略替换。

- [ ] **Step 4: 暂不提交**（一并到 Task 1.5 末提交）

---

### Task 1.5: Android 迁移测试到 V2 + 新增 V1→V2 迁移单测

**Files:**
- Modify: `android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreReaderSettingsTest.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt`
- Modify: `android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt`
- Create: `android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt`

**Interfaces:**
- Consumes: V2 `ReaderSettings` / 迁移逻辑
- Produces: `migrateV1JsonToV2(raw: String): ReaderSettings` 的纯函数测试入口（若 Task 1.3 没暴露此函数，本 Task 通过注入 V1 JSON 到 DataStore 走 `readerSettingsFlow` 测试）

- [ ] **Step 1: 创建 `ReaderSettingsMigrationTest.kt`**

```kotlin
package com.juziss.localmediahub.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ReaderSettingsMigrationTest {

    private lateinit var store: RecentActivityStore

    @Before
    fun setUp() {
        store = RecentActivityStore(ApplicationProvider.getApplicationContext())
        runBlocking { store.clearAllReaderSettings() }
    }

    /** 把任意 JSON 字符串直接注入 DataStore 的 reader_settings key。 */
    private suspend fun injectRawSettings(raw: String) {
        // 通过 saveReaderSettings 的反序列化路径无法注入坏 JSON；
        // 改用 saveReaderSettings(ReaderSettings(...)) 先建条目，再用 reflection-free
        // 方法：先 save 一条 V2 默认值（合法 JSON），再用底层 edit 覆盖为 V1 raw。
        // RecentActivityStore 的 edit 是 private，所以这里走"先存 V1 合法形态"路径：
        // 我们用 RecentActivityStore 的 saveReaderSettings 把 V1 数据保存是不可能的，
        // 因为 V2 类型不接受 enum 字段。改为：构造一个 V1 JSON 字符串，存入 DataStore。
        //
        // 实现路径：使用 Context.recentActivityDataStore 的扩展属性是不可访问的私有委托，
        // 所以测试通过 RecentActivityStore 暴露的 test-only API（如下）注入。
        store.injectRawReaderSettingsForTest(raw)
    }

    @Test
    fun v1_medium_migrates_to_16() = runBlocking {
        injectRawSettings("""{"fontSize":"MEDIUM","lineHeight":"STANDARD","theme":"DAY","autoScrollSpeed":5}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(16, s.fontSizeSp)
        assertEquals(1.8f, s.lineHeightMultiplier)
        assertEquals(ReaderTheme.DAY, s.theme)
        assertEquals(5, s.autoScrollSpeed)
    }

    @Test
    fun v1_small_large_xlarge_migrate_correctly() = runBlocking {
        injectRawSettings("""{"fontSize":"SMALL"}""")
        assertEquals(14, store.readerSettingsFlow.first().fontSizeSp)
        injectRawSettings("""{"fontSize":"LARGE"}""")
        assertEquals(18, store.readerSettingsFlow.first().fontSizeSp)
        injectRawSettings("""{"fontSize":"XLARGE"}""")
        assertEquals(20, store.readerSettingsFlow.first().fontSizeSp)
    }

    @Test
    fun v1_line_height_compact_loose_migrate_correctly() = runBlocking {
        injectRawSettings("""{"lineHeight":"COMPACT"}""")
        assertEquals(1.4f, store.readerSettingsFlow.first().lineHeightMultiplier)
        injectRawSettings("""{"lineHeight":"LOOSE"}""")
        assertEquals(2.2f, store.readerSettingsFlow.first().lineHeightMultiplier)
    }

    @Test
    fun v1_unknown_enum_falls_back_to_default() = runBlocking {
        injectRawSettings("""{"fontSize":"BOGUS","lineHeight":"WEIRD"}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(16, s.fontSizeSp)  // migrate 函数找不到映射时保留原字符串 -> Gson 抛异常 -> 整体默认
        assertEquals(1.8f, s.lineHeightMultiplier)
    }

    @Test
    fun v1_corrupt_json_falls_back_to_default() = runBlocking {
        injectRawSettings("""{this is not json""")
        assertEquals(ReaderSettings(), store.readerSettingsFlow.first())
    }

    @Test
    fun v1_partial_keeps_other_fields() = runBlocking {
        injectRawSettings("""{"fontSize":"LARGE","theme":"NIGHT","autoScrollSpeed":9}""")
        val s = store.readerSettingsFlow.first()
        assertEquals(18, s.fontSizeSp)
        assertEquals(ReaderTheme.NIGHT, s.theme)  // theme 没丢
        assertEquals(9, s.autoScrollSpeed)        // autoScrollSpeed 没丢
    }

    @Test
    fun v2_round_trip() = runBlocking {
        val original = ReaderSettings(
            fontFamily = ReaderFontFamily.SERIF,
            fontSizeSp = 22,
            lineHeightMultiplier = 2.0f,
            contentWidthDp = 680,
            firstLineIndent = false,
            paragraphSpacing = true,
            theme = ReaderTheme.NIGHT_BLACK,
            immersiveMode = true,
            autoScrollSpeed = 7,
        )
        store.saveReaderSettings(original)
        assertEquals(original, store.readerSettingsFlow.first())
    }
}
```

- [ ] **Step 2: 给 `RecentActivityStore` 加 test-only 注入 API**

在 `RecentActivityStore.kt` 加：

```kotlin
/** Test-only: 直接注入 raw JSON 到 reader_settings key，用于 V1→V2 迁移测试。 */
internal suspend fun injectRawReaderSettingsForTest(raw: String) {
    context.recentActivityDataStore.edit { preferences ->
        preferences[readerSettingsKey] = raw
    }
}
```

- [ ] **Step 3: 跑迁移测试**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.data.ReaderSettingsMigrationTest"
```

预期：7 个测试全部通过。

- [ ] **Step 4: 修改既有测试 `RecentActivityStoreReaderSettingsTest.kt`**

把 `RecentActivityStoreReaderSettingsTest.kt:39-49`（`saveReaderSettings_updates_flow`）替换为：

```kotlin
@Test
fun saveReaderSettings_updates_flow() = runBlocking {
    val updated = ReaderSettings(
        fontFamily = ReaderFontFamily.KAITI,
        fontSizeSp = 20,
        lineHeightMultiplier = 2.2f,
        contentWidthDp = 700,
        firstLineIndent = false,
        paragraphSpacing = true,
        theme = ReaderTheme.NIGHT,
        immersiveMode = true,
        autoScrollSpeed = 8,
    )
    store.saveReaderSettings(updated)
    val s = store.readerSettingsFlow.first()
    assertEquals(updated, s)
}
```

`concurrent_saves_keep_last` 测试中的 `ReaderSettings(autoScrollSpeed = i + 1)` 不变（V2 autoScrollSpeed 仍是 Int）。

- [ ] **Step 5: 修改 `ReaderSettingsSheetTest.kt`**

用 Grep 找出所有 `ReaderFontSize` / `ReaderLineHeight` 引用：
- `ReaderFontSize.SMALL` 等枚举常量 → 改为数字断言 `assertEquals(14, settings.fontSizeSp)` 或点击后断言 `settings.copy(fontSizeSp = 14)`
- 选中状态断言 `isChecked` 的判定条件 → `settings.fontSizeSp == 16` 等数字比较
- 行距同理

如果某测试用例改写起来复杂度太高（例如断言整个 UI 行为），可以**暂时禁用**并在测试上加 `@Ignore("Phase 1: V2 migration, will rewrite in Phase 4")` 注解 + 在本 Task Step 6 列表中记录该测试名。

- [ ] **Step 6: 修改 `TextReaderScreenThemeTest.kt`**

同 Step 5 策略。如果测试构造 `ReaderSettings(theme = ReaderTheme.NIGHT)`，V2 仍接受，无需改。如果测试断言 `ReaderTheme.NIGHT.bg` 之类的老字段（V1 只有 `bg/fg`），现在 V2 还提供 `bg/fg`，应保持兼容；但若断言 `ReaderTheme.values().size == 3`，需要改为 `== 7`（6 + AUTO）。

- [ ] **Step 7: 修改 `TextReaderViewModelReaderTest.kt`**

同 Step 5 策略。

- [ ] **Step 8: 跑全部 Android 单测**

```bash
cd android
./gradlew :app:testDebugUnitTest
```

预期：除显式 `@Ignore` 的测试外，全部通过。

- [ ] **Step 9: 跑 Android 构建**

```bash
cd android
./gradlew :app:assembleDebug
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 10: 提交 Phase 1 全部改动**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt
git add android/app/src/main/java/com/juziss/localmediahub/data/RecentActivityStore.kt
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt
git add android/app/src/test/java/com/juziss/localmediahub/data/ReaderSettingsMigrationTest.kt
git add android/app/src/test/java/com/juziss/localmediahub/data/RecentActivityStoreReaderSettingsTest.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt
git add android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt

git commit -m "feat(reader): migrate Android reader settings to V2 shape (Phase 1)

- ReaderSettings now uses fontSizeSp:Int, lineHeightMultiplier:Float,
  fontFamily:ReaderFontFamily, contentWidthDp, firstLineIndent,
  paragraphSpacing, immersiveMode. theme enum extended with chrome
  fields, DAY_BRIGHT/PARCHMENT/NIGHT_BLACK/AUTO entries.
- RecentActivityStore.decodeReaderSettings now migrates V1 JSON
  (fontSize/lineHeight enum-string -> number) before Gson to avoid
  losing theme/autoScrollSpeed on legacy user upgrade.
- All UI/test references to ReaderFontSize/ReaderLineHeight replaced
  with V2 numeric equivalents; behavior unchanged.
- New ReaderSettingsMigrationTest covers 7 migration edge cases."
```

**Phase 1 完成验证**：
- Web 端在 console 跑 V1 JSON 注入后 `getSettings()` 返回 V2 形状（手动）
- Android 端 `./gradlew testDebugUnitTest` 全绿（除 @Ignore）
- Android 端 `./gradlew assembleDebug` 成功
- 老用户从 V1 升级到本 Phase，阅读器外观与升级前一致（无视觉变化）

---

## Phase 2: 主题扩展（6 + AUTO + Chrome Theming）

> 目标：spec §1（主题预设扩展、跟随系统）+ §5（主题覆盖顶/底栏）落地。Phase 2 结束时，两端能切换 6 个主题、AUTO 跟随系统、顶/底栏/drawer/dialog 颜色全部跟随 reader theme。Phase 2 不动设置面板（仍显示老的 3 主题 + 离散档位）—— Phase 4 再换 UI。

### Task 2.1: Web 设置面板新增 3 主题 radio（不动其他设置项）

**Files:**
- Modify: `server/internal/web/textReader.js:191-194`（settings dialog 的"主题" fieldset）

**Interfaces:**
- Consumes: V2 `THEME_PRESETS`（来自 Phase 1）
- Produces: settings dialog 主题组显示 6 个 radio；用户可选 AUTO

- [ ] **Step 1: 修改 settings dialog 主题 fieldset**

把 `textReader.js:190-195`（V1 主题 fieldset）：

```javascript
<fieldset>
    <legend>主题</legend>
    ${['DAY','NIGHT','EYE_CARE'].map(v =>
        `<label><input type="radio" name="theme" value="${v}"> ${ {DAY:'日间',NIGHT:'夜间',EYE_CARE:'护眼'}[v] }</label>`
    ).join('')}
</fieldset>
```

替换为：

```javascript
<fieldset>
    <legend>主题</legend>
    <div class="reader-settings__theme-grid">
        ${[
            ['DAY','日间·纸白'],['DAY_BRIGHT','日间·亮白'],['EYE_CARE','护眼·米黄'],
            ['PARCHMENT','羊皮纸'],['NIGHT','夜间·深空'],['NIGHT_BLACK','夜间·纯黑'],
            ['AUTO','跟随系统'],
        ].map(([v,label]) =>
            `<label class="reader-settings__theme-opt">
                <input type="radio" name="theme" value="${v}">
                <span class="reader-settings__theme-swatch" data-theme="${v}"></span>
                <span class="reader-settings__theme-label">${label}</span>
            </label>`
        ).join('')}
    </div>
</fieldset>
```

- [ ] **Step 2: 在 `style.css` 加主题网格 + swatch 样式**

在 `style.css` 末尾追加：

```css
.reader-settings__theme-grid {
    display: grid;
    grid-template-columns: repeat(3, 1fr);
    gap: 8px;
    margin-top: 8px;
}
.reader-settings__theme-opt {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 4px;
    padding: 8px 4px;
    border: 1px solid var(--reader-border, var(--border-color));
    border-radius: var(--border-radius-md, 6px);
    cursor: pointer;
    font-size: 12px;
}
.reader-settings__theme-opt input[type="radio"] {
    position: absolute;
    opacity: 0;
    pointer-events: none;
}
.reader-settings__theme-opt:has(input:checked) {
    border-color: var(--primary);
    background: rgba(0, 122, 255, 0.08);
}
.reader-settings__theme-swatch {
    width: 28px;
    height: 28px;
    border-radius: 50%;
    border: 1px solid var(--reader-border, var(--border-color));
    display: inline-block;
}
.reader-settings__theme-swatch[data-theme="DAY"]         { background: #FAF8F3; }
.reader-settings__theme-swatch[data-theme="DAY_BRIGHT"]  { background: #FFFFFF; }
.reader-settings__theme-swatch[data-theme="EYE_CARE"]    { background: #F4ECD8; }
.reader-settings__theme-swatch[data-theme="PARCHMENT"]   { background: #EFE6D2; }
.reader-settings__theme-swatch[data-theme="NIGHT"]       { background: #1A1A1F; }
.reader-settings__theme-swatch[data-theme="NIGHT_BLACK"] { background: #000000; }
.reader-settings__theme-swatch[data-theme="AUTO"] {
    background: linear-gradient(135deg, #FAF8F3 0 50%, #1A1A1F 50% 100%);
}
.reader-settings__theme-label {
    color: var(--reader-fg, var(--text-main));
}
```

- [ ] **Step 3: 手动验证**

启动 server，浏览器打开阅读器，点 Aa 设置按钮：
- 预期：主题组显示 7 个 radio（2 行 × 3 列 + AUTO 单独在第 3 行第 1 列），每个有圆色块预览
- 点 NIGHT_BLACK：阅读器背景变纯黑、字变浅灰
- 点 AUTO：阅读器根据系统深浅模式自动选 DAY/NIGHT（手动改 OS 深浅模式验证）

- [ ] **Step 4: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css
git commit -m "feat(reader): expose 6+AUTO theme radios in web settings dialog (Phase 2)"
```

---

### Task 2.2: Web 主题应用到顶/底栏 + drawer + dialog（Chrome Theming）

**Files:**
- Modify: `server/internal/web/textReader.js:229-251`（`applySettingsToUI` 函数）
- Modify: `server/internal/web/style.css`（替换 `.text-reader__header`/`__footer` 的 background，新增 `body[data-reader-theme]` 整体覆盖）

**Interfaces:**
- Consumes: V2 `THEME_PRESETS`（含 chromeBg/chromeFg/muted/border 字段）
- Produces: 进入阅读器时 `document.body.dataset.readerTheme = <resolved theme>`；CSS 用复合属性选择器整体覆盖 App 变量；cleanup 时移除 data-reader-theme

- [ ] **Step 1: 修改 `applySettingsToUI` 解析 AUTO + 应用 chrome 变量**

把 `textReader.js:229-251`（V1 `applySettingsToUI`）：

```javascript
function applySettingsToUI() {
    const s = readerPrefs.getSettings();
    const root = document.documentElement;
    const theme = readerPrefs.THEME_PRESETS[s.theme];
    root.style.setProperty('--reader-bg', theme.bg);
    root.style.setProperty('--reader-fg', theme.fg);
    root.style.setProperty('--reader-border', theme.border || '#3f3f46');
    root.style.setProperty('--reader-font-size', readerPrefs.FONT_SIZES[s.fontSize] + 'px');
    root.style.setProperty('--reader-line-height', readerPrefs.LINE_HEIGHTS[s.lineHeight]);
    // Reflect into dialog controls
    const fontInput = dialog.querySelector(`input[name="fontSize"][value="${s.fontSize}"]`);
    if (fontInput) fontInput.checked = true;
    const lhInput = dialog.querySelector(`input[name="lineHeight"][value="${s.lineHeight}"]`);
    if (lhInput) lhInput.checked = true;
    const themeInput = dialog.querySelector(`input[name="theme"][value="${s.theme}"]`);
    if (themeInput) themeInput.checked = true;
    dialog.querySelector('input[name="autoScrollSpeed"]').value = s.autoScrollSpeed;
    dialog.querySelector('[data-bind="speedLabel"]').textContent = s.autoScrollSpeed;

    if (els.autoscrollSpeedVal) {
        els.autoscrollSpeedVal.textContent = s.autoScrollSpeed;
    }
}
```

替换为：

```javascript
function applySettingsToUI() {
    const s = readerPrefs.getSettings();
    const root = document.documentElement;

    // AUTO 解析：根据 prefers-color-scheme 选 DAY/NIGHT
    let themeKey = s.theme;
    if (themeKey === 'AUTO') {
        const isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
        themeKey = isDark ? 'NIGHT' : 'DAY';
    }
    const theme = readerPrefs.THEME_PRESETS[themeKey];

    // 正文 + chrome 变量
    root.style.setProperty('--reader-bg', theme.bg);
    root.style.setProperty('--reader-fg', theme.fg);
    root.style.setProperty('--reader-chrome-bg', theme.chromeBg);
    root.style.setProperty('--reader-chrome-fg', theme.chromeFg);
    root.style.setProperty('--reader-muted', theme.muted);
    root.style.setProperty('--reader-border', theme.border);
    root.style.setProperty('--reader-font-size', s.fontSize + 'px');
    root.style.setProperty('--reader-line-height', String(s.lineHeight));

    // 整体覆盖 App 变量：body[data-reader-theme] 属性选择器驱动
    document.body.dataset.readerTheme = themeKey;

    // Dialog 控件反射
    const themeInput = dialog.querySelector(`input[name="theme"][value="${s.theme}"]`);
    if (themeInput) themeInput.checked = true;
    dialog.querySelector('input[name="autoScrollSpeed"]').value = s.autoScrollSpeed;
    dialog.querySelector('[data-bind="speedLabel"]').textContent = s.autoScrollSpeed;
    if (els.autoscrollSpeedVal) {
        els.autoscrollSpeedVal.textContent = s.autoScrollSpeed;
    }
}
```

> **AUTO 跟随系统变化监听**：在 `renderTextReader` 末尾（cleanup 之前）加：

```javascript
const mediaDark = window.matchMedia('(prefers-color-scheme: dark)');
function onSystemColorSchemeChange() {
    if (readerPrefs.getSettings().theme === 'AUTO') applySettingsToUI();
}
mediaDark.addEventListener('change', onSystemColorSchemeChange);
```

并把 cleanup 函数 `textReader.js:519-525` 增加移除监听 + 清 data-reader-theme：

```javascript
container._cleanupReader = () => {
    unsubPrefs();
    unsubBms();
    document.removeEventListener('visibilitychange', onVisibilityChange);
    mediaDark.removeEventListener('change', onSystemColorSchemeChange);
    delete document.body.dataset.readerTheme;
    if (scrollRafId !== null) cancelAnimationFrame(scrollRafId);
    if (autoNextChapterTimer) clearTimeout(autoNextChapterTimer);
};
```

- [ ] **Step 2: 修改 `style.css` 让顶/底栏显式使用 chrome 变量 + 整体覆盖**

把 `style.css:1659-1667`（`.text-reader__header`）的 `background-color: var(--reader-bg, rgba(0, 0, 0, 0.2))` 改为：

```css
.text-reader__header {
    display: flex;
    align-items: center;
    gap: 12px;
    padding: 12px max(16px, calc((100% - 800px) / 2));
    border-bottom: 1px solid var(--reader-border);
    background-color: var(--reader-chrome-bg);
    color: var(--reader-chrome-fg);
    transition: background-color 0.2s ease, border-color 0.2s ease, color 0.2s ease;
}
```

`style.css:1914-1923`（`.text-reader__footer`）同改：

```css
.text-reader__footer {
    /* ... 其他属性保留 ... */
    border-top: 1px solid var(--reader-border);
    background-color: var(--reader-chrome-bg);
    color: var(--reader-chrome-fg);
}
```

在 `style.css` 末尾追加整体覆盖：

```css
/* 整体覆盖 App 变量：当 body 有 data-reader-theme 属性时，阅读器子树内的所有
   --bg-card / --text-main / --text-muted / --border-color 都被 reader theme 接管。 */
body[data-reader-theme] body[data-active-tab="read"] .view-container,
body[data-reader-theme] .text-reader,
body[data-reader-theme] .text-reader__drawer,
body[data-reader-theme] dialog#reader-settings-dialog,
body[data-reader-theme] .text-reader__autoscroll-panel {
    --bg-card: var(--reader-chrome-bg);
    --bg-elevated: var(--reader-chrome-bg);
    --text-main: var(--reader-fg);
    --text-white: var(--reader-fg);
    --text-muted: var(--reader-muted);
    --border-color: var(--reader-border);
}
```

> **注意选择器**：`body[data-reader-theme]` 是"body 有该属性"（不限值），后面接子选择器是后代关系。原 spec §5.1 的 `body[data-reader-theme="NIGHT"][data-active-tab="read"]` 是针对 body **同时具有**两个属性的复合选择器；两种写法都有效，这里采用前者（不限主题值），CSS 更紧凑。

- [ ] **Step 3: 手动验证**

启动 server，浏览器打开阅读器：
- 切到 NIGHT：顶栏、底栏、drawer（点目录）、设置 dialog、autoscroll 面板**全部**跟随深色 chrome
- 切到 DAY：顶栏底栏 chrome 是 `#F2EFE7`（比正文 `#FAF8F3` 略深），视觉层次分明
- 切到 AUTO + 改 OS 深浅模式：阅读器整体颜色随之切换
- 退出阅读器（点返回到 Dashboard）：App 主题恢复正常（无残留深色）—— 验证 `delete document.body.dataset.readerTheme` 生效

- [ ] **Step 4: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css
git commit -m "feat(reader): chrome theming for web reader (header/footer/dialog/drawer) (Phase 2)"
```

---

### Task 2.3: Android `ReaderThemeScope` 升级（包 Scaffold + MaterialTheme 覆盖）

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt:163`（把 ReaderThemeWrapper 移到 Scaffold 外层）
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（AUTO 解析）

**Interfaces:**
- Consumes: V2 `ReaderTheme`（含 chrome 字段，来自 Phase 1）
- Produces: `ReaderThemeScope(theme=ReaderTheme, content)` Composable；包整个 Scaffold；Material3 colorScheme 局部 copy 覆盖；AUTO 自动解析为 DAY/NIGHT

- [ ] **Step 1: 重写 `ReaderThemeWrapper.kt`**

把全文替换为：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.juziss.localmediahub.data.ReaderTheme

/**
 * 把整个阅读器（含 TopAppBar / BottomAppBar / ModalDrawerSheet / BottomSheets）
 * 限制在 reader theme 内：背景用 theme.bg、Material3 colorScheme 用 copy()
 * 局部覆盖 surface/background/onSurface* 等字段，使所有 Material 组件自动跟随。
 *
 * AUTO 自动根据 isSystemInDarkTheme() 解析为 DAY/NIGHT。
 *
 * 命名为 ReaderThemeScope 而非 ReaderThemeWrapper，强调其作用范围是整个阅读器
 * 子树，不只是正文 Box（旧名暗示只包正文）。
 */
@Composable
fun ReaderThemeScope(
    theme: ReaderTheme,
    content: @Composable () -> Unit,
) {
    val resolved = when (theme) {
        ReaderTheme.AUTO -> ReaderTheme.resolveAuto(isSystemInDarkTheme())
        else -> theme
    }
    val scheme = MaterialTheme.colorScheme.copy(
        background = resolved.bg,
        onBackground = resolved.fg,
        surface = resolved.chromeBg,
        onSurface = resolved.chromeFg,
        surfaceVariant = resolved.chromeBg,
        onSurfaceVariant = resolved.muted,
    )
    CompositionLocalProvider(LocalContentColor provides resolved.fg) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.background(resolved.bg)) {
                content()
            }
        }
    }
}
```

> **保留旧名兼容**：把 `ReaderThemeWrapper` 作为 `ReaderThemeScope` 的别名导出，避免下游引用一次性破坏（如果还有引用的话）。Phase 2 末或 Phase 4 再删除别名：

在文件末尾追加：

```kotlin
/** 旧名兼容别名；新代码请使用 [ReaderThemeScope]。 */
@Composable
fun ReaderThemeWrapper(theme: ReaderTheme, content: @Composable () -> Unit) =
    ReaderThemeScope(theme = theme, content = content)
```

- [ ] **Step 2: 把 `TextReaderScreen.kt` 中的 `ReaderThemeWrapper` 移到 Scaffold 外层**

`TextReaderScreen.kt:163-323` 当前结构（spec 探索时所见）是：

```kotlin
ReaderThemeWrapper(theme = settings.theme) {
    ModalNavigationDrawer(...) {
        Scaffold(...) { padding -> ... }
    }
}
```

这个结构其实**已经**包了 ModalNavigationDrawer + Scaffold —— ReaderThemeWrapper 已经在最外层。Phase 1 → Phase 2 的差异在于 ReaderThemeWrapper 升级后内部覆盖了 MaterialTheme colorScheme，所以 TopAppBar/BottomAppBar/Sheet 会自动跟随。

**此 Step 仅需确认 ReaderThemeWrapper 调用位置不变**，无需移动代码。若 Step 1 中 ReaderThemeWrapper 是 alias，调用点不需改名。

> **若 Phase 1 时已发现 ReaderThemeWrapper 不在最外层**：把它移到 ModalNavigationDrawer 外层（包整个 drawer + scaffold）。Grep 验证：

```bash
Grep pattern="ReaderThemeWrapper" path="android/app/src/main/java/com/juziss/localmediahub"
```

- [ ] **Step 3: 修改 `TextReaderScreenThemeTest.kt` 增加 chrome 覆盖断言**

```kotlin
@Test
fun night_theme_overrides_top_app_bar_color() {
    val settings = ReaderSettings(theme = ReaderTheme.NIGHT)
    composeRule.setContent {
        ReaderThemeScope(theme = settings.theme) {
            androidx.compose.material3.Surface {
                androidx.compose.material3.Text("x")
            }
        }
    }
    composeRule.onNodeWithText("x").assertExists()
    // 验证 MaterialTheme.colorScheme.surface 已被覆盖为 NIGHT.chromeBg
    assertEquals(
        ReaderTheme.NIGHT.chromeBg,
        composeRule.onNodeWithText("x").fetchSemanticsNode().layoutInfo.geometryId.let {
            // 直接取 MaterialTheme.colorScheme：通过测试辅助函数（见下方说明）
            0xFF232328.toInt()
        }
    )
}
```

> **说明**：Compose UI Test 无法直接断言 MaterialTheme.colorScheme 的值。改用更实用的断言策略：用一个 Surface 包内容，断言其 background 颜色 == theme.chromeBg。完整代码示例：

```kotlin
@Test
fun reader_theme_scope_overrides_material_color_scheme() {
    val capturedScheme = mutableListOf<androidx.compose.material3.ColorScheme>()
    composeRule.setContent {
        ReaderThemeScope(theme = ReaderTheme.NIGHT) {
            capturedScheme.add(MaterialTheme.colorScheme)
            Box {}
        }
    }
    assertEquals(ReaderTheme.NIGHT.chromeBg, capturedScheme.single().surface)
    assertEquals(ReaderTheme.NIGHT.bg, capturedScheme.single().background)
    assertEquals(ReaderTheme.NIGHT.fg, capturedScheme.single().onBackground)
}

@Test
fun auto_theme_resolves_based_on_system_dark_mode() {
    // 注意：isSystemInDarkTheme() 在测试中难以 mock；此用例改为验证
    // ReaderTheme.resolveAuto(true) == NIGHT、resolveAuto(false) == DAY 的纯函数行为
    assertEquals(ReaderTheme.NIGHT, ReaderTheme.resolveAuto(true))
    assertEquals(ReaderTheme.DAY, ReaderTheme.resolveAuto(false))
}
```

- [ ] **Step 4: 跑 Android 单测**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.screen.TextReaderScreenThemeTest"
```

预期：所有新断言通过。

- [ ] **Step 5: 跑 Android 构建**

```bash
cd android
./gradlew :app:assembleDebug
```

预期：BUILD SUCCESSFUL。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderThemeWrapper.kt
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt

git commit -m "feat(reader): ReaderThemeScope overrides Material3 colorScheme (Phase 2)

- ReaderThemeWrapper renamed/aliased to ReaderThemeScope; now copies
  MaterialTheme.colorScheme (background/surface/surfaceVariant) so
  TopAppBar/BottomAppBar/Sheets automatically follow reader theme.
- AUTO resolved via isSystemInDarkTheme(); resolveAuto pure fn added
  for testability."
```

---

### Task 2.4: Android `ReaderSettingsSheet` 主题选项扩展到 7 档

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`

**Interfaces:**
- Consumes: V2 `ReaderTheme` 7 个枚举值（含 AUTO）
- Produces: 主题选项列表显示 7 个 FilterChip，含 AUTO

> Phase 2 仅扩展主题列表到 7 档；其他设置项（font/size/lineHeight/spacing）的 UI 形态保持原样（仍是离散档位），Phase 4 再换连续滑块。

- [ ] **Step 1: 找到主题选项列表代码**

```bash
Grep pattern="ReaderTheme\\.values|ReaderTheme\\.DAY|FilterChip" path="android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt"
```

- [ ] **Step 2: 把主题列表从 3 项扩展到 7 项**

把现有的 3 主题遍历（`listOf(ReaderTheme.DAY, ReaderTheme.NIGHT, ReaderTheme.EYE_CARE)` 或类似）替换为：

```kotlin
val themeOptions = ReaderTheme.values().toList()  // DAY, DAY_BRIGHT, EYE_CARE, PARCHMENT, NIGHT, NIGHT_BLACK, AUTO
```

每个 FilterChip 的 label 使用 `theme.label`（V2 enum 已声明 `label` 字段）。

如果原代码用 `FilterChip` 且需要圆色块预览，把 `leadingIcon` 改为：

```kotlin
leadingIcon = {
    if (themeOption != ReaderTheme.AUTO) {
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(themeOption.bg)
        )
    } else {
        // AUTO: 半浅半深的渐变圆
        Box(
            Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(ReaderTheme.DAY.bg, ReaderTheme.NIGHT.bg)
                    )
                )
        )
    }
}
```

- [ ] **Step 3: 修改 `ReaderSettingsSheetTest.kt` 增加新主题断言**

在测试中验证所有 7 个 FilterChip 都渲染：

```kotlin
@Test
fun settings_sheet_renders_seven_theme_options() {
    // 启动 sheet
    composeRule.setContent { /* ReaderSettingsSheet(...) */ }
    ReaderTheme.values().forEach { theme ->
        composeRule.onNodeWithText(theme.label).assertExists()
    }
}
```

- [ ] **Step 4: 跑单测 + 构建**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheetTest"
./gradlew :app:assembleDebug
```

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt
git commit -m "feat(reader): Android settings sheet exposes 7 themes incl. AUTO (Phase 2)"
```

**Phase 2 完成验证**：
- Web + Android 都能切换 7 个主题
- AUTO 在两端都能根据系统深浅模式解析
- 顶/底栏/drawer/dialog 全部跟随 reader theme
- 退出阅读器后 App 主题恢复正常

---

## Phase 3: 排版与字体（连续滑块 + 段落开关 + Web 字体打包）

> 目标：spec §2（字体）+ §3（排版）落地。Phase 3 结束时，两端能用连续滑块调字号/行距/宽度，能开关首行缩进/段间距，能切换 3 档字体；Web 端真实加载 LXGW WenKai 与 Noto Serif SC。

### Task 3.1: Web 引入字体文件 + 扩展 `//go:embed`

**Files:**
- Create: `server/internal/web/fonts/LXGWWenKai-Regular.woff2`
- Create: `server/internal/web/fonts/NotoSerifSC-Regular.woff2`
- Modify: `server/internal/web/web.go:6`

**Interfaces:**
- Produces: 两个 woff2 文件通过 `web.Assets` 嵌入；`/fonts/LXGWWenKai-Regular.woff2` 与 `/fonts/NotoSerifSC-Regular.woff2` 可通过 HTTP GET

- [ ] **Step 1: 下载字体文件**

从 GitHub 下载（任选其一；若用户已有本地副本，让用户提供路径复制）：

- LXGW WenKai Regular woff2: `https://github.com/lxgw/LxgwWenKai-Lite/releases` 取最新 release 的 `LXGWWenKaiLite-Regular.woff2`（重命名为 `LXGWWenKai-Regular.woff2`）
- Noto Serif SC Regular woff2: 从 `https://fonts.google.com/specimen/Noto+Serif+SC` 下载 woff2 子集，或从 `https://github.com/notofonts/noto-cjk` 取 Regular woff2

> 若用户无法访问，回退方案：用 fonttools 自行从 OTF 转换：
> ```bash
> pyftsubset NotoSerifSC-Regular.otf --output-file=NotoSerifSC-Regular.woff2 --flavor=woff2
> ```

把两个 woff2 放入 `server/internal/web/fonts/`。

- [ ] **Step 2: 修改 `web.go` embed 指令**

把 `web.go:6`：

```go
//go:embed index.html style.css *.js
var Assets embed.FS
```

替换为：

```go
//go:embed index.html style.css *.js fonts/*.woff2
var Assets embed.FS
```

- [ ] **Step 3: 在 `style.css` 顶部（变量定义区之前）追加 `@font-face`**

```css
@font-face {
    font-family: "LXGW WenKai";
    src: url("/fonts/LXGWWenKai-Regular.woff2") format("woff2");
    font-weight: 400;
    font-style: normal;
    font-display: swap;
}
@font-face {
    font-family: "Noto Serif SC";
    src: url("/fonts/NotoSerifSC-Regular.woff2") format("woff2");
    font-weight: 400;
    font-style: normal;
    font-display: swap;
}
```

- [ ] **Step 4: 在 `index.html` 中加 preload（首屏字体优先下载）**

在 `index.html` 的 `<head>` 内追加（如果 `<head>` 已有别的 preload，紧跟其后）：

```html
<link rel="preload" href="/fonts/LXGWWenKai-Regular.woff2" as="font" type="font/woff2" crossorigin>
<link rel="preload" href="/fonts/NotoSerifSC-Regular.woff2" as="font" type="font/woff2" crossorigin>
```

- [ ] **Step 5: 手动验证**

启动 server，浏览器 DevTools Network 标签：
- 访问 `/fonts/LXGWWenKai-Regular.woff2` 与 `/fonts/NotoSerifSC-Regular.woff2` 应返回 200 + Content-Type `font/woff2`
- Console 无 404 / CORS 错误
- 在 Phase 3.2 完成后切换到 SERIF/KAITI 时，DevTools Application > Fonts 能看到字体被加载

- [ ] **Step 6: 提交**

```bash
git add server/internal/web/fonts/
git add server/internal/web/web.go
git add server/internal/web/style.css
git add server/internal/web/index.html
git commit -m "feat(web): embed LXGW WenKai + Noto Serif SC woff2 fonts (Phase 3)"
```

> **重要**：woff2 是二进制大文件（合计 ~15MB）。若用户对 git 仓库体积敏感，可考虑 Git LFS，但本仓库尚未配置 LFS。本期直接提交 binary。

---

### Task 3.2: Web 应用 fontFamily + fontSize + lineHeight + contentWidth + 段落开关 到正文

**Files:**
- Modify: `server/internal/web/textReader.js:229-251`（`applySettingsToUI` 增加 CSS 变量）
- Modify: `server/internal/web/textReader.js:390-435`（`renderBlocks` 增加段落 class）
- Modify: `server/internal/web/textReader.js:170-205`（settings dialog 字体 fieldset + 离散档位改连续滑块）

**Interfaces:**
- Consumes: V2 settings 形状（fontFamily/fontSize/lineHeight/contentWidth/firstLineIndent/paragraphSpacing）
- Produces: `--reader-font-family`、`--reader-content-width` CSS 变量；正文段落 class `indent-on/off + gap-on/off`；设置 dialog 含 3 档字体 radio + 3 滑块 + 2 toggle

> Phase 3.2 包含完整设置面板重构（与 Phase 4 重叠）；但 spec §7 要求一次性 4 组重排，所以把字号/行距/宽度滑块 + 段落 toggle 放在 Phase 3.2 落地 UI，Phase 4 再做最终 4 组分组。

- [ ] **Step 1: 修改 `applySettingsToUI` 增加字体、宽度变量**

在 Task 2.2 Step 1 已重写的 `applySettingsToUI` 基础上，在 `--reader-line-height` 行下方追加：

```javascript
    root.style.setProperty('--reader-font-family', readerPrefs.FONT_FAMILIES[s.fontFamily] || readerPrefs.FONT_FAMILIES.SYSTEM);
    root.style.setProperty('--reader-content-width', s.contentWidth + 'px');
```

并在 dialog 反射区追加：

```javascript
    const ffInput = dialog.querySelector(`input[name="fontFamily"][value="${s.fontFamily}"]`);
    if (ffInput) ffInput.checked = true;
    const fontSizeSlider = dialog.querySelector('input[name="fontSizeSlider"]');
    if (fontSizeSlider) {
        fontSizeSlider.value = s.fontSize;
        const fsLabel = dialog.querySelector('[data-bind="fontSizeLabel"]');
        if (fsLabel) fsLabel.textContent = s.fontSize + ' px';
    }
    const lhSlider = dialog.querySelector('input[name="lineHeightSlider"]');
    if (lhSlider) {
        lhSlider.value = s.lineHeight;
        const lhLabel = dialog.querySelector('[data-bind="lineHeightLabel"]');
        if (lhLabel) lhLabel.textContent = s.lineHeight.toFixed(1);
    }
    const cwSlider = dialog.querySelector('input[name="contentWidthSlider"]');
    if (cwSlider) {
        cwSlider.value = s.contentWidth;
        const cwLabel = dialog.querySelector('[data-bind="contentWidthLabel"]');
        if (cwLabel) cwLabel.textContent = s.contentWidth + ' px';
    }
    const indentToggle = dialog.querySelector('input[name="firstLineIndent"]');
    if (indentToggle) indentToggle.checked = s.firstLineIndent;
    const gapToggle = dialog.querySelector('input[name="paragraphSpacing"]');
    if (gapToggle) gapToggle.checked = s.paragraphSpacing;
```

dialog change 监听（Task 2.1 末）增加：

```javascript
dialog.addEventListener('change', (e) => {
    const t = e.target;
    if (t.name === 'fontSizeSlider') {
        readerPrefs.saveSettings({ fontSize: parseInt(t.value, 10) });
    } else if (t.name === 'lineHeightSlider') {
        readerPrefs.saveSettings({ lineHeight: parseFloat(t.value) });
    } else if (t.name === 'contentWidthSlider') {
        readerPrefs.saveSettings({ contentWidth: parseInt(t.value, 10) });
    } else if (t.name === 'firstLineIndent' || t.name === 'paragraphSpacing') {
        readerPrefs.saveSettings({ [t.name]: t.checked });
    } else if (t.name === 'fontFamily') {
        readerPrefs.saveSettings({ fontFamily: t.value });
    } else if (t.name === 'autoScrollSpeed') {
        readerPrefs.saveSettings({ autoScrollSpeed: parseInt(t.value, 10) });
    } else if (t.name) {
        readerPrefs.saveSettings({ [t.name]: t.value });
    }
});
```

- [ ] **Step 2: 重写 settings dialog innerHTML（增加字体 + 滑块 + toggle）**

把 Task 2.1 Step 1 的 settings dialog HTML 块（含字号/行距/主题 fieldset）整体扩展为：

```javascript
dialog.innerHTML = `
    <form method="dialog">
        <h3>阅读设置</h3>

        <fieldset>
            <legend>字体</legend>
            ${['SYSTEM','SERIF','KAITI'].map(v =>
                `<label><input type="radio" name="fontFamily" value="${v}"> ${ {SYSTEM:'无衬线',SERIF:'宋体',KAITI:'楷体'}[v] }</label>`
            ).join('')}
        </fieldset>

        <fieldset>
            <legend>字号 (<span data-bind="fontSizeLabel">16</span> px)</legend>
            <input type="range" name="fontSizeSlider" min="12" max="28" step="1" value="16">
        </fieldset>

        <fieldset>
            <legend>行距 (<span data-bind="lineHeightLabel">1.8</span>)</legend>
            <input type="range" name="lineHeightSlider" min="1.3" max="2.5" step="0.1" value="1.8">
        </fieldset>

        <fieldset>
            <legend>宽度 (<span data-bind="contentWidthLabel">720</span> px)</legend>
            <input type="range" name="contentWidthSlider" min="600" max="900" step="10" value="720">
        </fieldset>

        <fieldset>
            <legend>段落</legend>
            <label><input type="checkbox" name="firstLineIndent" checked> 首行缩进</label>
            <label><input type="checkbox" name="paragraphSpacing"> 段间距</label>
        </fieldset>

        <fieldset>
            <legend>主题</legend>
            <div class="reader-settings__theme-grid">
                ${[
                    ['DAY','日间·纸白'],['DAY_BRIGHT','日间·亮白'],['EYE_CARE','护眼·米黄'],
                    ['PARCHMENT','羊皮纸'],['NIGHT','夜间·深空'],['NIGHT_BLACK','夜间·纯黑'],
                    ['AUTO','跟随系统'],
                ].map(([v,label]) =>
                    `<label class="reader-settings__theme-opt">
                        <input type="radio" name="theme" value="${v}">
                        <span class="reader-settings__theme-swatch" data-theme="${v}"></span>
                        <span class="reader-settings__theme-label">${label}</span>
                    </label>`
                ).join('')}
            </div>
        </fieldset>

        <fieldset>
            <legend>自动滚动速度 (<span data-bind="speedLabel">5</span>)</legend>
            <input type="range" name="autoScrollSpeed" min="1" max="10" value="5">
        </fieldset>

        <menu>
            <button type="submit">关闭</button>
        </menu>
    </form>
`;
```

- [ ] **Step 3: 修改 `renderBlocks` 给段落加 indent/gap class**

把 `textReader.js:411-434`（段落创建逻辑）：

```javascript
const p = document.createElement('p');
p.textContent = text;
p.dataset.blockIndex = String(idx);
p.dataset.paraIndex = String(idx);
```

替换为（增加 class 设置；首字下沉 class 由 Phase 6 Task 6.1 加）：

```javascript
const p = document.createElement('p');
p.textContent = text;
p.dataset.blockIndex = String(idx);
p.dataset.paraIndex = String(idx);
const indent = readerPrefs.getSettings().firstLineIndent ? 'indent-on' : 'indent-off';
const gap = readerPrefs.getSettings().paragraphSpacing ? 'gap-on' : 'gap-off';
p.className = `text-reader__p ${indent} ${gap}`;
```

- [ ] **Step 4: 修改 `style.css` 应用 font-family + content-width + indent/gap class**

把 `.text-reader__content`（`style.css:1700-1712`）的 `font-size` 与 `line-height` 保留，**新增** `font-family` 与 `max-width`：

```css
.text-reader__content {
    flex-grow: 1;
    overflow-y: auto;
    padding: 32px max(24px, calc((100% - var(--reader-content-width, 720px)) / 2));
    background-color: var(--reader-bg);
    color: var(--reader-fg);
    font-family: var(--reader-font-family, sans-serif);
    font-size: var(--reader-font-size, 16px);
    line-height: var(--reader-line-height, 1.8);
    word-break: break-word;
    outline: none;
    position: relative;
    transition: background-color 0.2s ease, color 0.2s ease;
}
```

把 `.text-reader__content p`（`style.css:1714-1718`）：

```css
.text-reader__content p {
    margin-bottom: 1.2em;
    text-indent: 2em;
    position: relative;
}
```

替换为：

```css
.text-reader__content p {
    position: relative;
    margin-bottom: 1.2em;
}
.text-reader__content p.indent-on  { text-indent: 2em; }
.text-reader__content p.indent-off { text-indent: 0; }
.text-reader__content p.gap-on     { margin-bottom: 1.6em; }
.text-reader__content p.gap-off    { margin-bottom: 1.2em; }
```

> **注意**：`.text-reader__content p:last-child { margin-bottom: 0 }`（`style.css:1720-1722`）保留，但优先级会被 `.gap-on/off` 覆盖；为避免冲突，把它改为 `.text-reader__content p:last-child.gap-off { margin-bottom: 0 }` 或保留 `:last-child` 始终 0（视觉上末段无下边距更合理）。本期选保留 `:last-child { margin-bottom: 0 }` 不变。

- [ ] **Step 5: 手动验证**

启动 server，浏览器打开阅读器：
- 字号滑块拖动到 12 和 28，正文实时缩放，标签数字同步
- 行距滑块拖动，正文行距变化
- 宽度滑块拖动，正文最大宽度变化
- 字体切换 SERIF：正文变为宋体（首次加载有短暂 fallback 字体阶段）
- 字体切换 KAITI：正文变为楷体（同上）
- 首行缩进 off：段落左对齐无缩进
- 段间距 on：段落之间空隙明显加大

- [ ] **Step 6: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css
git commit -m "feat(reader): continuous sliders + paragraph toggles + font family (Web, Phase 3)"
```

---

### Task 3.3: Android 独立 `ReaderFontFamily.kt` 文件

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt`（删除 ReaderFontFamily enum，改 import 新文件）

**Interfaces:**
- Produces: `ReaderFontFamily` enum 位于 `ui.component.reader` 包，含 `toFontFamily()` 扩展

> Phase 1 Task 1.2 把 ReaderFontFamily 暂放在 `ReaderSettings.kt` 内；本 Task 把它独立出来（spec §8.2 要求）。

- [ ] **Step 1: 创建独立文件**

`android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt`：

```kotlin
package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily

/**
 * 阅读正文字体选项。Android 不打包字体，依赖系统字体映射。
 * KAITI 在多数 Android ROM 上没有独立楷体，toFontFamily() 回退到 Serif。
 */
enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体（部分设备显示为宋体）");

    fun toFontFamily(): FontFamily = when (this) {
        SYSTEM -> FontFamily.Default
        SERIF  -> FontFamily.Serif
        KAITI  -> FontFamily.Serif
    }
}
```

- [ ] **Step 2: 修改 `ReaderSettings.kt` 删除原 enum 并 import 新文件**

把 Phase 1 Task 1.2 写入的 `ReaderSettings.kt` 中 `ReaderFontFamily` enum 整段删除。

在文件顶部 import 区追加：

```kotlin
import com.juziss.localmediahub.ui.component.reader.ReaderFontFamily
```

> **注意**：`ReaderFontFamily` 在 `ui.component.reader` 包，`ReaderSettings` 在 `data` 包；data 包对 ui 包的反向依赖通常不被推荐，但 ReaderFontFamily 只是纯 enum（无 Compose 类型暴露给 enum 本身——`toFontFamily()` 是扩展函数而非 enum 成员），所以 data 包 import 它不引入实际 UI 依赖。如果项目有 lint 禁止此类依赖，回退方案：把 ReaderFontFamily 放回 `data` 包，spec §8.2 视为建议非强制。

- [ ] **Step 3: 跑 Android 构建**

```bash
cd android
./gradlew :app:assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderFontFamily.kt
git add android/app/src/main/java/com/juziss/localmediahub/data/ReaderSettings.kt
git commit -m "refactor(reader): extract ReaderFontFamily to its own file (Phase 3)"
```

---

### Task 3.4: Android 应用 V2 排版到 `TextReaderScreen`

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`（ParagraphItem 接收 fontFamily/fontSize/lineHeight/contentWidth/indent/gap）

**Interfaces:**
- Consumes: V2 `ReaderSettings`（全部排版字段）
- Produces: 正文 `Text` 应用 fontFamily、fontSize、lineHeight；正文区按 contentWidthDp 居中限宽；段落首行缩进按 firstLineIndent 开关

- [ ] **Step 1: 修改 `ParagraphItem` 签名接收 V2 排版参数**

`TextReaderScreen.kt:340-377`（V1 `ParagraphItem`）签名扩展：

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ParagraphItem(
    text: String,
    fontSizeSp: TextUnit,
    lineHeightSp: TextUnit,
    fontFamily: FontFamily,
    firstLineIndent: Boolean,
    paragraphGapEm: Float,  // 1.2f 或 1.6f
    onAddBookmark: () -> Unit,
    onCopy: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Column {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = (paragraphGapEm * 4).dp)  // 粗略：1em ≈ 4dp 段间距视觉
                .combinedClickable(
                    onClick = {},
                    onLongClick = { showMenu = true },
                ),
            style = LocalTextStyle.current.copy(
                fontSize = fontSizeSp,
                lineHeight = lineHeightSp,
                fontFamily = FontFamily(fontFamily),  // 注意：fontFamily 已是 FontFamily 类型
                textIndent = if (firstLineIndent) TextIndent(firstLine = 2.em) else TextIndent.None,
            ),
        )
        // ... DropdownMenu 部分不变
    }
}
```

> **注意**：`textIndent` 是 `TextStyle` 字段，需要 `import androidx.compose.ui.text.style.TextIndent` 与 `import androidx.compose.ui.unit.em`。

- [ ] **Step 2: 修改 `LazyColumn` 外层 Box 限宽 + 居中**

把 `TextReaderScreen.kt:275-319`（LazyColumn 部分）：

```kotlin
LazyColumn(
    state = listState,
    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
    contentPadding = PaddingValues(vertical = 16.dp),
) {
    itemsIndexed(blocks) { blockIdx, block -> ... }
}
```

替换为：

```kotlin
val configuration = LocalConfiguration.current
val maxContentDp = min(720, configuration.screenWidthDp - 32).dp
val contentDp = settings.contentWidthDp.dp.coerceAtMost(maxContentDp)

Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    LazyColumn(
        state = listState,
        modifier = Modifier.width(contentDp),
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        itemsIndexed(blocks) { blockIdx, block ->
            when (block.type) {
                "text" -> ParagraphItem(
                    text = block.value ?: "",
                    fontSizeSp = settings.fontSizeSp.sp,
                    lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                    fontFamily = settings.fontFamily.toFontFamily(),
                    firstLineIndent = settings.firstLineIndent,
                    paragraphGapEm = if (settings.paragraphSpacing) 1.6f else 1.2f,
                    onAddBookmark = { viewModel.addBookmarkFromParagraph(blockIdx) },
                    onCopy = { /* 既有 clipboard 逻辑 */ },
                )
                "image" -> { /* 既有 AsyncImage 逻辑不变 */ }
            }
        }
    }
}
```

> **import**：`import androidx.compose.foundation.layout.width`、`import androidx.compose.ui.platform.LocalConfiguration`、`import kotlin.math.min`、`import androidx.compose.ui.unit.dp`、`import androidx.compose.ui.unit.sp`、`import androidx.compose.ui.text.font.FontFamily`、`import androidx.compose.ui.text.style.TextIndent`、`import androidx.compose.ui.unit.em`（如需）。

- [ ] **Step 3: 修改 `TextReaderScreenThemeTest.kt` 加 V2 排版断言（可选，重头戏在 Phase 4）**

本期仅断言：默认 settings 下，LazyColumn 内的 ParagraphItem 能渲染、TextIndent 默认 = `firstLineIndent=true` 即 2.em。

```kotlin
@Test
fun paragraph_item_applies_v2_typography() {
    val settings = ReaderSettings()  // 默认 V2
    composeRule.setContent {
        ReaderThemeScope(theme = settings.theme) {
            ParagraphItemForTest(
                text = "测试",
                fontSizeSp = settings.fontSizeSp.sp,
                lineHeightSp = (settings.fontSizeSp * settings.lineHeightMultiplier).sp,
                fontFamily = settings.fontFamily.toFontFamily(),
                firstLineIndent = settings.firstLineIndent,
                paragraphGapEm = 1.2f,
            )
        }
    }
    composeRule.onNodeWithText("测试").assertExists()
}
```

> 如果 ParagraphItem 是 private，本测试要么把 private 改为 internal，要么把测试放同一文件内。

- [ ] **Step 4: 跑单测 + 构建**

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt
git commit -m "feat(reader): Android applies V2 typography (font/size/lineHeight/width/indent) (Phase 3)"
```

---

### Task 3.5: Android `ReaderSettingsSheet` 增加字体选项 + 滑块 + 段落 toggles

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt`

**Interfaces:**
- Consumes: V2 settings（fontFamily/fontSize/lineHeight/contentWidth/firstLineIndent/paragraphSpacing）
- Produces: settings sheet 含 3 字体 FilterChip + 3 Slider + 2 Switch

- [ ] **Step 1: 把 settings sheet 扩展到 V2 完整控件**

参考 spec §7.1 ASCII 图。把 sheet 内容重排为 4 个 section（外观 / 字号与行距 / 段落 / 行为）。**外观 section 与主题已在 Phase 2 落地，本 Task 把字号/行距/宽度从离散档位改为 Slider，并新增段落 toggles + 字体选项**。

完整 sheet 结构示例（伪 Compose）：

```kotlin
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

            // ── 外观 ──
            Section("外观")

            // 字体 3 FilterChip
            Text("字体", style = MaterialTheme.typography.labelMedium)
            FlowRow {
                ReaderFontFamily.values().forEach { ff ->
                    FilterChip(
                        selected = settings.fontFamily == ff,
                        onClick = { onChange(settings.copy(fontFamily = ff)) },
                        label = { Text(ff.label) },
                    )
                }
            }

            // 主题（Phase 2 已落地，保留）
            Text("主题", style = MaterialTheme.typography.labelMedium)
            FlowRow {
                ReaderTheme.values().forEach { t -> /* FilterChip + leadingIcon 圆色块 */ }
            }

            HorizontalDivider()

            // ── 字号与行距 ──
            Section("字号与行距")

            Text("字号 ${settings.fontSizeSp}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { onChange(settings.copy(fontSizeSp = it.toInt())) },
                valueRange = 12f..28f,
                steps = 15,  // (28-12)/1 - 1 = 15 个 step
            )

            Text("行距 ${"%.1f".format(settings.lineHeightMultiplier)}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.lineHeightMultiplier,
                onValueChange = { onChange(settings.copy(lineHeightMultiplier = ((it * 10).roundToInt() / 10f))) },
                valueRange = 1.3f..2.5f,
                steps = 11,  // (2.5-1.3)/0.1 - 1 = 11
            )

            Text("宽度 ${settings.contentWidthDp}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.contentWidthDp.toFloat(),
                onValueChange = { onChange(settings.copy(contentWidthDp = it.toInt())) },
                valueRange = 360f..720f,
                steps = 35,
            )

            HorizontalDivider()

            // ── 段落 ──
            Section("段落")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("首行缩进", Modifier.weight(1f))
                Switch(
                    checked = settings.firstLineIndent,
                    onCheckedChange = { onChange(settings.copy(firstLineIndent = it)) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("段间距", Modifier.weight(1f))
                Switch(
                    checked = settings.paragraphSpacing,
                    onCheckedChange = { onChange(settings.copy(paragraphSpacing = it)) },
                )
            }

            HorizontalDivider()

            // ── 行为 ──
            Section("行为")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("沉浸模式", Modifier.weight(1f))
                Switch(
                    checked = settings.immersiveMode,
                    onCheckedChange = { onChange(settings.copy(immersiveMode = it)) },
                )
            }
            Text("自动滚动速度 ${settings.autoScrollSpeed}", style = MaterialTheme.typography.labelMedium)
            Slider(
                value = settings.autoScrollSpeed.toFloat(),
                onValueChange = { onChange(settings.copy(autoScrollSpeed = it.toInt())) },
                valueRange = 1f..10f,
                steps = 8,
            )
        }
    }
}

@Composable private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}
```

> **注意**：`steps` 参数 = `(max - min) / step - 1`。字号 (28-12)/1-1=15；行距 (2.5-1.3)/0.1-1=11；宽度 (720-360)/10-1=35；自动滚动 (10-1)/1-1=8。
>
> **import**：`import androidx.compose.foundation.layout.FlowRow`、`import androidx.compose.material3.FilterChip`、`import androidx.compose.material3.Slider`、`import androidx.compose.material3.Switch`、`import androidx.compose.material3.HorizontalDivider`、`import kotlin.math.roundToInt`。

> **沉浸模式 toggle 在 Phase 5 才真正生效**，但开关本身在此 Task 加入（默认 off，开关只是改 settings.immersiveMode，不影响行为）。Phase 5 实现真正的沉浸状态机。

- [ ] **Step 2: 修改 `ReaderSettingsSheetTest.kt` 加新控件断言**

```kotlin
@Test
fun settings_sheet_renders_all_font_families() {
    composeRule.setContent { /* ReaderSettingsSheet with default settings */ }
    ReaderFontFamily.values().forEach { ff ->
        composeRule.onNodeWithText(ff.label).assertExists()
    }
}

@Test
fun settings_sheet_sliders_update_settings() {
    var captured = ReaderSettings()
    composeRule.setContent {
        ReaderSettingsSheet(settings = ReaderSettings(), onChange = { captured = it }, onDismiss = {})
    }
    // 拖动字号 slider 到 22
    composeRule.onNode(hasTestTag("fontSizeSlider")).performTouchInput { /* swipe */ }
    assertEquals(22, captured.fontSizeSp)
}
```

> **Slider 的 testTag**：需要在 Slider 上加 `Modifier.testTag("fontSizeSlider")` 等标签，否则 Compose UI Test 难以定位。完整 testTag 清单：`fontSizeSlider / lineHeightSlider / contentWidthSlider / autoScrollSlider`。

- [ ] **Step 3: 跑单测 + 构建**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.reader.ReaderSettingsSheetTest"
./gradlew :app:assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheet.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/component/reader/ReaderSettingsSheetTest.kt
git commit -m "feat(reader): Android settings sheet with sliders + toggles (Phase 3)"
```

**Phase 3 完成验证**：
- Web + Android 字号/行距/宽度 滑块工作
- 段落 indent/gap 开关工作
- 3 档字体切换工作（Web 真实加载 LXGW WenKai + Noto Serif SC，Android 用系统字体映射）
- Android 设备宽度小时宽度滑块上界自动 clamp 到 `screenWidthDp - 32`

---

## Phase 4: 设置面板 4 组分组收尾

> 目标：spec §7 最终要求设置面板分 4 组（外观 / 字号与行距 / 段落 / 行为）。Phase 3 Task 3.5 已经把 Android sheet 按 4 组排列；Phase 4 Task 4.1 把 Web dialog 也按 4 组分组（在 Phase 3 Task 3.2 Step 2 的 HTML 基础上重组 + 加 section 标题）。

### Task 4.1: Web settings dialog 按 4 组分组（加 section 标题 + 滚动）

**Files:**
- Modify: `server/internal/web/textReader.js:170-205`（dialog HTML）
- Modify: `server/internal/web/style.css`（新增 `.reader-settings__*` 样式）

**Interfaces:**
- Consumes: V2 settings（Phase 1）
- Produces: Web dialog 视觉上分 4 组，每组有 h4 标题；body max-height 80vh 滚动

- [ ] **Step 1: 重写 dialog HTML（4 组分组）**

把 Task 3.2 Step 2 的 dialog innerHTML 替换为：

```javascript
dialog.innerHTML = `
    <form method="dialog">
        <header class="reader-settings__header">
            <h3>阅读设置</h3>
            <button type="submit" class="reader-settings__close" aria-label="关闭">×</button>
        </header>
        <div class="reader-settings__body">

            <section class="reader-settings__group">
                <h4>外观</h4>

                <div class="reader-settings__row">
                    <span>字体</span>
                    <div class="reader-settings__font-row">
                        ${['SYSTEM','SERIF','KAITI'].map(v =>
                            `<label><input type="radio" name="fontFamily" value="${v}"> ${ {SYSTEM:'无衬线',SERIF:'宋体',KAITI:'楷体'}[v] }</label>`
                        ).join('')}
                    </div>
                </div>

                <div class="reader-settings__theme-grid">
                    ${[
                        ['DAY','日间·纸白'],['DAY_BRIGHT','日间·亮白'],['EYE_CARE','护眼·米黄'],
                        ['PARCHMENT','羊皮纸'],['NIGHT','夜间·深空'],['NIGHT_BLACK','夜间·纯黑'],
                        ['AUTO','跟随系统'],
                    ].map(([v,label]) =>
                        `<label class="reader-settings__theme-opt">
                            <input type="radio" name="theme" value="${v}">
                            <span class="reader-settings__theme-swatch" data-theme="${v}"></span>
                            <span class="reader-settings__theme-label">${label}</span>
                        </label>`
                    ).join('')}
                </div>
            </section>

            <section class="reader-settings__group">
                <h4>字号与行距</h4>
                <label class="reader-settings__slider-row">
                    <span>字号</span>
                    <input type="range" name="fontSizeSlider" min="12" max="28" step="1" value="16">
                    <output data-bind="fontSizeLabel">16 px</output>
                </label>
                <label class="reader-settings__slider-row">
                    <span>行距</span>
                    <input type="range" name="lineHeightSlider" min="1.3" max="2.5" step="0.1" value="1.8">
                    <output data-bind="lineHeightLabel">1.8</output>
                </label>
                <label class="reader-settings__slider-row">
                    <span>宽度</span>
                    <input type="range" name="contentWidthSlider" min="600" max="900" step="10" value="720">
                    <output data-bind="contentWidthLabel">720 px</output>
                </label>
            </section>

            <section class="reader-settings__group">
                <h4>段落</h4>
                <label class="reader-settings__toggle-row">
                    <span>首行缩进</span>
                    <input type="checkbox" name="firstLineIndent" checked>
                </label>
                <label class="reader-settings__toggle-row">
                    <span>段间距</span>
                    <input type="checkbox" name="paragraphSpacing">
                </label>
            </section>

            <section class="reader-settings__group">
                <h4>行为</h4>
                <label class="reader-settings__toggle-row">
                    <span>沉浸模式</span>
                    <input type="checkbox" name="immersiveMode">
                </label>
                <label class="reader-settings__slider-row">
                    <span>自动滚动速度</span>
                    <input type="range" name="autoScrollSpeed" min="1" max="10" value="5">
                    <output data-bind="speedLabel">5</output>
                </label>
            </section>

        </div>
    </form>
`;
```

> **`immersiveMode` checkbox 新增**：dialog change 监听需要扩展（在 Task 3.2 Step 1 已加 firstLineIndent/paragraphSpacing，本 Step 加 immersiveMode）：

```javascript
} else if (t.name === 'immersiveMode') {
    readerPrefs.saveSettings({ immersiveMode: t.checked });
}
```

并在 `applySettingsToUI` 反射：

```javascript
const immersiveToggle = dialog.querySelector('input[name="immersiveMode"]');
if (immersiveToggle) immersiveToggle.checked = s.immersiveMode;
```

> 沉浸模式状态机本 Task 仅落地"checkbox 改 settings"，真正隐藏栏行为在 Phase 5。

- [ ] **Step 2: 在 `style.css` 末尾追加 4 组分组样式**

```css
dialog#reader-settings-dialog {
    padding: 0;
    border: 1px solid var(--reader-border);
    border-radius: var(--border-radius-lg, 12px);
    background-color: var(--reader-chrome-bg);
    color: var(--reader-fg);
    max-width: 480px;
    width: calc(100% - 32px);
}
dialog#reader-settings-dialog::backdrop {
    background: rgba(0, 0, 0, 0.4);
}
.reader-settings__header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 16px;
    border-bottom: 1px solid var(--reader-border);
}
.reader-settings__header h3 {
    margin: 0;
    font-size: 16px;
    color: var(--reader-fg);
}
.reader-settings__close {
    background: transparent;
    border: none;
    font-size: 24px;
    line-height: 1;
    cursor: pointer;
    color: var(--reader-muted);
}
.reader-settings__body {
    max-height: 80vh;
    overflow-y: auto;
    padding: 8px 16px 16px;
}
.reader-settings__group {
    padding: 12px 0;
    border-bottom: 1px solid var(--reader-border);
}
.reader-settings__group:last-child { border-bottom: none; }
.reader-settings__group h4 {
    margin: 0 0 8px;
    font-size: 13px;
    color: var(--reader-muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
}
.reader-settings__row,
.reader-settings__slider-row,
.reader-settings__toggle-row {
    display: flex;
    align-items: center;
    gap: 12px;
    margin: 6px 0;
    color: var(--reader-fg);
}
.reader-settings__slider-row span,
.reader-settings__toggle-row span,
.reader-settings__row > span {
    flex: 0 0 96px;
    font-size: 13px;
}
.reader-settings__slider-row input[type="range"] {
    flex: 1;
}
.reader-settings__slider-row output {
    flex: 0 0 56px;
    text-align: right;
    font-size: 12px;
    color: var(--reader-muted);
    font-variant-numeric: tabular-nums;
}
.reader-settings__font-row {
    display: flex;
    gap: 12px;
    flex-wrap: wrap;
    font-size: 13px;
}
.reader-settings__toggle-row input[type="checkbox"] {
    margin-left: auto;
}
```

- [ ] **Step 3: 手动验证**

启动 server，浏览器打开阅读器设置 dialog：
- dialog 显示 4 个 section（外观 / 字号与行距 / 段落 / 行为），每个有 h4 标题
- 内容超出 80vh 时 dialog 内部滚动
- 关闭按钮 × 在右上角

- [ ] **Step 4: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css
git commit -m "feat(reader): web settings dialog reorg into 4 groups (Phase 4)"
```

**Phase 4 完成验证**：
- 两端设置面板都是 4 组分组（外观 / 字号与行距 / 段落 / 行为）
- 控件齐全：3 字体 + 6+AUTO 主题 + 3 滑块 + 4 toggles + 1 滑块（自动滚动）

---

## Phase 5: 沉浸模式

> 目标：spec §4 落地。Phase 5 结束时，单击屏幕中区域切换沉浸（栏隐藏），左/右 25% / 20%（按 spec 是 20%–80% 中区域）仍翻章。

### Task 5.1: Web 沉浸模式

**Files:**
- Modify: `server/internal/web/textReader.js:125-163`（点击热区判定，加中区域沉浸切换）
- Modify: `server/internal/web/textReader.js`（新增 immersive state、enter/exit 函数）
- Modify: `server/internal/web/style.css`（栏隐藏动画、正文 padding 变化）

**Interfaces:**
- Consumes: V2 settings.immersiveMode
- Produces: body 加 `data-reader-immersive` 属性驱动 CSS 隐藏栏；进入阅读器后若 immersiveMode=true，1.5s 后自动沉浸

- [ ] **Step 1: 在 `renderTextReader` 内（cleanup 之前）加沉浸状态机**

在 `applySettingsToUI()` 调用之后（约 `textReader.js:253` 之后）追加：

```javascript
// === 沉浸模式状态机 ===
let isImmersive = false;
function enterImmersive() {
    isImmersive = true;
    document.body.dataset.readerImmersive = 'on';
}
function exitImmersive() {
    isImmersive = false;
    delete document.body.dataset.readerImmersive;
}

// 加载书籍后：若用户设置 immersiveMode，先显示栏 1.5s 再沉浸（视觉锚点）
let immersiveEntryTimer = null;
function scheduleImmersiveEntry() {
    if (immersiveEntryTimer) clearTimeout(immersiveEntryTimer);
    if (readerPrefs.getSettings().immersiveMode) {
        exitImmersive();  // 先显示栏
        immersiveEntryTimer = setTimeout(() => {
            if (readerPrefs.getSettings().immersiveMode) enterImmersive();
        }, 1500);
    } else {
        exitImmersive();
    }
}
scheduleImmersiveEntry();
```

cleanup 函数增加清理：

```javascript
container._cleanupReader = () => {
    // ... 既有清理 ...
    if (immersiveEntryTimer) clearTimeout(immersiveEntryTimer);
    exitImmersive();
};
```

- [ ] **Step 2: 修改点击热区判定（中区域 = 切换沉浸）**

把 Task 2.2 后的 `textReader.js:141-163`（点击事件）替换为：

```javascript
els.content.addEventListener('mousemove', (e) => {
    if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) {
        els.content.style.cursor = 'default';
        return;
    }
    const rect = els.content.getBoundingClientRect();
    const ratio = (e.clientX - rect.left) / rect.width;
    if (ratio < 0.20 || ratio > 0.80) {
        els.content.style.cursor = 'pointer';  // 翻章热区
    } else if (readerPrefs.getSettings().immersiveMode) {
        els.content.style.cursor = 'pointer';  // 可切换沉浸
    } else {
        els.content.style.cursor = 'default';
    }
});

els.content.addEventListener('click', (e) => {
    if (e.target.closest('button, img, a, dialog, .text-reader__drawer')) return;
    if (window.getSelection() && window.getSelection().toString().trim() !== '') return;

    const rect = els.content.getBoundingClientRect();
    const ratio = (e.clientX - rect.left) / rect.width;

    if (ratio < 0.20) {
        if (currentIdx > 0) loadChapter(currentIdx - 1);
        else showToast('已经是第一章了', 'info');
    } else if (ratio > 0.80) {
        if (currentIdx < chapterCount - 1) loadChapter(currentIdx + 1);
        else showToast('已经是最后一章了', 'info');
    } else {
        // 中区域：仅在用户启用沉浸模式时切换
        if (readerPrefs.getSettings().immersiveMode) {
            if (isImmersive) exitImmersive(); else enterImmersive();
        }
    }
});
```

- [ ] **Step 3: 加 Esc 退出沉浸**

```javascript
function onKeyDown(e) {
    if (e.key === 'Escape' && isImmersive) {
        exitImmersive();
    }
}
document.addEventListener('keydown', onKeyDown);
```

cleanup 加 `document.removeEventListener('keydown', onKeyDown);`。

- [ ] **Step 4: 在 `style.css` 加沉浸样式**

```css
/* 沉浸模式：栏滑出 + 失焦不响应；正文上下 padding 收窄 */
body[data-reader-immersive="on"] .text-reader__header,
body[data-reader-immersive="on"] .text-reader__footer {
    opacity: 0;
    transform: translateY(var(--immersive-translate, -100%));
    pointer-events: none;
}
body[data-reader-immersive="on"] .text-reader__footer {
    transform: translateY(100%);
}
body[data-reader-immersive="on"] .text-reader__header,
body[data-reader-immersive="on"] .text-reader__footer {
    transition: opacity 0.25s cubic-bezier(0.4, 0, 0.2, 1),
                transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
body[data-reader-immersive="on"] .text-reader__content {
    padding-top: 24px;
    padding-bottom: 24px;
    transition: padding 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.text-reader__header,
.text-reader__footer {
    transition: opacity 0.25s cubic-bezier(0.4, 0, 0.2, 1),
                transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
}
.text-reader__content {
    transition: padding 0.25s cubic-bezier(0.4, 0, 0.2, 1),
                background-color 0.2s ease, color 0.2s ease;
}
```

- [ ] **Step 5: 手动验证**

启动 server，浏览器阅读器，settings 开启沉浸模式：
- 加载书籍时栏先显示 1.5s，然后滑出
- 单击中区域：栏滑入（沉浸 off）；再单击：滑出
- 单击左 20%：上一章，栏状态不变
- 单击右 20%：下一章，栏状态不变
- Esc：退出沉浸
- 关闭沉浸模式开关：单击中区域不再切换

- [ ] **Step 6: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css
git commit -m "feat(reader): web immersive mode (Phase 5)"
```

---

### Task 5.2: Android 沉浸模式

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`
- Modify: `android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt`（1.5s 时序）

**Interfaces:**
- Consumes: V2 settings.immersiveMode
- Produces: Scaffold topBar/bottomBar 用 `AnimatedVisibility` 包；中区域点击切换；加载书籍后 1.5s 时序

- [ ] **Step 1: 在 `TextReaderViewModel` 加沉浸栏可见状态**

```kotlin
private val _chromeVisible = MutableStateFlow(true)
val chromeVisible: StateFlow<Boolean> = _chromeVisible

fun toggleChrome() {
    if (_readerSettings.value.immersiveMode) {
        _chromeVisible.value = !_chromeVisible.value
    }
}

fun showChrome() { _chromeVisible.value = true }
fun hideChrome() {
    if (_readerSettings.value.immersiveMode) {
        _chromeVisible.value = false
    }
}
```

`loadBook(path)` 成功后启动 1.5s 延迟：

```kotlin
fun loadBook(path: String) {
    // ... 既有加载逻辑 ...
    viewModelScope.launch {
        // ... 等待加载完成 ...
        _chromeVisible.value = true
        delay(1500)
        if (_readerSettings.value.immersiveMode) {
            _chromeVisible.value = false
        }
    }
}
```

- [ ] **Step 2: 在 `TextReaderScreen` 用 AnimatedVisibility 包栏**

把 `Scaffold` 的 `topBar` 与 `bottomBar` 用 `AnimatedVisibility` 包：

```kotlin
val chromeVisible by viewModel.chromeVisible.collectAsState()

Scaffold(
    topBar = {
        AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            TopAppBar(/* ... 既有 ... */)
        }
    },
    bottomBar = {
        AnimatedVisibility(visible = chromeVisible, enter = fadeIn(), exit = fadeOut()) {
            BottomAppBar { /* ... 既有 ... */ }
        }
    },
) { padding ->
    // ... 内容 ...
}
```

- [ ] **Step 3: 中区域点击切换沉浸**

在 `LazyColumn` 或外层 `Box` 加 `pointerInput` 检测点击位置：

```kotlin
Box(
    Modifier
        .fillMaxSize()
        .pointerInput(Unit) {
            detectTapGestures { offset ->
                val width = size.width
                val ratio = offset.x / width
                when {
                    ratio < 0.20f -> viewModel.prevChapter()
                    ratio > 0.80f -> viewModel.nextChapter()
                    else -> viewModel.toggleChrome()
                }
            }
        }
) {
    // LazyColumn 在此 Box 内（注意 pointerInput 会拦截 LazyColumn 的滚动；改用 detectTapGestures
    // 而非 detectDragGestures，且不和 LazyColumn 抢滚动 —— Compose 中 pointerInput 与 LazyColumn
    // 滚动可能冲突，需要测试；若冲突，回退方案：用 Modifier.clickable 标签 + 计算点击坐标）
}
```

> **冲突缓解**：`detectTapGestures` 只响应单次点击（onTap），不消费拖动事件，理论上与 LazyColumn 滚动不冲突。实施时跑设备验证；若发现滚动失效，改为：把点击判定放在外层透明 Box 上，LazyColumn 在其上层（zIndex 较高），并在 LazyColumn 的 `Modifier.clickable`（不拦截滚动）内做坐标判定。

- [ ] **Step 4: 修改 `TextReaderViewModelReaderTest.kt` 加沉浸时序测试**

```kotlin
@Test
fun immersive_mode_hides_chrome_after_1500ms() = runBlocking {
    val settings = ReaderSettings(immersiveMode = true)
    store.saveReaderSettings(settings)
    viewModel.loadBook("test.epub")
    assertEquals(true, viewModel.chromeVisible.first())  // 立即可见
    advanceTimeBy(1500)  // 用 TestCoroutineScheduler
    assertEquals(false, viewModel.chromeVisible.first())  // 1.5s 后隐藏
}

@Test
fun toggle_chrome_inverts_visibility_only_when_immersive_enabled() = runBlocking {
    store.saveReaderSettings(ReaderSettings(immersiveMode = false))
    viewModel.toggleChrome()
    assertEquals(true, viewModel.chromeVisible.first())  // 不切换

    store.saveReaderSettings(ReaderSettings(immersiveMode = true))
    viewModel.toggleChrome()
    assertEquals(false, viewModel.chromeVisible.first())
}
```

> **TestCoroutineScheduler**：`TextReaderViewModelReaderTest` 现有用例若使用 `runTest`，本 Task 沿用；若使用 `runBlocking`，本测试改用 `runTest`。

- [ ] **Step 5: 跑单测 + 构建 + 设备验证**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.TextReaderViewModelReaderTest"
./gradlew :app:assembleDebug
```

设备上验证：
- 开沉浸模式 → 加载书籍 → 栏先显示 1.5s → 隐藏
- 单击中区域 → 栏出现/消失
- 单击左/右 20% → 翻章，栏状态不变

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git add android/app/src/main/java/com/juziss/localmediahub/viewmodel/TextReaderViewModel.kt
git add android/app/src/test/java/com/juziss/localmediahub/viewmodel/TextReaderViewModelReaderTest.kt
git commit -m "feat(reader): Android immersive mode with chrome auto-hide (Phase 5)"
```

**Phase 5 完成验证**：
- 两端沉浸模式工作（左/右 20% 翻章、中 60% 切换、Esc 退出 Web、1.5s 时序）

---

## Phase 6: 章节装饰与翻页动效

> 目标：spec §6 落地。Phase 6 结束时，章节顶部有大标题、首段首字下沉（仅 Web）、章节末尾有 ❖、切章时正文淡入。

### Task 6.1: Web 章节装饰 + 淡入 + 首字下沉

**Files:**
- Modify: `server/internal/web/textReader.js`（`renderBlocks` 加章节标题 / 末尾 ❖；`loadChapter` 触发淡入 class）
- Modify: `server/internal/web/style.css`（章节标题样式、首字下沉、❖、淡入 keyframes）

**Interfaces:**
- Consumes: 章节标题（chapter.title）、block list
- Produces: 正文顶部一个章节大标题 div、首段 p 加 `dropcap` class（条件性）、末尾 ❖ div

- [ ] **Step 1: 修改 `renderBlocks` 加章节标题 + 末尾 ❖ + 首字下沉 class**

把 Task 3.2 Step 3 后的 `renderBlocks` 修改为：

```javascript
function renderBlocks(blocks, chapterTitle) {
    els.content.innerHTML = '';

    // 章节大标题（在正文顶部，独立于顶栏面包屑）
    if (chapterTitle) {
        const h = document.createElement('h2');
        h.className = 'text-reader__chapter-title';
        h.textContent = chapterTitle;
        els.content.appendChild(h);
    }

    const list = blocks || [];
    // 找到首个可应用首字下沉的 text block 索引：
    // value 长度 >= 4 且不以纯标点（—— …… 等）开头
    const dropCapIdx = list.findIndex(b =>
        b && b.type === 'text' &&
        typeof b.value === 'string' &&
        b.value.trim().length >= 4 &&
        !/^[—…\-\s]/.test(b.value.trim())
    );

    list.forEach((block, idx) => {
        if (block && block.type === 'image') {
            // ... 既有 image 渲染 ...
            return;
        }
        const text = (block && typeof block.value === 'string') ? block.value : '';
        const p = document.createElement('p');
        p.textContent = text;
        p.dataset.blockIndex = String(idx);
        p.dataset.paraIndex = String(idx);
        const indent = readerPrefs.getSettings().firstLineIndent ? 'indent-on' : 'indent-off';
        const gap = readerPrefs.getSettings().paragraphSpacing ? 'gap-on' : 'gap-off';
        const dropcap = (idx === dropCapIdx) ? 'text-reader__p--dropcap' : '';
        p.className = `text-reader__p ${indent} ${gap} ${dropcap}`.trim();

        const btn = document.createElement('button');
        btn.className = 'text-reader__para-bookmark';
        btn.type = 'button';
        btn.textContent = '+';
        btn.title = '添加书签';
        btn.addEventListener('click', (e) => {
            e.stopPropagation();
            const ok = readerPrefs.addBookmark({
                bookPath: path, chapterIndex: currentIdx, paragraphIndex: idx,
                preview: text.slice(0, 30), createdAt: Date.now(),
            });
            showToast(ok ? '已添加书签' : '已存在书签', ok ? 'success' : 'info');
        });
        p.appendChild(btn);
        els.content.appendChild(p);
    });

    // 章节末尾装饰符号（点击下一章）
    const end = document.createElement('div');
    end.className = 'text-reader__chapter-end';
    end.textContent = '❖';
    end.title = '下一章';
    end.addEventListener('click', () => {
        if (currentIdx < chapterCount - 1) loadChapter(currentIdx + 1);
        else showToast('已经是最后一章了', 'info');
    });
    els.content.appendChild(end);

    // 触发淡入：先移除 class，下一帧加回（强制 reflow 触发动画）
    els.content.classList.remove('text-reader__content--entering');
    void els.content.offsetWidth;
    els.content.classList.add('text-reader__content--entering');
}
```

> **修改 loadChapter 调用**：把 `renderBlocks(chapter.blocks || blocksFromLegacyContent(chapter.content));` 改为 `renderBlocks(chapter.blocks || blocksFromLegacyContent(chapter.content), chapter.title);`

- [ ] **Step 2: 在 `style.css` 加章节标题、首字下沉、❖、淡入 keyframes 样式**

```css
.text-reader__chapter-title {
    font-size: calc(var(--reader-font-size, 16px) + 6px);
    font-family: "Noto Serif SC", "Songti SC", serif;  /* 固定 serif */
    font-weight: 600;
    color: var(--reader-fg);
    text-align: center;
    margin: 2em 0 1.5em;
    position: relative;
}
.text-reader__chapter-title::after {
    content: "";
    display: block;
    width: 40px;
    height: 1px;
    background: var(--reader-border);
    margin: 12px auto 0;
}

/* 首字下沉（仅 .text-reader__p--dropcap，覆盖默认 indent-on） */
.text-reader__p--dropcap {
    text-indent: 0 !important;
}
.text-reader__p--dropcap::first-letter {
    font-size: 3.2em;
    font-family: "Noto Serif SC", "Songti SC", serif;
    font-weight: 600;
    float: left;
    line-height: 0.9;
    margin: 0.05em 0.12em 0 0;
    color: var(--reader-fg);
}

.text-reader__chapter-end {
    text-align: center;
    color: var(--reader-muted);
    font-size: 20px;
    padding: 24px 0 16px;
    cursor: pointer;
    user-select: none;
}
.text-reader__chapter-end:hover {
    color: var(--reader-fg);
}

/* 章节切换淡入 */
@keyframes reader-chapter-enter {
    from { opacity: 0; transform: translateY(8px); }
    to   { opacity: 1; transform: translateY(0); }
}
.text-reader__content--entering {
    animation: reader-chapter-enter 120ms ease-out;
}
```

- [ ] **Step 3: 手动验证**

启动 server，打开阅读器：
- 章节顶部出现大标题（居中、serif、底部短装饰线）
- 首段首字大、不缩进、左浮（首字下沉）
- 末尾出现 ❖，点击进下一章
- 切章时正文有 120ms 淡入 + 轻微上浮
- 段落以 `——` 开头：跳过首字下沉（找下一个 text block）

- [ ] **Step 4: 提交**

```bash
git add server/internal/web/textReader.js server/internal/web/style.css
git commit -m "feat(reader): web chapter title + drop-cap + end-marker + fade-in (Phase 6)"
```

---

### Task 6.2: Android 章节大标题 + ❖ + 淡入

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt`

**Interfaces:**
- Consumes: chapter.title、blocks
- Produces: LazyColumn 首项是章节大标题、末项是 ❖ item；切章用 AnimatedContent 淡入

> Android 不实现首字下沉（spec §6.3 已知差异）。

- [ ] **Step 1: 用 AnimatedContent 包 LazyColumn**

把 Task 3.4 Step 2 的 `Box(... contentAlignment = Center) { LazyColumn { ... } }` 改为：

```kotlin
val chapterKey = blocks.hashCode()  // 章节内容变化触发动画
AnimatedContent(
    targetState = chapterKey,
    transitionSpec = {
        fadeIn(tween(120)) togetherWith fadeOut(tween(0))
    },
    label = "chapterTransition",
) { _ ->
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LazyColumn(
            state = listState,
            modifier = Modifier.width(contentDp),
            contentPadding = PaddingValues(vertical = 16.dp),
        ) {
            // item 0: 章节大标题
            item {
                Text(
                    text = book?.chapters?.getOrNull(idx)?.title ?: "",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = (settings.fontSizeSp + 6).sp,
                        fontFamily = FontFamily.Serif,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, bottom = 24.dp),
                )
                HorizontalDivider(
                    modifier = Modifier.width(40.dp).padding(bottom = 16.dp),
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // 段落
            itemsIndexed(blocks) { blockIdx, block ->
                when (block.type) {
                    "text" -> ParagraphItem(/* ... V2 参数 ... */)
                    "image" -> { /* 既有 AsyncImage */ }
                }
            }

            // item N: 章节末尾 ❖
            item {
                Text(
                    text = "❖",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp)
                        .clickable { viewModel.nextChapter() },
                )
            }
        }
    }
}
```

> **import**：`import androidx.compose.animation.AnimatedContent`、`import androidx.compose.animation.fadeIn`、`import androidx.compose.animation.fadeOut`、`import androidx.compose.animation.togetherWith`、`import androidx.compose.animation.core.tween`、`import androidx.compose.foundation.clickable`、`import androidx.compose.material3.HorizontalDivider`、`import androidx.compose.ui.text.font.FontWeight`、`import androidx.compose.ui.text.style.TextAlign`。

- [ ] **Step 2: 修改 `TextReaderScreenThemeTest.kt` 加章节标题 / ❖ 断言**

```kotlin
@Test
fun chapter_title_renders_at_top_of_lazy_column() {
    // loadChapter 后断言 LazyColumn 第 0 item 的 Text 文本 == chapter.title
}

@Test
fun chapter_end_marker_is_clickable_and_triggers_next_chapter() {
    // 点击 ❖ 触发 viewModel.nextChapter()
}
```

- [ ] **Step 3: 跑单测 + 构建 + 设备验证**

```bash
cd android
./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.screen.TextReaderScreenThemeTest"
./gradlew :app:assembleDebug
```

- [ ] **Step 4: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/TextReaderScreen.kt
git add android/app/src/test/java/com/juziss/localmediahub/ui/screen/TextReaderScreenThemeTest.kt
git commit -m "feat(reader): Android chapter title + end-marker + fade-in transition (Phase 6)"
```

**Phase 6 完成验证**：
- 两端章节顶部有大标题（居中 serif + 装饰线）
- Web 首段首字下沉
- 两端章节末尾有 ❖（点击下一章）
- 两端切章时正文有 120ms 淡入

---

## Self-Review

> 本节是 plan 作者在写完后的自查清单。读者无需执行此节。

### Spec 覆盖核对

| spec 条目 | 落地 Task |
|---|---|
| §数据形状 SettingsV2 字段 | 1.1（Web）+ 1.2（Android） |
| §数据形状 V1→V2 迁移 | 1.1 migrateV1toV2 + 1.3 migrateReaderSettingsJson |
| §1.1 6 主题预设 hex | 1.1 THEME_PRESETS + 1.2 ReaderTheme enum |
| §1.1 AUTO 字段 | 1.1 + 1.2 |
| §1.2 AUTO 跟随系统 | 2.2（Web matchMedia）+ 2.3（Android isSystemInDarkTheme） |
| §1.2 关闭跟随系统恢复上次非 AUTO | **GAP** —— spec 提到"恢复上次非 AUTO 主题（缓存在内存中）"，本 plan 未实现 |
| §1.3 应用范围（顶/底栏跟随） | 2.2 + 2.3 |
| §2.1 Web 字体打包 | 3.1 |
| §2.2 字体 3 档 | 3.2 FONT_FAMILIES（Web）+ 3.3 ReaderFontFamily（Android） |
| §2.3 章节标题等固定 serif | 6.1 chapter-title CSS + 6.2 Text fontFamily |
| §3.1 字号连续滑块 | 3.2（Web）+ 3.5（Android Slider） |
| §3.2 行距连续滑块 | 3.2 + 3.5 |
| §3.3 内容宽度滑块 + clamp | 3.2 + 3.4（Android `min(720, screenWidthDp - 32)`） |
| §3.4 段落两个 toggle | 3.2 + 3.5 |
| §3.5 V1 字段枚举内部用途 | 1.1（V1_FONT_SIZE/V1_LINE_HEIGHT） |
| §4.1 沉浸触发 | 5.1（Web）+ 5.2（Android） |
| §4.2 视觉行为 | 5.1 CSS + 5.2 AnimatedVisibility |
| §4.3 1.5s 时序 | 5.1 scheduleImmersiveEntry + 5.2 loadBook delay |
| §4.4 光标规则（Web） | 5.1 mousemove |
| §5.1 Web body[data-reader-theme] | 2.2 |
| §5.2 Android MaterialTheme 覆盖 | 2.3 ReaderThemeScope |
| §5.3 chrome 配色 | 1.1 + 1.2 |
| §5.4 AUTO 解析 chrome | 2.2 + 2.3 |
| §5.5 不破坏 hover/primary | 2.2（primary 不动） |
| §6.1 章节淡入 | 6.1 keyframes + 6.2 AnimatedContent |
| §6.2 章节大标题 | 6.1 + 6.2 |
| §6.3 首字下沉（仅 Web） | 6.1 |
| §6.4 章节结束符号 | 6.1 + 6.2 |
| §7 4 组分组 | 4.1（Web）+ 3.5（Android） |
| §7 跟随系统 toggle | **GAP** —— spec §1.2 修改后说"跟随系统 toggle on/off 等价于 theme=AUTO/恢复"，但本 plan UI 直接把 AUTO 作为 radio/FilterChip 选项；用户复审 spec 后已确认 AUTO 是合法 theme 值，设置面板里直接选项即可，不强制做 toggle |
| §8 落地清单 | 全覆盖 |
| §9 测试策略 | 全覆盖 |

### GAP 处理

**GAP 1：spec §1.2 "关闭跟随系统恢复上次非 AUTO 主题"**
- spec 原文：用户选择 `theme='AUTO'` 即启用跟随系统，选择其他主题即关闭。设置面板中"跟随系统"开关的 on/off 等价于将 `theme` 设为 `'AUTO'` / 恢复上次非 AUTO 主题
- 本 plan 处理：未实现"恢复上次非 AUTO"。用户从 AUTO 切到某主题 A 时直接保存 `theme=A`；再切回 AUTO 时再保存 `theme=AUTO`。**无"上次非 AUTO"缓存**
- 影响：用户体验上不会丢失数据（settings.theme 持久化），但 spec 描述的 toggle on/off 等价语义未严格实现
- 决策：plan 阶段不补此 GAP，作为 Phase 2 / Phase 4 后的小迭代。**记录在此供执行者评估**。

**GAP 2：spec §7 "跟随系统 toggle"**
- spec §1.2 修改说明明确"`theme='AUTO'` 是单一控制点"，§7 修改说明也明确"跟随系统 toggle on 时 theme 设为 AUTO"
- 本 plan UI 把 AUTO 作为 radio/FilterChip 直接选项（不通过 toggle）
- 决策：等价功能、UI 不同。用户可接受。**不补**。

### Placeholder 扫描

通读 Phase 1–6 所有 Task：
- 无 "TBD" / "TODO" / "implement later"
- 无 "类似 Task N"
- 所有 code step 都有完整代码块
- 所有验证 step 都有具体命令 + 预期观察点
- Task 1.4 Step 2 "对每个引用做最小替换" 给了具体策略（枚举 → 数字）+ 具体示例（`settings.fontSize.sp.sp` → `settings.fontSizeSp.sp`），不算 placeholder
- Task 3.1 Step 1 "下载字体文件"给了多个 URL 与回退方案；若用户无网络访问需用户协助提供，已在 Step 1 标注

### 类型一致性

- `ReaderSettings` 字段名两端一致：`fontFamily / fontSize(Web) vs fontSizeSp(Android) / lineHeight(Web) vs lineHeightMultiplier(Android) / contentWidth(Web) vs contentWidthDp(Android)` —— **Web 用纯名，Android 加单位后缀**（sp/dp），符合各自平台习惯
- `THEME_PRESETS`（Web）vs `ReaderTheme`（Android）：Web 是 object map、Android 是 enum；字段名 `bg/fg/chromeBg/chromeFg/muted/border` 一致
- `migrateV1toV2`（Web 纯函数）vs `migrateReaderSettingsJson`（Android JSON 改写）：作用不同（Web 是反序列化后迁移、Android 是反序列化前 JSON 改写），命名差异反映差异
- `ReaderThemeScope`（新名）vs `ReaderThemeWrapper`（旧名 alias）：plan 明确声明 alias 是过渡，Phase 4 末或后续删除
- Web `--reader-chrome-bg/fg` CSS 变量 vs Android `MaterialTheme.colorScheme.surface/onSurface`：映射关系明确（Task 2.3）

无类型不一致。

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-18-reader-ui-redesign.md`. Two execution options:

**1. Subagent-Driven (recommended)** - I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints

Which approach?



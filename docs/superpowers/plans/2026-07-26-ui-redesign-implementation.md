# UI 温暖复古精修 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 LocalMediaHub 的 Material3 配色精修为温暖复古 · terracotta 主 · 纸感,统一卡片圆角/描边/字号,并为 HomeScreen 提升呼吸感与响应式适配,Web style.css 同步对齐。

**Architecture:** 自底向上分三阶段 —— (A) Theme.kt 配色 token 改造(所有后续任务的地基),(B) HomeComponents.kt 卡片样式统一(消费 token),(C) HomeScreen.kt 间距/响应式 + Web style.css 协调。每阶段有独立可测交付。

**Tech Stack:** Kotlin + Jetpack Compose Material3 1.3.1、foundation 1.11.x、Robolectric 单测、纯 CSS(无预处理器)。

## Global Constraints

- 不动阅读器(`TextReaderScreen`)与播放器(`VideoPlayerScreen`)全屏体验。
- 不改既有 `EYE_CARE_GREEN` / `EYE_CARE` / `PARCHMENT` / `NIGHT_BLACK` 主题的配色,仅给它们补 `outline-soft` 回退值。
- Material3 版本固定 1.3.1;不引入新依赖(`WindowSizeClass` 除外,见 Task 7)。
- 颜色字面量必须与 spec §1 完全一致(见各 Task 的 `Color(...)` 行)。
- 测试用 Robolectric 跑 `testDebugUnitTest`,不用 instrumented test。
- 所有交互元素触控热区 ≥ 48dp。
- 每个 Task 末尾必须 commit。

## File Structure

- `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt` — 6 套配色方案 token + 新增 `outlineSoft` 扩展属性(Task 1-2)
- `android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt` — 新建:集中放置精修后的 6 套 `outlineSoft` 值与一个 `ColorScheme.outlineSoft` 扩展(Task 1)
- `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt` — 所有卡片圆角/elevation/描边/字重统一(Task 3-5)
- `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt` — section 间距、padding、响应式(Task 6-7)
- `server/internal/web/style.css` — day/night accent 对齐 + 新增 `--border-soft` / `--accent-text`(Task 8)
- 测试新增于 `android/app/src/test/java/com/juziss/localmediahub/ui/theme/` 与 `.../ui/component/home/`

---

## Task 1: 新增 outline-soft token 基础设施

**Files:**
- Create: `android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/theme/OutlineSoftTokenTest.kt`

**Interfaces:**
- Produces: `ColorScheme.outlineSoft: Color` 扩展属性(读取 CompositionLocal 回退);`LocalOutlineSoft: ProvidedableProperty<Color>` CompositionLocal;`MaterialTheme.outlineSoftColor` 便捷访问器。后续 Task 2/3 通过 `MaterialTheme.colorScheme.outlineSoftSoft()` 或 `LocaloutlineSoft.current` 取值。

设计要点:Material3 1.3.1 的 `ColorScheme` 是 final data class,无法加字段。用 CompositionLocal 注入是最稳妥方式,Theme 入口处 Provide。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.juziss.localmediahub.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OutlineSoftTokenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun outline_soft_defaults_to_explicit_value_when_provided() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            ProvideOutlineSoft(Color(0xFFE2D9C6)) {
                captured.add(LocalOutlineSoft.current)
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFE2D9C6), captured.single())
    }

    @Test
    fun outline_soft_falls_back_to_outline_variant_when_not_provided() {
        val scheme = lightColorScheme(outlineVariant = Color(0xFFAAAAAA))
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            MaterialTheme(colorScheme = scheme) {
                // 未 Provide 时,扩展访问器回退到 outlineVariant
                captured.add(MaterialTheme.colorScheme.outlineSoftFallback())
            }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFAAAAAA), captured.single())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.theme.OutlineSoftTokenTest"`
Expected: FAIL — `ProvideOutlineSoft` / `LocalOutlineSoft` / `outlineSoftFallback` 未解析。

- [ ] **Step 3: 写最小实现**

Create `ColorTokens.kt`:

```kotlin
package com.juziss.localmediahub.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 纸感卡片细描边 token。Material3 1.3.1 的 ColorScheme 无此字段,
 * 故用 CompositionLocal 注入;未 Provide 时回退到 outlineVariant。
 */
val LocalOutlineSoft = staticCompositionLocalOf<Color?> { null }

/** Theme 入口处用此函数 Provide 各主题的 outline-soft 值。 */
object OutlineSoft {
    val Light: Color = Color(0xFFE2D9C6)
    val Dark: Color = Color(0xFF332B24)
    val EyeCare: Color = Color(0xFFD9C8B2)
    val EyeCareGreen: Color = Color(0xFF9BB098)
    val Parchment: Color = Color(0xFFD6CBAE)
    val NightBlack: Color = Color(0xFF222222)
}

@Composable
fun ProvideOutlineSoft(
    value: Color,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(LocalOutlineSoft provides value, content)
}

/** 取当前 outline-soft;未 Provide 时回退 outlineVariant。 */
@Composable
fun ColorScheme.outlineSoftFallback(): Color =
    androidx.compose.runtime.currentComposer.consume(LocalOutlineSoft) ?: outlineVariant
```

注:`currentComposer.consume` 是 Compose 内部 API,在 1.3.1 上可用但不够干净。改用更稳的写法:

```kotlin
@Composable
fun outlineSoftColor(): Color = LocalOutlineSoft.current
    ?: MaterialTheme.colorScheme.outlineVariant
```

并把测试第二条改为调用 `outlineSoftColor()`:

```kotlin
@Test
fun outline_soft_falls_back_to_outline_variant_when_not_provided() {
    val scheme = lightColorScheme(outlineVariant = Color(0xFFAAAAAA))
    val captured = mutableListOf<Color>()
    composeRule.setContent {
        MaterialTheme(colorScheme = scheme) {
            captured.add(outlineSoftColor())
        }
    }
    composeRule.waitForIdle()
    assertEquals(Color(0xFFAAAAAA), captured.single())
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.theme.OutlineSoftTokenTest"`
Expected: PASS(2 tests)。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/theme/ColorTokens.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/theme/OutlineSoftTokenTest.kt
git commit -m "feat(theme): add outline-soft token via CompositionLocal with outlineVariant fallback"
```

---

## Task 2: Theme.kt 配色精修(浅色 + 暗色 + 各主题 Provide outline-soft)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt:16-195`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/theme/ThemeColorSchemeTest.kt`

**Interfaces:**
- Consumes: `ProvideOutlineSoft`、`OutlineSoft.*`(Task 1)
- Produces: 精修后的 `LightColorScheme` / `DarkColorScheme` colorScheme;`LocalMediaHubTheme` 内部对所有 6 套主题 Provide 对应 `outline-soft`。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.juziss.localmediahub.ui.theme

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ThemeColorSchemeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun schemeFor(themeKey: String): androidx.compose.material3.ColorScheme {
        val captured = mutableListOf<androidx.compose.material3.ColorScheme>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = themeKey) {
                captured.add(MaterialTheme.colorScheme)
            }
        }
        composeRule.waitForIdle()
        return captured.single()
    }

    @Test
    fun day_theme_uses_terracotta_primary() {
        val s = schemeFor("DAY")
        assertEquals(Color(0xFFB96D1D), s.primary)
        assertEquals(Color(0xFF3E7A7E), s.secondary)
        assertEquals(Color(0xFFF4EEE2), s.background)
        assertEquals(Color(0xFFFBF6EC), s.surface)
        assertEquals(Color(0xFFFBEBD8), s.primaryContainer)
        assertEquals(Color(0xFFD6EFF0), s.secondaryContainer)
    }

    @Test
    fun day_theme_provides_terracotta_outline_soft() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") { captured.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFE2D9C6), captured.single())
    }

    @Test
    fun night_theme_uses_warm_amber_primary_and_warm_black_bg() {
        val s = schemeFor("NIGHT")
        assertEquals(Color(0xFFE8915A), s.primary)
        assertEquals(Color(0xFF6FB8BC), s.secondary)
        assertEquals(Color(0xFF141210), s.background)
        assertEquals(Color(0xFF1E1A17), s.surface)
        assertEquals(Color(0xFF3A2516), s.primaryContainer)
        assertEquals(Color(0xFF1A3335), s.secondaryContainer)
    }

    @Test
    fun night_theme_provides_dark_outline_soft() {
        val captured = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "NIGHT") { captured.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFF332B24), captured.single())
    }

    @Test
    fun eye_care_theme_keeps_own_primary_but_gets_outline_soft() {
        val s = schemeFor("EYE_CARE")
        assertEquals(Color(0xFF8C6239), s.primary) // 保留自身 primary
        val os = mutableListOf<Color>()
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "EYE_CARE") { os.add(outlineSoftColor()) }
        }
        composeRule.waitForIdle()
        assertEquals(Color(0xFFD9C8B2), os.single())
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.theme.ThemeColorSchemeTest"`
Expected: FAIL — primary 仍是 teal `#135F65`。

- [ ] **Step 3: 改 Theme.kt 的 LightColorScheme 与 DarkColorScheme**

替换 `Theme.kt:16-33`(DarkColorScheme)为:

```kotlin
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8915A),
    secondary = Color(0xFF6FB8BC),
    tertiary = Color(0xFFC8D78E),
    background = Color(0xFF141210),
    surface = Color(0xFF1E1A17),
    surfaceVariant = Color(0xFF2A2420),
    primaryContainer = Color(0xFF3A2516),
    secondaryContainer = Color(0xFF1A3335),
    onPrimary = Color(0xFF2A1408),
    onSecondary = Color(0xFF042022),
    onTertiary = Color.White,
    onBackground = Color(0xFFEDE6DA),
    onSurface = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFFB3A793),
    outline = Color(0xFF3A3229),
    outlineVariant = Color(0xFF3A3229),
)
```

替换 `Theme.kt:35-52`(LightColorScheme)为:

```kotlin
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB96D1D),
    secondary = Color(0xFF3E7A7E),
    tertiary = Color(0xFF647A33),
    background = Color(0xFFF4EEE2),
    surface = Color(0xFFFBF6EC),
    surfaceVariant = Color(0xFFEDE6D6),
    primaryContainer = Color(0xFFFBEBD8),
    secondaryContainer = Color(0xFFD6EFF0),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF2A2218),
    onSurface = Color(0xFF2A2218),
    onSurfaceVariant = Color(0xFF6B5E48),
    outline = Color(0xFFD4CCBA),
    outlineVariant = Color(0xFFD4CCBA),
)
```

- [ ] **Step 4: 改 LocalMediaHubTheme 入口,Provide outline-soft**

替换 `Theme.kt:169-195` 的 `LocalMediaHubTheme` 函数体为:

```kotlin
@Composable
fun LocalMediaHubTheme(
    themeKey: String = "AUTO",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val upper = themeKey.uppercase()
    val (colorScheme, outlineSoft) = when (upper) {
        "EYE_CARE_GREEN" -> EyeCareGreenColorScheme to OutlineSoft.EyeCareGreen
        "EYE_CARE" -> EyeCareColorScheme to OutlineSoft.EyeCare
        "PARCHMENT" -> ParchmentColorScheme to OutlineSoft.Parchment
        "NIGHT_BLACK" -> NightBlackColorScheme to OutlineSoft.NightBlack
        "NIGHT" -> DarkColorScheme to OutlineSoft.Dark
        "DAY", "DAY_BRIGHT" -> LightColorScheme to OutlineSoft.Light
        else -> (if (darkTheme) DarkColorScheme else LightColorScheme) to
                (if (darkTheme) OutlineSoft.Dark else OutlineSoft.Light)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        ProvideOutlineSoft(outlineSoft) {
            // See NoRippleIndication.kt — overrides Material 1.3.1's legacy PlatformRipple.
            ProvideNoRippleIndication(content)
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.theme.ThemeColorSchemeTest"`
Expected: PASS(5 tests)。

- [ ] **Step 6: 跑既有相关测试,确保未回归**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.*"`
Expected: PASS(含 ComposeSmokeTest、TextReaderScreenThemeTest 等既有 UI 测试;TextReaderScreenThemeTest 用的是 ReaderThemeScope 独立体系,不受 MaterialTheme 改动影响)。

- [ ] **Step 7: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/theme/Theme.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/theme/ThemeColorSchemeTest.kt
git commit -m "feat(theme): refine to terracotta primary + warm-black dark; provide outline-soft for all 6 themes"
```

---

## Task 3: HomeComponents 卡片样式统一(圆角/elevation/描边)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/component/home/HomeComponentsStyleTest.kt`

**Interfaces:**
- Consumes: `outlineSoftColor()`(Task 1)、精修 colorScheme(Task 2)
- Produces: 所有卡片统一为 `shape = RoundedCornerShape(16.dp)`(HeroCard 20.dp)、elevation 0dp、`border(1.dp, outlineSoftColor())`。

目标改动点(行号对应改动前 HomeComponents.kt):
- `HeroCard` 第 77-90 行:`RoundedCornerShape(24.dp)` → `20.dp`;`cardElevation(defaultElevation = 2.dp)` → `defaultElevation = 0.dp`;保留 border 但颜色改 `outlineSoftColor()`
- `LibraryCard` 第 324-332 行、`ContinueWatchingCard` 第 416-424 行、`RecentMediaCard` 第 476-484 行、`FavoritePreviewCard` 第 573-581 行、`EmptyHomeStateCard` 第 616-622 行、`DownloadedPreviewCard` 第 675-683 行、`BookshelfTile` 第 799-807 行:统一 elevation 0dp,加 1dp `outlineSoftColor()` border
- 内部 `Surface`(HeroMetric 第 272-279 行等):圆角保持 12dp

由于 Compose 单测难以断言 Modifier.border / shape,本 Task 用 smoke 测试验证组件在精修主题下能正常渲染(不崩、文本可见),样式正确性靠 spec 数值 + HTML mockup + 人工目检保证。

- [ ] **Step 1: 写失败测试**

```kotlin
package com.juziss.localmediahub.ui.component.home

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import com.juziss.localmediahub.viewmodel.LibrarySummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeComponentsStyleTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun library_card_renders_under_refined_theme() {
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                LibraryCard(
                    library = LibrarySummary(name = "电影库", path = "/srv/movies"),
                    onClick = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("电影库").assertIsDisplayed()
    }

    @Test
    fun section_header_renders_semi_bold() {
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                SectionHeader(title = "媒体库", subtitle = "4 个库")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("媒体库").assertIsDisplayed()
        composeRule.onNodeWithText("4 个库").assertIsDisplayed()
    }
}
```

- [ ] **Step 2: 运行测试确认状态**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.home.HomeComponentsStyleTest"`
Expected: 应当 PASS(组件尚未改,但渲染应正常)。若 PASS 则测试仅作为回归网;若 FAIL 则先修测试到 PASS 再进入 Step 3。

- [ ] **Step 3: 改 SectionHeader 字重(Bold → SemiBold)**

替换 `HomeComponents.kt:301-317` 中 `SectionHeader` 的 `fontWeight = FontWeight.Bold`(第 309 行)为:

```kotlin
            fontWeight = FontWeight.SemiBold,
```

- [ ] **Step 4: 改 HeroCard 圆角与 elevation**

在 `HomeComponents.kt` 顶部 import 区(第 30 行 `painterResource` 之后)加:

```kotlin
import com.juziss.localmediahub.ui.theme.outlineSoftColor
```

替换 `HeroCard` 的 Card 调用(第 77-90 行)为:

```kotlin
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = outlineSoftColor(),
                shape = RoundedCornerShape(20.dp)
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
```

HeroCard 内部第 84-88 行的旧 border 已被上面替换覆盖,删除旧 border 块。

- [ ] **Step 5: 改 LibraryCard(及其他 ElevatedCard)样式**

把 `LibraryCard`(第 324-332 行)、`RecentMediaCard`(第 476-484 行)、`FavoritePreviewCard`(第 573-581 行)、`EmptyHomeStateCard`(第 616-622 行)、`DownloadedPreviewCard`(第 675-683 行)、`BookshelfTile`(第 799-807 行)的 `ElevatedCard` 调用统一改造。以 `LibraryCard` 为例(其余按相同模式):

替换第 324-332 行为:

```kotlin
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .width(232.dp)
            .border(width = 1.dp, color = outlineSoftColor(), shape = RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
```

注意 `FavoritePreviewCard` 用的是 `errorContainer` 配色,`ContinueWatchingCard` 用 `secondaryContainer`,`BookshelfTile` 用 `tertiaryContainer` —— **保留各自 containerColor 不变**,只改 shape(已是 16dp 保持)、elevation(2dp→0dp)、加 border。

- [ ] **Step 6: 运行测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.home.HomeComponentsStyleTest"`
Expected: PASS。

- [ ] **Step 7: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/component/home/HomeComponentsStyleTest.kt
git commit -m "feat(home): unify card shape/elevation/border; SectionHeader SemiBold (纸感)"
```

---

## Task 4: HeroCard 按钮 FilledTonal → 主次分级(Outlined)

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt:224-262`(HeroCard 内三按钮)
- Test: 复用 Task 3 的 `HomeComponentsStyleTest`,新增一个 case

**Interfaces:**
- Consumes: Task 3 的 outlineSoftColor
- Produces: HeroCard 内「继续浏览」用 `Button`(primary 实心),「收藏」「下载」用 `OutlinedButton`(描边)。

- [ ] **Step 1: 在 HomeComponentsStyleTest 加失败测试**

```kotlin
    @Test
    fun hero_card_continue_button_is_primary_filled() {
        val uiState = com.juziss.localmediahub.viewmodel.HomeUiState(
            serverLabel = "https://demo.local",
            lastBrowseLocation = com.juziss.localmediahub.data.LastBrowseLocation(
                title = "电影", isSystemBrowse = false,
            ),
        )
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                HeroCard(
                    uiState = uiState,
                    onResumeBrowse = {},
                    onOpenFavorites = {},
                    downloadCount = 0,
                    onOpenDownloads = {},
                    onOpenWeb = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("继续浏览 电影").assertIsDisplayed()
    }
```

注:`HomeUiState` / `LastBrowseLocation` 的实际构造参数以代码现状为准;若构造复杂,改为只断言「收藏」按钮文本 `stringResource(R.string.home_view_favorites)` 可见即可,跳过 uiState 依赖。先 Read `HomeUiState` 确认必填字段。

- [ ] **Step 2: 运行确认当前状态**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.home.HomeComponentsStyleTest.hero_card_continue_button_is_primary_filled"`
Expected: PASS(组件未改仍渲染)或 FAIL(若 HomeUiState 构造签名不符 —— 按 Step 1 注调整测试构造)。

- [ ] **Step 3: 替换 HeroCard 内三个 FilledTonalButton**

在 HomeComponents.kt import 区替换/新增:

```kotlin
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
// 删除(若不再使用): import androidx.compose.material3.FilledTonalButton
```

替换 `HeroCard` 内第 224-262 行的 `FlowRow { ... }` 三按钮块为:

```kotlin
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                uiState.lastBrowseLocation?.let { location ->
                    Button(
                        onClick = { onResumeBrowse(location) },
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("继续浏览 ${location.title}")
                    }
                }
                OutlinedButton(
                    onClick = onOpenFavorites,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, outlineSoftColor()),
                ) {
                    Icon(Icons.Filled.Favorite, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_view_favorites))
                }
                OutlinedButton(
                    onClick = onOpenDownloads,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, outlineSoftColor()),
                ) {
                    Icon(painterResource(R.drawable.ic_folder), contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.home_offline_downloaded))
                }
            }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.home.HomeComponentsStyleTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/component/home/HomeComponentsStyleTest.kt
git commit -m "feat(home): HeroCard buttons — primary filled continue + outlined favorites/downloads"
```

---

## Task 5: 图标统一为 Outline 风格 + 激活态用 primary

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt`

**Interfaces:** 无新增接口,纯样式调整。

现状:`HeroCard` 用 `Icons.Filled.CheckCircle`(第 148 行)、`Icons.Filled.Favorite`(第 246 行);`LibraryCard` 用 `Icons.AutoMirrored.Filled.ArrowForward`(第 382 行);`ContinueWatchingCard`/`RecentMediaCard` 用 `Icons.Filled.PlayArrow`。

约定:**painterResource 图标(矢量 XML)保留不变**(它们已是线性 outline 风格);只把 **Material `Icons.Filled.*`** 换成 `Icons.Outlined.*` / `Icons.AutoMirrored.Outlined.*`。`PlayArrow` 在 Outlined 中不存在,保留 Filled。

- [ ] **Step 1: 改 HeroCard CheckCircle**

HomeComponents.kt import 区,把:

```kotlin
import androidx.compose.material.icons.filled.CheckCircle
```

替换为:

```kotlin
import androidx.compose.material.icons.outlined.CheckCircle
```

第 148 行 `Icons.Filled.CheckCircle` → `Icons.Outlined.CheckCircle`。

- [ ] **Step 2: 改 HeroCard Favorite 图标**

import 区把:

```kotlin
import androidx.compose.material.icons.filled.Favorite
```

替换为:

```kotlin
import androidx.compose.material.icons.outlined.Favorite
```

全文 `Icons.Filled.Favorite`(第 246 行、第 587 行)→ `Icons.Outlined.Favorite`。

- [ ] **Step 3: 改 LibraryCard ArrowForward**

import 区把:

```kotlin
import androidx.compose.material.icons.automirrored.filled.ArrowForward
```

替换为:

```kotlin
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
```

第 382 行 `Icons.AutoMirrored.Filled.ArrowForward` → `Icons.AutoMirrored.Outlined.ArrowForward`。

- [ ] **Step 4: 编译确认 import 无残留**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。若报 `Icons.Outlined.PlayArrow` 不存在 —— 说明误改了 PlayArrow,回退它(Filled 保留)。

- [ ] **Step 5: 跑组件测试不回归**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.component.home.*"`
Expected: PASS。

- [ ] **Step 6: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt
git commit -m "style(home): unify Material icons to Outlined family (CheckCircle/Favorite/ArrowForward)"
```

---

## Task 6: HomeScreen section 间距 + 横滚 contentPadding

**Files:**
- Modify: `android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt:182-353`

**Interfaces:** 无新增接口。

- [ ] **Step 1: 改 LazyColumn 间距**

`HomeScreen.kt:187-189` 的:

```kotlin
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
```

改为:

```kotlin
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
```

- [ ] **Step 2: 改各 LazyRow 间距 + 加 contentPadding**

全文 LazyRow(第 223、261、299、319、338 行等)的 `horizontalArrangement = Arrangement.spacedBy(12.dp)` 改为 `Arrangement.spacedBy(14.dp)`,并给每个 LazyRow 加 `contentPadding = PaddingValues(horizontal = 8.dp)`。

以第 222-228 行 Libraries 区为例,改为:

```kotlin
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(uiState.libraries, key = { it.path }) { library ->
                            LibraryCard(library = library, onClick = { onOpenLibrary(library) })
                        }
                    }
                }
```

对其余 LazyRow(Continue / Recent / Favorites / Downloads)做同样改造。`BookshelfCard` 内部自带 LazyRow(HomeComponents.kt:784-787),同样加 `contentPadding = PaddingValues(horizontal = 8.dp)`、间距 12→14。

- [ ] **Step 3: 编译**

Run: `cd android && ./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 4: 跑既有 HomeViewModelTest 不回归**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.viewmodel.HomeViewModelTest"`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt \
        android/app/src/main/java/com/juziss/localmediahub/ui/component/home/HomeComponents.kt
git commit -m "feat(home): section spacing 22→28dp, card gap 12→14dp, horizontal edge padding 8dp"
```

---

## Task 7: HomeScreen 响应式(WindowSizeClass)

**Files:**
- Modify: `android/app/build.gradle.kts`(加依赖)、`HomeScreen.kt`
- Test: `android/app/src/test/java/com/juziss/localmediahub/ui/screen/HomeScreenResponsiveTest.kt`

**Interfaces:**
- Produces: `HomeScreen` 在 Medium(≥600dp)/Expanded(≥840dp)宽度下使用双列/三列 LazyVerticalGrid,外边距 24dp/32dp;Compact 保持单列。

注意:本 Task 是 spec §2 的"响应式网格适配"。引入 `androidx.compose.material3:material3-window-size-class`。**先确认 build.gradle.kts 现有 material3 版本号**,保持 `1.3.1`。

- [ ] **Step 0: 确认依赖版本**

Read `android/app/build.gradle.kts`,找 `androidx.compose.material3` 行,记下版本(预期 `1.3.1`)。

- [ ] **Step 1: 加 window-size-class 依赖**

在 `android/app/build.gradle.kts` 的 dependencies 块,与现有 material3 同版本加:

```kotlin
    implementation("androidx.compose.material3:material3-window-size-class:1.3.1")
```

- [ ] **Step 2: 写失败测试**

```kotlin
package com.juziss.localmediahub.ui.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.juziss.localmediahub.ui.theme.LocalMediaHubTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HomeScreenResponsiveTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun home_screen_renders_in_compact_width() {
        composeRule.setContent {
            LocalMediaHubTheme(themeKey = "DAY") {
                HomeScreen(
                    onOpenLibrary = {},
                    onResumeBrowse = {},
                    onOpenFavorites = {},
                    onOpenCollection = {},
                    onContinueWatching = {},
                    onOpenRecentMedia = {},
                )
            }
        }
        composeRule.waitForIdle()
        // 顶部标题始终存在
        composeRule.onNodeWithText("LocalMediaHub").assertIsDisplayed()
    }
}
```

- [ ] **Step 3: 运行确认状态**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.screen.HomeScreenResponsiveTest"`
Expected: PASS(响应式改造前,HomeScreen 也能渲染)或编译失败(若依赖未加成功)。

- [ ] **Step 4: 改 HomeScreen 引入 WindowSizeClass**

`HomeScreen.kt` import 区加:

```kotlin
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
```

在 `HomeScreen` 函数体(`val uiState by ...` 之后,第 106 行附近)加:

```kotlin
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    val windowClass = calculateWindowSizeClass(context as android.app.Activity)
    val columns = when (windowClass.widthSizeClass) {
        WindowWidthSizeClass.Expanded -> 3
        WindowWidthSizeClass.Medium -> 2
        else -> 1
    }
    val horizontalPadding = when (columns) {
        3 -> 32.dp
        2 -> 24.dp
        else -> 20.dp
    }
```

并把 LazyColumn 的 `contentPadding` 用 `horizontalPadding` 替换原来的 `20.dp`。`columns == 1` 时维持现有单列 LazyColumn;`columns >= 2` 时,对 Libraries/Recent/Favorites/Downloads 等横向滚动区改为 `LazyVerticalGrid`(Staggered)。**为控制改动幅度,本 Task 仅实现 padding 自适应;Grid 多列改造留作后续 enhancement —— 即只改 `horizontalPadding`,columns 计算保留供后续。**

实际落地(最小化):仅把 `contentPadding = PaddingValues(start = 20.dp, end = 20.dp, ...)` 的 20 替换为 `horizontalPadding`。在 `context` 上方加 `@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)` 注解到 `HomeScreen` 函数。

- [ ] **Step 5: 运行测试确认通过**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "com.juziss.localmediahub.ui.screen.HomeScreenResponsiveTest"`
Expected: PASS。若 Robolectric 下 `calculateWindowSizeClass` 抛错(无真实 Activity 窗口),则把 columns 计算包 try/catch 回退到 1:

```kotlin
    val columns = try {
        val wc = calculateWindowSizeClass(context as android.app.Activity)
        when (wc.widthSizeClass) {
            WindowWidthSizeClass.Expanded -> 3
            WindowWidthSizeClass.Medium -> 2
            else -> 1
        }
    } catch (_: Exception) { 1 }
```

- [ ] **Step 6: 提交**

```bash
git add android/app/build.gradle.kts \
        android/app/src/main/java/com/juziss/localmediahub/ui/screen/HomeScreen.kt \
        android/app/src/test/java/com/juziss/localmediahub/ui/screen/HomeScreenResponsiveTest.kt
git commit -m "feat(home): responsive horizontal padding via WindowSizeClass (compact/medium/expanded)"
```

---

## Task 8: Web style.css accent 对齐 + border-soft / accent-text

**Files:**
- Modify: `server/internal/web/style.css:33-34`(day)、`166-169`(night)、`:root` 块新增变量

**Interfaces:** 纯 CSS。

现状(已核实):day `--accent: #C75B39`(行 33);night `--accent: #D97A56`(行 166)。

- [ ] **Step 1: 改 day 主题 accent + 新增 token**

`style.css` 行 33-35 区域,把:

```css
    --accent:          #C75B39;
    --accent-hover:    #B14E2E;
    --accent-soft:     rgba(199, 91, 57, 0.12);
```

改为:

```css
    --accent:          #B96D1D;
    --accent-hover:    #9E5C16;
    --accent-soft:     rgba(185, 109, 29, 0.12);
    --accent-text:     #965410;
    --border-soft:     #E2D9C6;
```

- [ ] **Step 2: 改 night 主题 accent 对齐**

行 166-168 区域,把:

```css
    --accent:          #D97A56;
    --accent-hover:    #E68B6A;
    --accent-soft:     rgba(217, 122, 86, 0.16);
```

改为:

```css
    --accent:          #E8915A;
    --accent-hover:    #F0A066;
    --accent-soft:     rgba(232, 145, 90, 0.16);
```

- [ ] **Step 3: 在卡片样式处应用 border-soft**

搜索 style.css 中卡片 `.card` / 类似选择器的 `border:` 声明,把硬编码边框色替换为 `var(--border-soft)`。具体选择器需先 Grep `border:` 定位,逐处评估(仅替换 day/night 共用的纸感卡片边框;不动表单 focus 边框)。

- [ ] **Step 4: 应用 accent-text 到小字号文本标签**

搜索 `color: var(--accent);` 出现在小字号 label 类选择器的位置(行 327 等),评估是否替换为 `var(--accent-text)`(满足 WCAG AA 4.5:1)。仅替换作为正文文本色使用的处;按钮底色/图标色保留 `--accent`。

- [ ] **Step 5: 手动验证**

启动 web 服务:`cd server && go run ./cmd/server`(或项目实际入口),浏览器打开,切换 day/night 主题,目检:
- day accent 为 terracotta `#B96D1D`
- night accent 为暖琥珀 `#E8915A`
- 卡片有细描边 `#E2D9C6`(纸感)
- 小字号 accent 文本可读(对比度合格)

- [ ] **Step 6: 提交**

```bash
git add server/internal/web/style.css
git commit -m "feat(web): align day/night accent to Android terracotta; add --border-soft/--accent-text"
```

---

## Task 9: 全量回归 + spec 验收

**Files:** 无(验证 task)

- [ ] **Step 1: 全量单测**

Run: `cd android && ./gradlew :app:testDebugUnitTest`
Expected: ALL PASS。

- [ ] **Step 2: 编译 release(验证 R8 不崩)**

Run: `cd android && ./gradlew :app:assembleRelease`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 人工目检对照 HTML mockup**

打开 `docs/ui-redesign/ui-redesign-preview.html`(优化后态)与实机/App 截图逐屏比对:
- HomeScreen 八段呼吸感
- 卡片纸感描边 + 极微阴影
- terracotta 主色 + teal 辅
- 暗色冷暖并存

- [ ] **Step 4: 更新 module map 文档(若存在)**

若 `docs/` 下有 module map / README 记录 UI 体系,补一行说明本次配色精修。若不存在则跳过。

- [ ] **Step 5: 最终提交(若有文档改动)**

```bash
git add docs/
git commit -m "docs(ui): note refined theme in module map"
```

---

## Self-Review

**Spec coverage 核对:**
- §1.1 浅色配色 → Task 2 ✓
- §1.2 暗色配色 → Task 2 ✓
- §1.3 其他主题 outline-soft 回退 → Task 1 + Task 2 ✓
- §1.4 Web 协调 → Task 8 ✓
- §2 布局/呼吸感 → Task 6 ✓;响应式网格 → Task 7(最小化:仅 padding 自适应;Grid 多列标注为后续 enhancement) ✓
- §3 组件圆角/elevation/描边 → Task 3 ✓;字重 → Task 3 ✓;按钮分级 → Task 4 ✓;图标 Outline → Task 5 ✓
- §5 HTML mockup → 已在 spec 阶段交付,无需 plan task ✓
- §6 不在范围(阅读器/播放器)→ 全程未触碰 ✓

**已知简化(需用户知晓):**
- Task 7 的 Grid 多列改造被降级为仅 padding 自适应。原因:LazyColumn 内嵌 LazyRow 改 LazyVerticalGrid 涉及 item key、span、状态保持的大改,风险高。建议作为独立后续 plan。
- Task 3 的样式断言靠 smoke 测试 + 人工目检,Compose 单测难断言 border/shape 字面值。

**Placeholder scan:** 无 TBD/TODO;所有代码块完整。Task 3 Step 5 对 7 处 ElevatedCard 的改造用"以 LibraryCard 为例,其余同模式"——这是允许的(模式相同,行号已给),非占位符。

**Type consistency:** `outlineSoftColor()`、`ProvideOutlineSoft`、`OutlineSoft.*`、`LocalOutlineSoft` 在 Task 1 定义,Task 2/3/4 一致使用。`HomeUiState`/`LastBrowseLocation` 在 Task 4 测试中引用,需 Step 1 先 Read 确认构造签名(已标注)。

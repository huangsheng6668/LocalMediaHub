package com.juziss.localmediahub.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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

/** 在 Theme 入口处包裹 content 以注入 outline-soft 值。 */
@Composable
fun ProvideOutlineSoft(
    value: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalOutlineSoft provides value, content)
}

/** 取当前 outline-soft;未 Provide 时回退到 outlineVariant。 */
@Composable
fun outlineSoftColor(): Color =
    LocalOutlineSoft.current ?: MaterialTheme.colorScheme.outlineVariant

/**
 * 小字号文本标签的 primary 高对比变体（WCAG AA 4.5:1 on surface）。
 * primary 在纸感 surface 上对比度不足；文本标签用此 token。
 * 未 Provide 时回退 colorScheme.primary。
 */
val LocalPrimaryText = staticCompositionLocalOf<Color?> { null }

object PrimaryText {
    val Light: Color = Color(0xFF965410)
    val Dark: Color = Color(0xFFF2A878)        // 与 Web --accent-text 对齐
    val EyeCare: Color = Color(0xFF6B4A2A)      // 棕系深化，AA on #F5EBDC
    val EyeCareGreen: Color = Color(0xFF1F3A23) // 深绿，AA on #B9C7B6
    val Parchment: Color = Color(0xFF4A3520)    // 深棕，AA on #F4ECD8
    val NightBlack: Color = Color(0xFFE0E0E0)   // 暗色高对比白
}

/** 在 Theme 入口处包裹 content 以注入 primary-text 值。 */
@Composable
fun ProvidePrimaryText(
    value: Color,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalPrimaryText provides value, content)
}

/** 取当前 primary-text;未 Provide 时回退到 colorScheme.primary。 */
@Composable
fun primaryTextColor(): Color =
    LocalPrimaryText.current ?: MaterialTheme.colorScheme.primary

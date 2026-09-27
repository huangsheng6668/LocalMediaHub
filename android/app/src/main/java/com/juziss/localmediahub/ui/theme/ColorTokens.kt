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
    val Light: Color = Color(0xFFE5E4DF)
    val Dark: Color = Color(0xFF22242B)
    val EyeCare: Color = Color(0xFFE2D9C4)
    val EyeCareGreen: Color = Color(0xFFC6D4C2)
    val Parchment: Color = Color(0xFFDCD0B5)
    val NightBlack: Color = Color(0xFF1C1E24)
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
    val Light: Color = Color(0xFF2F5640)      // moss 深化，AA on 暖灰纸面
    val Dark: Color = Color(0xFFA3CDAF)        // 与 Web --accent-text 对齐
    val EyeCare: Color = Color(0xFF3C5843)     // moss 深化，AA on #F5F1E6
    val EyeCareGreen: Color = Color(0xFF2A4831) // 深绿，AA on #DDE6DA
    val Parchment: Color = Color(0xFF3F5837)    // 深绿，AA on #EFE8D5
    val NightBlack: Color = Color(0xFFE7E9EE)   // 暗色高对比白
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

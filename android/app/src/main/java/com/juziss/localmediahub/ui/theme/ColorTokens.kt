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

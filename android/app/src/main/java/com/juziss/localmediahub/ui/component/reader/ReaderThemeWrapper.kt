package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import com.juziss.localmediahub.ui.theme.ProvideNoRippleIndication

/**
 * 解析后的阅读器完整配色。CUSTOM 主题的 chrome/border 从三色派生
 * （chromeBg=bg、chromeFg=fg、border=muted），语义与 Web 端一致。
 */
data class ReaderColors(
    val bg: Color,
    val fg: Color,
    val chromeBg: Color,
    val chromeFg: Color,
    val muted: Color,
    val border: Color,
) {
    companion object {
        fun fromTheme(t: ReaderTheme) =
            ReaderColors(t.bg, t.fg, t.chromeBg, t.chromeFg, t.muted, t.border)
    }
}

/** CUSTOM 主题的三色；null 表示该项回退到系统深浅对应的 DAY/NIGHT 预设。 */
data class CustomReaderColors(val bg: Color?, val fg: Color?, val muted: Color?)

/**
 * 纯函数解析（便于单测）：AUTO → DAY/NIGHT；CUSTOM → 三色 + 派生色，
 * null 回退；其余主题 → 自身预设色。
 */
internal fun resolveReaderColors(
    theme: ReaderTheme,
    isDark: Boolean,
    custom: CustomReaderColors?,
): ReaderColors = when (theme) {
    ReaderTheme.AUTO -> ReaderColors.fromTheme(ReaderTheme.resolveAuto(isDark))
    ReaderTheme.CUSTOM -> {
        val fb = ReaderColors.fromTheme(ReaderTheme.resolveAuto(isDark))
        ReaderColors(
            bg = custom?.bg ?: fb.bg,
            fg = custom?.fg ?: fb.fg,
            chromeBg = custom?.bg ?: fb.chromeBg,
            chromeFg = custom?.fg ?: fb.chromeFg,
            muted = custom?.muted ?: fb.muted,
            border = custom?.muted ?: fb.border,
        )
    }
    else -> ReaderColors.fromTheme(theme)
}

/** 解析 #RRGGBB；非法或 null 返回 null。 */
internal fun String?.toComposeColorOrNull(): Color? {
    val h = this?.removePrefix("#") ?: return null
    if (h.length != 6 || h.toLongOrNull(16) == null) return null
    return Color(0xFF000000L or h.toLong(16))
}

/** 把 settings 的 hex 三色解析为 [CustomReaderColors]（非法/缺失 → null → 渲染处回退）。 */
fun ReaderSettings.toCustomReaderColors(): CustomReaderColors =
    CustomReaderColors(
        bg = customBg.toComposeColorOrNull(),
        fg = customFg.toComposeColorOrNull(),
        muted = customMuted.toComposeColorOrNull(),
    )

/**
 * 把整个阅读器（含 TopAppBar / BottomAppBar / ModalDrawerSheet / BottomSheets）
 * 限制在 reader theme 内：背景用 theme.bg、Material3 colorScheme 用 copy()
 * 局部覆盖 surface/background/onSurface* 等字段，使所有 Material 组件自动跟随。
 *
 * AUTO 自动根据 [isSystemInDarkTheme] 解析为 DAY/NIGHT（Phase 2 Task 2.3）。
 *
 * 命名为 ReaderThemeScope 而非 ReaderThemeWrapper，强调其作用范围是整个阅读器
 * 子树，不只是正文 Box（旧名暗示只包正文）。
 */
@Composable
fun ReaderThemeScope(
    theme: ReaderTheme,
    bgImageUri: String? = null,
    customColors: CustomReaderColors? = null,
    content: @Composable () -> Unit,
) {
    val resolved = resolveReaderColors(theme, isSystemInDarkTheme(), customColors)
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
            ProvideNoRippleIndication {
                Box(Modifier.background(resolved.bg)) {
                    if (!bgImageUri.isNullOrBlank()) {
                        AsyncImage(
                            model = bgImageUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(resolved.bg.copy(alpha = 0.70f))
                        )
                    }
                    content()
                }
            }
        }
    }
}

/** 旧名兼容别名；新代码请使用 [ReaderThemeScope]。 */
@Composable
fun ReaderThemeWrapper(
    theme: ReaderTheme,
    bgImageUri: String? = null,
    customColors: CustomReaderColors? = null,
    content: @Composable () -> Unit,
) = ReaderThemeScope(theme = theme, bgImageUri = bgImageUri, customColors = customColors, content = content)


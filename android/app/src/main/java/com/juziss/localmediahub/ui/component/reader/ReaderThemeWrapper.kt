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
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import com.juziss.localmediahub.data.ReaderTheme
import com.juziss.localmediahub.ui.theme.ProvideNoRippleIndication

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
    content: @Composable () -> Unit,
) = ReaderThemeScope(theme = theme, bgImageUri = bgImageUri, content = content)


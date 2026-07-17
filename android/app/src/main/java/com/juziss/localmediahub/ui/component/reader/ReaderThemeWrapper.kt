package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.material3.LocalContentColor
import com.juziss.localmediahub.data.ReaderTheme

/**
 * Wraps [content] with a themed background and foreground color so the
 * reading area honors the user's day/night/eye-care preference without
 * affecting the App-level MaterialTheme. TopAppBar, BottomAppBar and
 * BottomSheets stay on the system Material theme.
 *
 * Named ReaderThemeWrapper (not ReaderTheme) to avoid Kotlin compiler
 * ambiguity with the [ReaderTheme] enum of the same name.
 */
@Composable
fun ReaderThemeWrapper(theme: ReaderTheme, content: @Composable () -> Unit) {
    Box(Modifier.background(theme.bg)) {
        CompositionLocalProvider(LocalContentColor provides theme.fg) {
            content()
        }
    }
}

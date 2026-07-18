package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme

/**
 * Modal bottom sheet exposing the 4 reading preferences. Each control fires
 * [onChange] immediately — there is no Apply button. The caller persists the
 * new settings and recomposes the reader with them.
 *
 * The body is delegated to [ReaderSettingsSheetContent] so it can be unit
 * tested in isolation — [ModalBottomSheet] hosts its content in a separate
 * window whose input dispatch is not reliably drivable under Robolectric.
 *
 * Phase 1: font size + line height are still 4 / 3 discrete options (V1 UX
 * preserved), but the underlying values are now V2 numeric (Int / Float)
 * rather than enum. Phase 3/4 will replace this with continuous sliders.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ReaderSettingsSheetContent(settings = settings, onChange = onChange)
    }
}

// V1 → V2 transition: keep the 4-step font / 3-step line-height option lists
// (V1 visual) but as numeric primitives matching the V2 ReaderSettings shape.
private val FONT_SIZE_OPTIONS = listOf(14, 16, 18, 20)
private val LINE_HEIGHT_OPTIONS = listOf(1.4f, 1.8f, 2.2f)

private fun fontSizeLabel(sp: Int): String = when (sp) {
    14 -> "小"
    16 -> "中"
    18 -> "大"
    20 -> "超大"
    else -> sp.toString()
}

private fun lineHeightLabel(m: Float): String = when (m) {
    1.4f -> "紧凑"
    1.8f -> "标准"
    2.2f -> "宽松"
    else -> m.toString()
}

/**
 * The four-section body of [ReaderSettingsSheet]. Public so tests can compose
 * it directly without the [ModalBottomSheet] host (whose separate window
 * does not dispatch input events reliably under Robolectric).
 */
@Composable
fun ReaderSettingsSheetContent(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(16.dp))

        // Section: font size
        Text("字体大小", style = MaterialTheme.typography.labelLarge)
        ChipRow(
            options = FONT_SIZE_OPTIONS,
            selected = settings.fontSizeSp,
            labelFor = { fontSizeLabel(it) },
            onSelect = { onChange(settings.copy(fontSizeSp = it)) },
        )
        Spacer(Modifier.size(16.dp))

        // Section: line height
        Text("行距", style = MaterialTheme.typography.labelLarge)
        ChipRow(
            options = LINE_HEIGHT_OPTIONS,
            selected = settings.lineHeightMultiplier,
            labelFor = { lineHeightLabel(it) },
            onSelect = { onChange(settings.copy(lineHeightMultiplier = it)) },
        )
        Spacer(Modifier.size(16.dp))

        // Section: theme (chip with color dot)
        Text("主题", style = MaterialTheme.typography.labelLarge)
        ThemeChipRow(
            selected = settings.theme,
            onSelect = { onChange(settings.copy(theme = it)) },
        )
        Spacer(Modifier.size(16.dp))

        // Section: auto-scroll speed (1..10 slider)
        Text("自动滚动速度", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = settings.autoScrollSpeed.toFloat(),
                onValueChange = { onChange(settings.copy(autoScrollSpeed = it.toInt().coerceIn(1, 10))) },
                valueRange = 1f..10f,
                steps = 8,  // 10 discrete stops (1, 2, ..., 10)
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text("${settings.autoScrollSpeed}")
        }
    }
}

@Composable
private fun <T> ChipRow(
    options: List<T>,
    selected: T,
    labelFor: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            FilterChip(
                selected = opt == selected,
                onClick = { onSelect(opt) },
                label = { Text(labelFor(opt)) },
                colors = FilterChipDefaults.filterChipColors(),
            )
        }
    }
}

@Composable
private fun ThemeChipRow(
    selected: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ReaderTheme.entries.forEach { theme ->
            FilterChip(
                selected = theme == selected,
                onClick = { onSelect(theme) },
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(theme.bg)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(theme.label)
                    }
                },
            )
        }
    }
}

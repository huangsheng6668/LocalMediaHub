package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.juziss.localmediahub.data.ReaderSettings
import com.juziss.localmediahub.data.ReaderTheme
import kotlin.math.roundToInt

/**
 * Modal bottom sheet exposing the V2 reader preferences. Each control fires
 * [onChange] immediately — there is no Apply button. The caller persists the
 * new settings and recomposes the reader with them.
 *
 * The body is delegated to [ReaderSettingsSheetContent] so it can be unit
 * tested in isolation — [ModalBottomSheet] hosts its content in a separate
 * window whose input dispatch is not reliably drivable under Robolectric.
 *
 * Phase 3: discrete font-size / line-height chips replaced with continuous
 * sliders; font-family FilterChips, content-width slider, and
 * first-line-indent + paragraph-spacing switches added.
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

/**
 * The four-section body of [ReaderSettingsSheet]. Public so tests can compose
 * it directly without the [ModalBottomSheet] host (whose separate window
 * does not dispatch input events reliably under Robolectric).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsSheetContent(
    settings: ReaderSettings,
    onChange: (ReaderSettings) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
        Text("阅读设置", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(12.dp))

        // ── 外观 ──
        Section("外观")

        // 字体
        Text("字体", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ReaderFontFamily.entries.forEach { ff ->
                FilterChip(
                    selected = settings.fontFamily == ff,
                    onClick = { onChange(settings.copy(fontFamily = ff)) },
                    label = { Text(ff.label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        // 主题（Phase 2 已落地，保留）
        Text("主题", style = MaterialTheme.typography.labelMedium)
        ThemeChipRow(
            selected = settings.theme,
            onSelect = { onChange(settings.copy(theme = it)) },
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ── 字号与行距 ──
        Section("字号与行距")

        // 字号 Slider 12..28 step 1 (15 steps -> steps param = 15)
        Text("字号 ${settings.fontSizeSp}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.fontSizeSp.toFloat(),
            onValueChange = { onChange(settings.copy(fontSizeSp = it.roundToInt().coerceIn(12, 28))) },
            valueRange = 12f..28f,
            steps = 15,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("fontSizeSlider"),
        )
        Spacer(Modifier.size(8.dp))

        // 行距 Slider 1.3..2.5 step 0.1 (11 steps -> steps param = 11)
        Text("行距 ${"%.1f".format(settings.lineHeightMultiplier)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.lineHeightMultiplier,
            onValueChange = {
                val snapped = ((it * 10).roundToInt() / 10f).coerceIn(1.3f, 2.5f)
                onChange(settings.copy(lineHeightMultiplier = snapped))
            },
            valueRange = 1.3f..2.5f,
            steps = 11,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lineHeightSlider"),
        )
        Spacer(Modifier.size(8.dp))

        // 宽度 Slider 360..720 step 10 (35 steps -> steps param = 35)
        Text("宽度 ${settings.contentWidthDp}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.contentWidthDp.toFloat(),
            onValueChange = { onChange(settings.copy(contentWidthDp = it.roundToInt().coerceIn(360, 720))) },
            valueRange = 360f..720f,
            steps = 35,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("contentWidthSlider"),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

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

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ── 行为 ──
        Section("行为")
        // 沉浸模式 toggle 在 Phase 5 才真正生效，但开关本身在此 Task 加入。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("沉浸模式", Modifier.weight(1f))
            Switch(
                checked = settings.immersiveMode,
                onCheckedChange = { onChange(settings.copy(immersiveMode = it)) },
            )
        }
        Spacer(Modifier.size(8.dp))

        // 自动滚动速度 1..10 step 1 (8 steps -> steps param = 8)
        Text("自动滚动速度 ${settings.autoScrollSpeed}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.autoScrollSpeed.toFloat(),
            onValueChange = { onChange(settings.copy(autoScrollSpeed = it.roundToInt().coerceIn(1, 10))) },
            valueRange = 1f..10f,
            steps = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("autoScrollSlider"),
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeChipRow(
    selected: ReaderTheme,
    onSelect: (ReaderTheme) -> Unit,
) {
    // Phase 2 Task 2.4: 7 themes (incl. AUTO) no longer fit on one row on
    // narrow phones — switch to FlowRow so chips wrap. AUTO's swatch is a
    // half-light/half-dark gradient (its bg/fg are Transparent placeholders
    // until resolved by ReaderThemeScope).
    //
    // Phase 2 §1.2: when AUTO is selected the 6 concrete theme chips are
    // disabled (greyed out by Material3) — AUTO itself stays enabled so the
    // user can switch to a concrete theme; once they do, every chip becomes
    // interactive again.
    val isAutoSelected = selected == ReaderTheme.AUTO
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ReaderTheme.entries.forEach { theme ->
            FilterChip(
                selected = theme == selected,
                onClick = { onSelect(theme) },
                enabled = !(isAutoSelected && theme != ReaderTheme.AUTO),
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ThemeSwatch(theme)
                        Spacer(Modifier.width(6.dp))
                        Text(theme.label)
                    }
                },
            )
        }
    }
}

@Composable
private fun ThemeSwatch(theme: ReaderTheme) {
    val base = Modifier
        .size(12.dp)
        .clip(CircleShape)
        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    if (theme == ReaderTheme.AUTO) {
        Box(base.background(Brush.linearGradient(listOf(ReaderTheme.DAY.bg, ReaderTheme.NIGHT.bg))))
    } else {
        Box(base.background(theme.bg))
    }
}

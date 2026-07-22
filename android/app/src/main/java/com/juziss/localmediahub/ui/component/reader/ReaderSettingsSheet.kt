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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
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
        Spacer(Modifier.size(8.dp))

        // 背景图片
        val context = androidx.compose.ui.platform.LocalContext.current
        Text("背景图片", style = MaterialTheme.typography.labelMedium)
        val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
        ) { uri ->
            uri?.let { inputUri ->
                try {
                    val bgFile = java.io.File(context.filesDir, "reader_background.jpg")
                    context.contentResolver.openInputStream(inputUri)?.use { input ->
                        java.io.FileOutputStream(bgFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    onChange(settings.copy(bgImageUri = bgFile.toURI().toString()))
                } catch (_: Exception) {
                    onChange(settings.copy(bgImageUri = inputUri.toString()))
                }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedButton(
                onClick = { imagePickerLauncher.launch("image/*") }
            ) {
                Text(if (settings.bgImageUri.isNullOrBlank()) "选择图片" else "更换图片")
            }
            if (!settings.bgImageUri.isNullOrBlank()) {
                androidx.compose.material3.TextButton(
                    onClick = {
                        try {
                            val bgFile = java.io.File(context.filesDir, "reader_background.jpg")
                            if (bgFile.exists()) bgFile.delete()
                        } catch (_: Exception) {}
                        onChange(settings.copy(bgImageUri = null))
                    }
                ) {
                    Text("清除背景图")
                }
            }
        }

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

        // 行距 Slider 1.2..2.4 step 0.1 (12 steps -> steps param = 11)
        Text("行距 ${String.format(java.util.Locale.US, "%.1f", settings.lineHeightMultiplier)}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.lineHeightMultiplier,
            onValueChange = { onChange(settings.copy(lineHeightMultiplier = it)) },
            valueRange = 1.2f..2.4f,
            steps = 11,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("lineHeightSlider"),
        )
        Spacer(Modifier.size(8.dp))

        // 宽度 Slider 360..1400 step 10
        Text("宽度 ${settings.contentWidthDp}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.contentWidthDp.toFloat(),
            onValueChange = { onChange(settings.copy(contentWidthDp = it.roundToInt().coerceIn(360, 1400))) },
            valueRange = 360f..1400f,
            steps = 103,
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
        Text("阅读模式", style = MaterialTheme.typography.labelMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.juziss.localmediahub.data.ReadingMode.entries.forEach { mode ->
                FilterChip(
                    selected = settings.readingMode == mode,
                    onClick = { onChange(settings.copy(readingMode = mode)) },
                    label = { Text(mode.label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("沉浸模式", Modifier.weight(1f))
            Switch(
                checked = settings.immersiveMode,
                onCheckedChange = { onChange(settings.copy(immersiveMode = it)) },
            )
        }
        Spacer(Modifier.size(8.dp))

        // 自动滚动速度 1..10 step 1
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

        Spacer(Modifier.height(32.dp))
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

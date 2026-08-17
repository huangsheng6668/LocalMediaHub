package com.juziss.localmediahub.ui.component.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.res.stringResource
import com.juziss.localmediahub.R
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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import com.juziss.localmediahub.ble.BleConnState

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
    bleEnabled: Boolean = false,
    bleConnState: BleConnState = BleConnState.DISABLED,
    onBleConnect: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ReaderSettingsSheetContent(
            settings = settings,
            onChange = onChange,
            bleEnabled = bleEnabled,
            bleConnState = bleConnState,
            onBleConnect = onBleConnect,
        )
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
    bleEnabled: Boolean = false,
    bleConnState: BleConnState = BleConnState.DISABLED,
    onBleConnect: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .navigationBarsPadding()
    ) {
        Text(stringResource(R.string.reader_settings_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(12.dp))

        // ── 外观 ──
        Section(stringResource(R.string.rs_section_appearance))

        // 字体
        Text(stringResource(R.string.rs_font), style = MaterialTheme.typography.labelMedium)
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
        Text(stringResource(R.string.rs_theme), style = MaterialTheme.typography.labelMedium)
        ThemeChipRow(
            selected = settings.theme,
            onSelect = { onChange(settings.copy(theme = it)) },
        )
        Spacer(Modifier.size(8.dp))

        if (settings.theme == ReaderTheme.CUSTOM) {
            Text(stringResource(R.string.rs_custom_colors), style = MaterialTheme.typography.labelMedium)
            CustomColorRow(stringResource(R.string.rs_custom_bg), settings.customBg, "customBgHex") {
                onChange(settings.copy(customBg = it))
            }
            CustomColorRow(stringResource(R.string.rs_custom_fg), settings.customFg, "customFgHex") {
                onChange(settings.copy(customFg = it))
            }
            CustomColorRow(stringResource(R.string.rs_custom_muted), settings.customMuted, "customMutedHex") {
                onChange(settings.copy(customMuted = it))
            }
            Spacer(Modifier.size(8.dp))
        }

        // 背景图片
        val context = androidx.compose.ui.platform.LocalContext.current
        Text(stringResource(R.string.rs_bg_image), style = MaterialTheme.typography.labelMedium)
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
                Text(if (settings.bgImageUri.isNullOrBlank()) stringResource(R.string.rs_choose_image) else stringResource(R.string.rs_change_image))
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
                    Text(stringResource(R.string.rs_clear_bg))
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ── 字号与行距 ──
        Section(stringResource(R.string.rs_section_font_spacing))

        // 字号 Slider 12..28 step 1 (15 steps -> steps param = 15)
        Text(stringResource(R.string.rs_font_size_fmt, settings.fontSizeSp), style = MaterialTheme.typography.labelMedium)
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
        Text(
            stringResource(
                R.string.rs_line_height_fmt,
                String.format(java.util.Locale.US, "%.1f", settings.lineHeightMultiplier),
            ),
            style = MaterialTheme.typography.labelMedium,
        )
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
        Text(stringResource(R.string.rs_width_fmt, settings.contentWidthDp), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.contentWidthDp.toFloat(),
            onValueChange = { onChange(settings.copy(contentWidthDp = it.roundToInt().coerceIn(360, 1400))) },
            valueRange = 360f..1400f,
            steps = 103,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("contentWidthSlider"),
        )
        Spacer(Modifier.size(8.dp))

        // 字间距 Slider 0..1 step 0.05（20 档 -> steps = 19），吸附到 0.05 步进
        Text(
            stringResource(
                R.string.rs_letter_spacing_fmt,
                String.format(java.util.Locale.US, "%.2f", settings.letterSpacing),
            ),
            style = MaterialTheme.typography.labelMedium,
        )
        Slider(
            value = settings.letterSpacing,
            onValueChange = { onChange(settings.copy(letterSpacing = (it * 20).roundToInt() / 20f)) },
            valueRange = 0f..1f,
            steps = 19,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("letterSpacingSlider"),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ── 段落 ──
        Section(stringResource(R.string.rs_section_paragraph))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rs_first_line_indent), Modifier.weight(1f))
            Switch(
                checked = settings.firstLineIndent,
                onCheckedChange = { onChange(settings.copy(firstLineIndent = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rs_paragraph_spacing), Modifier.weight(1f))
            Switch(
                checked = settings.paragraphSpacing,
                onCheckedChange = { onChange(settings.copy(paragraphSpacing = it)) },
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ── 行为 ──
        Section(stringResource(R.string.rs_section_behavior))
        Text(stringResource(R.string.rs_reading_mode), style = MaterialTheme.typography.labelMedium)
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

        Text(stringResource(R.string.rs_page_turn_animation), style = MaterialTheme.typography.labelMedium)
        val isChapter = settings.readingMode == com.juziss.localmediahub.data.ReadingMode.CHAPTER
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.juziss.localmediahub.data.PageTurnStyle.entries.forEach { style ->
                FilterChip(
                    selected = settings.pageTurnStyle == style,
                    onClick = { onChange(settings.copy(pageTurnStyle = style)) },
                    enabled = isChapter,
                    label = { Text(style.label) },
                    colors = FilterChipDefaults.filterChipColors(),
                )
            }
        }
        Spacer(Modifier.size(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.rs_immersive_mode), Modifier.weight(1f))
            Switch(
                checked = settings.immersiveMode,
                onCheckedChange = { onChange(settings.copy(immersiveMode = it)) },
            )
        }
        Spacer(Modifier.size(8.dp))

        // 自动滚动速度 1..10 step 1
        Text(stringResource(R.string.rs_autoscroll_speed_fmt, settings.autoScrollSpeed), style = MaterialTheme.typography.labelMedium)
        Slider(
            value = settings.autoScrollSpeed.toFloat(),
            onValueChange = { onChange(settings.copy(autoScrollSpeed = it.roundToInt().coerceIn(1, 10))) },
            valueRange = 1f..10f,
            steps = 8,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("autoScrollSlider"),
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ── 蓝牙备用通道 ──
        Section(stringResource(R.string.ble_channel_title))
        BleStatusCapsuleRow(
            bleEnabled = bleEnabled,
            bleConnState = bleConnState,
            onBleConnect = onBleConnect,
        )

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
fun BleStatusCapsuleRow(
    bleEnabled: Boolean,
    bleConnState: BleConnState,
    onBleConnect: () -> Unit,
) {
    val (dotColor, statusTextRes) = when {
        !bleEnabled || bleConnState == BleConnState.DISABLED ->
            MaterialTheme.colorScheme.outline to R.string.ble_status_capsule_disabled
        bleConnState == BleConnState.CONNECTED ->
            Color(0xFF4CAF50) to R.string.ble_status_capsule_connected
        bleConnState == BleConnState.CONNECTING ->
            Color(0xFFFFB300) to R.string.ble_status_capsule_connecting
        else ->
            MaterialTheme.colorScheme.outline to R.string.ble_status_capsule_idle
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("bleStatusCapsule"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
                    .testTag("bleStatusDot")
            )
            Text(
                text = stringResource(statusTextRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (bleEnabled && bleConnState != BleConnState.CONNECTED) {
                OutlinedButton(
                    onClick = onBleConnect,
                    enabled = bleConnState != BleConnState.CONNECTING,
                    modifier = Modifier.testTag("bleConnectNowBtn"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.ble_connect_now),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
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
    when (theme) {
        // AUTO: half-light/half-dark gradient (bg/fg are Transparent placeholders
        // until resolved by ReaderThemeScope).
        ReaderTheme.AUTO -> Box(base.background(Brush.linearGradient(listOf(ReaderTheme.DAY.bg, ReaderTheme.NIGHT.bg))))
        // CUSTOM: bg/fg are Transparent placeholders — render a DAY.bg→DAY.fg
        // gradient mirroring Web's linear-gradient(135deg, #FAF8F3 0 50%, #2B2B2B 50% 100%).
        ReaderTheme.CUSTOM -> Box(base.background(Brush.linearGradient(listOf(ReaderTheme.DAY.bg, ReaderTheme.DAY.fg))))
        else -> Box(base.background(theme.bg))
    }
}

/** 常用阅读背景/文字色预设（每行各 12 色）。 */
private val PRESET_COLORS = listOf(
    "#FAF8F3", "#FFFFFF", "#F4ECD8", "#B9C7B6", "#EFE6D2", "#1A1A1F",
    "#000000", "#2B2B2B", "#3D3D3D", "#5B4636", "#1F2E20", "#C9C9CE",
)

/**
 * 一行自定义颜色控件：12 色预设色板 + hex 文本输入。
 * 仅当输入匹配 #RRGGBB 时提交；空输入提交 null（与 Web 的 null 语义一致）；其余不触发 onChange。
 */
@Composable
private fun CustomColorRow(
    label: String,
    value: String?,
    inputTag: String,
    onCommit: (String?) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(48.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.weight(1f),
        ) {
            PRESET_COLORS.forEach { hex ->
                val selected = value.equals(hex, ignoreCase = true)
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color(0xFF000000L or (hex.removePrefix("#").toLongOrNull(16) ?: 0L)))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { onCommit(hex) },
                )
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("hex", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(48.dp))
        androidx.compose.material3.OutlinedTextField(
            value = value ?: "",
            onValueChange = { input ->
                val v = input.trim().uppercase()
                if (v.isEmpty()) onCommit(null)
                else if (Regex("^#[0-9A-Fa-f]{6}$").matches(v)) onCommit(v)
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(inputTag),
            textStyle = MaterialTheme.typography.bodySmall,
        )
    }
}

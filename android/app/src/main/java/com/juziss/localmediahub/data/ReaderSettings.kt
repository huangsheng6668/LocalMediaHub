package com.juziss.localmediahub.data

import androidx.compose.ui.graphics.Color

/**
 * 全局阅读器设置（V2）。一组设置应用于所有书。
 * 通过 RecentActivityStore 持久化在 `reader_settings` DataStore key 下。
 *
 * 字段语义见 docs/superpowers/specs/2026-07-18-reader-ui-redesign-design.md §数据形状。
 */
data class ReaderSettings(
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val fontSizeSp: Int = 16,
    val lineHeightMultiplier: Float = 1.8f,
    val contentWidthDp: Int = 600,
    val firstLineIndent: Boolean = true,
    val paragraphSpacing: Boolean = false,
    val theme: ReaderTheme = ReaderTheme.DAY,
    val immersiveMode: Boolean = false,
    val autoScrollSpeed: Int = 5,  // 1..10
)

/**
 * 阅读区主题（含 chrome 配色字段）。AUTO 不携带颜色，由调用方解析为
 * DAY/NIGHT（亮/暗系统模式）。具体 hex 值来自 spec §1.1 表格。
 */
enum class ReaderTheme(
    val bg: Color,
    val fg: Color,
    val chromeBg: Color,
    val chromeFg: Color,
    val muted: Color,
    val border: Color,
    val label: String,
) {
    DAY(
        bg = Color(0xFFFAF8F3), fg = Color(0xFF2B2B2B),
        chromeBg = Color(0xFFF2EFE7), chromeFg = Color(0xFF3D3D3D),
        muted = Color(0xFF7A7A78), border = Color(0xFFE5E2D8),
        label = "日间·纸白",
    ),
    DAY_BRIGHT(
        bg = Color(0xFFFFFFFF), fg = Color(0xFF212121),
        chromeBg = Color(0xFFF5F5F5), chromeFg = Color(0xFF333333),
        muted = Color(0xFF7A7A7A), border = Color(0xFFE0E0E0),
        label = "日间·亮白",
    ),
    EYE_CARE(
        bg = Color(0xFFF4ECD8), fg = Color(0xFF5B4636),
        chromeBg = Color(0xFFEDE3CC), chromeFg = Color(0xFF6B5644),
        muted = Color(0xFF9C8870), border = Color(0xFFD8CBAF),
        label = "护眼·米黄",
    ),
    PARCHMENT(
        bg = Color(0xFFEFE6D2), fg = Color(0xFF3D3327),
        chromeBg = Color(0xFFE5D9BF), chromeFg = Color(0xFF4D4034),
        muted = Color(0xFF8C7E66), border = Color(0xFFD3C7AB),
        label = "羊皮纸",
    ),
    NIGHT(
        bg = Color(0xFF1A1A1F), fg = Color(0xFFC9C9CE),
        chromeBg = Color(0xFF232328), chromeFg = Color(0xFFB0B0B5),
        muted = Color(0xFF84848A), border = Color(0xFF2D2D33),
        label = "夜间·深空",
    ),
    NIGHT_BLACK(
        bg = Color(0xFF000000), fg = Color(0xFFBFBFBF),
        chromeBg = Color(0xFF0A0A0A), chromeFg = Color(0xFFA8A8A8),
        muted = Color(0xFF787878), border = Color(0xFF1C1C1C),
        label = "夜间·纯黑",
    ),
    AUTO(
        bg = Color.Transparent, fg = Color.Transparent,
        chromeBg = Color.Transparent, chromeFg = Color.Transparent,
        muted = Color.Transparent, border = Color.Transparent,
        label = "跟随系统",
    );

    companion object {
        /** AUTO 在亮/暗模式下解析到的预设。 */
        fun resolveAuto(isDark: Boolean): ReaderTheme = if (isDark) NIGHT else DAY
    }
}

/**
 * 阅读正文字体选项。Android 不打包字体，依赖系统字体映射。
 * KAITI 在多数 Android ROM 上没有独立楷体，toFontFamily() 回退到 Serif。
 */
enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体");

    fun toFontFamily(): androidx.compose.ui.text.font.FontFamily = when (this) {
        SYSTEM -> androidx.compose.ui.text.font.FontFamily.Default
        SERIF  -> androidx.compose.ui.text.font.FontFamily.Serif
        KAITI  -> androidx.compose.ui.text.font.FontFamily.Serif
    }
}

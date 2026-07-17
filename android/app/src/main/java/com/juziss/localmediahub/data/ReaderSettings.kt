package com.juziss.localmediahub.data

import androidx.compose.ui.graphics.Color

/**
 * Global reader preferences. One set applies to all books.
 * Persisted via RecentActivityStore under the `reader_settings` DataStore key.
 */
data class ReaderSettings(
    val fontSize: ReaderFontSize = ReaderFontSize.MEDIUM,
    val lineHeight: ReaderLineHeight = ReaderLineHeight.STANDARD,
    val theme: ReaderTheme = ReaderTheme.DAY,
    val autoScrollSpeed: Int = 5,  // 1..10
)

enum class ReaderFontSize(val sp: Int) {
    SMALL(14), MEDIUM(16), LARGE(18), XLARGE(20);
}

enum class ReaderLineHeight(val multiplier: Float) {
    COMPACT(1.4f), STANDARD(1.8f), LOOSE(2.2f);
}

/**
 * Reading-area theme. Scoped to TextReader body via ReaderThemeWrapper —
 * does NOT replace App-level MaterialTheme (TopAppBar/BottomAppBar/Sheet
 * keep following the system theme).
 */
enum class ReaderTheme(val bg: Color, val fg: Color, val label: String) {
    DAY(Color(0xFFFFFFFF), Color(0xFF212121), "日间"),
    NIGHT(Color(0xFF121212), Color(0xFFE0E0E0), "夜间"),
    EYE_CARE(Color(0xFFF4ECD8), Color(0xFF5B4636), "护眼");
}

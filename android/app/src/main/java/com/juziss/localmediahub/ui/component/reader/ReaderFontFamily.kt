package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily

enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体·文楷"),
    MONO("等宽");

    fun toFontFamily(): FontFamily = when (this) {
        SYSTEM -> FontFamily.Default
        SERIF  -> FontFamily.Serif
        // 回退方案：v1.522 全量字体 ~25MB，超出 spec 4-13MB 上限；作者
        // 未发布子集版，按 spec §1.3 回退到系统宋体。
        KAITI  -> FontFamily.Serif
        MONO   -> FontFamily.Monospace
    }
}

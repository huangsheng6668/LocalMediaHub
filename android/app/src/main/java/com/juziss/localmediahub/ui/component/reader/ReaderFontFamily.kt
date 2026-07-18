package com.juziss.localmediahub.ui.component.reader

import androidx.compose.ui.text.font.FontFamily

/**
 * 阅读正文字体选项。Android 不打包字体，依赖系统字体映射。
 * KAITI 在多数 Android ROM 上没有独立楷体，toFontFamily() 回退到 Serif。
 *
 * 注意：本 enum 位于 `ui.component.reader` 包，但 `data.ReaderSettings` 也
 * 引用它。data 包对 ui 包的反向依赖通常不被推荐，但 ReaderFontFamily 只是
 * 纯 enum（无 Compose 类型暴露给 enum 本身——`toFontFamily()` 是扩展函数
 * 而非 enum 成员），所以 data 包 import 它不引入实际 UI 依赖。spec §8.2
 * 把这种分离视为建议。
 */
enum class ReaderFontFamily(val label: String) {
    SYSTEM("无衬线"),
    SERIF("宋体"),
    KAITI("楷体（部分设备显示为宋体）");

    fun toFontFamily(): FontFamily = when (this) {
        SYSTEM -> FontFamily.Default
        SERIF  -> FontFamily.Serif
        KAITI  -> FontFamily.Serif
    }
}

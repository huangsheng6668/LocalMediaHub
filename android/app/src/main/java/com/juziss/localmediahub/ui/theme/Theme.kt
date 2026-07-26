package com.juziss.localmediahub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFE8915A),
    secondary = Color(0xFF6FB8BC),
    tertiary = Color(0xFFC8D78E),
    background = Color(0xFF141210),
    surface = Color(0xFF1E1A17),
    surfaceVariant = Color(0xFF2A2420),
    primaryContainer = Color(0xFF3A2516),
    secondaryContainer = Color(0xFF1A3335),
    onPrimary = Color(0xFF2A1408),
    onSecondary = Color(0xFF042022),
    onTertiary = Color.White,
    onBackground = Color(0xFFEDE6DA),
    onSurface = Color(0xFFEDE6DA),
    onSurfaceVariant = Color(0xFFB3A793),
    outline = Color(0xFF3A3229),
    outlineVariant = Color(0xFF3A3229),
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFFB96D1D),
    secondary = Color(0xFF3E7A7E),
    tertiary = Color(0xFF647A33),
    background = Color(0xFFF4EEE2),
    surface = Color(0xFFFBF6EC),
    surfaceVariant = Color(0xFFEDE6D6),
    primaryContainer = Color(0xFFFBEBD8),
    secondaryContainer = Color(0xFFD6EFF0),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF2A2218),
    onSurface = Color(0xFF2A2218),
    onSurfaceVariant = Color(0xFF6B5E48),
    outline = Color(0xFFD4CCBA),
    outlineVariant = Color(0xFFD4CCBA),
)

private val AppTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 31.sp,
        lineHeight = 37.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 25.sp,
        lineHeight = 31.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 23.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
)

private val EyeCareGreenColorScheme = lightColorScheme(
    primary = Color(0xFF2C5030),
    secondary = Color(0xFF384C3A),
    tertiary = Color(0xFF4D5E4F),
    background = Color(0xFFB9C7B6),
    surface = Color(0xFFACBCAB),
    surfaceVariant = Color(0xFF9BB098),
    primaryContainer = Color(0xFFC3D0C2),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF1F2E20),
    onSurface = Color(0xFF1F2E20),
    onSurfaceVariant = Color(0xFF384C3A),
    outline = Color(0xFF9BB098),
)

private val EyeCareColorScheme = lightColorScheme(
    primary = Color(0xFF8C6239),
    secondary = Color(0xFF734F2D),
    tertiary = Color(0xFF7A6654),
    background = Color(0xFFF5EBDC),
    surface = Color(0xFFEBDCC8),
    surfaceVariant = Color(0xFFD9C8B2),
    primaryContainer = Color(0xFFFAF3E8),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF3D3126),
    onSurface = Color(0xFF3D3126),
    onSurfaceVariant = Color(0xFF5C4B3D),
    outline = Color(0xFFD9C8B2),
)

private val ParchmentColorScheme = lightColorScheme(
    primary = Color(0xFF6B4C2A),
    secondary = Color(0xFF543B20),
    tertiary = Color(0xFF756750),
    background = Color(0xFFF4ECD8),
    surface = Color(0xFFE8DFC9),
    surfaceVariant = Color(0xFFD6CBAE),
    primaryContainer = Color(0xFFFBF6E9),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFF3B3224),
    onSurface = Color(0xFF3B3224),
    onSurfaceVariant = Color(0xFF574B38),
    outline = Color(0xFFD6CBAE),
)

private val NightBlackColorScheme = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    secondary = Color(0xFFAAAAAA),
    tertiary = Color(0xFF777777),
    background = Color.Black,
    surface = Color(0xFF121212),
    surfaceVariant = Color(0xFF1A1A1A),
    primaryContainer = Color(0xFF222222),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
    onSurfaceVariant = Color(0xFFAAAAAA),
    outline = Color(0xFF333333),
)

@Composable
fun LocalMediaHubTheme(
    themeKey: String = "AUTO",
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val upper = themeKey.uppercase()
    val (colorScheme, outlineSoft) = when (upper) {
        "EYE_CARE_GREEN" -> EyeCareGreenColorScheme to OutlineSoft.EyeCareGreen
        "EYE_CARE" -> EyeCareColorScheme to OutlineSoft.EyeCare
        "PARCHMENT" -> ParchmentColorScheme to OutlineSoft.Parchment
        "NIGHT_BLACK" -> NightBlackColorScheme to OutlineSoft.NightBlack
        "NIGHT" -> DarkColorScheme to OutlineSoft.Dark
        "DAY", "DAY_BRIGHT" -> LightColorScheme to OutlineSoft.Light
        else -> (if (darkTheme) DarkColorScheme else LightColorScheme) to
                (if (darkTheme) OutlineSoft.Dark else OutlineSoft.Light)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
    ) {
        ProvideOutlineSoft(outlineSoft) {
            // See NoRippleIndication.kt — overrides Material 1.3.1's legacy PlatformRipple
            // (which only implements Indication, not IndicationNodeFactory) so that
            // foundation 1.11.x's clickable doesn't crash on release R8 builds.
            ProvideNoRippleIndication(content)
        }
    }
}

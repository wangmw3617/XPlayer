package com.zhiwei.xplayer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zhiwei.xplayer.core.data.ThemeMode

// ---------------------------------------------------------------- 品牌色板 ----
// 播放器应用偏「影院感」：主色用低饱和的靛蓝，强调色用青绿表示「播放中/硬解」，
// 琥珀色留给进度与高亮。整体避免大面积高饱和，否则视频画面周围会显得吵。

private val Indigo40 = Color(0xFF3D5AFE)
private val Indigo80 = Color(0xFFB7C4FF)
private val IndigoContainerLight = Color(0xFFDDE1FF)
private val IndigoContainerDark = Color(0xFF1B2C87)

private val Teal40 = Color(0xFF00897B)
private val Teal80 = Color(0xFF6FDDD0)
private val TealContainerLight = Color(0xFFCCF1EB)
private val TealContainerDark = Color(0xFF00504A)

private val Amber40 = Color(0xFF9A6400)
private val Amber80 = Color(0xFFF5C26B)
private val AmberContainerLight = Color(0xFFFFE3AE)
private val AmberContainerDark = Color(0xFF5C3D00)

private val Neutral10 = Color(0xFF0A0E1A)
private val Neutral99 = Color(0xFFFBFBFF)

private val LightColors = lightColorScheme(
    primary = Indigo40,
    onPrimary = Color.White,
    primaryContainer = IndigoContainerLight,
    onPrimaryContainer = Color(0xFF001158),

    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = TealContainerLight,
    onSecondaryContainer = Color(0xFF00201C),

    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = AmberContainerLight,
    onTertiaryContainer = Color(0xFF301F00),

    background = Neutral99,
    onBackground = Color(0xFF1A1B21),
    surface = Neutral99,
    onSurface = Color(0xFF1A1B21),
    surfaceVariant = Color(0xFFE2E1EC),
    onSurfaceVariant = Color(0xFF45464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Indigo80,
    onPrimary = Color(0xFF001A75),
    primaryContainer = IndigoContainerDark,
    onPrimaryContainer = IndigoContainerLight,

    secondary = Teal80,
    onSecondary = Color(0xFF00382F),
    secondaryContainer = TealContainerDark,
    onSecondaryContainer = TealContainerLight,

    tertiary = Amber80,
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = AmberContainerDark,
    onTertiaryContainer = AmberContainerLight,

    background = Neutral10,
    onBackground = Color(0xFFE4E1E9),
    surface = Neutral10,
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF45464F),
    onSurfaceVariant = Color(0xFFC6C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF45464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

/** 等宽字体用于日志与时间码 */
val MonospaceStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 12.sp,
    lineHeight = 17.sp,
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun XPlayerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

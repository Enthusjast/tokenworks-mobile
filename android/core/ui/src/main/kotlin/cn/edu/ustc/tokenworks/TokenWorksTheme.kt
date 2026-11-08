package cn.edu.ustc.tokenworks

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt


/** Chat-only multipliers, applied on top of Android's unchanged system font scale. */
data class TokenWorksReadingStyle(
    val bodyScale: Float = 1f,
    val codeScale: Float = 1f,
    val wrapCode: Boolean = false,
)

val LocalTokenWorksReadingStyle = staticCompositionLocalOf { TokenWorksReadingStyle() }

private val light = lightColorScheme(
    primary = Color(0xFF3F51B5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1E5FF),
    onPrimaryContainer = Color(0xFF172568),
    secondary = Color(0xFF535F76),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7F4),
    onSecondaryContainer = Color(0xFF172034),
    tertiary = Color(0xFF765A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE08A),
    onTertiaryContainer = Color(0xFF251A00),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF191C23),
    surface = Color(0xFFF7F8FC),
    onSurface = Color(0xFF191C23),
    surfaceVariant = Color(0xFFE2E4EF),
    onSurfaceVariant = Color(0xFF444753),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEEF0F7),
    surfaceContainerHigh = Color(0xFFE8EAF2),
    surfaceContainerHighest = Color(0xFFE2E4EC),
    outline = Color(0xFF747783),
    outlineVariant = Color(0xFFC4C6D3),
    inverseSurface = Color(0xFF2E3038),
    inverseOnSurface = Color(0xFFF0F1FA),
    inversePrimary = Color(0xFFBAC3FF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)
private val dark = darkColorScheme(
    primary = Color(0xFFBAC3FF),
    onPrimary = Color(0xFF13236B),
    primaryContainer = Color(0xFF2A3C91),
    onPrimaryContainer = Color(0xFFE1E5FF),
    secondary = Color(0xFFBAC6DF),
    onSecondary = Color(0xFF253047),
    secondaryContainer = Color(0xFF3C485E),
    onSecondaryContainer = Color(0xFFDEE7FC),
    tertiary = Color(0xFFE9C349),
    onTertiary = Color(0xFF3E2E00),
    tertiaryContainer = Color(0xFF594400),
    onTertiaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF11131A),
    onBackground = Color(0xFFE3E5EF),
    surface = Color(0xFF11131A),
    onSurface = Color(0xFFE3E5EF),
    surfaceVariant = Color(0xFF444753),
    onSurfaceVariant = Color(0xFFC4C6D3),
    surfaceContainerLowest = Color(0xFF0C0E14),
    surfaceContainerLow = Color(0xFF191B23),
    surfaceContainer = Color(0xFF1E2029),
    surfaceContainerHigh = Color(0xFF282A33),
    surfaceContainerHighest = Color(0xFF33353E),
    outline = Color(0xFF8E909D),
    outlineVariant = Color(0xFF444753),
    inverseSurface = Color(0xFFE3E5EF),
    inverseOnSurface = Color(0xFF2E3038),
    inversePrimary = Color(0xFF3F51B5),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Semantic colors that Material's [androidx.compose.material3.ColorScheme] does not carry: tertiary
 * text, the code well, and diff tints. Every value was measured against its own background and
 * exceeds 4.5:1, so text never relies on the surrounding surface staying a specific shade.
 */
data class TokenWorksSurfaces(
    val textTertiary: Color,
    val textDisabled: Color,
    val codeWellBackground: Color,
    val codeWellHeader: Color,
    val codeWellForeground: Color,
    val codeWellBorder: Color,
    val codeWellHeaderForeground: Color,
    val diffAddBackground: Color,
    val diffAddForeground: Color,
    val diffDelBackground: Color,
    val diffDelForeground: Color,
    val diffHunkForeground: Color,
)

val lightSurfaces = TokenWorksSurfaces(
    textTertiary = Color(0xFF5F6472),
    textDisabled = Color(0xFFA3A7B4),
    codeWellBackground = Color(0xFFEEF0F6),
    codeWellHeader = Color(0xFFE6E9F1),
    codeWellForeground = Color(0xFF1B1E26),
    codeWellBorder = Color(0xFFD6DAE4),
    codeWellHeaderForeground = Color(0xFF454A57),
    diffAddBackground = Color(0xFFE6F4EA),
    diffAddForeground = Color(0xFF116B2C),
    diffDelBackground = Color(0xFFFDE9E9),
    diffDelForeground = Color(0xFFA1262B),
    diffHunkForeground = Color(0xFF5B5F6B),
)

val darkSurfaces = TokenWorksSurfaces(
    textTertiary = Color(0xFFA0A5B4),
    textDisabled = Color(0xFF5C616E),
    codeWellBackground = Color(0xFF0C0F16),
    codeWellHeader = Color(0xFF141821),
    codeWellForeground = Color(0xFFDDE1EA),
    codeWellBorder = Color(0xFF262B36),
    codeWellHeaderForeground = Color(0xFF9AA0AF),
    diffAddBackground = Color(0xFF12281A),
    diffAddForeground = Color(0xFF7FD69A),
    diffDelBackground = Color(0xFF2B1618),
    diffDelForeground = Color(0xFFF2A0A0),
    diffHunkForeground = Color(0xFF9AA0AF),
)

val LocalTokenWorksSurfaces = staticCompositionLocalOf { lightSurfaces }

/** The active theme's semantic colors; read inside composition only. */
@Composable
fun tokenWorksSurfaces(): TokenWorksSurfaces = LocalTokenWorksSurfaces.current

object TokenWorksSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val gutter = 16.dp
    val inTurn = 8.dp
    val betweenTurns = 24.dp
    val readingWidth = 720.dp
    val floatInset = 12.dp
    val minimumTouchTarget = 48.dp
    val iconSize = 20.dp
}

object TokenWorksRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 20.dp
    val xl = 28.dp
}

/** Column geometry shared by the shell and its tests: navigation, chat and panel widths in dp. */
object TokenWorksMetrics {
    val key = 44.dp
    val edge = 12.dp
    val gap = 8.dp
    val railWidth = 68.dp
    val sidebarWidth = 320.dp
    val panelMin = 320.dp
    val panelIdealMin = 360.dp
    val panelMax = 420.dp
    const val panelFraction = 0.30f
    val chatMin = 400.dp
    val chatComfort = 600.dp
    val drawerMaxWidth = 320.dp
    val chatBarHeight = 56.dp
    val composerKey = 44.dp
    val composerBarHeight = 56.dp

    /** 30% of the window, snapped to 4 dp and clamped to the panel range. */
    fun panelIdeal(windowWidthDp: Int): Dp =
        ((windowWidthDp * panelFraction).roundToInt() / 4 * 4)
            .coerceIn(panelIdealMin.value.toInt(), panelMax.value.toInt())
            .dp
}

object TokenWorksMotion {
    const val press = 90
    const val quick = 160
    const val standard = 240
    const val collapse = 280
    const val emphasized = 340
    const val sheetIn = 380
    const val sheetOut = 260
    const val pressScale = 0.97f
}

private val typography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Light,
        fontSize = 28.sp,
        lineHeight = 35.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 29.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 23.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(TokenWorksRadius.xs),
    small = RoundedCornerShape(TokenWorksRadius.sm),
    medium = RoundedCornerShape(TokenWorksRadius.md),
    large = RoundedCornerShape(TokenWorksRadius.lg),
    extraLarge = RoundedCornerShape(TokenWorksRadius.xl),
)

@Composable
fun TokenWorksTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalTokenWorksSurfaces provides if (darkTheme) darkSurfaces else lightSurfaces) {
        MaterialTheme(
            colorScheme = if (darkTheme) dark else light,
            typography = typography,
            shapes = shapes,
            content = content,
        )
    }
}

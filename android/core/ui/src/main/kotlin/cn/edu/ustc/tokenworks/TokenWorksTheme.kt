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
    primaryContainer = Color(0xFFE2E7FB),
    onPrimaryContainer = Color(0xFF1B2766),
    secondary = Color(0xFF565D6B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E5EC),
    onSecondaryContainer = Color(0xFF1D2129),
    tertiary = Color(0xFF6B578F),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEBE5F8),
    onTertiaryContainer = Color(0xFF372B52),
    background = Color(0xFFF5F6F9),
    onBackground = Color(0xFF1B1E26),
    surface = Color(0xFFF5F6F9),
    onSurface = Color(0xFF1B1E26),
    surfaceVariant = Color(0xFFE2E5ED),
    onSurfaceVariant = Color(0xFF494E5A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFEFF1F6),
    surfaceContainerHigh = Color(0xFFE9EBF1),
    surfaceContainerHighest = Color(0xFFE2E5ED),
    outline = Color(0xFF767B87),
    outlineVariant = Color(0xFFD6D9E1),
    inverseSurface = Color(0xFF2E3138),
    inverseOnSurface = Color(0xFFF0F1F6),
    inversePrimary = Color(0xFFB9C2FF),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)
private val dark = darkColorScheme(
    primary = Color(0xFFB9C2FF),
    onPrimary = Color(0xFF16246B),
    primaryContainer = Color(0xFF2B3C8F),
    onPrimaryContainer = Color(0xFFDFE4FF),
    secondary = Color(0xFFC0C6D2),
    onSecondary = Color(0xFF232833),
    secondaryContainer = Color(0xFF343944),
    onSecondaryContainer = Color(0xFFDCE1EC),
    tertiary = Color(0xFFCDB9EE),
    onTertiary = Color(0xFF2A1D44),
    tertiaryContainer = Color(0xFF43395C),
    onTertiaryContainer = Color(0xFFEBE5F8),
    background = Color(0xFF15171C),
    onBackground = Color(0xFFE4E6EC),
    surface = Color(0xFF15171C),
    onSurface = Color(0xFFE4E6EC),
    surfaceVariant = Color(0xFF464B56),
    onSurfaceVariant = Color(0xFFC6CAD4),
    surfaceContainerLowest = Color(0xFF101216),
    surfaceContainerLow = Color(0xFF1A1C22),
    surfaceContainer = Color(0xFF1E2128),
    surfaceContainerHigh = Color(0xFF282B33),
    surfaceContainerHighest = Color(0xFF33363F),
    outline = Color(0xFF9094A0),
    outlineVariant = Color(0xFF464B56),
    inverseSurface = Color(0xFFE4E6EC),
    inverseOnSurface = Color(0xFF2E3138),
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
    val success: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
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
    success = Color(0xFF226B3A),
    successContainer = Color(0xFFDDF2E1),
    onSuccessContainer = Color(0xFF0F4D26),
    warning = Color(0xFF8A5A00),
    warningContainer = Color(0xFFFFE9C4),
    onWarningContainer = Color(0xFF5C3D00),
    info = Color(0xFF34589E),
    infoContainer = Color(0xFFE1E9FB),
    onInfoContainer = Color(0xFF1F3D77),
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
    success = Color(0xFF8BD6A2),
    successContainer = Color(0xFF1B3A26),
    onSuccessContainer = Color(0xFFB4E8C4),
    warning = Color(0xFFE8C069),
    warningContainer = Color(0xFF453411),
    onWarningContainer = Color(0xFFF4D89A),
    info = Color(0xFFA9C2F2),
    infoContainer = Color(0xFF27395C),
    onInfoContainer = Color(0xFFCBDCF8),
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

    /** Entrance fade for content that appears without a layout change. */
    const val fadeIn = 180

    /** Vertical distance of the slide-in entrance, in dp. */
    const val enterOffsetDp = 12f
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
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
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

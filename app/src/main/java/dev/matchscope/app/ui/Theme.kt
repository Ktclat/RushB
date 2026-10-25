package dev.matchscope.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** Semantic colors are scoped to composition, including offscreen pages and charts. */
@Immutable
internal data class RushColors(
    val dark: Boolean,
    val background: Color, val panel: Color, val panelRaised: Color,
    val ink: Color, val quiet: Color, val subtle: Color,
    val accent: Color, val accentSurface: Color,
    val green: Color, val red: Color, val liveSurface: Color,
    val stroke: Color, val warningSurface: Color, val comparison: Color,
    val europe: Color, val asia: Color, val americas: Color,
    val events: List<Color>,
)

internal val DarkColors = RushColors(
    dark = true,
    background = Color(0xFF0E131A), panel = Color(0xFF171E27), panelRaised = Color(0xFF202A36),
    ink = Color(0xFFEAF0F6), quiet = Color(0xFF9AA8BA), subtle = Color(0xFF293544),
    accent = Color(0xFFF5F7FA), accentSurface = Color(0xFF344151),
    green = Color(0xFF80C8A5), red = Color(0xFFFF766D), liveSurface = Color(0xFF302024),
    stroke = Color(0xFF303C4B), warningSurface = Color(0xFF242D39), comparison = Color(0xFFA4BCD2),
    europe = Color(0xFF7AAAF4), asia = Color(0xFF68C8BE), americas = Color(0xFFB895F2),
    events = listOf(0xFF76ACE0, 0xFFDA8AAF, 0xFFA1C776, 0xFFB39ADD, 0xFFE4B06D, 0xFF6CC2BF, 0xFFE49685, 0xFF9AADD7).map { Color(it) },
)

internal val LightColors = RushColors(
    dark = false,
    background = Color(0xFFF5F6F8), panel = Color(0xFFFFFFFF), panelRaised = Color(0xFFF0F2F5),
    ink = Color(0xFF1C2530), quiet = Color(0xFF566170), subtle = Color(0xFFE2E6EB),
    accent = Color(0xFF234E70), accentSurface = Color(0xFFE7EFF6),
    green = Color(0xFF176D4A), red = Color(0xFFB3312C), liveSurface = Color(0xFFFCEAE8),
    stroke = Color(0xFFDDE2E8), warningSurface = Color(0xFFEDF1F5), comparison = Color(0xFF4C6C8C),
    europe = Color(0xFF295FAA), asia = Color(0xFF126E65), americas = Color(0xFF7650A6),
    events = listOf(0xFF2C639C, 0xFF9D426D, 0xFF496D2B, 0xFF7253A1, 0xFF885B20, 0xFF216D69, 0xFFA34C39, 0xFF526799).map { Color(it) },
)

internal val LocalRushColors = staticCompositionLocalOf { DarkColors }
val Background: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.background
val Panel: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.panel
val PanelRaised: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.panelRaised
val Ink: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.ink
val Quiet: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.quiet
val Subtle: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.subtle
val Accent: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.accent
val AccentSurface: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.accentSurface
val Green: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.green
val Red: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.red
val LiveSurface: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.liveSurface
val Stroke: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.stroke
val WarningSurface: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.warningSurface
val ComparisonColor: Color @Composable @ReadOnlyComposable get() = LocalRushColors.current.comparison

@Composable
fun MatchScopeTheme(darkTheme: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColors else LightColors
    val scheme = remember(colors) {
        (if (darkTheme) darkColorScheme() else lightColorScheme()).copy(
            primary = colors.accent, onPrimary = colors.background, primaryContainer = colors.accentSurface, onPrimaryContainer = colors.ink,
            secondary = colors.accent, onSecondary = colors.background, secondaryContainer = colors.subtle, onSecondaryContainer = colors.ink,
            tertiary = colors.green, onTertiary = colors.background, tertiaryContainer = colors.accentSurface, onTertiaryContainer = colors.ink,
            background = colors.background, onBackground = colors.ink,
            surface = colors.panel, onSurface = colors.ink, surfaceVariant = colors.panelRaised, onSurfaceVariant = colors.quiet,
            surfaceDim = colors.background, surfaceBright = colors.panel,
            surfaceContainerLowest = colors.background, surfaceContainerLow = colors.panel, surfaceContainer = colors.panelRaised,
            surfaceContainerHigh = colors.subtle, surfaceContainerHighest = colors.subtle,
            error = colors.red, onError = colors.background, errorContainer = colors.liveSurface, onErrorContainer = colors.ink,
            outline = colors.quiet, outlineVariant = colors.stroke, surfaceTint = Color.Transparent,
            inverseSurface = colors.ink, inverseOnSurface = colors.background, inversePrimary = colors.background,
        )
    }
    CompositionLocalProvider(LocalRushColors provides colors, LocalContentColor provides colors.ink) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp),
            medium = RoundedCornerShape(10.dp), large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
        ),
        typography = Typography(bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)),
        content = content,
    )
    }
}

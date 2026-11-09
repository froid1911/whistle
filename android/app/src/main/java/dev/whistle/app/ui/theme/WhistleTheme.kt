package dev.whistle.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

/** Spec shapes: chips/pills 999 (use [Pill]), cards 20, composer 24, sheets 28 top. */
object WhistleShapes {
    val Pill = RoundedCornerShape(percent = 50)
    val Card = RoundedCornerShape(20.dp)
    val Composer = RoundedCornerShape(24.dp)
    val Bubble = RoundedCornerShape(18.dp)
    val Inset = RoundedCornerShape(14.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

private val WhistleM3Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = WhistleShapes.Card,
    large = WhistleShapes.Composer,
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The palette mapped onto Material roles so untouched M3 components (dialogs, menus, snackbars,
 * text fields) inherit it: no default purple anywhere. Primary is [WhistleColors.wool] — the one
 * loud color, lamp, is reserved for "needs you" and never used as a generic accent.
 */
fun WhistleColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = wool,
        onPrimary = night,
        primaryContainer = cardHi,
        onPrimaryContainer = wool,
        inversePrimary = night,
        secondary = sage,
        onSecondary = night,
        secondaryContainer = cardHi,
        onSecondaryContainer = wool,
        tertiary = sky,
        onTertiary = night,
        tertiaryContainer = lerp(card, sky, 0.18f),
        onTertiaryContainer = wool,
        background = night,
        onBackground = wool,
        surface = night,
        onSurface = wool,
        surfaceVariant = card,
        onSurfaceVariant = sage,
        surfaceTint = Color.Transparent, // elevation by surface color only
        inverseSurface = wool,
        inverseOnSurface = night,
        error = ember,
        onError = if (isDark) night else Color.White,
        errorContainer = lerp(card, ember, 0.22f),
        onErrorContainer = wool,
        outline = line,
        outlineVariant = lerp(night, line, 0.6f),
        scrim = Color.Black,
        surfaceBright = cardHi,
        surfaceDim = night,
        surfaceContainerLowest = if (isDark) inset else Color.White,
        surfaceContainerLow = lerp(night, card, 0.5f),
        surfaceContainer = card,
        surfaceContainerHigh = lerp(card, cardHi, 0.5f),
        surfaceContainerHighest = cardHi,
    )
}

/** App theme: fixed brand palette (no dynamic color), dark/light by the system setting. */
@Composable
fun WhistleTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkWhistle else LightWhistle
    CompositionLocalProvider(LocalWhistleColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = WhistleTypography,
            shapes = WhistleM3Shapes,
            content = content,
        )
    }
}

/** Shorthand: `Whistle.colors.lamp`. */
object Whistle {
    val colors: WhistleColors
        @Composable @ReadOnlyComposable get() = LocalWhistleColors.current
}

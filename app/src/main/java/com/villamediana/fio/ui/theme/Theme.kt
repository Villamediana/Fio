package com.villamediana.fio.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.villamediana.fio.R
import com.villamediana.fio.settings.ThemeMode

@Immutable
data class Palette(
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surface2: Color,
    val surface3: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
    val success: Color,
)

object Palettes {
    /** Paper and ink, with one vermilion thread. */
    val Light = Palette(
        dark = false,
        background = Color(0xFFF6F4EF),
        surface = Color(0xFFFFFFFF),
        surface2 = Color(0xFFEFECE6),
        surface3 = Color(0xFFE5E1D9),
        text = Color(0xFF17171A),
        textMuted = Color(0xFF68645D),
        textFaint = Color(0xFFA39E95),
        line = Color(0x1A17171A),
        accent = Color(0xFFEC4F2A),
        onAccent = Color(0xFFFFFFFF),
        danger = Color(0xFFC62E2E),
        success = Color(0xFF1F8A70),
    )

    val Dark = Palette(
        dark = true,
        background = Color(0xFF111113),
        surface = Color(0xFF1A1A1D),
        surface2 = Color(0xFF222226),
        surface3 = Color(0xFF2D2D32),
        text = Color(0xFFF2F0EB),
        textMuted = Color(0xFF9D988F),
        textFaint = Color(0xFF5F5B55),
        line = Color(0x1FF2F0EB),
        accent = Color(0xFFFF6A43),
        onAccent = Color(0xFF17171A),
        danger = Color(0xFFFF6B6B),
        success = Color(0xFF3FBF9C),
    )
}

val LocalPalette = staticCompositionLocalOf { Palettes.Light }

/** The colors of the theme on screen. */
object FioColors {
    val palette: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
    val Background @Composable @ReadOnlyComposable get() = palette.background
    val Surface @Composable @ReadOnlyComposable get() = palette.surface
    val Surface2 @Composable @ReadOnlyComposable get() = palette.surface2
    val Surface3 @Composable @ReadOnlyComposable get() = palette.surface3
    val Text @Composable @ReadOnlyComposable get() = palette.text
    val TextMuted @Composable @ReadOnlyComposable get() = palette.textMuted
    val TextFaint @Composable @ReadOnlyComposable get() = palette.textFaint
    val Line @Composable @ReadOnlyComposable get() = palette.line
    val Accent @Composable @ReadOnlyComposable get() = palette.accent
    val OnAccent @Composable @ReadOnlyComposable get() = palette.onAccent
    val Danger @Composable @ReadOnlyComposable get() = palette.danger
    val Success @Composable @ReadOnlyComposable get() = palette.success
}

private fun outfit(weight: Int) = Font(
    R.font.outfit,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Outfit = FontFamily(outfit(300), outfit(400), outfit(500), outfit(600), outfit(700))

private fun style(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = Outfit,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val FioType = Typography(
    displayLarge = style(48, 500, 54, -1.2),
    displayMedium = style(38, 500, 44, -0.8),
    displaySmall = style(30, 500, 36, -0.5),
    headlineLarge = style(28, 500, 34, -0.4),
    headlineMedium = style(24, 500, 30, -0.3),
    headlineSmall = style(21, 500, 27, -0.2),
    titleLarge = style(19, 500, 25, -0.1),
    titleMedium = style(16, 500, 22),
    titleSmall = style(14, 600, 19),
    bodyLarge = style(16, 400, 23),
    bodyMedium = style(14, 400, 20),
    bodySmall = style(12, 400, 16),
    labelLarge = style(14, 500, 18),
    labelMedium = style(12, 500, 16, 0.2),
    labelSmall = style(11, 600, 14, 0.6),
)

/** Motion: quick out, soft landing. */
object Motion {
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> enter(ms: Int = 320): FiniteAnimationSpec<T> = tween(ms, easing = Ease)
    fun <T> exit(ms: Int = 200): FiniteAnimationSpec<T> = tween(ms, easing = Ease)
    fun <T> soft(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    val slide: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 420f)
}

@Composable
fun FioTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = if (dark) Palettes.Dark else Palettes.Light
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.background, surface = p.surface, onSurface = p.text, onBackground = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.textMuted, outline = p.line, error = p.danger)
    } else {
        lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.background, surface = p.surface, onSurface = p.text, onBackground = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.textMuted, outline = p.line, error = p.danger)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = FioType, content = content)
    }
}

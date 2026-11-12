package com.villamediana.novelo.ui.theme

import androidx.annotation.StringRes
import com.villamediana.novelo.settings.t
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.villamediana.novelo.R
import com.villamediana.novelo.settings.ThemeMode

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
    /** Warm linen and brown ink, with one terracotta thread. */
    val Light = Palette(
        dark = false,
        background = Color(0xFFF3EBDD),
        surface = Color(0xFFFBF6EC),
        surface2 = Color(0xFFEADFCC),
        surface3 = Color(0xFFE0D2BC),
        text = Color(0xFF3A2A1F),
        textMuted = Color(0xFF715E4C),
        textFaint = Color(0xFF9C8670),
        line = Color(0x1F3A2A1F),
        accent = Color(0xFFC2593A),
        onAccent = Color(0xFFFFF8EE),
        danger = Color(0xFFB4382B),
        success = Color(0xFF4F7F4A),
    )

    /** Cocoa under a lamp: the same warmth at night. */
    val Dark = Palette(
        dark = true,
        background = Color(0xFF221A15),
        surface = Color(0xFF2D231D),
        surface2 = Color(0xFF3A2E26),
        surface3 = Color(0xFF46382F),
        text = Color(0xFFF4E8D7),
        textMuted = Color(0xFFBBA78F),
        textFaint = Color(0xFF8C7A66),
        line = Color(0x1FF4E8D7),
        accent = Color(0xFFE8875F),
        onAccent = Color(0xFF2A1C10),
        danger = Color(0xFFFF8A7A),
        success = Color(0xFF8CC79A),
    )

    /** What goes on the accent: warm white or dark ink, whichever reads better on it. */
    val OnLight = Color(0xFFFFF8EE)
    val OnDark = Color(0xFF2A1C10)
}

/** A highlight color to choose in Tema, tuned for each theme (linen and cocoa). */
data class AccentDef(val id: String, @StringRes val nameRes: Int, val light: Color, val dark: Color) {
    val name: String get() = t(nameRes)
}

object NoveloAccents {
    /**
     * The ids are the ones from before the cozy colors (the choice she saved and the icon's
     * launcher entry stay hers): vermilion is terracotta now, graphite is caramel.
     */
    val all = listOf(
        AccentDef("vermilion", R.string.accent_terracota, Color(0xFFC2593A), Color(0xFFE8875F)),
        AccentDef("pink", R.string.accent_framboesa, Color(0xFFB84F6A), Color(0xFFE68BA3)),
        AccentDef("graphite", R.string.accent_caramelo, Color(0xFFA86A30), Color(0xFFE2A866)),
        AccentDef("green", R.string.accent_oliva, Color(0xFF6F7D45), Color(0xFFAEBB82)),
        AccentDef("blue", R.string.accent_azul, Color(0xFF5671A0), Color(0xFF93ABD3)),
        AccentDef("teal", R.string.accent_salvia, Color(0xFF4C7F77), Color(0xFF86BDB4)),
        AccentDef("purple", R.string.accent_ameixa, Color(0xFF84587F), Color(0xFFC79DC2)),
    )

    fun byId(id: String?): AccentDef = all.firstOrNull { it.id == id } ?: all.first()
}

private fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** What reads on [accent]: warm white or dark ink, by contrast. */
fun onAccentFor(accent: Color): Color =
    if (contrast(accent, Palettes.OnLight) >= contrast(accent, Palettes.OnDark)) Palettes.OnLight else Palettes.OnDark

/** [this] palette with [a] as its thread: the button, the marks, the chosen things. */
fun Palette.withAccent(a: AccentDef): Palette {
    val c = if (dark) a.dark else a.light
    return copy(accent = c, onAccent = onAccentFor(c))
}

val LocalPalette = staticCompositionLocalOf { Palettes.Light }

/** The colors of the theme on screen. */
object NoveloColors {
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

/**
 * The letters: Fraunces (a soft serif) for what is said big and for the voice of the app,
 * Nunito (round) for reading and doing, Caveat (a hand) only for the diary's dates.
 * Fraunces is drawn for its size (opsz), the way a browser does it.
 */
private fun fraunces(size: Int, weight: Int, italic: Boolean) = Font(
    if (italic) R.font.fraunces_italic else R.font.fraunces,
    weight = FontWeight(weight),
    style = if (italic) FontStyle.Italic else FontStyle.Normal,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight),
        FontVariation.Setting("opsz", size.toFloat().coerceIn(9f, 144f)),
    ),
)

/** Fraunces at [size], in the weights the screens ask for. */
fun serifFamily(size: Int, italic: Boolean = false): FontFamily =
    FontFamily(fraunces(size, 400, italic), fraunces(size, 500, italic), fraunces(size, 600, italic))

private fun nunito(weight: Int) = Font(
    R.font.nunito,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Nunito = FontFamily(nunito(400), nunito(500), nunito(600), nunito(700), nunito(800))

private fun caveat(weight: Int) = Font(
    R.font.caveat,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

val Caveat = FontFamily(caveat(500), caveat(600), caveat(700))

private fun serif(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = serifFamily(size),
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

private fun sans(size: Int, weight: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = Nunito,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = spacing.sp,
)

val NoveloType = Typography(
    displayLarge = serif(48, 400, 54, -1.0),
    displayMedium = serif(38, 400, 44, -0.6),
    displaySmall = serif(30, 400, 36, -0.4),
    headlineLarge = serif(28, 400, 34, -0.3),
    headlineMedium = serif(24, 400, 30, -0.2),
    headlineSmall = serif(21, 400, 27, -0.1),
    titleLarge = serif(19, 400, 26),
    titleMedium = sans(16, 600, 22),
    titleSmall = sans(14, 700, 19),
    bodyLarge = sans(16, 400, 23),
    bodyMedium = sans(14, 400, 20),
    bodySmall = sans(12, 400, 16),
    labelLarge = sans(14, 600, 18),
    labelMedium = sans(12, 600, 16, 0.1),
    labelSmall = sans(11, 700, 14, 0.4),
)

/** The few voices outside the type scale. */
object NoveloText {
    /** What the button invites ("O que está na sua cabeça?"): the serif, leaning. */
    val Invite = TextStyle(fontFamily = serifFamily(22, italic = true), fontWeight = FontWeight(400), fontStyle = FontStyle.Italic, fontSize = 22.sp, lineHeight = 29.sp)

    /** The word above a group ("Pra hoje"). */
    val Section = TextStyle(fontFamily = serifFamily(16, italic = true), fontWeight = FontWeight(400), fontStyle = FontStyle.Italic, fontSize = 16.sp, lineHeight = 21.sp)

    /** The diary's own reading: the serif, roomy. */
    val Diary = TextStyle(fontFamily = serifFamily(17), fontWeight = FontWeight(400), fontSize = 17.sp, lineHeight = 27.sp)

    /** A date written by hand (diary). */
    val Hand = TextStyle(fontFamily = Caveat, fontWeight = FontWeight(600), fontSize = 19.sp, lineHeight = 22.sp)
}

/** Motion: quick out, soft landing. */
object Motion {
    val Ease = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> enter(ms: Int = 320): FiniteAnimationSpec<T> = tween(ms, easing = Ease)
    fun <T> exit(ms: Int = 200): FiniteAnimationSpec<T> = tween(ms, easing = Ease)
    fun <T> soft(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    val slide: FiniteAnimationSpec<IntOffset> = spring(dampingRatio = 0.9f, stiffness = 420f)
}

@Composable
fun NoveloTheme(mode: ThemeMode = ThemeMode.LIGHT, accent: String? = null, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val p = (if (dark) Palettes.Dark else Palettes.Light).withAccent(NoveloAccents.byId(accent))
    val scheme: ColorScheme = if (dark) {
        darkColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.background, surface = p.surface, onSurface = p.text, onBackground = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.textMuted, outline = p.line, error = p.danger)
    } else {
        lightColorScheme(primary = p.accent, onPrimary = p.onAccent, background = p.background, surface = p.surface, onSurface = p.text, onBackground = p.text, surfaceVariant = p.surface2, onSurfaceVariant = p.textMuted, outline = p.line, error = p.danger)
    }
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = NoveloType, content = content)
    }
}

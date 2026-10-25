package com.noahprail.kairos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.noahprail.kairos.R

// The Kairos brand system on Material 3 (see brand/README.md).
//
// Mapping: `ink` is the interactive color (primary), `ground`/`raised` are the surfaces, and `signal` is the only hue.
// Spend `signal` once per view (the record dot, the playhead, or the single primary action), never on body text,
// and put text on a signal fill in `onSignal`. Material You dynamic color is off on purpose, so the brand stays fixed.

/** Brand colors that have no Material role. */
data class BrandColors(
    val signal: Color,
    val onSignal: Color,
    val hairline: Color,
    val muted: Color,
)

private val Ground = Color(0xFFF2F0EA)
private val Raised = Color(0xFFFAF9F5)
private val Ink = Color(0xFF14130F)
private val Muted = Color(0xFF6B675E)
private val Hairline = Color(0xFFD9D6CC)
private val Signal = Color(0xFFF23F12)

private val GroundDark = Color(0xFF14130F)
private val RaisedDark = Color(0xFF1E1D19)
private val InkDark = Color(0xFFF2F0EA)
private val MutedDark = Color(0xFFA8A398)
private val HairlineDark = Color(0xFF2E2C27)
private val SignalDark = Color(0xFFFF5A2E)
private val OnSignal = Color(0xFF14130F)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Ground,
    primaryContainer = Hairline,
    onPrimaryContainer = Ink,
    secondary = Muted,
    onSecondary = Ground,
    secondaryContainer = Hairline,
    onSecondaryContainer = Ink,
    tertiary = Signal,
    onTertiary = OnSignal,
    tertiaryContainer = Hairline,
    onTertiaryContainer = Ink,
    error = Ink,
    onError = Ground,
    errorContainer = Ink,
    onErrorContainer = Ground,
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceVariant = Raised,
    onSurfaceVariant = Muted,
    surfaceContainerLowest = Raised,
    surfaceContainerLow = Raised,
    surfaceContainer = Raised,
    surfaceContainerHigh = Raised,
    surfaceContainerHighest = Hairline,
    outline = Muted,
    outlineVariant = Hairline,
    inverseSurface = Ink,
    inverseOnSurface = Ground,
    inversePrimary = InkDark,
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = InkDark,
    onPrimary = GroundDark,
    primaryContainer = HairlineDark,
    onPrimaryContainer = InkDark,
    secondary = MutedDark,
    onSecondary = GroundDark,
    secondaryContainer = HairlineDark,
    onSecondaryContainer = InkDark,
    tertiary = SignalDark,
    onTertiary = OnSignal,
    tertiaryContainer = HairlineDark,
    onTertiaryContainer = InkDark,
    error = InkDark,
    onError = GroundDark,
    errorContainer = InkDark,
    onErrorContainer = GroundDark,
    background = GroundDark,
    onBackground = InkDark,
    surface = GroundDark,
    onSurface = InkDark,
    surfaceVariant = RaisedDark,
    onSurfaceVariant = MutedDark,
    surfaceContainerLowest = RaisedDark,
    surfaceContainerLow = RaisedDark,
    surfaceContainer = RaisedDark,
    surfaceContainerHigh = RaisedDark,
    surfaceContainerHighest = HairlineDark,
    outline = MutedDark,
    outlineVariant = HairlineDark,
    inverseSurface = InkDark,
    inverseOnSurface = GroundDark,
    inversePrimary = Ink,
    surfaceTint = Color.Transparent,
)

private val LightBrand = BrandColors(signal = Signal, onSignal = OnSignal, hairline = Hairline, muted = Muted)
private val DarkBrand = BrandColors(signal = SignalDark, onSignal = OnSignal, hairline = HairlineDark, muted = MutedDark)

val LocalBrandColors = staticCompositionLocalOf { LightBrand }

/** Space Grotesk (the wordmark, headlines and titles) and JetBrains Mono (every time, duration and config value). */
@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val DisplayFamily = FontFamily(variable(R.font.space_grotesk, 600))
val MonoFamily = FontFamily(variable(R.font.jetbrains_mono, 500))

/** Brand text styles with no Material equivalent. */
object BrandText {
    /** Uppercase section and state labels are set with this style plus `uppercase()`. */
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.08.em)
    val timecode = TextStyle(fontFamily = MonoFamily, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val timecodeLarge = TextStyle(fontFamily = MonoFamily, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium)
}

private fun brandTypography(base: Typography): Typography {
    fun TextStyle.display(spacing: Double) = copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, letterSpacing = spacing.em)
    return base.copy(
        displayLarge = base.displayLarge.display(-0.02),
        displayMedium = base.displayMedium.display(-0.02),
        displaySmall = base.displaySmall.display(-0.02),
        headlineLarge = base.headlineLarge.display(-0.02),
        headlineMedium = base.headlineMedium.display(-0.02),
        headlineSmall = base.headlineSmall.display(-0.01),
        titleLarge = base.titleLarge.display(-0.01),
        titleMedium = base.titleMedium.display(0.0),
    )
}

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

val ColorScheme.brand: BrandColors
    @Composable get() = LocalBrandColors.current

/** Material 3 with the Kairos palette, type and shapes. Follows the system light/dark setting. */
@Composable
fun KairosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalBrandColors provides if (dark) DarkBrand else LightBrand) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = brandTypography(Typography()),
            shapes = BrandShapes,
            content = content,
        )
    }
}

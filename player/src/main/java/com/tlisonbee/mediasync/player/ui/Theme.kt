package com.tlisonbee.mediasync.player.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeFidelity

/** Light or dark. */
enum class ThemeMode(val label: String, val description: String) {
    SYSTEM("Follow system", "Light or dark as the phone is set."),
    LIGHT("Light", ""),
    DARK("Dark", ""),
    BLACK("Black", "Dark with a pure black background, for AMOLED screens."),
    ;

    @Composable
    fun isDark(): Boolean = when (this) {
        SYSTEM -> isSystemInDarkTheme()
        LIGHT -> false
        DARK, BLACK -> true
    }
}

/** The accent colour: the wallpaper's, as Android picks it, or one of these. */
enum class Accent(val label: String, val seed: Int?) {
    WALLPAPER("Wallpaper", null),
    BLUE("Blue", 0xFF2962FF.toInt()),
    CYAN("Cyan", 0xFF00B8D4.toInt()),
    TEAL("Teal", 0xFF00897B.toInt()),
    GREEN("Green", 0xFF43A047.toInt()),
    YELLOW("Yellow", 0xFFFDD835.toInt()),
    ORANGE("Orange", 0xFFFB8C00.toInt()),
    RED("Red", 0xFFE53935.toInt()),
    PINK("Pink", 0xFFD81B60.toInt()),
    PURPLE("Purple", 0xFF8E24AA.toInt()),
}

/** Segue's colours: Material's roles worked out from the accent, light or dark, and black if chosen. */
fun segueColors(context: Context, accent: Accent, dark: Boolean, black: Boolean): ColorScheme {
    val scheme = when (val seed = accent.seed) {
        null -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        else -> accented(fromSeed(SchemeFidelity(Hct.fromInt(seed), dark, 0.0), dark), seed, dark)
    }
    return if (black && dark) scheme.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF080808),
        surfaceContainer = Color(0xFF0E0E0E),
    ) else scheme
}

/**
 * Material's dark schemes lighten the primary colour until most hues are pastel; an accent you pick
 * should look like itself, so the primary keeps the colour's hue and as much of its saturation as
 * fits at a lightness that still reads on the background (and on it, black or white text).
 */
private fun accented(scheme: ColorScheme, seed: Int, dark: Boolean): ColorScheme {
    val hct = Hct.fromInt(seed)
    val primary = Color(Hct.from(hct.hue, hct.chroma, if (dark) 70.0 else 40.0).toInt())
    val on = Color(Hct.from(hct.hue, 16.0, if (dark) 10.0 else 100.0).toInt())
    return scheme.copy(primary = primary, onPrimary = on, surfaceTint = primary)
}

private fun fromSeed(s: DynamicScheme, dark: Boolean): ColorScheme {
    val c = MaterialDynamicColors()
    fun DynamicColor.color() = Color(getArgb(s))
    // Every role from the scheme; which base is used only matters for the light/dark default of any role left out.
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.primary().color(),
        onPrimary = c.onPrimary().color(),
        primaryContainer = c.primaryContainer().color(),
        onPrimaryContainer = c.onPrimaryContainer().color(),
        inversePrimary = c.inversePrimary().color(),
        secondary = c.secondary().color(),
        onSecondary = c.onSecondary().color(),
        secondaryContainer = c.secondaryContainer().color(),
        onSecondaryContainer = c.onSecondaryContainer().color(),
        tertiary = c.tertiary().color(),
        onTertiary = c.onTertiary().color(),
        tertiaryContainer = c.tertiaryContainer().color(),
        onTertiaryContainer = c.onTertiaryContainer().color(),
        background = c.background().color(),
        onBackground = c.onBackground().color(),
        surface = c.surface().color(),
        onSurface = c.onSurface().color(),
        surfaceVariant = c.surfaceVariant().color(),
        onSurfaceVariant = c.onSurfaceVariant().color(),
        surfaceTint = c.surfaceTint().color(),
        inverseSurface = c.inverseSurface().color(),
        inverseOnSurface = c.inverseOnSurface().color(),
        error = c.error().color(),
        onError = c.onError().color(),
        errorContainer = c.errorContainer().color(),
        onErrorContainer = c.onErrorContainer().color(),
        outline = c.outline().color(),
        outlineVariant = c.outlineVariant().color(),
        scrim = c.scrim().color(),
        surfaceBright = c.surfaceBright().color(),
        surfaceContainer = c.surfaceContainer().color(),
        surfaceContainerHigh = c.surfaceContainerHigh().color(),
        surfaceContainerHighest = c.surfaceContainerHighest().color(),
        surfaceContainerLow = c.surfaceContainerLow().color(),
        surfaceContainerLowest = c.surfaceContainerLowest().color(),
        surfaceDim = c.surfaceDim().color(),
    )
}

@Composable
fun SegueTheme(mode: ThemeMode, accent: Accent, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = mode.isDark()
    val colors = remember(accent, dark, mode) { segueColors(context, accent, dark, mode == ThemeMode.BLACK) }
    MaterialTheme(colorScheme = colors, content = content)
}

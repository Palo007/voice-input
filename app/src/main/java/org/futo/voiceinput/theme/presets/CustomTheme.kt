package org.futo.voiceinput.theme.presets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils
import org.futo.voiceinput.R
import org.futo.voiceinput.settings.CUSTOM_THEME_COLOR
import org.futo.voiceinput.settings.CUSTOM_THEME_MODE
import org.futo.voiceinput.settings.getSettingBlocking
import org.futo.voiceinput.theme.ThemeOption

object CustomThemeMode {
    const val SYSTEM = "system"
    const val DARK = "dark"
    const val LIGHT = "light"
    const val BLACK = "black"
    val all = listOf(SYSTEM, DARK, LIGHT, BLACK)
}

/** Accent colors offered in the custom theme editor. */
val CustomThemeSwatches: List<Int> = listOf(
    0xFF1E88E5, 0xFF3949AB, 0xFF5E35B1, 0xFF8E24AA, 0xFFD81B60, 0xFFE53935,
    0xFFF4511E, 0xFFFB8C00, 0xFFFDD835, 0xFF7CB342, 0xFF43A047, 0xFF00897B,
    0xFF00ACC1, 0xFF6D4C41, 0xFF546E7A, 0xFF757575
).map { it.toInt() }

/** Builds a full Material 3 color scheme from one accent color, using HSL tones. */
fun customColorScheme(context: Context, seed: Int, mode: String): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    val hue = hsl[0]
    // Grays get neutral palettes; colors get at least some saturation so the accent stays visible.
    val gray = hsl[1] < 0.08f
    val saturation = if (gray) 0f else hsl[1].coerceIn(0.45f, 0.9f)
    fun tone(h: Float, s: Float, t: Int) =
        Color(ColorUtils.HSLToColor(floatArrayOf((h + 360f) % 360f, s.coerceIn(0f, 1f), t / 100f)))
    fun p(t: Int) = tone(hue, saturation, t)
    fun s(t: Int) = tone(hue, saturation * 0.35f, t)
    fun ter(t: Int) = tone(hue + 60f, saturation * 0.55f, t)
    fun n(t: Int) = tone(hue, if (gray) 0f else 0.06f, t)
    fun nv(t: Int) = tone(hue, if (gray) 0f else 0.14f, t)

    val dark = when (mode) {
        CustomThemeMode.LIGHT -> false
        CustomThemeMode.DARK, CustomThemeMode.BLACK -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            Configuration.UI_MODE_NIGHT_NO
    }
    if (!dark) return lightColorScheme(
        primary = p(40), onPrimary = Color.White, primaryContainer = p(90), onPrimaryContainer = p(10),
        inversePrimary = p(80), surfaceTint = p(40),
        secondary = s(40), onSecondary = Color.White, secondaryContainer = s(90), onSecondaryContainer = s(10),
        tertiary = ter(40), onTertiary = Color.White, tertiaryContainer = ter(90), onTertiaryContainer = ter(10),
        background = n(98), onBackground = n(10), surface = n(98), onSurface = n(10),
        surfaceVariant = nv(90), onSurfaceVariant = nv(30), outline = nv(50), outlineVariant = nv(80),
        inverseSurface = n(20), inverseOnSurface = n(95),
        surfaceDim = n(87), surfaceBright = n(98), surfaceContainerLowest = Color.White,
        surfaceContainerLow = n(96), surfaceContainer = n(94), surfaceContainerHigh = n(92),
        surfaceContainerHighest = n(90)
    )
    val black = mode == CustomThemeMode.BLACK
    val base = if (black) Color.Black else n(6)
    return darkColorScheme(
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
        inversePrimary = p(40), surfaceTint = p(80),
        secondary = s(80), onSecondary = s(20), secondaryContainer = s(30), onSecondaryContainer = s(90),
        tertiary = ter(80), onTertiary = ter(20), tertiaryContainer = ter(30), onTertiaryContainer = ter(90),
        background = base, onBackground = n(90), surface = base, onSurface = n(90),
        surfaceVariant = nv(if (black) 20 else 30), onSurfaceVariant = nv(80), outline = nv(60), outlineVariant = nv(30),
        inverseSurface = n(90), inverseOnSurface = n(20),
        surfaceDim = base, surfaceBright = n(if (black) 18 else 24),
        surfaceContainerLowest = if (black) Color.Black else n(4),
        surfaceContainerLow = n(if (black) 4 else 10), surfaceContainer = n(if (black) 7 else 12),
        surfaceContainerHigh = n(if (black) 10 else 17), surfaceContainerHighest = n(if (black) 14 else 22)
    )
}

val CustomTheme = ThemeOption(
    dynamic = false,
    key = "Custom",
    name = R.string.custom_theme_name,
    available = { true }
) { context ->
    customColorScheme(context,
        context.getSettingBlocking(CUSTOM_THEME_COLOR.key, CUSTOM_THEME_COLOR.default),
        context.getSettingBlocking(CUSTOM_THEME_MODE.key, CUSTOM_THEME_MODE.default))
}

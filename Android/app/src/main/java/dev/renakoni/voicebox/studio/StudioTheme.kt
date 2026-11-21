package dev.renakoni.voicebox.studio

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.app.Activity
import dev.renakoni.voicebox.AppSettingsRepository.ThemeMode
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

// Paper and clay in daylight; the same hue relationships soften for evening use.
internal fun oklch(lightness: Double, chroma: Double, hue: Double): Color {
    val a = chroma * cos(Math.toRadians(hue))
    val b = chroma * sin(Math.toRadians(hue))
    val l = (lightness + 0.3963377774 * a + 0.2158037573 * b).pow(3)
    val m = (lightness - 0.1055613458 * a - 0.0638541728 * b).pow(3)
    val s = (lightness - 0.0894841775 * a - 1.2914855480 * b).pow(3)
    fun channel(value: Double): Float = (if (value <= 0.0031308) 12.92 * value
        else 1.055 * value.pow(1.0 / 2.4) - 0.055).coerceIn(0.0, 1.0).toFloat()
    return Color(channel(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s),
        channel(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s),
        channel(-0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s))
}

private val Light = lightColorScheme(
    primary = oklch(.49, .13, 40.0), onPrimary = oklch(.99, .005, 75.0),
    primaryContainer = oklch(.93, .035, 55.0), onPrimaryContainer = oklch(.36, .08, 40.0),
    secondary = oklch(.47, .035, 55.0), onSecondary = oklch(.99, .005, 75.0),
    secondaryContainer = oklch(.93, .016, 65.0), onSecondaryContainer = oklch(.30, .02, 55.0),
    background = oklch(.98, .006, 75.0), onBackground = oklch(.23, .012, 55.0),
    surface = oklch(.98, .006, 75.0), onSurface = oklch(.23, .012, 55.0),
    surfaceVariant = oklch(.94, .008, 65.0), onSurfaceVariant = oklch(.47, .014, 55.0),
    surfaceContainer = oklch(.96, .008, 65.0), surfaceContainerLow = oklch(.99, .004, 75.0),
    surfaceContainerHigh = oklch(.93, .008, 65.0),
    outline = oklch(.58, .012, 55.0), outlineVariant = oklch(.87, .01, 65.0),
    error = oklch(.48, .18, 25.0), errorContainer = oklch(.95, .025, 25.0),
    onErrorContainer = oklch(.38, .12, 25.0),
)
private val Dark = darkColorScheme(
    primary = oklch(.79, .105, 50.0), onPrimary = oklch(.23, .04, 40.0),
    primaryContainer = oklch(.32, .05, 40.0), onPrimaryContainer = oklch(.90, .05, 55.0),
    secondary = oklch(.77, .03, 65.0), onSecondary = oklch(.24, .012, 55.0),
    secondaryContainer = oklch(.30, .015, 55.0), onSecondaryContainer = oklch(.91, .012, 65.0),
    background = oklch(.18, .008, 55.0), onBackground = oklch(.92, .008, 65.0),
    surface = oklch(.18, .008, 55.0), onSurface = oklch(.92, .008, 65.0),
    surfaceVariant = oklch(.28, .009, 55.0), onSurfaceVariant = oklch(.75, .012, 65.0),
    surfaceContainer = oklch(.23, .008, 55.0), surfaceContainerLow = oklch(.21, .008, 55.0),
    surfaceContainerHigh = oklch(.29, .009, 55.0),
    outline = oklch(.62, .01, 65.0), outlineVariant = oklch(.36, .01, 55.0),
    error = oklch(.80, .10, 25.0), errorContainer = oklch(.30, .07, 25.0),
    onErrorContainer = oklch(.90, .05, 25.0),
)
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 27.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
)

@Composable
fun StudioTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val view = LocalView.current
    SideEffect {
        val window = (view.context as Activity).window
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, content = content)
}

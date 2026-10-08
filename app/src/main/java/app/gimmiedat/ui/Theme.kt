package app.gimmiedat.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.gimmiedat.R
import app.gimmiedat.data.ThemeMode

/**
 * yoinks/src/theme.ts, verbatim: primary / gray / dark / background per mode.
 * `line` is the terminal's dimmed border gray; `faint` the empty-cell shade.
 */
@Immutable
data class Palette(
    val bg: Color,
    val fg: Color,
    val gray: Color,
    val line: Color,
    val faint: Color,
    val onFg: Color,
    val isDark: Boolean,
)

val DarkPalette = Palette(
    bg = Color(0xFF18181B),
    fg = Color(0xFFFFFFFF),
    gray = Color(0xFFA1A1AA),
    line = Color(0xFF52525B),
    faint = Color(0xFF3F3F46),
    onFg = Color(0xFF18181B),
    isDark = true,
)

val LightPalette = Palette(
    bg = Color(0xFFFFFFFF),
    fg = Color(0xFF18181B),
    gray = Color(0xFF52525B),
    line = Color(0xFFA1A1AA),
    faint = Color(0xFFD4D4D8),
    onFg = Color(0xFFFFFFFF),
    isDark = false,
)

val Mono = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

object Type {
    val body = TextStyle(fontFamily = Mono, fontSize = 15.sp, lineHeight = 22.sp)
    val small = TextStyle(fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp)
    val tiny = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 16.sp)
    val title = TextStyle(fontFamily = Mono, fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold)
    val big = TextStyle(fontFamily = Mono, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold)
}

val LocalPalette = staticCompositionLocalOf { DarkPalette }

@Composable
fun resolvePalette(mode: ThemeMode): Palette = when (mode) {
    ThemeMode.AUTO -> if (isSystemInDarkTheme()) DarkPalette else LightPalette
    ThemeMode.LIGHT -> LightPalette
    ThemeMode.DARK -> DarkPalette
}

@Composable
fun GimmieDatTheme(palette: Palette, content: @Composable () -> Unit) {
    val scheme = if (palette.isDark) {
        darkColorScheme(
            primary = palette.fg, onPrimary = palette.onFg, background = palette.bg, onBackground = palette.fg,
            surface = palette.bg, onSurface = palette.fg, surfaceContainer = palette.bg,
            surfaceContainerLow = palette.bg, surfaceContainerHigh = palette.bg, onSurfaceVariant = palette.gray,
            outline = palette.line, outlineVariant = palette.faint, secondary = palette.gray,
        )
    } else {
        lightColorScheme(
            primary = palette.fg, onPrimary = palette.onFg, background = palette.bg, onBackground = palette.fg,
            surface = palette.bg, onSurface = palette.fg, surfaceContainer = palette.bg,
            surfaceContainerLow = palette.bg, surfaceContainerHigh = palette.bg, onSurfaceVariant = palette.gray,
            outline = palette.line, outlineVariant = palette.faint, secondary = palette.gray,
        )
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalPalette provides palette, content = content)
    }
}

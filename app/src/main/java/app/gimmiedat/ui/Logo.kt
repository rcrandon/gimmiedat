package app.gimmiedat.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

// gimmie 'dat in the pixel font of yoinks/src/components/logo.tsx (its I, Nab 'Dat's
// ' D A T, and G M E drawn to the same rules), drawn as pixels instead of terminal cells.
// Each letter dithers one stroke of its middle band; the word gap is 3 columns so the
// ' reads as part of 'dat.
private val ART = listOf(
    "█▀▀▀ ▀█▀ █▄ ▄█ █▄ ▄█ ▀█▀ █▀▀   █ █▀▄ █▀█ ▀█▀",
    "█ ▀▓  ▓  █ ▀ ▓ █ ▀ ▓  ▓  ▓▀      █ ▓ █▀▓  ▓ ",
    "▀▀▀▀ ▀▀▀ ▀   ▀ ▀   ▀ ▀▀▀ ▀▀▀     ▀▀  ▀ ▀  ▀ ",
)
private const val ROWS = 3
private const val COLS = 44

// intro: each glyph flickers in as ░, sharpens to ▒, then resolves
private const val INTRO_MS = 900f
private const val INTRO_SPREAD_MS = 550f
// shimmer: a tilted beam crosses the glyphs, thinning them one density step
private const val SWEEP_MS = 1000f
private const val SWEEP_EVERY_MS = 7_000L
private const val TILT = 2 // columns of lean per row, beam slants like /
private const val HALF = 2.4f // beam half-width

private fun ease(t: Float) = 1 - (1 - t).pow(3)

private enum class LogoPhase { INTRO, IDLE, SWEEP }

private data class Cell(val ch: Char, val color: Color)

private fun cellAt(ch: Char, row: Int, col: Int, phase: LogoPhase, t: Float, delay: Float, fg: Color, gray: Color): Cell {
    if (ch == ' ' || phase == LogoPhase.IDLE) return Cell(ch, fg)
    val half = ch == '▀' || ch == '▄'
    if (phase == LogoPhase.INTRO) {
        val dt = t - delay
        if (dt < 0) return Cell(' ', fg)
        if (dt < 110) return Cell(if (half) ch else '░', gray)
        if (dt < 220) return Cell(if (half) ch else '▒', gray)
        return Cell(ch, fg)
    }
    // sweep, beam position leans right as it climbs, only glyphs are touched
    val pMin = -TILT * ROWS - HALF
    val pMax = COLS + HALF
    val p = pMin + ease((t / SWEEP_MS).coerceIn(0f, 1f)) * (pMax - pMin)
    val d = abs(col - (ROWS - 1 - row) * TILT - p)
    if (d <= HALF && 1 - d / HALF > 0.35f) {
        if (half) return Cell(ch, gray)
        return Cell(
            when (ch) {
                '█' -> '▒'
                '▓' -> '░'
                else -> ch
            },
            fg,
        )
    }
    return Cell(ch, fg)
}

/**
 * Draws one glyph into its cell. A cell is 4×8 "squares" (the terminal's 1:2 cell);
 * shades are dithers on a page-wide grid so neighbouring cells line up, like the
 * `shade` pattern in assets/logo-*.svg.
 */
private fun DrawScope.glyph(ch: Char, col: Int, row: Int, color: Color, s: Float) {
    val x0 = col * 4 * s
    val y0 = row * 8 * s
    when (ch) {
        '█' -> drawRect(color, Offset(x0, y0), Size(4 * s, 8 * s))
        '▀' -> drawRect(color, Offset(x0, y0), Size(4 * s, 4 * s))
        '▄' -> drawRect(color, Offset(x0, y0 + 4 * s), Size(4 * s, 4 * s))
        '▓', '▒', '░' -> {
            for (j in 0 until 8) for (i in 0 until 4) {
                val gx = col * 4 + i
                val gy = row * 8 + j
                val on = when (ch) {
                    '░' -> gx % 2 == 0 && gy % 2 == 0
                    else -> (gx + gy) % 2 == 0
                }
                if (on) drawRect(color, Offset(x0 + i * s, y0 + j * s), Size(s, s))
            }
        }
    }
}

@Composable
fun PixelLogo(
    cell: Dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val animate = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val delays = remember { List(ROWS) { FloatArray(COLS) { Random.nextFloat() * INTRO_SPREAD_MS } } }
    var phase by remember { mutableStateOf(if (animate) LogoPhase.INTRO else LogoPhase.IDLE) }
    var t by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        suspend fun run(duration: Float) {
            val start = withFrameMillis { it }
            while (true) {
                val now = withFrameMillis { it }
                val elapsed = (now - start).toFloat()
                if (elapsed >= duration) break
                t = elapsed
            }
            t = 0f
        }
        if (phase == LogoPhase.INTRO) run(INTRO_MS)
        while (true) {
            phase = LogoPhase.IDLE
            delay(SWEEP_EVERY_MS)
            phase = LogoPhase.SWEEP
            run(SWEEP_MS)
        }
    }

    // integer pixel squares keep the blocks razor sharp at any density
    val animatedCell by animateFloatAsState(cell.value, tween(320), label = "logoCell")
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        // 44 columns is wide: on most phones the squares step down a size or two to fit
        val fit = if (constraints.hasBoundedWidth) constraints.maxWidth / (COLS * 4) else Int.MAX_VALUE
        val s = max(1, minOf(fit, (with(density) { animatedCell.dp.toPx() } / 4f).roundToInt()))
        val width = with(density) { (COLS * 4 * s).toDp() }
        val height = with(density) { (ROWS * 8 * s).toDp() }

        var m = Modifier.size(width, height).semantics { contentDescription = "Gimmie 'Dat" }
        if (onClick != null) m = m.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)

        Canvas(m) {
            val sf = s.toFloat()
            for (row in 0 until ROWS) {
                val line = ART[row]
                for (col in 0 until COLS) {
                    val c = cellAt(line[col], row, col, phase, t, delays[row][col], palette.fg, palette.gray)
                    if (c.ch != ' ') glyph(c.ch, col, row, c.color, sf)
                }
            }
        }
    }
}

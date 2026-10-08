package app.gimmiedat.ui

import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/** Half the title row: the frame's top line runs through the middle of the title. */
val TitleBand = 9.5.dp
private val FrameRadius = 7.dp

@Composable
fun Gap(height: Dp) = Spacer(Modifier.height(height))

/**
 * `╭─ Title ──────╮`, a rounded frame with its title sitting on the top border,
 * like the CLI's Panel / FramedInput. With [openRight] the right side is left open so a
 * button can be forged onto it.
 */
@Composable
fun TerminalFrame(
    title: String?,
    modifier: Modifier = Modifier,
    openRight: Boolean = false,
    titleColor: Color = LocalPalette.current.fg,
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    Box(modifier.padding(top = TitleBand)) {
        Box(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    val sw = 1.dp.toPx()
                    val r = FrameRadius.toPx()
                    val half = sw / 2
                    val path = Path()
                    if (openRight) {
                        path.moveTo(size.width, half)
                        path.lineTo(half + r, half)
                        path.arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 270f, -90f, false)
                        path.lineTo(half, size.height - half - r)
                        path.arcTo(Rect(half, size.height - half - 2 * r, half + 2 * r, size.height - half), 180f, -90f, false)
                        path.lineTo(size.width, size.height - half)
                    } else {
                        path.addRoundRect(RoundRect(half, half, size.width - half, size.height - half, CornerRadius(r)))
                    }
                    drawPath(path, palette.line, style = Stroke(sw))
                }
                .padding(contentPadding),
            content = content,
        )
        if (title != null) {
            Text(
                title,
                style = Type.small,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset(x = 12.dp, y = -TitleBand)
                    .background(palette.bg)
                    .padding(horizontal = 6.dp),
            )
        }
    }
}

/**
 * The "Paste a link" box with a filled button forged onto its right edge, the frame's
 * lines run straight into the block so input and button read as one control.
 */
@Composable
fun FramedInput(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "https://youtube.com/watch?v=…",
    button: String = "gimmie 'dat",
    readOnly: Boolean = false,
    buttonDim: Boolean = false,
) {
    val palette = LocalPalette.current
    Row(modifier.height(IntrinsicSize.Min)) {
        TerminalFrame(
            title = title,
            openRight = true,
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 14.dp, end = 10.dp, top = 14.dp, bottom = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("❯ ", style = Type.body, color = palette.fg)
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    singleLine = true,
                    textStyle = Type.body.copy(color = if (readOnly) palette.gray else palette.fg),
                    cursorBrush = SolidColor(palette.fg),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Go,
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                    ),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (value.isEmpty()) {
                                Text(placeholder, style = Type.body, color = palette.gray, maxLines = 1, overflow = TextOverflow.Clip)
                            }
                            inner()
                        }
                    },
                )
            }
        }
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val fill = if (buttonDim) palette.gray else palette.fg
        Box(
            Modifier
                .padding(top = TitleBand)
                .fillMaxHeight()
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(fill.copy(alpha = if (pressed) 0.82f else 1f))
                .clickable(interactionSource = interaction, indication = null, enabled = !buttonDim, onClick = onSubmit)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(button, style = Type.body.copy(fontWeight = FontWeight.Bold), color = palette.onFg)
        }
    }
}

/** A terminal-ish button: outlined, or [filled] for the one primary action on screen. */
@Composable
fun TermButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(FrameRadius)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val base = if (filled) {
        Modifier.background(palette.fg.copy(alpha = if (pressed) 0.82f else 1f), shape)
    } else {
        Modifier
            .background(if (pressed) palette.faint.copy(alpha = 0.5f) else Color.Transparent, shape)
            .border(1.dp, palette.line, shape)
    }
    Box(
        modifier
            .clip(shape)
            .then(base)
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Type.body.copy(fontWeight = FontWeight.Bold),
            color = if (filled) palette.onFg else palette.fg,
            maxLines = 1,
        )
    }
}

private val SPINNER = listOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏") // ink-spinner "dots"

@Composable
fun Spinner(style: TextStyle = Type.body, color: Color = LocalPalette.current.fg) {
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(80)
            frame = (frame + 1) % SPINNER.size
        }
    }
    Text(SPINNER[frame], style = style, color = color)
}

/** "⠋ fetching video info…" */
@Composable
fun SpinnerLine(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spinner(Type.small)
        Text(" $text", style = Type.small, color = palette.gray)
    }
}

/** A tiled dither (░), loading placeholders and the empty part of the progress bar. */
@Composable
fun rememberDitherBrush(color: Color, square: Dp = 2.dp): ShaderBrush {
    val s = max(1, with(LocalDensity.current) { square.toPx() }.roundToInt())
    return remember(color, s) {
        val tile = ImageBitmap(s * 2, s * 2)
        GraphicsCanvas(tile).drawRect(Rect(0f, 0f, s.toFloat(), s.toFloat()), Paint().apply { this.color = color })
        ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    }
}

/**
 * The CLI's `█████░░░░░  47%` bar, one cell per character. The cell at the leading edge
 * shades in (░ ▒ ▓) so the bar creeps instead of jumping a whole cell at a time.
 * [fraction] null = indeterminate (a block bouncing along the track).
 */
@Composable
fun ProgressBlocks(fraction: Float?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val s = max(1, with(density) { 2.dp.toPx() }.roundToInt())
    val barHeight = with(density) { (8 * s).toDp() }
    val animated by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(450), label = "progress")
    val context = LocalContext.current
    val motion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    var clock by remember { mutableIntStateOf(0) }
    if (fraction == null && motion) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            while (true) clock = (withFrameMillis { it } - start).toInt()
        }
    }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.weight(1f).height(barHeight)) {
            val cellW = 4 * s
            val n = max(1, floor(size.width / cellW).toInt())
            val sf = s.toFloat()
            fun dither(i: Int, level: Int, color: Color) {
                // level: 1 = ░ (25%), 2 = ▒ (50%), 3 = ▓ (75%)
                for (y in 0 until 8) for (x in 0 until 4) {
                    val gx = i * 4 + x
                    val on = when (level) {
                        1 -> gx % 2 == 0 && y % 2 == 0
                        2 -> (gx + y) % 2 == 0
                        else -> !(gx % 2 == 1 && y % 2 == 1)
                    }
                    if (on) drawRect(color, Offset(gx * sf, y * sf), Size(sf, sf))
                }
            }
            if (fraction == null) {
                val span = 4
                val period = (n + span) * 2
                val tick = (clock / 70) % period
                val head = if (tick < n + span) tick else period - tick
                for (i in 0 until n) {
                    if (i in (head - span) until head) {
                        drawRect(palette.fg, Offset(i * cellW.toFloat(), 0f), Size(cellW.toFloat(), 8 * sf))
                    } else {
                        dither(i, 1, palette.line)
                    }
                }
            } else {
                val fill = animated * n
                val full = floor(fill).toInt()
                val partial = fill - full
                for (i in 0 until n) {
                    when {
                        i < full -> drawRect(palette.fg, Offset(i * cellW.toFloat(), 0f), Size(cellW.toFloat(), 8 * sf))
                        i == full && partial > 0.05f -> dither(i, if (partial < 0.34f) 1 else if (partial < 0.67f) 2 else 3, palette.fg)
                        else -> dither(i, 1, palette.line)
                    }
                }
            }
        }
        if (fraction != null) {
            // fixed-width percent, "5%" vs "100%" must not change the line width
            Text(
                " " + "${(animated * 100).roundToInt()}%".padStart(4),
                style = Type.small.copy(fontWeight = FontWeight.Bold),
                color = palette.fg,
            )
        }
    }
}

data class Hint(val key: String, val label: String, val onClick: (() -> Unit)? = null)

/** The footer row: `esc back  ·  ◐ theme:auto`, every hint is a button. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HintBar(items: List<Hint>, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    val palette = LocalPalette.current
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.Center,
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        items.forEachIndexed { index, hint ->
            if (index > 0 || leading != null) Text("·", style = Type.small, color = palette.line)
            val text = buildAnnotatedString {
                withStyle(SpanStyle(color = palette.fg)) { append(hint.key) }
                withStyle(SpanStyle(color = palette.gray)) { append(" ${hint.label}") }
            }
            Text(
                text,
                style = Type.small,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .then(if (hint.onClick != null) Modifier.clickable(onClick = hint.onClick) else Modifier)
                    .padding(horizontal = 10.dp, vertical = 12.dp),
            )
        }
    }
}

/** Centered secondary line used for hints under the input. */
@Composable
fun GrayLine(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Text(
        text,
        style = Type.small,
        color = palette.gray,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

@Composable
fun FixedWidth(width: Dp, content: @Composable BoxScope.() -> Unit) =
    Box(Modifier.width(width), contentAlignment = Alignment.CenterStart, content = content)

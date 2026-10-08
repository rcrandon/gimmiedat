package app.gimmiedat.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.gimmiedat.BuildConfig
import app.gimmiedat.Phase
import app.gimmiedat.Grabber
import app.gimmiedat.data.History
import app.gimmiedat.data.HistoryEntry
import app.gimmiedat.data.MediaSaver
import app.gimmiedat.data.Prefs
import app.gimmiedat.data.SavedFile
import app.gimmiedat.engine.DownloadChoice
import app.gimmiedat.engine.DownloadProgress
import app.gimmiedat.engine.Engine
import app.gimmiedat.engine.Kind
import app.gimmiedat.engine.extractUrl
import app.gimmiedat.engine.formatAgo
import app.gimmiedat.engine.formatBytes
import app.gimmiedat.engine.formatDuration
import app.gimmiedat.engine.formatEta
import app.gimmiedat.engine.formatSpeed
import app.gimmiedat.engine.truncate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

enum class ClipState { NONE, TEXT, LINK }

private const val TAGLINE = "see video, paste video, gimmie 'dat video"
private const val REPO = "github.com/rcrandon/gimmiedat"
private const val SITES = "youtube · x · instagram · threads\ntiktok · +1800 more"

@Composable
fun GimmieDatRoot(clip: StateFlow<ClipState>, readClipboard: () -> String?) {
    val mode by Prefs.theme.collectAsState()
    val palette = resolvePalette(mode)
    val activity = LocalContext.current as ComponentActivity
    LaunchedEffect(palette.isDark) {
        val style = if (palette.isDark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        activity.enableEdgeToEdge(style, style)
    }
    GimmieDatTheme(palette) {
        GimmieDatScreen(clip, readClipboard)
    }
}

@Composable
private fun GimmieDatScreen(clipFlow: StateFlow<ClipState>, readClipboard: () -> String?) {
    val phase by Grabber.phase.collectAsStateWithLifecycle()
    val theme by Prefs.theme.collectAsState()
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var showAbout by rememberSaveable { mutableStateOf(false) }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val pick: (DownloadChoice) -> Unit = { choice ->
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (Build.VERSION.SDK_INT >= 33 && !Prefs.askedForNotifications &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // asked once, right when it starts to matter: progress while you're in another app
            Prefs.askedForNotifications = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Grabber.pick(choice)
    }
    val submit: (String) -> Unit = { text ->
        focus.clearFocus()
        if (Grabber.submit(text)) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        else haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }

    // esc: back out of a step. During a download, back just leaves, it keeps going.
    BackHandler(enabled = phase is Phase.Probing) { Grabber.cancel() }
    BackHandler(enabled = phase is Phase.Picking || phase is Phase.Failed || phase is Phase.Done) { Grabber.reset() }

    val home = phase is Phase.Input
    val cycleTheme = { Prefs.setTheme(theme.next()) }
    val themeHint = Hint("◐", "theme:${theme.label}", cycleTheme)
    val aboutHint = Hint("?", "about") { showAbout = true }
    val hints = when (phase) {
        is Phase.Input -> listOf(themeHint, aboutHint)
        is Phase.Probing -> listOf(Hint("esc", "cancel") { Grabber.cancel() }, themeHint)
        is Phase.Picking -> listOf(Hint("esc", "back") { Grabber.reset() }, themeHint)
        is Phase.Downloading -> listOf(Hint("esc", "cancel") { Grabber.cancel() }, themeHint)
        is Phase.Done -> listOf(themeHint, aboutHint)
        is Phase.Failed -> listOf(Hint("↻", "try again") { Grabber.retry() }, Hint("esc", "back") { Grabber.reset() }, themeHint)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val top by animateDpAsState(if (home) 64.dp else 16.dp, tween(320), label = "top")
                Spacer(Modifier.height(top))
                // the logo takes you home, like clicking it in the CLI
                PixelLogo(
                    cell = if (home) 11.dp else 5.dp,
                    onClick = {
                        when (phase) {
                            is Phase.Probing -> Grabber.cancel()
                            is Phase.Downloading, is Phase.Input -> Unit
                            else -> Grabber.reset()
                        }
                    },
                )
                AnimatedVisibility(home, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Gap(18.dp)
                        Text(TAGLINE, style = Type.small.copy(fontWeight = FontWeight.Medium), color = palette.fg, textAlign = TextAlign.Center)
                        Gap(4.dp)
                        Text(SITES, style = Type.tiny, color = palette.gray, textAlign = TextAlign.Center)
                    }
                }
                Gap(if (home) 36.dp else 22.dp)

                AnimatedContent(
                    targetState = phase,
                    contentKey = { it::class },
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(260)) { it / 14 }) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "phase",
                ) { p ->
                    Column(Modifier.fillMaxWidth().widthIn(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (p) {
                            is Phase.Input -> InputPhase(p, clipFlow, readClipboard, submit)
                            is Phase.Probing -> ProbingPhase(p)
                            is Phase.Picking -> PickingPhase(p, pick)
                            is Phase.Downloading -> DownloadingPhase(p)
                            is Phase.Done -> DonePhase(p)
                            is Phase.Failed -> FailedPhase(p)
                        }
                    }
                }
                Gap(24.dp)
            }
            HintBar(hints, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
        }
    }

    if (showAbout) AboutSheet(onDismiss = { showAbout = false })
}

// ───────────────────────────── input ─────────────────────────────

@Composable
private fun InputPhase(
    phase: Phase.Input,
    clipFlow: StateFlow<ClipState>,
    readClipboard: () -> String?,
    submit: (String) -> Unit,
) {
    val input by Grabber.input.collectAsState()
    val clip by clipFlow.collectAsState()
    val engine by Engine.status.collectAsState()
    val history by History.entries.collectAsState()
    val context = LocalContext.current

    FramedInput(
        title = "Paste a link",
        value = input,
        onValueChange = {
            Grabber.input.value = it
            Grabber.clearWarning()
            // pasting a whole link grabs it right away, like the CLI's submitOnPaste
            if (it.length - input.length > 8 && extractUrl(it) == it.trim()) submit(it)
        },
        onSubmit = { submit(input) },
        modifier = Modifier.fillMaxWidth(),
    )
    Gap(6.dp)
    val pasteFromClipboard = {
        val text = readClipboard()
        val url = extractUrl(text)
        when {
            url != null -> submit(url)
            text.isNullOrBlank() -> Toast.makeText(context, "your clipboard is empty", Toast.LENGTH_SHORT).show()
            else -> Grabber.input.value = text.trim()
        }
    }
    when {
        phase.warning != null -> GrayLine("✗ ${phase.warning}")
        input.isEmpty() && clip == ClipState.LINK -> GrayLine("link in your clipboard: tap to grab it", onClick = pasteFromClipboard)
        input.isEmpty() && clip == ClipState.TEXT -> GrayLine("⧉ tap to paste from your clipboard", onClick = pasteFromClipboard)
        input.isEmpty() -> GrayLine("or share a video to gimmie 'dat from any app")
        else -> GrayLine("↵ to grab it")
    }
    if (!engine.ready && Engine.isFirstRun) {
        Gap(2.dp)
        SpinnerLine("first run: unpacking the engine…")
    }
    if (history.isNotEmpty()) {
        Gap(28.dp)
        RecentPanel(history)
    }
}

@Composable
private fun RecentPanel(history: List<HistoryEntry>) {
    TerminalFrame(
        title = "Recent",
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        Column {
            history.take(20).forEach { entry -> RecentRow(entry) }
        }
    }
}

@Composable
private fun RecentRow(entry: HistoryEntry) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val file = entry.files.firstOrNull()
    val glyph = if (entry.kind == Kind.AUDIO) "♪" else "▶"
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { if (file != null) openFile(context, file) }
            .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FileThumb(file?.uri, entry.thumbnail, Modifier.width(76.dp), glyph)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = Type.small.copy(fontWeight = FontWeight.Bold), color = palette.fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val size = formatBytes(entry.totalSize)
            val count = if (entry.files.size > 1) " · ${entry.files.size} files" else ""
            Text(
                "$glyph ${entry.label}$count${if (size.isNotEmpty()) " · $size" else ""} · ${formatAgo(entry.time)}",
                style = Type.tiny,
                color = palette.gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Text(
                "⋯",
                style = Type.title,
                color = palette.gray,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { menu = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = palette.bg,
                border = androidx.compose.foundation.BorderStroke(1.dp, palette.line),
                shape = RoundedCornerShape(7.dp),
            ) {
                MenuRow("⇪ share") { menu = false; shareFiles(context, entry.files) }
                MenuRow("↻ grab again") { menu = false; Grabber.submit(entry.url) }
                MenuRow("⧉ copy link") {
                    menu = false
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(ClipData.newPlainText("link", entry.url))
                }
                MenuRow("✕ remove from list") { menu = false; History.remove(entry.id) }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label, style = Type.small, color = LocalPalette.current.fg) },
        onClick = onClick,
    )
}

// ───────────────────────────── probing ─────────────────────────────

@Composable
private fun ProbingPhase(phase: Phase.Probing) {
    FramedInput(
        title = phase.platform.label,
        value = phase.url,
        onValueChange = {},
        onSubmit = {},
        readOnly = true,
        buttonDim = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Gap(14.dp)
    SpinnerLine(phase.status)
    if (phase.status.startsWith("first run")) {
        GrayLine("one-time setup, takes a few seconds")
    }
}

// ───────────────────────────── picking ─────────────────────────────

@Composable
private fun PickingPhase(phase: Phase.Picking, pick: (DownloadChoice) -> Unit) {
    val palette = LocalPalette.current
    val info = phase.info
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        RemoteThumb(info.thumbnail, Modifier.width(128.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(info.title, style = Type.small.copy(fontWeight = FontWeight.Bold), color = palette.fg, maxLines = 4, overflow = TextOverflow.Ellipsis)
            Gap(6.dp)
            val meta = buildString {
                append("▸ ").append(phase.platform.label)
                if (info.entryCount > 0) append(" · ${info.entryCount} videos")
                info.duration?.let { formatDuration(it) }?.takeIf { it.isNotEmpty() }?.let { append(" · ").append(it) }
                info.uploader?.let { append(" · ").append(it) }
            }
            Text(meta, style = Type.tiny, color = palette.gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    Gap(20.dp)
    var cursor by remember { mutableIntStateOf(0) }
    TerminalFrame(
        title = "Download",
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        Column {
            phase.choices.forEachIndexed { index, choice ->
                ChoiceRow(choice, selected = index == cursor, onPress = { cursor = index }, onClick = { pick(choice) })
            }
        }
    }
    if (info.entryCount > 1) {
        Gap(8.dp)
        GrayLine("grabs all ${info.entryCount}, each lands in Download/Gimmie 'Dat")
    }
}

@Composable
private fun ChoiceRow(choice: DownloadChoice, selected: Boolean, onPress: () -> Unit, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) { if (pressed) onPress() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(if (pressed) palette.faint.copy(alpha = 0.45f) else palette.bg)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FixedWidth(22.dp) { Text(if (selected) "❯" else " ", style = Type.body, color = palette.fg) }
        Text(
            choice.display,
            style = Type.body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
            color = palette.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ───────────────────────────── downloading ─────────────────────────────

private fun partLabel(p: DownloadProgress) =
    // explains the bar resetting between files (video, then audio)
    if (p.totalParts > 1) "part ${p.part + 1}/${p.totalParts}  " else ""

private fun downloadMeta(p: DownloadProgress): String {
    val speed = p.speed?.let(::formatSpeed).orEmpty()
    val eta = p.eta?.let(::formatEta)?.takeIf { it.isNotEmpty() }?.let { "$it left" }.orEmpty()
    return "${partLabel(p)}${speed.padStart(10)}  ${eta.padEnd(12)}".trimEnd()
}

private fun indeterminateMeta(p: DownloadProgress): String {
    val bytes = formatBytes(p.downloadedBytes)
    val speed = p.speed?.let(::formatSpeed).orEmpty()
    return "${partLabel(p)}${bytes.padStart(8)}  ${speed.padEnd(10)}".trimEnd()
}

@Composable
private fun DownloadingPhase(phase: Phase.Downloading) {
    val palette = LocalPalette.current
    val info = phase.from.info
    RemoteThumb(info.thumbnail, Modifier.fillMaxWidth(0.82f))
    Gap(18.dp)
    Text(
        "${truncate(info.title, 64)} · ${phase.choice.label}",
        style = Type.small,
        color = palette.gray,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Gap(22.dp)
    if (phase.itemCount > 1) {
        Text("item ${phase.item} of ${phase.itemCount}", style = Type.small, color = palette.fg)
        Gap(8.dp)
    }
    // every branch is the same three rows, bar, gap, meta, so the layout never jumps
    val progress = phase.progress
    val total = progress?.totalBytes
    when {
        phase.saving -> {
            ProgressBlocks(1f, Modifier.fillMaxWidth())
            Gap(12.dp)
            SpinnerLine("saving to Download/Gimmie 'Dat…")
        }
        phase.processing -> {
            ProgressBlocks(1f, Modifier.fillMaxWidth())
            Gap(12.dp)
            SpinnerLine("processing…")
        }
        progress != null && total != null && total > 0 -> {
            ProgressBlocks((progress.downloadedBytes / total).toFloat(), Modifier.fillMaxWidth())
            Gap(12.dp)
            Text(downloadMeta(progress), style = Type.small, color = palette.gray)
        }
        progress != null -> {
            ProgressBlocks(null, Modifier.fillMaxWidth())
            Gap(12.dp)
            Text(indeterminateMeta(progress), style = Type.small, color = palette.gray)
        }
        else -> {
            ProgressBlocks(0f, Modifier.fillMaxWidth())
            Gap(12.dp)
            SpinnerLine(if (phase.refreshing) "link expired, grabbing a fresh one…" else "starting download…")
        }
    }
    Gap(30.dp)
    GrayLine("you can leave. gimmie 'dat keeps going in the background")
}

// ───────────────────────────── done / error ─────────────────────────────

@Composable
private fun DonePhase(phase: Phase.Done) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val queued by Grabber.queued.collectAsState()
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    val file = phase.files.first()
    val audio = phase.choice.kind == Kind.AUDIO
    FileThumb(file.uri, phase.from.info.thumbnail, Modifier.fillMaxWidth(0.82f), if (audio) "♪" else "▶")
    Gap(22.dp)
    Text("✓ got it!", style = Type.big, color = palette.fg)
    Gap(4.dp)
    Text(
        if (phase.files.size > 1) "${phase.files.size} files in:" else "find your file in:",
        style = Type.small,
        color = palette.fg,
    )
    Text(
        if (phase.files.size > 1) "Download/${MediaSaver.FOLDER}/" else file.path,
        style = Type.small,
        color = palette.gray,
        textAlign = TextAlign.Center,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    Gap(22.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TermButton(if (audio) "♪ play" else "▶ play", { openFile(context, file) })
        TermButton("⇪ share", { shareFiles(context, phase.files) })
    }
    Gap(12.dp)
    val next = queued
    TermButton(
        if (next != null) "↵ grab the next one" else "↵ grab another",
        onClick = { if (next != null) Grabber.startQueued() else Grabber.reset() },
        filled = true,
    )
    if (next != null) {
        Gap(6.dp)
        GrayLine(truncate(next, 48))
    }
}

@Composable
private fun FailedPhase(phase: Phase.Failed) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(Unit) { haptics.performHapticFeedback(HapticFeedbackType.Reject) }
    Text("✗ ${phase.message}", style = Type.body.copy(fontWeight = FontWeight.Bold), color = palette.fg, textAlign = TextAlign.Center)
    val lower = phase.message.lowercase()
    val hint = when {
        "sign in" in lower || "login" in lower || "log in" in lower || "private" in lower || "cookies" in lower ->
            "some posts need an account. gimmie 'dat can only grab public ones"
        "unsupported url" in lower -> "that site isn't one yt-dlp knows. try the post's own link"
        "connection" in lower || "reach" in lower -> "check wi-fi or data, then try again"
        // without auto-update a stale yt-dlp is the usual culprit, and only the user can refresh it
        !BuildConfig.YTDLP_AUTO_UPDATE -> "sites change often: ? about → ↻ update yt-dlp, then try again"
        else -> null
    }
    if (hint != null) {
        Gap(6.dp)
        GrayLine(hint)
    }
    Gap(20.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TermButton("↻ try again", { Grabber.retry() }, filled = true)
        TermButton("esc back", { Grabber.reset() })
    }
}

// ───────────────────────────── about ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSheet(onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val engine by Engine.status.collectAsState()
    val theme by Prefs.theme.collectAsState()
    val history by History.entries.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = palette.bg,
        contentColor = palette.fg,
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
        dragHandle = { Text("──────", style = Type.small, color = palette.line, modifier = Modifier.padding(top = 10.dp)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PixelLogo(cell = 6.dp)
            Gap(14.dp)
            Text("gimmie 'dat for android · v${BuildConfig.VERSION_NAME}", style = Type.small.copy(fontWeight = FontWeight.Bold), color = palette.fg)
            // source, releases and licenses all live in Gimmie 'Dat's own repo
            GrayLine("$REPO ↗") {
                openUrl(context, "https://$REPO")
            }
            Text("a pocket port of yoinks by pablo stanley", style = Type.tiny, color = palette.gray)
            Gap(14.dp)
            TerminalFrame("Engine", Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    EngineRow("yt-dlp", engine.ytdlpVersion ?: BuildConfig.YTDLP_BUNDLED)
                    EngineRow("python", "3.12")
                    EngineRow("ffmpeg", "bundled")
                    EngineRow("quickjs", "2025-04-26")
                    Gap(12.dp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (engine.updating) {
                            SpinnerLine("checking for a newer yt-dlp…")
                        } else {
                            TermButton("↻ update yt-dlp", {
                                scope.launch { runCatching { Engine.update() } }
                            })
                        }
                    }
                    engine.lastUpdateResult?.let {
                        Gap(6.dp)
                        Text(it, style = Type.tiny, color = palette.gray)
                    }
                    Gap(4.dp)
                    Text(
                        if (BuildConfig.YTDLP_AUTO_UPDATE) "checks by itself once a day, and again whenever a site stops working"
                        else "only updates when you tap it. if a site stops working, try this first",
                        style = Type.tiny,
                        color = palette.gray,
                    )
                }
            }
            Gap(14.dp)
            TerminalFrame("Theme", Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp)) {
                Column {
                    app.gimmiedat.data.ThemeMode.entries.forEach { mode ->
                        val selected = mode == theme
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { Prefs.setTheme(mode) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            FixedWidth(22.dp) { Text(if (selected) "❯" else " ", style = Type.body, color = palette.fg) }
                            Text(
                                when (mode) {
                                    app.gimmiedat.data.ThemeMode.AUTO -> "auto · follows your phone"
                                    app.gimmiedat.data.ThemeMode.LIGHT -> "light"
                                    app.gimmiedat.data.ThemeMode.DARK -> "dark"
                                },
                                style = Type.body.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal),
                                color = palette.fg,
                            )
                        }
                    }
                }
            }
            Gap(16.dp)
            Text(
                "files land in Download/Gimmie 'Dat, where Gallery, Files and your music app pick them up.",
                style = Type.tiny, color = palette.gray, textAlign = TextAlign.Center,
            )
            Gap(10.dp)
            Text(
                "gimmie 'dat is a personal-archiving tool. Downloading content may violate a platform's terms of service. Only download what you have the right to keep, and be excellent to creators.",
                style = Type.tiny, color = palette.gray, textAlign = TextAlign.Center,
            )
            if (history.isNotEmpty()) {
                Gap(16.dp)
                TermButton("✕ clear recent list", { History.clear() })
                Gap(4.dp)
                Text("(your files stay put)", style = Type.tiny, color = palette.gray)
            }
            Gap(24.dp)
        }
    }
}

@Composable
private fun EngineRow(name: String, value: String) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(name.padEnd(10), style = Type.small, color = palette.gray)
        Text(value, style = Type.small, color = palette.fg)
    }
}

// ───────────────────────────── actions ─────────────────────────────

fun openFile(context: Context, file: SavedFile) {
    if (!MediaSaver.exists(context, file.uri)) {
        Toast.makeText(context, "that file's gone. tap ⋯ then grab again", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(file.uri, file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "no app here can open ${file.mime}", Toast.LENGTH_SHORT).show()
    }
}

fun shareFiles(context: Context, files: List<SavedFile>) {
    val existing = files.filter { MediaSaver.exists(context, it.uri) }
    if (existing.isEmpty()) {
        Toast.makeText(context, "that file's gone. tap ⋯ then grab again", Toast.LENGTH_SHORT).show()
        return
    }
    val send = if (existing.size == 1) {
        Intent(Intent.ACTION_SEND).setType(existing[0].mime).putExtra(Intent.EXTRA_STREAM, existing[0].uri)
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(existing.map { it.uri }))
    }
    send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null))
}

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

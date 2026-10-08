package app.gimmiedat

import android.content.Context
import app.gimmiedat.data.History
import app.gimmiedat.data.HistoryEntry
import app.gimmiedat.data.MediaSaver
import app.gimmiedat.data.SavedFile
import app.gimmiedat.engine.DownloadChoice
import app.gimmiedat.engine.DownloadHandlers
import app.gimmiedat.engine.DownloadProgress
import app.gimmiedat.engine.Engine
import app.gimmiedat.engine.Platform
import app.gimmiedat.engine.VideoInfo
import app.gimmiedat.engine.GrabError
import app.gimmiedat.engine.buildChoices
import app.gimmiedat.engine.detectPlatform
import app.gimmiedat.engine.extractUrl
import app.gimmiedat.engine.isProbablyUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface Phase {
    data class Input(val warning: String? = null) : Phase

    data class Probing(val url: String, val platform: Platform, val status: String) : Phase

    data class Picking(
        val url: String,
        val platform: Platform,
        val info: VideoInfo,
        val choices: List<DownloadChoice>,
        val infoJson: File,
    ) : Phase

    data class Downloading(
        val from: Picking,
        val choice: DownloadChoice,
        val progress: DownloadProgress? = null,
        val processing: Boolean = false,
        val refreshing: Boolean = false,
        val saving: Boolean = false,
        val item: Int = 0,
        val itemCount: Int = 0,
    ) : Phase

    data class Done(val from: Picking, val choice: DownloadChoice, val files: List<SavedFile>) : Phase

    data class Failed(
        val url: String,
        val platform: Platform,
        val message: String,
        /** Set when the download (not the probe) failed, "try again" re-runs that step. */
        val retryPick: Pair<Picking, DownloadChoice>? = null,
    ) : Phase
}

/**
 * The whole app is one state machine, same as the CLI's <App/>: input → probing → picking →
 * downloading → done | error. It lives at process scope so a download keeps going (and keeps
 * its progress) when the activity is gone.
 */
object Grabber {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _phase = MutableStateFlow<Phase>(Phase.Input())
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    /** The text in the "Paste a link" box. */
    val input = MutableStateFlow("")

    /** A link that arrived while a download was running, offered once it's done. */
    private val _queued = MutableStateFlow<String?>(null)
    val queued: StateFlow<String?> = _queued.asStateFlow()

    val isDownloading get() = _phase.value is Phase.Downloading

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Text from the box, a share sheet or the clipboard. Returns false when nothing was started. */
    fun submit(raw: String): Boolean {
        val url = extractUrl(raw) ?: raw.trim()
        if (!isProbablyUrl(url)) {
            if (!isDownloading) _phase.value = Phase.Input(warning = "that doesn’t look like a link. paste a full url")
            return false
        }
        if (isDownloading) {
            _queued.value = url
            return false
        }
        startProbe(url)
        return true
    }

    private fun startProbe(url: String) {
        job?.cancel()
        input.value = url
        val platform = detectPlatform(url)
        _phase.value = Phase.Probing(url, platform, "warming up…")
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    Engine.probe(url) { status ->
                        _phase.update { if (it is Phase.Probing && it.url == url) it.copy(status = status) else it }
                    }
                }
                _phase.value = Phase.Picking(url, platform, result.info, buildChoices(result.info), result.infoJson)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _phase.value = Phase.Failed(url, platform, messageOf(e))
            }
        }
    }

    fun pick(choice: DownloadChoice) {
        val picking = _phase.value as? Phase.Picking ?: return
        startDownload(picking, choice, reuseInfo = true)
    }

    private fun startDownload(picking: Phase.Picking, choice: DownloadChoice, reuseInfo: Boolean) {
        job?.cancel()
        _phase.value = Phase.Downloading(picking, choice)
        DownloadService.start(app)

        fun patch(block: (Phase.Downloading) -> Phase.Downloading) =
            _phase.update { if (it is Phase.Downloading && it.from === picking) block(it) else it }

        val handlers = object : DownloadHandlers {
            override fun onProgress(progress: DownloadProgress) =
                patch { it.copy(progress = progress, processing = false) }

            override fun onProcessing() = patch { it.copy(processing = true) }
            override fun onItem(index: Int, count: Int) =
                patch { it.copy(item = index, itemCount = count, progress = null, processing = false) }
        }

        job = scope.launch {
            val workDir = File(app.cacheDir, "work/${System.currentTimeMillis()}")
            try {
                val files = withContext(Dispatchers.IO) {
                    workDir.mkdirs()
                    try {
                        // reuse the probe's metadata, starts immediately instead of re-extracting
                        val cached = picking.infoJson.takeIf { reuseInfo && it.exists() }
                        Engine.download(picking.url, cached, choice, workDir, handlers)
                    } catch (e: GrabError) {
                        // media urls in the cached info can expire, retry with a fresh extraction
                        if (!reuseInfo) throw e
                        workDir.deleteRecursively()
                        workDir.mkdirs()
                        patch { it.copy(progress = null, processing = false, refreshing = true) }
                        Engine.download(picking.url, null, choice, workDir, handlers)
                    }
                }
                patch { it.copy(processing = false, saving = true) }
                val saved = withContext(Dispatchers.IO) { files.map { MediaSaver.publish(app, it) } }
                History.add(
                    HistoryEntry(
                        id = "${System.currentTimeMillis()}",
                        url = picking.url,
                        title = picking.info.title,
                        platform = picking.platform.label,
                        label = choice.label.substringBefore(" · ~"),
                        kind = choice.kind,
                        thumbnail = picking.info.thumbnail,
                        files = saved,
                        time = System.currentTimeMillis(),
                    ),
                )
                _phase.value = Phase.Done(picking, choice, saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _phase.value = Phase.Failed(picking.url, picking.platform, messageOf(e), picking to choice)
            } finally {
                withContext(Dispatchers.IO + NonCancellable) { workDir.deleteRecursively() }
            }
        }
    }

    /** esc, stop whatever is running; keep the link around so a cancel isn't destructive. */
    fun cancel() {
        val url = when (val p = _phase.value) {
            is Phase.Probing -> p.url
            is Phase.Downloading -> p.from.url
            else -> null
        }
        job?.cancel()
        job = null
        _phase.value = Phase.Input()
        // a link shared mid-download is the one they want next
        val next = _queued.value ?: url
        _queued.value = null
        if (next != null) input.value = next
    }

    /** Back to the empty input, the CLI's resetToInput. */
    fun reset() {
        if (isDownloading) return
        job?.cancel()
        job = null
        input.value = _queued.value ?: ""
        _queued.value = null
        _phase.value = Phase.Input()
    }

    /** "grab the next one", the link that was shared while the last download ran. */
    fun startQueued() {
        val next = _queued.value ?: return
        if (isDownloading) return
        _queued.value = null
        startProbe(next)
    }

    fun retry() {
        val failed = _phase.value as? Phase.Failed ?: return
        val pick = failed.retryPick
        if (pick != null) startDownload(pick.first, pick.second, reuseInfo = false) else startProbe(failed.url)
    }

    fun clearWarning() {
        _phase.update { if (it is Phase.Input && it.warning != null) Phase.Input() else it }
    }

    private fun messageOf(e: Throwable): String {
        if (e !is GrabError) android.util.Log.e("gimmiedat", "unexpected failure", e)
        return when (e) {
            is GrabError -> e.message ?: "Something went wrong."
            is IOException -> e.message ?: "Couldn't save the file."
            else -> "Something broke inside gimmie 'dat (${e.javaClass.simpleName}${e.message?.let { ": $it" } ?: ""}). Try again."
        }
    }
}

package app.gimmiedat.engine

import android.content.Context
import android.util.Log
import app.gimmiedat.BuildConfig
import app.gimmiedat.data.Prefs
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A failure worth showing the user, already cleaned of yt-dlp noise. */
class GrabError(message: String) : Exception(message)

data class EngineStatus(
    val ready: Boolean = false,
    val ytdlpVersion: String? = null,
    val updating: Boolean = false,
    val lastUpdateResult: String? = null,
)

data class ProbeResult(val info: VideoInfo, val infoJson: File)

data class DownloadProgress(
    val downloadedBytes: Double,
    val totalBytes: Double?,
    val speed: Double?,
    val eta: Double?,
    val part: Int,
    /** How many files one item resolves to (video+audio merges are 2). */
    val totalParts: Int,
)

interface DownloadHandlers {
    fun onProgress(progress: DownloadProgress)
    fun onProcessing()
    /** Multi-video posts: "Downloading item 2 of 5". */
    fun onItem(index: Int, count: Int)
}

/**
 * yt-dlp, a python runtime, ffmpeg and QuickJS (for YouTube's JS challenges) all ship
 * inside the APK via youtubedl-android. This object owns their lifecycle: unpacking on
 * first run, keeping yt-dlp fresh, and running it with the CLI's arguments.
 */
object Engine {
    private const val TAG = "gimmiedat"
    private const val LIB_PREFS = "youtubedl-android" // youtubedl-android's own prefs file
    private const val UPDATE_EVERY_MS = 24L * 60 * 60 * 1000
    private const val RETRY_UPDATE_AFTER_MS = 60L * 60 * 1000

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initLock = Mutex()
    /** yt-dlp runs and yt-dlp self-updates must never overlap, an update swaps the binary. */
    private val runLock = Mutex()
    @Volatile private var initialized = false

    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val cacheDir get() = File(app.filesDir, "yt-dlp-cache")
    private val probeDir get() = File(app.cacheDir, "probe").apply { mkdirs() }

    val isFirstRun: Boolean
        get() = !File(app.noBackupFilesDir, "youtubedl-android/packages/python").exists()

    fun start(context: Context) {
        app = context.applicationContext
        scope.launch {
            runCatching { ensureReady {} }
            maybeAutoUpdate()
        }
    }

    suspend fun ensureReady(onStatus: (String) -> Unit) {
        if (initialized) return
        onStatus(if (isFirstRun) "first run: unpacking the engine…" else "warming up…")
        initLock.withLock {
            if (initialized) return
            withContext(Dispatchers.IO) {
                try {
                    syncBundledYtDlp()
                    YoutubeDL.getInstance().init(app)
                    FFmpeg.getInstance().init(app)
                    probeDir.listFiles()?.forEach { it.delete() }
                    File(app.cacheDir, "work").deleteRecursively() // leftovers from a killed process
                } catch (e: Throwable) {
                    Log.e(TAG, "engine init failed", e)
                    val cause = generateSequence(e) { it.cause }.last()
                    throw GrabError("Couldn't unpack the download engine. Is your storage full? (${cause.javaClass.simpleName}: ${cause.message})")
                }
            }
            initialized = true
            _status.update { it.copy(ready = true, ytdlpVersion = installedVersion()) }
        }
    }

    private fun libPrefs() = app.getSharedPreferences(LIB_PREFS, Context.MODE_PRIVATE)

    private fun installedVersion(): String? = libPrefs().getString("dlpVersion", null)

    /**
     * youtubedl-android only copies its raw yt-dlp on a fresh install. If this APK carries a
     * newer yt-dlp than the one already on disk (app update), swap it in and record its version
     * so the updater compares against the truth.
     */
    private fun syncBundledYtDlp() {
        val bundled = BuildConfig.YTDLP_BUNDLED
        val installed = installedVersion()
        if (installed == null || installed < bundled) {
            File(app.noBackupFilesDir, "youtubedl-android/yt-dlp").deleteRecursively()
            libPrefs().edit()
                .putString("dlpVersion", bundled)
                .putString("dlpVersionName", "yt-dlp $bundled")
                .commit()
            // a freshly built APK already carries the newest yt-dlp, no need to re-download it today
            if (Prefs.lastUpdateCheck == 0L) Prefs.lastUpdateCheck = BuildConfig.BUILD_TIME
        }
    }

    private suspend fun maybeAutoUpdate() {
        if (!BuildConfig.YTDLP_AUTO_UPDATE) return
        if (System.currentTimeMillis() - Prefs.lastUpdateCheck < UPDATE_EVERY_MS) return
        runCatching { update() }
    }

    /** Pulls the latest stable yt-dlp. Returns true if a new version was installed. */
    suspend fun update(): Boolean {
        ensureReady {}
        _status.update { it.copy(updating = true, lastUpdateResult = null) }
        try {
            val result = runLock.withLock {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                }
            }
            Prefs.lastUpdateCheck = System.currentTimeMillis()
            val updated = result == YoutubeDL.UpdateStatus.DONE
            _status.update {
                it.copy(
                    updating = false,
                    ytdlpVersion = installedVersion(),
                    lastUpdateResult = if (updated) "updated to ${installedVersion()}" else "already up to date",
                )
            }
            return updated
        } catch (e: Exception) {
            _status.update { it.copy(updating = false, lastUpdateResult = "couldn't reach github, try again later") }
            throw GrabError("Couldn't update yt-dlp. Check your connection and try again.")
        }
    }

    private fun baseOptions(request: YoutubeDLRequest) = request.apply {
        addOption("--no-warnings")
        addOption("--socket-timeout", "30")
        // keeps solved YouTube player challenges between runs, repeat probes are much faster
        addOption("--cache-dir", cacheDir.absolutePath)
    }

    suspend fun probe(url: String, onStatus: (String) -> Unit): ProbeResult {
        ensureReady(onStatus)
        onStatus("fetching video info…")
        return try {
            probeOnce(url)
        } catch (e: GrabError) {
            // Sites change and yt-dlp ships fixes weekly. If our copy might be stale, refresh and retry once.
            // Builds without auto-update (F-Droid) leave that to the user, the failure screen says how.
            val stale = System.currentTimeMillis() - Prefs.lastUpdateCheck > RETRY_UPDATE_AFTER_MS
            if (!BuildConfig.YTDLP_AUTO_UPDATE || !stale || looksLikeNetworkTrouble(e.message)) throw e
            onStatus("updating yt-dlp…")
            val updated = runCatching { update() }.getOrDefault(false)
            if (!updated) throw e
            onStatus("fetching video info…")
            probeOnce(url)
        }
    }

    private suspend fun probeOnce(url: String): ProbeResult {
        val request = baseOptions(YoutubeDLRequest(url)).apply {
            addOption("-J")
            addOption("--no-playlist")
            // playlists list their entries without resolving each one, fast, and the
            // download step resolves them anyway
            addOption("--flat-playlist")
        }
        val response = runLock.withLock { exec(request) }
        val info = try {
            parseVideoInfo(response.out)
        } catch (_: JSONException) {
            throw GrabError("Could not parse video info from yt-dlp.")
        }
        val infoJson = File(probeDir, "info-${System.nanoTime()}.json")
        withContext(Dispatchers.IO) { infoJson.writeText(response.out) }
        return ProbeResult(info, infoJson)
    }

    private const val PROGRESS_PREFIX = "GIMMIE|"
    private const val PROGRESS_TEMPLATE =
        "${PROGRESS_PREFIX}%(progress.downloaded_bytes)s|%(progress.total_bytes)s|%(progress.total_bytes_estimate)s|%(progress.speed)s|%(progress.eta)s"
    private val ITEM_LINE = Regex("""Downloading item (\d+) of (\d+)""")

    /**
     * Runs one download into [workDir] and returns every file yt-dlp finished there.
     * With [infoJson] the probe's metadata is reused, so it starts immediately.
     */
    suspend fun download(
        url: String,
        infoJson: File?,
        choice: DownloadChoice,
        workDir: File,
        handlers: DownloadHandlers,
    ): List<File> {
        ensureReady {}
        val request = baseOptions(YoutubeDLRequest(if (infoJson != null) emptyList() else listOf(url))).apply {
            if (infoJson != null) addOption("--load-info-json", infoJson.absolutePath)
            addCommands(choice.args)
            if (choice.kind == Kind.AUDIO) {
                addOption("--embed-metadata")
                addOption("--embed-thumbnail")
            }
            addOption("--no-playlist")
            addOption("--newline")
            // --print implies --quiet, which hides the progress lines and the
            // [Merger]/[ExtractAudio] lines the processing phase is detected from
            addOption("--no-quiet")
            addOption("--progress")
            addOption("--progress-template", "download:$PROGRESS_TEMPLATE")
            addOption("--print", "after_move:filepath")
            addOption("--no-simulate")
            addOption("--no-mtime")
            // shared storage is FAT-flavoured: keep names that survive the trip to Downloads
            addOption("--windows-filenames")
            addOption("-o", File(workDir, "%(playlist_index&{} - |)s%(title).60s.%(ext)s").absolutePath)
        }

        val root = workDir.absolutePath
        val files = mutableListOf<String>()
        var part = 0
        var totalParts = 1
        var lastDownloaded = 0.0

        runLock.withLock {
            exec(request) { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith(PROGRESS_PREFIX) -> {
                        val fields = line.removePrefix(PROGRESS_PREFIX).split('|')
                        val downloaded = toNumber(fields.getOrNull(0)) ?: 0.0
                        if (downloaded < lastDownloaded) part++
                        lastDownloaded = downloaded
                        handlers.onProgress(
                            DownloadProgress(
                                downloadedBytes = downloaded,
                                totalBytes = toNumber(fields.getOrNull(1)) ?: toNumber(fields.getOrNull(2)),
                                speed = toNumber(fields.getOrNull(3)),
                                eta = toNumber(fields.getOrNull(4)),
                                part = part,
                                totalParts = totalParts,
                            ),
                        )
                    }
                    "Downloading 1 format(s):" in line -> {
                        // "[info] xxx: Downloading 1 format(s): 395+251", each id is one file
                        totalParts = line.substringAfter("format(s):").trim().split('+').size
                        part = 0
                        lastDownloaded = 0.0
                    }
                    line.startsWith("[download] Downloading item ") -> {
                        ITEM_LINE.find(line)?.let { m ->
                            handlers.onItem(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                        }
                    }
                    line.startsWith("[Merger]") || line.startsWith("[ExtractAudio]") ||
                        line.startsWith("[EmbedThumbnail]") || line.startsWith("[Metadata]") ||
                        line.startsWith("[VideoConvertor]") || line.startsWith("[FixupM3u8]") -> handlers.onProcessing()
                    line.startsWith(root) -> files += line
                }
            }
        }

        val existing = files.distinct().map(::File).filter { it.isFile }
        if (existing.isEmpty()) throw GrabError("yt-dlp finished but no file came out. Try another format.")
        return existing
    }

    private fun toNumber(value: String?): Double? {
        if (value.isNullOrEmpty() || value == "NA" || value == "None") return null
        return value.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * Runs yt-dlp on a worker thread. Coroutine cancellation kills the process (and,
     * via youtubedl-android, its ffmpeg/quickjs children).
     */
    private suspend fun exec(request: YoutubeDLRequest, onLine: ((String) -> Unit)? = null): YoutubeDLResponse =
        suspendCancellableCoroutine { cont ->
            val id = UUID.randomUUID().toString()
            val callback: ((Float, Long, String) -> Unit)? = onLine?.let { cb -> { _, _, line -> cb(line) } }
            val worker = thread(name = "yt-dlp") {
                try {
                    cont.resume(YoutubeDL.getInstance().execute(request, id, false, callback))
                } catch (e: YoutubeDL.CanceledException) {
                    cont.resumeWithException(CancellationException("cancelled"))
                } catch (e: YoutubeDLException) {
                    cont.resumeWithException(GrabError(cleanYtDlpError(e.message)))
                } catch (e: Throwable) {
                    cont.resumeWithException(e)
                }
            }
            cont.invokeOnCancellation {
                // the process registers a beat after the thread starts, keep trying briefly
                thread(name = "yt-dlp-kill") {
                    repeat(40) {
                        if (YoutubeDL.getInstance().destroyProcessById(id) || !worker.isAlive) return@thread
                        Thread.sleep(100)
                    }
                }
            }
        }
}

private val NETWORK_HINTS = listOf(
    "Unable to download", "getaddrinfo", "Network is unreachable", "timed out",
    "Connection refused", "Connection reset", "No address associated", "Temporary failure",
)

internal fun looksLikeNetworkTrouble(message: String?): Boolean =
    message != null && NETWORK_HINTS.any { message.contains(it, ignoreCase = true) }

/** Last "ERROR:" line, minus "ERROR: [extractor] id:", the part a human wants to read. */
internal fun cleanYtDlpError(stderr: String?): String {
    val lines = stderr.orEmpty().lines().map { it.trim() }
    val last = lines.lastOrNull { it.startsWith("ERROR:") }
    if (last != null) {
        // extractor errors read "ERROR: [youtube] dQw4w9WgXcQ: Video unavailable"
        val message = last
            .replace(Regex("""^ERROR:\s*(\[[^\]]+\]\s*([\w-]+:\s+)?)?"""), "")
            .trim()
        if (looksLikeNetworkTrouble(message)) return "Couldn't reach the site. Check your connection and try again."
        if (message.isNotEmpty()) return message
    }
    val tail = lines.lastOrNull { it.isNotEmpty() && !it.startsWith("WARNING") }
    return if (tail != null) "yt-dlp: $tail" else "yt-dlp stopped unexpectedly. Try again."
}

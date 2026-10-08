package app.gimmiedat.engine

import org.json.JSONArray
import org.json.JSONObject

// Ported from the VideoInfo / buildChoices half of yoinks/src/lib/ytdlp.ts

data class RawFormat(
    val formatId: String,
    val ext: String?,
    val vcodec: String?,
    val acodec: String?,
    val height: Int?,
    val width: Int?,
    val abr: Double?,
    val tbr: Double?,
    val filesize: Double?,
    val filesizeApprox: Double?,
) {
    val hasVideo get() = vcodec != null && vcodec != "none"
    val hasAudio get() = acodec != null && acodec != "none"
    val size get() = filesize ?: filesizeApprox
}

data class VideoInfo(
    val id: String?,
    val title: String,
    val uploader: String?,
    val duration: Double?,
    val webpageUrl: String?,
    val extractorKey: String?,
    val thumbnail: String?,
    val formats: List<RawFormat>,
    /** > 0 when the link resolved to a playlist / multi-video post. */
    val entryCount: Int,
)

private fun JSONObject.str(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null

private fun JSONObject.num(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

private fun parseFormats(array: JSONArray?): List<RawFormat> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val f = array.optJSONObject(i) ?: return@mapNotNull null
        RawFormat(
            formatId = f.str("format_id") ?: return@mapNotNull null,
            ext = f.str("ext"),
            vcodec = f.str("vcodec"),
            acodec = f.str("acodec"),
            height = f.num("height")?.toInt(),
            width = f.num("width")?.toInt(),
            abr = f.num("abr"),
            tbr = f.num("tbr"),
            filesize = f.num("filesize"),
            filesizeApprox = f.num("filesize_approx"),
        )
    }
}

/** Thumbnail url: the top-level field, else the best entry of the thumbnails list. */
private fun JSONObject.bestThumbnail(): String? {
    str("thumbnail")?.let { return it }
    val thumbs = optJSONArray("thumbnails") ?: return null
    var best: String? = null
    var bestScore = Double.NEGATIVE_INFINITY
    for (i in 0 until thumbs.length()) {
        val t = thumbs.optJSONObject(i) ?: continue
        val url = t.str("url") ?: continue
        val score = t.num("preference") ?: ((t.num("width") ?: 0.0) * (t.num("height") ?: 0.0))
        if (score >= bestScore) {
            bestScore = score
            best = url
        }
    }
    return best
}

fun parseVideoInfo(json: String): VideoInfo {
    val o = JSONObject(json)
    val entries = o.optJSONArray("entries")
    val isPlaylist = o.str("_type") == "playlist" && entries != null
    // A multi-video post: formats live on the entries. Offer every height any entry has.
    val formats = if (isPlaylist) {
        (0 until entries!!.length()).flatMap { parseFormats(entries.optJSONObject(it)?.optJSONArray("formats")) }
    } else {
        parseFormats(o.optJSONArray("formats"))
    }
    val firstEntry = if (isPlaylist && entries!!.length() > 0) entries.optJSONObject(0) else null
    return VideoInfo(
        id = o.str("id"),
        title = o.str("title") ?: firstEntry?.str("title") ?: o.str("webpage_url") ?: "untitled",
        uploader = o.str("uploader") ?: o.str("channel") ?: o.str("uploader_id") ?: firstEntry?.str("uploader"),
        duration = if (isPlaylist) null else o.num("duration"),
        webpageUrl = o.str("webpage_url") ?: o.str("original_url"),
        extractorKey = o.str("extractor_key"),
        thumbnail = o.bestThumbnail() ?: firstEntry?.bestThumbnail(),
        formats = formats,
        entryCount = if (isPlaylist) (o.num("playlist_count")?.toInt() ?: entries!!.length()) else 0,
    )
}

enum class Kind { VIDEO, AUDIO }

data class DownloadChoice(
    val label: String,
    val kind: Kind,
    val args: List<String>,
    val sizeBytes: Double?,
) {
    /** "▶ 1080p · mp4 · ~80 MB" / "♪ audio only · mp3", the picker row. */
    val display get() = (if (kind == Kind.AUDIO) "♪ " else "▶ ") + label
}

private const val MAX_VIDEO_CHOICES = 8

fun buildChoices(info: VideoInfo): List<DownloadChoice> {
    val formats = info.formats
    val choices = mutableListOf<DownloadChoice>()
    // sizes are per-video; a playlist's total isn't knowable up front, so don't guess
    val showSizes = info.entryCount == 0

    // Sites don't always report sizes (YouTube often omits them for some clients). Fall back to
    // bitrate × duration; if even that's unknown, show no size rather than a misleading one.
    fun sizeOf(f: RawFormat): Double? =
        f.size ?: if (f.tbr != null && info.duration != null) f.tbr * 1000 / 8 * info.duration else null

    val audioOnly = formats.filter { it.hasAudio && !it.hasVideo }
    val bestAudio = audioOnly.maxByOrNull { it.abr ?: it.tbr ?: 0.0 }
    val audioSize = bestAudio?.let(::sizeOf)

    val videos = formats.filter { it.hasVideo && it.height != null }
    val heights = videos.mapNotNull { it.height }.distinct().sortedDescending()

    for (height in heights.take(MAX_VIDEO_CHOICES)) {
        val best = videos.filter { it.height == height }.maxByOrNull(::scoreVideo)!!
        val size = sizeOf(best)?.let { it + (if (best.hasAudio) 0.0 else audioSize ?: 0.0) } ?: 0.0
        val sizeLabel = if (showSizes && size > 0) " · ~${formatBytes(size)}" else ""
        choices += DownloadChoice(
            kind = Kind.VIDEO,
            label = "${height}p · mp4$sizeLabel",
            args = listOf(
                "-f", "bv*[height=$height]+ba/b[height=$height]/bv*[height<=$height]+ba/b",
                "--merge-output-format", "mp4",
            ),
            sizeBytes = size.takeIf { showSizes && it > 0 },
        )
    }

    if (choices.isEmpty()) {
        choices += DownloadChoice(
            kind = Kind.VIDEO,
            label = "best available · mp4",
            args = listOf("-f", "bv*+ba/b", "--merge-output-format", "mp4"),
            sizeBytes = null,
        )
    }

    val audioSizeLabel = if (showSizes && audioSize != null && audioSize > 0) " · ~${formatBytes(audioSize)}" else ""
    choices += DownloadChoice(
        kind = Kind.AUDIO,
        label = "audio only · mp3$audioSizeLabel",
        args = listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"),
        sizeBytes = audioSize.takeIf { showSizes },
    )
    return choices
}

private fun scoreVideo(f: RawFormat): Double {
    var score = f.tbr ?: 0.0
    if (f.ext == "mp4") score += 10_000
    if (f.vcodec?.startsWith("avc") == true) score += 5_000
    return score
}

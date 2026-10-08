package app.gimmiedat.engine

import java.net.URI

// Ported from yoinks/src/lib/platforms.ts

data class Platform(val key: String, val label: String)

private val PLATFORMS = listOf(
    listOf("youtube.com", "youtu.be", "music.youtube.com") to Platform("youtube", "YouTube"),
    listOf("x.com", "twitter.com") to Platform("x", "X / Twitter"),
    listOf("instagram.com") to Platform("instagram", "Instagram"),
    listOf("threads.net", "threads.com") to Platform("threads", "Threads"),
    listOf("tiktok.com") to Platform("tiktok", "TikTok"),
    listOf("vimeo.com") to Platform("vimeo", "Vimeo"),
    listOf("twitch.tv") to Platform("twitch", "Twitch"),
    listOf("reddit.com", "redd.it") to Platform("reddit", "Reddit"),
    listOf("facebook.com", "fb.watch") to Platform("facebook", "Facebook"),
    listOf("bsky.app") to Platform("bluesky", "Bluesky"),
    listOf("soundcloud.com") to Platform("soundcloud", "SoundCloud"),
)

fun detectPlatform(url: String): Platform {
    val hostname = try {
        URI(url.trim()).host?.lowercase()
    } catch (_: Exception) {
        null
    } ?: return Platform("unknown", "Unknown site")

    for ((hosts, platform) in PLATFORMS) {
        if (hosts.any { hostname == it || hostname.endsWith(".$it") }) return platform
    }
    return Platform("generic", hostname.removePrefix("www."))
}

fun isProbablyUrl(input: String): Boolean {
    val trimmed = input.trim()
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return false
    return try {
        val u = URI(trimmed)
        (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrEmpty()
    } catch (_: Exception) {
        false
    }
}

private val URL_IN_TEXT = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

/**
 * Apps rarely share a bare link, TikTok sends "Check out this video… https://vm.tiktok.com/x/",
 * others append a title. Pull the first http(s) url out and drop trailing punctuation.
 */
fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = URL_IN_TEXT.find(text)?.value ?: return null
    val cleaned = match.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '»', '…')
    return cleaned.takeIf { isProbablyUrl(it) }
}

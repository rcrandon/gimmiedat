package app.gimmiedat.engine

import java.util.Locale
import kotlin.math.roundToLong

// Ported from yoinks/src/lib/format.ts, same rounding, same labels.

fun formatBytes(bytes: Double): String {
    if (!bytes.isFinite() || bytes <= 0) return ""
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    val number = if (value >= 10 || unit == 0) value.roundToLong().toString()
    else String.format(Locale.US, "%.1f", value)
    return "$number ${units[unit]}"
}

fun formatBytes(bytes: Long): String = formatBytes(bytes.toDouble())

fun formatDuration(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0) return ""
    val s = seconds.roundToLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    val mm = if (h > 0) m.toString().padStart(2, '0') else m.toString()
    val ss = sec.toString().padStart(2, '0')
    return if (h > 0) "$h:$mm:$ss" else "$mm:$ss"
}

fun truncate(text: String, max: Int): String =
    if (text.length > max) text.take(max - 1) + "…" else text

fun formatSpeed(bytesPerSecond: Double): String {
    if (!bytesPerSecond.isFinite() || bytesPerSecond <= 0) return ""
    return "${formatBytes(bytesPerSecond)}/s"
}

fun formatEta(seconds: Double): String {
    if (!seconds.isFinite() || seconds <= 0) return ""
    return formatDuration(seconds)
}

/** "just now", "5m ago", "3h ago", "2d ago", then a date. */
fun formatAgo(thenMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val s = ((nowMillis - thenMillis) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        s < 7 * 86_400 -> "${s / 86_400}d ago"
        else -> java.text.SimpleDateFormat("MMM d", Locale.getDefault()).format(java.util.Date(thenMillis)).lowercase()
    }
}

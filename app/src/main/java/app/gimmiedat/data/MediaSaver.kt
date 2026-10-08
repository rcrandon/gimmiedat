package app.gimmiedat.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException

data class SavedFile(
    val uri: Uri,
    val name: String,
    /** Human path, e.g. "Download/Gimmie 'Dat/clip.mp4". */
    val path: String,
    val mime: String,
    val size: Long,
)

/**
 * Moves a finished file from Gimmie 'Dat's private work dir into the shared
 * Download/Gimmie 'Dat folder through MediaStore, no storage permission needed, and
 * the file shows up in Files, Gallery and music apps straight away.
 */
object MediaSaver {
    const val FOLDER = "Gimmie 'Dat"

    fun mimeFor(file: File): String {
        val ext = file.extension.lowercase()
        return when (ext) {
            "mp4", "m4v" -> "video/mp4"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus", "ogg" -> "audio/ogg"
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }
    }

    fun publish(context: Context, file: File): SavedFile {
        val resolver = context.contentResolver
        val mime = mimeFor(file)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values)
            ?: throw IOException("Couldn't create the file in Downloads.")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out, 1 shl 16) }
            } ?: throw IOException("Couldn't write to Downloads.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            val full = e.message?.contains("ENOSPC") == true || e.message?.contains("No space") == true
            throw IOException(if (full) "Your phone is out of space." else "Couldn't save to Downloads: ${e.message}", e)
        }
        // MediaStore renames on collisions ("clip (1).mp4"), report the name it actually used
        val finalName = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: file.name
        return SavedFile(uri, finalName, "Download/$FOLDER/$finalName", mime, file.length())
    }

    fun exists(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}

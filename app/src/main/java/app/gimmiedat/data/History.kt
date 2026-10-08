package app.gimmiedat.data

import android.content.Context
import android.net.Uri
import app.gimmiedat.engine.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HistoryEntry(
    val id: String,
    val url: String,
    val title: String,
    val platform: String,
    val label: String,
    val kind: Kind,
    val thumbnail: String?,
    val files: List<SavedFile>,
    val time: Long,
) {
    val totalSize get() = files.sumOf { it.size }
}

/** Past grabs, newest first, the phone version of the CLI's ↑ history. */
object History {
    private const val LIMIT = 50
    private lateinit var file: File
    private val _entries = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        _entries.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun add(entry: HistoryEntry) {
        _entries.value = (listOf(entry) + _entries.value.filter { it.id != entry.id }).take(LIMIT)
        persist()
    }

    @Synchronized
    fun remove(id: String) {
        _entries.value = _entries.value.filter { it.id != id }
        persist()
    }

    @Synchronized
    fun clear() {
        _entries.value = emptyList()
        persist()
    }

    private fun persist() {
        // history is a nicety, never let it break a download
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(serialize(_entries.value))
            tmp.renameTo(file)
        }
    }

    private fun serialize(list: List<HistoryEntry>): String = JSONArray().apply {
        list.forEach { e ->
            put(JSONObject().apply {
                put("id", e.id); put("url", e.url); put("title", e.title); put("platform", e.platform)
                put("label", e.label); put("kind", e.kind.name); put("thumbnail", e.thumbnail ?: JSONObject.NULL)
                put("time", e.time)
                put("files", JSONArray().apply {
                    e.files.forEach { f ->
                        put(JSONObject().apply {
                            put("uri", f.uri.toString()); put("name", f.name); put("path", f.path)
                            put("mime", f.mime); put("size", f.size)
                        })
                    }
                })
            })
        }
    }.toString()

    private fun parse(text: String): List<HistoryEntry> {
        val array = JSONArray(text)
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                val files = o.getJSONArray("files")
                HistoryEntry(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    title = o.getString("title"),
                    platform = o.optString("platform"),
                    label = o.optString("label"),
                    kind = Kind.valueOf(o.optString("kind", "VIDEO")),
                    thumbnail = if (o.isNull("thumbnail")) null else o.optString("thumbnail"),
                    files = (0 until files.length()).map { j ->
                        val f = files.getJSONObject(j)
                        SavedFile(
                            uri = Uri.parse(f.getString("uri")),
                            name = f.getString("name"),
                            path = f.getString("path"),
                            mime = f.getString("mime"),
                            size = f.optLong("size"),
                        )
                    },
                    time = o.getLong("time"),
                )
            }.getOrNull()
        }
    }
}

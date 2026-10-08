package app.gimmiedat.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    AUTO, LIGHT, DARK;

    val label get() = name.lowercase()
    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

object Prefs {
    private lateinit var prefs: SharedPreferences

    private val _theme = MutableStateFlow(ThemeMode.AUTO)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("gimmiedat", Context.MODE_PRIVATE)
        _theme.value = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.AUTO)
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    var lastUpdateCheck: Long
        get() = prefs.getLong("lastUpdateCheck", 0L)
        set(value) = prefs.edit().putLong("lastUpdateCheck", value).apply()

    var askedForNotifications: Boolean
        get() = prefs.getBoolean("askedForNotifications", false)
        set(value) = prefs.edit().putBoolean("askedForNotifications", value).apply()
}

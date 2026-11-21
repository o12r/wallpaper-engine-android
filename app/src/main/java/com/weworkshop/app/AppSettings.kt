package com.weworkshop.app

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.theme_system),
    LIGHT(R.string.theme_light),
    DARK(R.string.theme_dark),
    BLACK(R.string.theme_black),
}

/** Language choices; [tag] null means "follow system". */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    CHINESE("zh"),
}

/** App-wide user preferences. */
object AppSettings {
    private lateinit var app: Context
    private val prefs: SharedPreferences by lazy { app.getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val _theme = MutableStateFlow(ThemeMode.SYSTEM)
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    private val _ages = MutableStateFlow(setOf("Everyone"))
    val allowedAges: StateFlow<Set<String>> = _ages.asStateFlow()

    private val _filtersExpanded = MutableStateFlow(true)
    val filtersExpanded: StateFlow<Boolean> = _filtersExpanded.asStateFlow()

    /** How many workshop items download at the same time. */
    private val _concurrentDownloads = MutableStateFlow(3)
    val concurrentDownloads: StateFlow<Int> = _concurrentDownloads.asStateFlow()

    /** Parallel chunk connections used inside one item's download. */
    private val _chunkThreads = MutableStateFlow(8)
    val chunkThreads: StateFlow<Int> = _chunkThreads.asStateFlow()

    fun init(context: Context) {
        if (::app.isInitialized) return
        app = context.applicationContext
        _theme.value = runCatching { ThemeMode.valueOf(prefs.getString("theme", null)!!) }.getOrDefault(ThemeMode.SYSTEM)
        // Migrate the rating filter stored by older versions.
        val legacy = app.getSharedPreferences("browse", Context.MODE_PRIVATE).getStringSet("ages", null)
        _ages.value = (prefs.getStringSet("ages", null) ?: legacy ?: setOf("Everyone")).toSet()
        _filtersExpanded.value = prefs.getBoolean("filters_expanded", true)
        _concurrentDownloads.value = prefs.getInt("concurrent_downloads", 3).coerceIn(1, 5)
        _chunkThreads.value = prefs.getInt("chunk_threads", 8).coerceIn(2, 32)
    }

    fun setConcurrentDownloads(n: Int) {
        _concurrentDownloads.value = n.coerceIn(1, 5)
        prefs.edit().putInt("concurrent_downloads", _concurrentDownloads.value).apply()
    }

    fun setChunkThreads(n: Int) {
        _chunkThreads.value = n.coerceIn(2, 32)
        prefs.edit().putInt("chunk_threads", _chunkThreads.value).apply()
    }

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit().putString("theme", mode.name).apply()
    }

    fun setAllowedAges(ages: Set<String>) {
        _ages.value = ages
        prefs.edit().putStringSet("ages", ages).apply()
    }

    fun setFiltersExpanded(v: Boolean) {
        _filtersExpanded.value = v
        prefs.edit().putBoolean("filters_expanded", v).apply()
    }

    /** Per-app language uses the platform LocaleManager (Android 13+). */
    val languageSupported: Boolean get() = Build.VERSION.SDK_INT >= 33

    fun language(ctx: Context): AppLanguage {
        if (Build.VERSION.SDK_INT < 33) return AppLanguage.SYSTEM
        val locales = ctx.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return AppLanguage.SYSTEM
        return when (locales[0].language) {
            "zh" -> AppLanguage.CHINESE
            "en" -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    fun setLanguage(ctx: Context, lang: AppLanguage) {
        if (Build.VERSION.SDK_INT < 33) return
        ctx.getSystemService(LocaleManager::class.java).applicationLocales =
            lang.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
    }
}

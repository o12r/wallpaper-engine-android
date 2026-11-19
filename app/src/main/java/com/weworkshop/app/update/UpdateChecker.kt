package com.weworkshop.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.weworkshop.app.App
import com.weworkshop.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer APK and installs it through the system package installer. */
object UpdateChecker {
    const val REPO = "o12r/wallpaper-engine-android"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val AUTO_INTERVAL_MS = 12 * 60 * 60 * 1000L

    data class Release(
        val version: String,
        val tag: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val pageUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val percent: Int) : State
        data class NeedPermission(val release: Release) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Whether the update dialog should be showing (set by a found update or a user action). */
    private val _dialog = MutableStateFlow(false)
    val dialog: StateFlow<Boolean> = _dialog.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val prefs by lazy { App.instance.getSharedPreferences("update", Context.MODE_PRIVATE) }

    private val _autoCheck = MutableStateFlow(true)
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    val currentVersion: String by lazy {
        val ctx = App.instance
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"
    }

    fun init() {
        _autoCheck.value = prefs.getBoolean("auto", true)
    }

    fun setAutoCheck(v: Boolean) {
        _autoCheck.value = v
        prefs.edit().putBoolean("auto", v).apply()
    }

    /** Called on app start: quietly checks at most every 12 h and only pops up for unskipped versions. */
    fun autoCheck() {
        if (!_autoCheck.value) return
        val last = prefs.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < AUTO_INTERVAL_MS) return
        check(manual = false)
    }

    fun check(manual: Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Checking
            if (manual) _dialog.value = false
            try {
                val rel = fetchLatest()
                prefs.edit().putLong("last_check", System.currentTimeMillis()).apply()
                if (compareVersions(rel.version, currentVersion) > 0) {
                    _state.value = State.Available(rel)
                    if (manual || prefs.getString("skipped", null) != rel.version) _dialog.value = true
                } else {
                    _state.value = State.UpToDate
                }
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                if (manual) _dialog.value = false
            }
        }
    }

    fun dismiss() {
        _dialog.value = false
        val s = _state.value
        if (s is State.Failed || s is State.NeedPermission) {
            _state.value = s.releaseOrNull()?.let { State.Available(it) } ?: State.Idle
        }
    }

    fun skip(release: Release) {
        prefs.edit().putString("skipped", release.version).apply()
        _dialog.value = false
    }

    fun showDialog() {
        if (_state.value.releaseOrNull() != null) _dialog.value = true
    }

    fun downloadAndInstall(ctx: Context, release: Release) {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            _state.value = State.NeedPermission(release)
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                _state.value = State.Downloading(release, 0)
                val file = download(release)
                _state.value = State.Available(release)
                _dialog.value = false
                install(ctx.applicationContext, file)
            } catch (e: Exception) {
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName, release)
            }
        }
    }

    private fun State.releaseOrNull(): Release? = when (this) {
        is State.Available -> release
        is State.Downloading -> release
        is State.NeedPermission -> release
        is State.Failed -> release
        else -> null
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "WE-Workshop-Android/$currentVersion")
        }

    private fun fetchLatest(): Release {
        val conn = open(API).apply { setRequestProperty("Accept", "application/vnd.github+json") }
        try {
            if (conn.responseCode != 200) throw IOException("GitHub HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val assets = json.optJSONArray("assets")
            var apkUrl = ""
            var apkSize = 0L
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = a.getString("browser_download_url")
                    apkSize = a.optLong("size")
                    break
                }
            }
            return Release(
                version = tag.removePrefix("v").removePrefix("V"),
                tag = tag,
                notes = json.optString("body"),
                apkUrl = apkUrl,
                apkSize = apkSize,
                pageUrl = json.optString("html_url", "https://github.com/$REPO/releases"),
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun download(release: Release): File {
        if (release.apkUrl.isEmpty()) throw IOException(App.str(R.string.update_no_apk))
        val dir = File(App.instance.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "WE-Workshop-${release.version}.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            _state.value = State.Downloading(release, pct)
                        }
                    }
                    if (total > 0 && done != total) throw IOException("incomplete download")
                }
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun install(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Compares dotted numeric versions ("1.2.10" > "1.2.9"); non-numeric suffixes are ignored. */
    fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}

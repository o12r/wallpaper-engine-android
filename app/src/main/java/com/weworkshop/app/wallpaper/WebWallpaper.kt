package com.weworkshop.app.wallpaper

import android.annotation.SuppressLint
import android.app.Presentation
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import com.weworkshop.app.download.DownloadManager
import org.json.JSONObject
import java.io.File

/** Helpers for Wallpaper Engine "Web" wallpapers (an index.html page plus assets). */
object WebWallpaper {
    private const val PREFS = "web_wallpaper"
    const val KEY_PATH = "page_path"
    const val KEY_PRESET = "preset_dir"

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The page to load: project.json's "file", else index.html, else the shallowest .html file. */
    fun entryFile(id: Long): File? {
        val dir = DownloadManager.contentDir(id)
        val name = runCatching {
            JSONObject(File(dir, "project.json").readText().trimStart('\uFEFF')).optString("file")
        }.getOrNull()
        name?.takeIf { it.isNotBlank() }?.let { File(dir, it) }?.takeIf { it.isFile }?.let { return it }
        val html = DownloadManager.contentFiles(id).filter { it.extension.lowercase() in setOf("html", "htm") }
        return html.firstOrNull { it.name.equals("index.html", true) }
            ?: html.minByOrNull { it.absolutePath.length }
    }

    /**
     * What to run for [id]: its own page, or (for a preset) the page of the wallpaper it depends on plus the
     * preset's folder. Null if there is nothing to run (e.g. the dependency is not downloaded).
     */
    fun resolve(id: Long, type: String? = null): Pair<File, File?>? {
        // A preset's project.json has no usable "type", so decide by its "dependency" instead.
        val dep = dependencyId(id)
        if (dep == null) return if (type.equals("Web", true)) entryFile(id)?.let { it to null } else null
        val page = entryFile(dep) ?: return null
        return page to DownloadManager.contentDir(id)
    }

    /** Workshop id of the wallpaper a preset depends on (project.json "dependency"), or null. */
    fun dependencyId(id: Long): Long? = runCatching {
        JSONObject(File(DownloadManager.contentDir(id), "project.json").readText().trimStart('﻿'))
            .optString("dependency").toLongOrNull()
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(w: WebView) {
        w.setBackgroundColor(Color.BLACK)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
        }
    }

    fun component(ctx: Context) = ComponentName(ctx, WebWallpaperService::class.java)

    fun prepare(ctx: Context, page: File, preset: File? = null): Intent {
        prefs(ctx).edit().putString(KEY_PRESET, preset?.absolutePath).putString(KEY_PATH, page.absolutePath).commit()
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(ctx))
    }
}

/** Preview container: a plain GPU-composited WebView that runs the page at the phone's own viewport, like a browser. */
class VirtualWebView(context: Context) : FrameLayout(context) {
    val web = WebView(context)
    val music: WebMusicDriver

    init {
        setBackgroundColor(Color.BLACK)
        WebWallpaper.configure(web)
        addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        music = WebMusicDriver(web)
    }
}

/**
 * EXPERIMENTAL live wallpaper for Web wallpapers. The page runs in a WebView inside a Presentation window on a
 * private VirtualDisplay whose output surface is the wallpaper surface itself, so the WebView is composited by
 * the GPU like in an app (no CPU copy). If that is not possible it falls back to software drawing.
 */
class WebWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WebEngine()

    private inner class WebEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val main = Handler(Looper.getMainLooper())
        private val prefs = WebWallpaper.prefs(this@WebWallpaperService)
        private var web: WebView? = null
        private var music: WebMusicDriver? = null
        private var vd: VirtualDisplay? = null
        private var presentation: Presentation? = null
        private var software = false
        private var loaded: String? = null
        private var w = 0
        private var h = 0
        private var visible = false

        /** Colours reported to the system (desktop text, status bar), refreshed while visible. */
        @Volatile private var colors: WallpaperColors? = null

        /** Pages animate, so keep the reported colours in step with what is on screen. */
        private val colourTick = object : Runnable {
            override fun run() {
                readColors()
                if (visible) main.postDelayed(this, COLOUR_INTERVAL_MS)
            }
        }

        private val frame = object : Runnable {
            override fun run() {
                drawSoftware()
                if (visible && software) main.postDelayed(this, 33)
            }
        }

        /** Launchers and the system UI ask for these to decide text and status bar colours. */
        override fun onComputeColors(): WallpaperColors? = colors

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            if (key == WebWallpaper.KEY_PATH || key == WebWallpaper.KEY_PRESET) main.post { load() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (web != null && width == w && height == h) return
            w = width; h = height
            teardown()
            loaded = null
            if (!buildHardware(holder)) buildSoftware()
            load()
            if (visible) applyVisible(true)
        }

        private fun buildHardware(holder: SurfaceHolder): Boolean = try {
            val dpi = resources.displayMetrics.densityDpi
            val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val d = dm.createVirtualDisplay(
                "we-web", w, h, dpi, holder.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            ) ?: throw IllegalStateException("no virtual display")
            vd = d
            val view = WebView(createDisplayContext(d.display)).also { WebWallpaper.configure(it) }
            val pres = Presentation(this@WebWallpaperService, d.display)
            pres.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            pres.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            pres.show()
            presentation = pres
            web = view
            music = WebMusicDriver(view)
            software = false
            true
        } catch (t: Throwable) {
            Log.w("WebWallpaper", "virtual display failed, falling back to software", t)
            teardown()
            false
        }

        private fun buildSoftware() {
            web = WebView(applicationContext).also {
                WebWallpaper.configure(it)
                it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                it.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                it.layout(0, 0, w, h)
            }
            music = WebMusicDriver(web!!)
            software = true
        }

        private fun teardown() {
            main.removeCallbacks(frame)
            music?.stop(); music = null
            try { presentation?.dismiss() } catch (_: Throwable) {}
            presentation = null
            web?.destroy(); web = null
            vd?.release(); vd = null
        }

        private fun load() {
            val path = prefs.getString(WebWallpaper.KEY_PATH, null) ?: return
            val preset = prefs.getString(WebWallpaper.KEY_PRESET, null)
            if ("$path|$preset" == loaded) return
            val f = File(path)
            if (!f.isFile) return
            loaded = "$path|$preset"
            colors = null
            music?.load(f, preset?.let(::File))
        }

        private fun applyVisible(v: Boolean) {
            main.removeCallbacks(frame)
            main.removeCallbacks(colourTick)
            if (v) {
                web?.onResume(); web?.resumeTimers(); music?.start()
                if (software) main.post(frame)
                // Give the page a moment to paint before looking at it.
                main.postDelayed(colourTick, COLOUR_INTERVAL_MS / 2)
            } else {
                music?.stop(); web?.onPause(); web?.pauseTimers()
            }
        }

        /**
         * Draws the page small and reports how light it is, so the launcher can pick readable icon,
         * label and status bar colours. A page that renders nothing leaves the last result in place.
         */
        private fun readColors() {
            val view = web ?: return
            val width = w
            val height = h
            if (width <= 0 || height <= 0) return
            try {
                val bmp = Bitmap.createBitmap(
                    (width / COLOUR_DOWNSCALE).coerceAtLeast(1),
                    (height / COLOUR_DOWNSCALE).coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
                val canvas = Canvas(bmp)
                canvas.scale(1f / COLOUR_DOWNSCALE, 1f / COLOUR_DOWNSCALE)
                view.draw(canvas)
                val (base, top) = ColorReport.analyze(bmp)
                bmp.recycle()
                if (base == null) {
                    status("colours: page drew nothing")
                    return
                }
                val dark = ColorReport.wantsDarkText(top)
                colors = ColorReport.withTextHint(base, dark)
                status("colours top=%.2f darkText=$dark".format(top))
                notifyColorsChanged()
            } catch (t: Throwable) {
                Log.w("WebWallpaper", "colour readback failed", t)
                status("colours failed: ${t.message}")
            }
        }

        /** Phones drop app logs, so the last colour result is also kept on disk for diagnosis. */
        private fun status(msg: String) {
            runCatching { File(filesDir, "web_status.txt").appendText("${System.currentTimeMillis()} $msg" + System.lineSeparator()) }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            applyVisible(v)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            teardown()
            w = 0; h = 0
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            teardown()
            super.onDestroy()
        }

        private fun drawSoftware() {
            if (!software) return
            val view = web ?: return
            val holder = surfaceHolder
            val canvas = try { holder.lockCanvas() } catch (t: Throwable) { null } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                view.draw(canvas)
            } catch (t: Throwable) {
                Log.w("WebWallpaper", "draw failed", t)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }
    }

    private companion object {
        /** How often the reported colours are refreshed while the wallpaper is visible. */
        const val COLOUR_INTERVAL_MS = 15_000L

        /** The page is drawn at 1/[COLOUR_DOWNSCALE] size for the analysis. */
        const val COLOUR_DOWNSCALE = 8
    }
}

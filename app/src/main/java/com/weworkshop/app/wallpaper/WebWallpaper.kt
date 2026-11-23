package com.weworkshop.app.wallpaper

import android.annotation.SuppressLint
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.util.Log
import android.view.SurfaceHolder
import android.view.View
import android.webkit.WebView
import com.weworkshop.app.download.DownloadManager
import org.json.JSONObject
import java.io.File

/** Helpers for Wallpaper Engine "Web" wallpapers (an index.html page plus assets). */
object WebWallpaper {
    private const val PREFS = "web_wallpaper"
    const val KEY_PATH = "page_path"

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

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(w: WebView) {
        w.setBackgroundColor(Color.BLACK)
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mediaPlaybackRequiresUserGesture = false
        }
    }

    fun component(ctx: Context) = ComponentName(ctx, WebWallpaperService::class.java)

    fun prepare(ctx: Context, page: File): Intent {
        prefs(ctx).edit().putString(KEY_PATH, page.absolutePath).commit()
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(ctx))
    }
}

/**
 * EXPERIMENTAL live wallpaper that renders a Web wallpaper's page in an off-screen WebView and
 * paints it onto the wallpaper surface about 30 times a second.
 */
class WebWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = WebEngine()

    private inner class WebEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private val main = Handler(Looper.getMainLooper())
        private val prefs = WebWallpaper.prefs(this@WebWallpaperService)
        private var web: WebView? = null
        private var music: WebMusicDriver? = null
        private var loaded: String? = null
        private var w = 0
        private var h = 0
        private var visible = false

        private val frame = object : Runnable {
            override fun run() {
                draw()
                if (visible) main.postDelayed(this, 33)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            prefs.registerOnSharedPreferenceChangeListener(this)
        }

        override fun onSharedPreferenceChanged(p: SharedPreferences?, key: String?) {
            if (key == WebWallpaper.KEY_PATH) main.post { load() }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            w = width; h = height
            if (web == null) {
                web = WebView(applicationContext).also {
                    WebWallpaper.configure(it)
                    // Software layer so WebView.draw() can paint into the surface's canvas.
                    it.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }
                music = WebMusicDriver(web!!)
            }
            web?.apply {
                measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                layout(0, 0, w, h)
            }
            load()
        }

        private fun load() {
            val path = prefs.getString(WebWallpaper.KEY_PATH, null) ?: return
            if (path == loaded) return
            val f = File(path)
            if (!f.isFile) return
            loaded = path
            music?.load(f)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            main.removeCallbacks(frame)
            if (v) {
                web?.onResume(); web?.resumeTimers(); music?.start()
                main.post(frame)
            } else {
                music?.stop(); web?.onPause(); web?.pauseTimers()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            music?.stop()
            main.removeCallbacks(frame)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            main.removeCallbacks(frame)
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            music?.stop(); music = null
            web?.destroy(); web = null
            super.onDestroy()
        }

        private fun draw() {
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
}

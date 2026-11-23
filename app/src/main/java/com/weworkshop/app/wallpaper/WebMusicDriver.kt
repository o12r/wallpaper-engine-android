package com.weworkshop.app.wallpaper

import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.weworkshop.app.AppSettings
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Feeds a web wallpaper the same JavaScript API Wallpaper Engine's desktop runtime provides:
 * wallpaperRegisterAudioListener (128 floats, 64 left + 64 right) and the
 * wallpaperRegisterMedia{Status,Properties,Thumbnail,Playback,Timeline}Listener family.
 * The shim is injected into the page's HTML before its own scripts run.
 */
class WebMusicDriver(private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private var pageUrl: String? = null
    private var running = false
    private var lastVersion = -1
    private var lastSpectrum: FloatArray? = null
    private var tick = 0
    private var wasEnabled: Boolean? = null

    init {
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!request.isForMainFrame || request.url.toString() != pageUrl) return null
                val f = File(request.url.path ?: return null)
                if (!f.isFile) return null
                return try {
                    val html = injectInto(f.readText(Charsets.UTF_8))
                    WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
                } catch (_: Throwable) { null }
            }

            // Fallback for pages the interception didn't cover; the shim is idempotent.
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                view.evaluateJavascript(SHIM, null)
                lastVersion = -1; wasEnabled = null; lastSpectrum = null
            }
        }
    }

    fun load(file: File) {
        val url = Uri.fromFile(file).toString()
        pageUrl = url
        web.loadUrl(url)
    }

    fun start() {
        if (running) return
        running = true
        MusicBridge.acquire(web.context)
        main.post(loop)
    }

    fun stop() {
        if (!running) return
        running = false
        main.removeCallbacks(loop)
        MusicBridge.release()
    }

    private val loop = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            main.postDelayed(this, 33)
        }
    }

    private fun step() {
        tick++
        val enabled = AppSettings.musicReactive.value
        if (tick % 60 == 0) MusicBridge.ensure(web.context) // picks up permissions granted meanwhile
        val t = MusicBridge.track
        val status = enabled && t != null
        if (status != wasEnabled) {
            wasEnabled = status
            js("__weMedia('status',{enabled:$status})")
        }
        if (!enabled) return

        val spec = MusicBridge.spectrum
        if (lastSpectrum == null || !spec.contentEquals(lastSpectrum!!)) {
            lastSpectrum = spec
            val sb = StringBuilder("__weAudio([")
            for (i in spec.indices) { if (i > 0) sb.append(','); sb.append("%.3f".format(java.util.Locale.US, spec[i])) }
            js(sb.append("])").toString())
        }

        val v = MusicBridge.version
        if (v != lastVersion) {
            lastVersion = v
            if (t == null) {
                js("__weMedia('playback',{state:0})")
            } else {
                js("__weMedia('properties',${JSONObject().put("title", t.title).put("artist", t.artist).put("albumTitle", t.album).put("albumArtist", t.artist).put("subTitle", "").put("genres", "").put("contentType", "music")})")
                if (t.thumbnail != null) {
                    val c = t.color
                    val hex = "#%06X".format(c and 0xFFFFFF)
                    val dark = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 < 128
                    val text = if (dark) "#FFFFFF" else "#000000"
                    js("__weMedia('thumbnail',${JSONObject().put("thumbnail", t.thumbnail).put("primaryColor", hex).put("secondaryColor", hex).put("tertiaryColor", hex).put("textColor", text).put("highContrastColor", text)})")
                }
                js("__weMedia('playback',{state:${t.state}})")
            }
        }
        if (t != null && tick % 15 == 0) {
            js("__weMedia('timeline',{position:${t.positionNowMs() / 1000.0},duration:${t.durationMs / 1000.0}})")
        }
    }

    private fun js(code: String) {
        web.evaluateJavascript("window.__weMedia&&(function(){$code})()", null)
    }

    companion object {
        /** window.__weAudio(arr) / __weMedia(kind, obj) are called natively; pages register via wallpaperRegister*Listener. */
        const val SHIM = """(function(){if(window.__weShim)return;window.__weShim=true;
var L={audio:[],status:[],properties:[],thumbnail:[],playback:[],timeline:[]},last={};
function reg(k,cb){if(typeof cb!=='function')return;L[k].push(cb);if(last[k]!==undefined){try{cb(last[k])}catch(e){}}}
window.wallpaperRegisterAudioListener=function(cb){reg('audio',cb)};
window.wallpaperRegisterMediaStatusListener=function(cb){reg('status',cb)};
window.wallpaperRegisterMediaPropertiesListener=function(cb){reg('properties',cb)};
window.wallpaperRegisterMediaThumbnailListener=function(cb){reg('thumbnail',cb)};
window.wallpaperRegisterMediaPlaybackListener=function(cb){reg('playback',cb)};
window.wallpaperRegisterMediaTimelineListener=function(cb){reg('timeline',cb)};
function fire(k,v){last[k]=v;L[k].forEach(function(f){try{f(v)}catch(e){}})}
window.__weAudio=function(a){fire('audio',a)};
window.__weMedia=function(k,o){fire(k,o)};
window.__weMedia('status',{enabled:false});
})();"""

        private val HEAD = Regex("<head[^>]*>", RegexOption.IGNORE_CASE)
        private val HTML = Regex("<html[^>]*>", RegexOption.IGNORE_CASE)

        /** Puts the shim right after <head> (or <html>) so it runs before any page script. */
        fun injectInto(html: String): String {
            val tag = "<script>$SHIM</script>"
            val m = HEAD.find(html) ?: HTML.find(html)
            return if (m != null) html.substring(0, m.range.last + 1) + tag + html.substring(m.range.last + 1) else tag + html
        }
    }
}

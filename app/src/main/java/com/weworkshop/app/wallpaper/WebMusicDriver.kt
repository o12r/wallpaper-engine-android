package com.weworkshop.app.wallpaper

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.weworkshop.app.AppSettings
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * Loads a web wallpaper and feeds it the JavaScript API Wallpaper Engine's desktop runtime provides:
 * wallpaperRegisterAudioListener (128 floats, 64 left + 64 right), the wallpaperRegisterMedia*Listener family,
 * and wallpaperPropertyListener.applyUserProperties with the defaults from project.json.
 *
 * The page is served from a virtual https origin ([HOST]) backed by the wallpaper's folder instead of file://,
 * because file:// pages cannot fetch() / XHR their own assets ("Failed to fetch"). The shim is injected into the page's HTML before its own scripts run.
 */
class WebMusicDriver(private val web: WebView) {
    private val main = Handler(Looper.getMainLooper())
    private var root: File? = null
    private var presetDir: File? = null
    private var properties: String? = null
    private var running = false
    private var lastVersion = -1
    private var lastSpectrum: FloatArray? = null
    private var tick = 0
    private var wasEnabled: Boolean? = null

    init {
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val r = root ?: return null
                if (request.url.host != HOST || request.method != "GET") return null
                return serve(r, request.url.path.orEmpty(), request.requestHeaders["Range"], presetDir)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                lastVersion = -1; wasEnabled = null; lastSpectrum = null
            }

            override fun onPageFinished(view: WebView, url: String?) {
                val p = properties ?: return
                val call = "window.__weProps&&__weProps($p)"
                view.evaluateJavascript(call, null)
                // The page often registers its listener late / after assets load.
                for (d in longArrayOf(1000, 3000)) main.postDelayed({ view.evaluateJavascript(call, null) }, d)
            }
        }
    }

    /** [preset]: a preset's folder (its project.json "preset" values override the wallpaper's defaults). */
    fun load(file: File, preset: File? = null) {
        val r = rootOf(file)
        root = r
        presetDir = preset
        properties = readProperties(r, preset)
        val rel = file.relativeTo(r).path.replace(File.separatorChar, '/').split('/').joinToString("/") { android.net.Uri.encode(it) }
        web.loadUrl("https://$HOST/$rel")
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
        const val HOST = "we.local"

        /** window.__weAudio(arr) / __weMedia(kind, obj) are called natively; pages register via wallpaperRegister*Listener. */
        const val SHIM = """(function(){if(window.__weShim)return;window.__weShim=true;
// A <video> whose src was set to null (-> .../null) must stay invisible, as in Wallpaper Engine; Chrome would paint a grey error box over the page.
try{var st=document.createElement('style');st.textContent='video[src="null"],video[src=""],video[src$="/null"],video:not([src]){display:none!important}';(document.head||document.documentElement).appendChild(st)}catch(e){}
// Wallpapers size text as screenHeight/300*N, tuned for a landscape PC. On a portrait phone that is huge, so scale it down by width (relative to 16:9).
document.addEventListener('DOMContentLoaded',function(){try{var ids=['clock','oDate','weather'],busy=false;
function fix(e){if(busy)return;var w=innerWidth,h=innerHeight;if(w>=h)return;var px=parseFloat(e.style.fontSize);if(!(px>0))return;var k=w/(h*16/9);if(e.__k===e.style.fontSize)return;busy=true;e.style.fontSize=Math.max(1,Math.round(px*k))+'px';e.__k=e.style.fontSize;busy=false}
ids.forEach(function(i){var e=document.getElementById(i);if(!e)return;fix(e);new MutationObserver(function(){fix(e)}).observe(e,{attributes:true,attributeFilter:['style']})})}catch(e){}});
window.__weProps=function(p){try{var l=window.wallpaperPropertyListener;if(l&&l.applyUserProperties)l.applyUserProperties(p)}catch(e){}};
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

        /** Puts the shim right after <head> (or <html>) so they apply before any page script. */
        /** Pages build local-file URLs as 'file:///' + property value; point those at the virtual origin instead. */
        const val FILE_PREFIX = "__f/"
        private fun fixFileUrls(s: String) = if (s.contains("file:///")) s.replace("file:///", "https://$HOST/$FILE_PREFIX") else s

        fun injectInto(html: String): String {
            val clean = fixFileUrls(html)
            val tag = "<script>$SHIM</script>"
            val m = HEAD.find(clean) ?: HTML.find(clean)
            return if (m != null) clean.substring(0, m.range.last + 1) + tag + clean.substring(m.range.last + 1) else tag + clean
        }

        /** The wallpaper's folder: the nearest ancestor holding project.json, else the page's own folder. */
        fun rootOf(page: File): File {
            var d = page.parentFile
            var i = 0
            while (d != null && i < 8) {
                if (File(d, "project.json").isFile) return d
                d = d.parentFile; i++
            }
            return page.parentFile ?: page
        }

        /** project.json's general.properties, as the object WE passes to applyUserProperties. */
        fun readProperties(root: File, preset: File? = null): String? = runCatching {
            val j = JSONObject(File(root, "project.json").readText().trimStart('\uFEFF'))
            val props = j.optJSONObject("general")?.optJSONObject("properties") ?: return null
            val out = JSONObject()
            for (k in props.keys()) {
                val p = props.optJSONObject(k) ?: continue
                if (p.has("value")) out.put(k, JSONObject().put("value", p.get("value")).put("type", p.optString("type")).put("text", p.optString("text")))
            }
            val over = preset?.let {
                runCatching { JSONObject(File(it, "project.json").readText().trimStart('\uFEFF')).optJSONObject("preset") }.getOrNull()
            }
            if (over != null) for (k in over.keys()) {
                val v = over.opt(k) ?: continue
                val cur = out.optJSONObject(k)
                if (cur != null) cur.put("value", v) else out.put(k, JSONObject().put("value", v).put("type", "").put("text", ""))
            }
            out.toString()
        }.getOrNull()

        private val MIME = mapOf(
            "html" to "text/html", "htm" to "text/html", "js" to "text/javascript", "mjs" to "text/javascript",
            "css" to "text/css", "json" to "application/json", "wasm" to "application/wasm", "svg" to "image/svg+xml",
            "woff" to "font/woff", "woff2" to "font/woff2", "ttf" to "font/ttf", "otf" to "font/otf",
            "mp4" to "video/mp4", "webm" to "video/webm", "ogg" to "audio/ogg", "mp3" to "audio/mpeg", "wav" to "audio/wav",
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
            "txt" to "text/plain", "xml" to "text/xml", "glsl" to "text/plain", "frag" to "text/plain", "vert" to "text/plain",
        )

        private fun mimeOf(f: File): String {
            val ext = f.extension.lowercase()
            return MIME[ext] ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        private fun notFound() = WebResourceResponse(
            "text/plain", "utf-8", 404, "Not Found", mapOf("Access-Control-Allow-Origin" to "*"),
            ByteArrayInputStream(ByteArray(0)),
        )

        /** Serves a file under [root] for the virtual origin, with CORS and Range support (needed by <video> / <audio>). */
        fun serve(root: File, urlPath: String, range: String?, preset: File? = null): WebResourceResponse {
            val rel0 = urlPath.trimStart('/')
            val viaFileUrl = rel0.startsWith(FILE_PREFIX)
            val rel = if (viaFileUrl) rel0.removePrefix(FILE_PREFIX) else rel0
            // 'file:///x' lookups: the preset's own folder first, then the wallpaper it belongs to.
            val bases = if (viaFileUrl) listOfNotNull(preset, root) else listOf(root)
            var f: File? = null
            for (b in bases) {
                val base = runCatching { b.canonicalFile }.getOrNull() ?: continue
                val c = runCatching { File(base, rel).canonicalFile }.getOrNull() ?: continue
                if (c.path.startsWith(base.path) && c.isFile) { f = c; break }
            }
            if (f == null) return notFound()
            val mime = mimeOf(f)
            val text = mime.startsWith("text/") || mime == "application/json"
            val headers = mutableMapOf("Access-Control-Allow-Origin" to "*", "Accept-Ranges" to "bytes", "Cache-Control" to "no-cache")
            val enc = if (text) "utf-8" else null
            return try {
                if (mime == "text/html") {
                    val bytes = injectInto(f.readText(Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
                    return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(bytes))
                }
                if (mime == "text/javascript" && f.length() < 8_000_000) {
                    val src = f.readText(Charsets.UTF_8)
                    if (src.contains("file:///")) {
                        val bytes = fixFileUrls(src).toByteArray(Charsets.UTF_8)
                        return WebResourceResponse(mime, "utf-8", 200, "OK", headers, ByteArrayInputStream(bytes))
                    }
                }
                val len = f.length()
                val m = range?.let { Regex("bytes=(\\d*)-(\\d*)").matchEntire(it.trim()) }
                if (m != null && len > 0) {
                    val a = m.groupValues[1]; val b = m.groupValues[2]
                    val start = if (a.isEmpty()) (len - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) else a.toLong()
                    val end = if (a.isEmpty() || b.isEmpty()) len - 1 else b.toLong().coerceAtMost(len - 1)
                    if (start > end || start >= len) return WebResourceResponse(
                        mime, enc, 416, "Range Not Satisfiable", headers + ("Content-Range" to "bytes */$len"), ByteArrayInputStream(ByteArray(0)),
                    )
                    headers["Content-Range"] = "bytes $start-$end/$len"
                    headers["Content-Length"] = (end - start + 1).toString()
                    WebResourceResponse(mime, enc, 206, "Partial Content", headers, slice(f, start, end - start + 1))
                } else {
                    headers["Content-Length"] = len.toString()
                    WebResourceResponse(mime, enc, 200, "OK", headers, f.inputStream())
                }
            } catch (_: Throwable) { notFound() }
        }

        private fun slice(f: File, start: Long, length: Long): InputStream {
            val raf = RandomAccessFile(f, "r")
            raf.seek(start)
            return object : InputStream() {
                var left = length
                override fun read(): Int {
                    if (left <= 0) return -1
                    left--; return raf.read()
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left <= 0) return -1
                    val n = raf.read(b, off, minOf(len.toLong(), left).toInt())
                    if (n > 0) left -= n
                    return n
                }
                override fun close() = raf.close()
            }
        }
    }
}

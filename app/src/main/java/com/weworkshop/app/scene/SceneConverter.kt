package com.weworkshop.app.scene

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.util.Log
import com.weworkshop.app.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * EXPERIMENTAL: turns a Wallpaper Engine scene into a looping MP4 for the video wallpaper service.
 * Only image layers are drawn (shaders, effects, scripts and real particle systems are not);
 * they get gentle parallax and breathing motion, plus generic particles if the scene has any.
 */
object SceneConverter {
    private const val TAG = "SceneConverter"
    const val OUTPUT_NAME = "scene_live.mp4"
    private const val FPS = 30
    private const val SECONDS = 12
    private const val MAX_SIDE = 1920

    sealed interface State {
        data class Running(val progress: Float) : State
        data class Failed(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = mutableMapOf<Long, Job>()
    private val _states = MutableStateFlow<Map<Long, State>>(emptyMap())
    val states: StateFlow<Map<Long, State>> = _states.asStateFlow()

    fun output(id: Long) = File(DownloadManager.contentDir(id), OUTPUT_NAME)

    fun canConvert(id: Long): Boolean = DownloadManager.contentDir(id).let {
        File(it, "scene.pkg").isFile || File(it, "scene.json").isFile
    }

    @Synchronized
    fun convert(id: Long) {
        if (jobs[id]?.isActive == true) return
        _states.update { it + (id to State.Running(0f)) }
        jobs[id] = scope.launch {
            val out = output(id)
            val tmp = File(out.parentFile, "$OUTPUT_NAME.part")
            try {
                ScenePackage.open(DownloadManager.contentDir(id))?.use { pkg ->
                    val scene = loadScene(pkg)
                    when (val v = scene.video) {
                        null -> render(scene, tmp) { p -> _states.update { it + (id to State.Running(p)) } }
                        else -> tmp.writeBytes(v) // the scene is a video texture: use it as-is
                    }
                } ?: throw IOException("No scene.pkg / scene.json")
                if (!tmp.renameTo(out)) throw IOException("Cannot write ${out.path}")
                _states.update { it - id }
            } catch (t: Throwable) {
                tmp.delete()
                Log.e(TAG, "convert $id failed", t)
                _states.update { it + (id to State.Failed(t.message ?: t.javaClass.simpleName)) }
            }
        }
    }

    fun cancel(id: Long) {
        synchronized(this) { jobs.remove(id) }?.cancel()
        _states.update { it - id }
    }

    /** One still frame of the scene's static layers at t=0. Video-texture scenes return their first frame. */
    suspend fun still(id: Long, maxSide: Int = 1920): Bitmap = withContext(Dispatchers.Default) {
        val pkg = ScenePackage.open(DownloadManager.contentDir(id)) ?: throw IOException("No scene.pkg / scene.json")
        pkg.use {
            val scene = loadScene(pkg)
            val video = scene.video
            if (video != null) {
                val tmp = File.createTempFile("scene_still", ".mp4", DownloadManager.contentDir(id))
                val mmr = MediaMetadataRetriever()
                try {
                    tmp.writeBytes(video)
                    mmr.setDataSource(tmp.absolutePath)
                    mmr.getFrameAtTime(0) ?: throw IOException("Cannot decode first video frame")
                } finally {
                    runCatching { mmr.release() }
                    tmp.delete()
                }
            } else {
                try {
                    val k = min(1f, maxSide / max(scene.width, scene.height))
                    val outW = max(1, (scene.width * k).roundToInt())
                    val outH = max(1, (scene.height * k).roundToInt())
                    val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.drawColor(scene.clear)
                    canvas.scale(outW / scene.width, outH / scene.height)
                    // Same slight overscan as render().
                    canvas.scale(1.04f, 1.04f, scene.width / 2, scene.height / 2)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    val m = Matrix()
                    for (l in scene.layers) {
                        m.reset()
                        m.setScale(l.w / l.bitmap.width, l.h / l.bitmap.height)
                        m.postTranslate(-l.w / 2, -l.h / 2)
                        m.postRotate(l.angle)
                        m.postTranslate(l.x, l.y)
                        paint.alpha = (l.alpha * 255).toInt()
                        canvas.drawBitmap(l.bitmap, m, paint)
                    }
                    bmp
                } finally {
                    scene.layers.forEach { it.bitmap.recycle() }
                }
            }
        }
    }

    // region scene parsing

    private class Layer(
        val bitmap: Bitmap, val x: Float, val y: Float, val w: Float, val h: Float,
        val angle: Float, val alpha: Float, val depth: Float,
    )

    private class Scene(val width: Float, val height: Float, val clear: Int, val layers: List<Layer>, val particles: Boolean, val video: ByteArray?)

    /** WE values are numbers, "x y z" strings, or {"value": ...} when bound to a user property. */
    private fun raw(o: JSONObject, key: String): Any? = o.opt(key).let { if (it is JSONObject && it.has("value")) it.opt("value") else it }
    private fun vec(o: JSONObject, key: String, def: FloatArray): FloatArray {
        val s = raw(o, key) ?: return def
        if (s is Number) return FloatArray(def.size) { s.toFloat() }
        val p = s.toString().trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
        return FloatArray(def.size) { p.getOrNull(it) ?: def[it] }
    }
    private fun bool(o: JSONObject, key: String, def: Boolean) = raw(o, key)?.let { it == true || it.toString() == "true" || it.toString() == "1" } ?: def
    private fun num(o: JSONObject, key: String, def: Float) = (raw(o, key) as? Number)?.toFloat() ?: raw(o, key)?.toString()?.toFloatOrNull() ?: def

    private fun loadScene(pkg: ScenePackage): Scene {
        val json = JSONObject(pkg.readText("scene.json") ?: throw IOException("scene.json missing"))
        val general = json.optJSONObject("general") ?: JSONObject()
        val ortho = general.optJSONObject("orthogonalprojection")
        val objects = json.optJSONArray("objects") ?: JSONArray()
        val byId = (0 until objects.length()).mapNotNull { objects.optJSONObject(it) }.associateBy { it.optInt("id", -1) }

        // Absolute origin/scale, following "parent" links.
        fun absolute(o: JSONObject, guard: Int = 0): Pair<FloatArray, FloatArray> {
            val origin = vec(o, "origin", floatArrayOf(0f, 0f))
            val scale = vec(o, "scale", floatArrayOf(1f, 1f))
            val parent = byId[o.optInt("parent", -1)]
            if (parent == null || guard > 16) return origin to scale
            val (po, ps) = absolute(parent, guard + 1)
            return floatArrayOf(po[0] + origin[0] * ps[0], po[1] + origin[1] * ps[1]) to floatArrayOf(scale[0] * ps[0], scale[1] * ps[1])
        }

        var width = ortho?.optDouble("width", 0.0)?.toFloat() ?: 0f
        var height = ortho?.optDouble("height", 0.0)?.toFloat() ?: 0f
        val decoded = mutableListOf<Triple<JSONObject, JSONObject, TexImage>>()
        var particles = false
        for (i in 0 until objects.length()) {
            val o = objects.optJSONObject(i) ?: continue
            if (o.has("particle")) { if (bool(o, "visible", true)) particles = true; continue }
            val image = o.optString("image").takeIf { it.endsWith(".json") } ?: continue
            if (!bool(o, "visible", true)) continue
            try {
                val model = JSONObject(pkg.readText(image) ?: continue)
                val material = JSONObject(pkg.readText(model.optString("material")) ?: continue)
                val texName = material.optJSONArray("passes")?.optJSONObject(0)?.optJSONArray("textures")?.optString(0)
                    ?.takeIf { it.isNotBlank() && it != "null" } ?: continue
                val tex = pkg.read("materials/$texName.tex") ?: continue // built-in assets (util/white...) aren't packed
                decoded += Triple(o, model, TexDecoder.decode(tex, MAX_SIDE))
            } catch (t: Throwable) {
                Log.w(TAG, "skipping layer ${o.optString("name")}: ${t.message}")
            }
        }
        if (decoded.isEmpty()) throw IOException("No drawable image layers in this scene")

        // A scene that is just one big video texture: hand the MP4 over directly.
        decoded.map { it.third }.filterIsInstance<TexImage.Video>().maxByOrNull { it.width * it.height }?.let { v ->
            val stills = decoded.count { it.third is TexImage.Still }
            if (stills <= 1) return Scene(0f, 0f, 0, emptyList(), false, v.mp4)
        }

        val stillLayers = decoded.filter { it.third is TexImage.Still }
        if (width <= 0f || height <= 0f) {
            val b = (stillLayers.first().third as TexImage.Still).bitmap
            width = b.width.toFloat(); height = b.height.toFloat()
        }
        val n = stillLayers.size
        val layers = stillLayers.mapIndexed { idx, (o, model, tex) ->
            val bmp = (tex as TexImage.Still).bitmap
            val (origin, scale) = absolute(o)
            val fullscreen = model.optBoolean("fullscreen", false)
            val size = vec(o, "size", floatArrayOf(model.optDouble("width", bmp.width.toDouble()).toFloat(), model.optDouble("height", bmp.height.toDouble()).toFloat()))
            val pd = vec(o, "parallaxDepth", floatArrayOf(Float.NaN, Float.NaN))
            Layer(
                bitmap = bmp,
                x = if (fullscreen) width / 2 else origin[0],
                y = if (fullscreen) height / 2 else height - origin[1], // WE's y axis points up
                w = if (fullscreen) width else size[0] * scale[0],
                h = if (fullscreen) height else size[1] * scale[1],
                angle = -Math.toDegrees(vec(o, "angles", floatArrayOf(0f, 0f, 0f))[2].toDouble()).toFloat(),
                alpha = num(o, "alpha", 1f).coerceIn(0f, 1f),
                depth = if (!pd[0].isNaN()) pd[0].coerceIn(0f, 2f) else if (n > 1) idx / (n - 1f) else 0f,
            )
        }
        val c = vec(general, "clearcolor", floatArrayOf(0f, 0f, 0f))
        val clear = Color.rgb((c[0] * 255).toInt().coerceIn(0, 255), (c[1] * 255).toInt().coerceIn(0, 255), (c[2] * 255).toInt().coerceIn(0, 255))
        return Scene(width, height, clear, layers, particles, null)
    }

    // endregion

    // region rendering + encoding

    private suspend fun render(scene: Scene, out: File, progress: (Float) -> Unit) {
        val k = min(1f, MAX_SIDE / max(scene.width, scene.height))
        val outW = ((scene.width * k).roundToInt() / 16) * 16
        val outH = ((scene.height * k).roundToInt() / 16) * 16
        if (outW < 16 || outH < 16) throw IOException("Scene too small")

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, outW, outH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (outW * outH * FPS * 0.2).toInt().coerceAtMost(20_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var written = 0L
        val info = MediaCodec.BufferInfo()

        fun drain(end: Boolean) {
            while (true) {
                val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    i == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                    i >= 0 -> {
                        val buf = codec.getOutputBuffer(i)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            // Surface timestamps follow wall-clock; restamp to a steady frame rate.
                            info.presentationTimeUs = written++ * 1_000_000L / FPS
                            muxer.writeSampleData(track, buf, info)
                        }
                        codec.releaseOutputBuffer(i, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val rnd = Random(42)
        val parts = if (scene.particles) List(70) { FloatArray(6) { rnd.nextFloat() } } else emptyList()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val m = Matrix()
        val total = FPS * SECONDS
        try {
            for (f in 0 until total) {
                coroutineContext.ensureActive()
                val ph = (2 * PI * f / total).toFloat() // one full cycle per loop -> seamless
                val canvas: Canvas = surface.lockHardwareCanvas()
                try {
                    canvas.drawColor(scene.clear)
                    canvas.scale(outW / scene.width, outH / scene.height)
                    // Slight overscan so swaying back layers never reveal the edges.
                    canvas.scale(1.04f, 1.04f, scene.width / 2, scene.height / 2)
                    for ((i, l) in scene.layers.withIndex()) {
                        val amp = scene.width * 0.008f * l.depth
                        val breathe = 1f + 0.01f * (0.4f + l.depth) * sin(ph + i * 0.7f)
                        m.reset()
                        m.setScale(l.w / l.bitmap.width * breathe, l.h / l.bitmap.height * breathe)
                        m.postTranslate(-l.w * breathe / 2, -l.h * breathe / 2)
                        m.postRotate(l.angle)
                        m.postTranslate(l.x + amp * sin(ph), l.y + amp * 0.5f * sin(2 * ph))
                        paint.alpha = (l.alpha * 255).toInt()
                        canvas.drawBitmap(l.bitmap, m, paint)
                    }
                    for (p in parts) {
                        val speed = 1 + (p[2] * 2).toInt() // whole screens per loop
                        val y = ((p[1] - speed * f.toFloat() / total) % 1f + 1f) % 1f
                        val x = p[0] * scene.width + scene.width * 0.01f * sin(ph * (1 + (p[3] * 3).toInt()) + p[4] * 6f)
                        val r = scene.width * (0.0012f + 0.0025f * p[5])
                        val tw = 0.5f + 0.5f * sin(ph * 3 + p[4] * 6f)
                        dot.alpha = (40 * tw).toInt(); canvas.drawCircle(x, y * scene.height, r * 3, dot)
                        dot.alpha = (160 * tw + 40).toInt(); canvas.drawCircle(x, y * scene.height, r, dot)
                    }
                } finally {
                    surface.unlockCanvasAndPost(canvas)
                }
                drain(false)
                progress((f + 1f) / total)
            }
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            surface.release()
            if (track >= 0) runCatching { muxer.stop() }
            muxer.release()
            scene.layers.forEach { it.bitmap.recycle() }
        }
    }

    // endregion
}

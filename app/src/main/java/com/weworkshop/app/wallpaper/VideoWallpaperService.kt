package com.weworkshop.app.wallpaper

import android.app.WallpaperColors
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.exoplayer.ExoPlayer
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
import org.json.JSONObject
import java.io.File

/**
 * System live wallpaper ("WE 视频壁纸") that loops a downloaded Wallpaper Engine video.
 *
 * Shows up in the system's live-wallpaper picker; its settings screen
 * ([WallpaperSettingsActivity]) chooses which downloaded video plays.
 */
class VideoWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = VideoEngine()

    private inner class VideoEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener {
        private var player: ExoPlayer? = null
        private var loadedPath: String? = null
        private val prefs = WallpaperPrefs.prefs(this@VideoWallpaperService)
        private val main = Handler(Looper.getMainLooper())

        /** Colors of the current video, so the system picks dark status-bar text on light videos. */
        @Volatile private var colors: VideoColors? = null
        @Volatile private var colorsFor: String? = null

        /** Shade, blur, saturation and upscaling settings; read by the effects on the GL thread. */
        @Volatile private var look = WallpaperPrefs.look(this@VideoWallpaperService)
        private var builtStructure: List<Any>? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0

        override fun onComputeColors(): WallpaperColors? {
            val darkText = when (WallpaperPrefs.statusText(this@VideoWallpaperService)) {
                WallpaperPrefs.TEXT_DARK -> true
                WallpaperPrefs.TEXT_LIGHT -> false
                else -> colors?.darkText(look) ?: return null
            }
            return withTextHint(colors?.colors, darkText)
        }

        private fun notifyColors() {
            try { notifyColorsChanged() } catch (t: Throwable) { Log.w(TAG, "notifyColorsChanged failed", t) }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // The system asks for colors as soon as the engine exists (also every time Android
            // restarts the wallpaper). Answer from the saved analysis instead of "unknown",
            // which makes it fall back to white status-bar text.
            val f = WallpaperPrefs.resolveVideo(this@VideoWallpaperService) ?: return
            WallpaperPrefs.cachedColors(this@VideoWallpaperService, f)?.let {
                colors = it
                colorsFor = f.absolutePath + "|" + look.geometryKey
            }
        }

        private fun updateColors(f: File) {
            val key = f.absolutePath + "|" + look.geometryKey // what's behind the status bar
            if (key == colorsFor) return
            WallpaperPrefs.cachedColors(this@VideoWallpaperService, f)?.let {
                colors = it
                colorsFor = key
                notifyColors()
                return
            }
            colorsFor = key
            val ctx = this@VideoWallpaperService
            Thread({
                val c = analyze(ctx, f)
                main.post {
                    if (colorsFor != key) return@post
                    if (c == null) colorsFor = null // try again next time the video loads
                    colors = c
                    notifyColors()
                }
            }, "wallpaper-colors").start()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            val frame = holder.surfaceFrame
            surfaceWidth = frame.width()
            surfaceHeight = frame.height()
            buildPlayer()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (width == surfaceWidth && height == surfaceHeight) return
            surfaceWidth = width
            surfaceHeight = height
            if (builtStructure != null) buildPlayer() // effects render at the surface size
        }

        /**
         * (Re)creates the player. Effects are fixed when the player is prepared, so changing
         * blur, upscaling or turning shade/saturation on or off needs a new one; the shade and
         * saturation amounts are read on every frame.
         */
        @OptIn(UnstableApi::class)
        private fun buildPlayer() {
            val holder = surfaceHolder ?: return
            val position = player?.currentPosition ?: 0L
            player?.release()
            loadedPath = null
            builtStructure = wantedStructure()
            player = ExoPlayer.Builder(this@VideoWallpaperService).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                if (builtStructure != null) {
                    setVideoEffects(VideoEffects.build({ look }, surfaceWidth, surfaceHeight))
                    // The effects need the surface size, which ExoPlayer only tracks for holders.
                    setVideoSurfaceHolder(holder)
                } else {
                    setVideoSurface(holder.surface)
                }
            }
            applySettings()
            load()
            if (position > 0) player?.seekTo(position)
        }

        /** Effect chain the current look needs, or null to play without effects. */
        private fun wantedStructure(): List<Any>? =
            if (look.isDefault || surfaceWidth <= 0 || surfaceHeight <= 0) null else look.structure(surfaceWidth, surfaceHeight)

        private fun onLookChanged() {
            look = WallpaperPrefs.look(this@VideoWallpaperService)
            if (player != null && wantedStructure() != builtStructure) buildPlayer()
            loadedPath?.let { updateColors(File(it)) } // crop or rotation shows another part on top
            notifyColors() // the shade changes which text color is readable
        }

        private fun applySettings() {
            val p = player ?: return
            p.volume = if (WallpaperPrefs.muted(this@VideoWallpaperService)) 0f else 1f
            // Crop to fill. (MediaCodec's SCALE_TO_FIT stretches instead of letterboxing.)
            p.videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
        }

        private fun load() {
            val p = player ?: return
            val f = WallpaperPrefs.resolveVideo(this@VideoWallpaperService) ?: return
            if (f.absolutePath == loadedPath) return
            loadedPath = f.absolutePath
            updateColors(f)
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            p.prepare()
            p.playWhenReady = isVisible
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                WallpaperPrefs.KEY_PATH -> load()
                WallpaperPrefs.KEY_MUTED -> applySettings()
                WallpaperPrefs.KEY_STATUS_TEXT -> notifyColors()
                in WallpaperPrefs.LOOK_KEYS -> onLookChanged()
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) {
                load() // pick up a video downloaded while we were hidden
                // Re-state the colors each time we're shown, in case the launcher or system UI
                // lost them (e.g. after restarting). The system rate-limits this itself.
                notifyColors()
            }
            player?.playWhenReady = visible
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            player?.release()
            player = null
            loadedPath = null
            builtStructure = null
            super.onSurfaceDestroyed(holder)
        }
    }

    companion object {
        private const val TAG = "VideoWallpaper"
        private const val SAMPLE_HEIGHT = 160

        /**
         * Dark text is used when the strip behind the status bar averages at least this relative
         * luminance. WallpaperColors.fromBitmap only asks for dark text on almost entirely
         * bright images, which leaves white text on many light videos.
         */
        private const val DARK_TEXT_LUMINANCE = 0.4

        /** Share of the screen height behind the status bar. */
        private const val STATUS_BAR_SHARE = 0.06

        /** [topLuminance]: mean relative luminance (0..1) of the area behind the status bar. */
        class VideoColors(val colors: WallpaperColors?, val topLuminance: Double) {
            /** Whether status bar text should be dark, with [look]'s shade over the top. */
            fun darkText(look: WallpaperLook = WallpaperLook()): Boolean {
                val a = look.shade / 100.0
                val lum = topLuminance * (1 - a) + (if (look.shadeWhite) a else 0.0)
                return lum >= DARK_TEXT_LUMINANCE
            }
        }

        /** Analyzes [video] for this screen's shape and saves the result for later engines. */
        fun analyze(ctx: Context, video: File): VideoColors? {
            val dm = ctx.resources.displayMetrics
            val aspect = minOf(dm.widthPixels, dm.heightPixels).toFloat() / maxOf(dm.widthPixels, dm.heightPixels).coerceAtLeast(1)
            val look = WallpaperPrefs.look(ctx)
            return videoColors(video, aspect, look)?.also { WallpaperPrefs.saveColors(ctx, video, look, it) }
        }

        /**
         * Looks at a few frames of [video], each cropped to the screen's [aspect] and turned as
         * [look] says (what the wallpaper actually shows): their overall colors (for system theming) and whether the
         * area behind the status bar is light enough to need dark text.
         */
        fun videoColors(video: File, aspect: Float, look: WallpaperLook): VideoColors? {
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(video.absolutePath)
                val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val times = if (durationUs > 0) listOf(0.1, 0.4, 0.7).map { (durationUs * it).toLong() } else listOf(0L)
                val frames = times.mapNotNull {
                    mmr.getScaledFrameAtTime(it, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                }.map { asShown(it, aspect, look) }
                if (frames.isEmpty()) return null

                val topLuminance = frames.map { topLuminance(it) }.average()
                // Side by side at the same height, so no empty (black) areas skew the result.
                val scaled = frames.map {
                    Bitmap.createScaledBitmap(it, (SAMPLE_HEIGHT * it.width / it.height).coerceAtLeast(1), SAMPLE_HEIGHT, true)
                }
                val strip = Bitmap.createBitmap(scaled.sumOf { it.width }, SAMPLE_HEIGHT, Bitmap.Config.ARGB_8888)
                Canvas(strip).apply {
                    var x = 0f
                    for (b in scaled) { drawBitmap(b, x, 0f, null); x += b.width }
                }
                Log.i(TAG, "${video.name}: status bar luminance %.2f".format(topLuminance))
                VideoColors(WallpaperColors.fromBitmap(strip), topLuminance)
            } catch (t: Throwable) {
                Log.w(TAG, "couldn't compute colors for $video", t)
                null
            } finally {
                mmr.release()
            }
        }

        /** Mean relative luminance (0..1) of the strip of [b] that sits behind the status bar. */
        private fun topLuminance(b: Bitmap): Double {
            val h = (b.height * STATUS_BAR_SHARE).toInt().coerceIn(1, b.height)
            val px = IntArray(b.width * h)
            b.getPixels(px, 0, b.width, 0, 0, b.width, h)
            fun lin(c: Int): Double {
                val v = c / 255.0
                return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
            }
            return px.sumOf { 0.2126 * lin(Color.red(it)) + 0.7152 * lin(Color.green(it)) + 0.0722 * lin(Color.blue(it)) } / px.size
        }

        /**
         * [base] with the "supports dark text" hint set as asked. The constructor taking hints is
         * public from Android 12; before that, colors of a plain white or black image carry the
         * same hint (those versions have no wallpaper-based theming for the colors to affect).
         */
        fun withTextHint(base: WallpaperColors?, darkText: Boolean): WallpaperColors {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Some launchers (e.g. RedMagic/nubia) ignore the hint and pick the status bar
                // color from how light the primary color is, so make the primary agree with it:
                // the most fitting of the three colors, or a lighter/darker version of it.
                val palette = listOfNotNull(base?.primaryColor, base?.secondaryColor, base?.tertiaryColor)
                val primary = if (darkText) {
                    palette.firstOrNull { it.luminance() >= LIGHT_PRIMARY } ?: blend(palette.firstOrNull(), Color.WHITE, LIGHT_PRIMARY)
                } else {
                    palette.firstOrNull { it.luminance() <= DARK_PRIMARY } ?: blend(palette.firstOrNull(), Color.BLACK, DARK_PRIMARY)
                }
                val rest = palette.filter { it != primary }
                var hints = base?.colorHints ?: 0
                hints = if (darkText) {
                    (hints or WallpaperColors.HINT_SUPPORTS_DARK_TEXT) and WallpaperColors.HINT_SUPPORTS_DARK_THEME.inv()
                } else {
                    (hints or WallpaperColors.HINT_SUPPORTS_DARK_THEME) and WallpaperColors.HINT_SUPPORTS_DARK_TEXT.inv()
                }
                return WallpaperColors(primary, rest.getOrNull(0), rest.getOrNull(1), hints)
            }
            val solid = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            solid.eraseColor(if (darkText) Color.WHITE else Color.BLACK)
            return WallpaperColors.fromBitmap(solid)
        }

        /** Primary colors at least / at most this luminance count as light / dark. */
        private const val LIGHT_PRIMARY = 0.6f
        private const val DARK_PRIMARY = 0.15f

        /** Moves [from] toward [target] (white or black) until its luminance reaches [lum]. */
        private fun blend(from: Color?, target: Int, lum: Float): Color {
            val c = from ?: return Color.valueOf(target)
            val t = Color.valueOf(target)
            var mixed = c
            var f = 0f
            while (f < 1f) {
                f += 0.05f
                mixed = Color.valueOf(
                    c.red() + (t.red() - c.red()) * f,
                    c.green() + (t.green() - c.green()) * f,
                    c.blue() + (t.blue() - c.blue()) * f,
                )
                if (if (target == Color.WHITE) mixed.luminance() >= lum else mixed.luminance() <= lum) break
            }
            return mixed
        }

        /**
         * [b] as the wallpaper shows it on a screen of [aspect] (width / height): the same
         * crop, zoom, pan, flip and rotation as the GPU effects, sampled at low resolution.
         */
        private fun asShown(b: Bitmap, aspect: Float, look: WallpaperLook): Bitmap {
            if (b.width <= 0 || b.height <= 0) return b
            val outW = 108
            val outH = (outW / aspect).toInt().coerceAtLeast(1)
            val t = look.sourceTransform(b.width.toFloat() / b.height, aspect)
            val src = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
            val out = IntArray(outW * outH)
            for (y in 0 until outH) {
                val py = 1f - 2f * (y + 0.5f) / outH // y up, like GL
                for (x in 0 until outW) {
                    val px = 2f * (x + 0.5f) / outW - 1f
                    val sx = t[0] * px + t[1] * py + t[4]
                    val sy = t[2] * px + t[3] * py + t[5]
                    val ix = ((sx + 1f) / 2f * b.width).toInt().coerceIn(0, b.width - 1)
                    val iy = ((1f - sy) / 2f * b.height).toInt().coerceIn(0, b.height - 1)
                    out[y * outW + x] = src[iy * b.width + ix]
                }
            }
            return Bitmap.createBitmap(out, outW, outH, Bitmap.Config.ARGB_8888)
        }

        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "mov", "m4v")

        fun component(ctx: Context) = ComponentName(ctx, VideoWallpaperService::class.java)

        /** Intent that opens the system preview for this live wallpaper. */
        fun changeIntent(ctx: Context): Intent =
            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(ctx))

        /** Selects [video] and returns the intent that opens the system live-wallpaper preview. */
        fun prepare(ctx: Context, video: File): Intent {
            WallpaperPrefs.select(ctx, video)
            return changeIntent(ctx)
        }

        fun isActive(ctx: Context): Boolean =
            WallpaperManager.getInstance(ctx).wallpaperInfo?.component == component(ctx)
    }
}

object WallpaperPrefs {
    const val KEY_PATH = "video_path"
    const val KEY_MUTED = "muted"
    const val KEY_STATUS_TEXT = "status_text"
    const val TEXT_AUTO = "auto"
    const val TEXT_DARK = "dark"
    const val TEXT_LIGHT = "light"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("video_wallpaper", Context.MODE_PRIVATE)

    fun select(ctx: Context, video: File) {
        prefs(ctx).edit().putString(KEY_PATH, video.absolutePath).commit()
    }

    fun selectedPath(ctx: Context): String? = prefs(ctx).getString(KEY_PATH, null)
    fun muted(ctx: Context) = prefs(ctx).getBoolean(KEY_MUTED, true)
    fun setMuted(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_MUTED, v).apply()

    /** Status bar text color over the wallpaper: [TEXT_AUTO], [TEXT_DARK] or [TEXT_LIGHT]. */
    fun statusText(ctx: Context): String = prefs(ctx).getString(KEY_STATUS_TEXT, TEXT_AUTO) ?: TEXT_AUTO
    fun setStatusText(ctx: Context, v: String) = prefs(ctx).edit().putString(KEY_STATUS_TEXT, v).apply()

    const val KEY_SHADE = "shade"
    const val KEY_SHADE_WHITE = "shade_white"
    const val KEY_BLUR = "blur"
    const val KEY_SATURATION = "saturation"
    const val KEY_UPSCALE = "upscale"
    const val KEY_ROTATION = "rotation"
    const val KEY_FLIP_H = "flip_h"
    const val KEY_FLIP_V = "flip_v"
    const val KEY_ZOOM = "crop_zoom"
    const val KEY_PAN_X = "crop_x"
    const val KEY_PAN_Y = "crop_y"
    val LOOK_KEYS = setOf(KEY_SHADE, KEY_SHADE_WHITE, KEY_BLUR, KEY_SATURATION, KEY_UPSCALE, KEY_ROTATION, KEY_FLIP_H, KEY_FLIP_V, KEY_ZOOM, KEY_PAN_X, KEY_PAN_Y)

    fun look(ctx: Context): WallpaperLook = prefs(ctx).let {
        WallpaperLook(
            shade = it.getInt(KEY_SHADE, 0),
            shadeWhite = it.getBoolean(KEY_SHADE_WHITE, false),
            blur = it.getInt(KEY_BLUR, 0),
            saturation = it.getInt(KEY_SATURATION, 100),
            upscale = it.getBoolean(KEY_UPSCALE, false),
            rotation = it.getInt(KEY_ROTATION, 0),
            flipH = it.getBoolean(KEY_FLIP_H, false),
            flipV = it.getBoolean(KEY_FLIP_V, false),
            zoom = it.getFloat(KEY_ZOOM, 1f),
            panX = it.getFloat(KEY_PAN_X, 0f),
            panY = it.getFloat(KEY_PAN_Y, 0f),
        )
    }

    fun setLook(ctx: Context, l: WallpaperLook) = prefs(ctx).edit()
        .putInt(KEY_SHADE, l.shade).putInt(KEY_BLUR, l.blur).putInt(KEY_SATURATION, l.saturation)
        .putBoolean(KEY_UPSCALE, l.upscale).putInt(KEY_ROTATION, l.rotation)
        .putBoolean(KEY_FLIP_H, l.flipH).putBoolean(KEY_FLIP_V, l.flipV)
        .putFloat(KEY_ZOOM, l.zoom).putFloat(KEY_PAN_X, l.panX).putFloat(KEY_PAN_Y, l.panY)
        .putBoolean(KEY_SHADE_WHITE, l.shadeWhite).apply()

    private const val KEY_COLORS = "colors_cache"

    /** Saved analysis of [video], if it was made for this exact file (path, size and date). */
    fun cachedColors(ctx: Context, video: File): VideoWallpaperService.Companion.VideoColors? {
        val o = runCatching { JSONObject(prefs(ctx).getString(KEY_COLORS, null) ?: return null) }.getOrNull() ?: return null
        if (o.optString("path") != video.absolutePath || o.optLong("size") != video.length() ||
            o.optLong("mtime") != video.lastModified() || o.optString("geo") != look(ctx).geometryKey
        ) return null
        if (!o.has("lum")) return null // saved by an older version
        val colors = if (o.has("primary")) {
            val primary = Color.valueOf(o.getInt("primary"))
            val secondary = if (o.has("secondary")) Color.valueOf(o.getInt("secondary")) else null
            val tertiary = if (o.has("tertiary")) Color.valueOf(o.getInt("tertiary")) else null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                WallpaperColors(primary, secondary, tertiary, o.optInt("hints"))
            } else {
                WallpaperColors(primary, secondary, tertiary)
            }
        } else null
        return VideoWallpaperService.Companion.VideoColors(colors, o.getDouble("lum"))
    }

    fun saveColors(ctx: Context, video: File, look: WallpaperLook, c: VideoWallpaperService.Companion.VideoColors) {
        val o = JSONObject()
            .put("path", video.absolutePath).put("size", video.length()).put("mtime", video.lastModified())
            .put("lum", c.topLuminance).put("geo", look.geometryKey)
        c.colors?.let { wc ->
            o.put("primary", wc.primaryColor.toArgb())
            wc.secondaryColor?.let { o.put("secondary", it.toArgb()) }
            wc.tertiaryColor?.let { o.put("tertiary", it.toArgb()) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) o.put("hints", wc.colorHints)
        }
        prefs(ctx).edit().putString(KEY_COLORS, o.toString()).apply()
    }

    /** Selected video if it still exists, otherwise the most recently downloaded video. */
    fun resolveVideo(ctx: Context): File? {
        selectedPath(ctx)?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
        DownloadManager.init(ctx)
        return downloadedVideos().firstOrNull()?.second
    }

    /** (workshop id, largest video file) for every downloaded item, newest first. */
    fun downloadedVideos(): List<Pair<Long, File>> {
        val downloaded = (DownloadManager.rootDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .mapNotNull { it.name.toLongOrNull() }
        // Items found by scanning Download/WallpaperEngine live outside rootDir.
        val imported = DownloadManager.entries.value.values
            .filter { it.localDir != null && it.status is DownloadStatus.Done }
            .map { it.id }
        return (downloaded + imported).distinct()
            .map { it to DownloadManager.contentDir(it) }
            .sortedByDescending { it.second.lastModified() }
            .mapNotNull { (id, _) ->
                DownloadManager.contentFiles(id)
                    .filter { it.extension.lowercase() in VideoWallpaperService.VIDEO_EXTENSIONS }
                    .maxByOrNull { it.length() }
                    ?.let { id to it }
            }
    }
}

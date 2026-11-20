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
import androidx.media3.exoplayer.ExoPlayer
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.download.DownloadStatus
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

        override fun onComputeColors(): WallpaperColors? {
            val darkText = when (WallpaperPrefs.statusText(this@VideoWallpaperService)) {
                WallpaperPrefs.TEXT_DARK -> true
                WallpaperPrefs.TEXT_LIGHT -> false
                else -> colors?.darkText ?: return null
            }
            return withTextHint(colors?.colors, darkText)
        }

        private fun notifyColors() {
            try { notifyColorsChanged() } catch (t: Throwable) { Log.w(TAG, "notifyColorsChanged failed", t) }
        }

        private fun updateColors(f: File) {
            val key = f.absolutePath
            if (key == colorsFor) return
            colorsFor = key
            val dm = resources.displayMetrics
            val aspect = dm.widthPixels.toFloat() / dm.heightPixels.coerceAtLeast(1)
            Thread({
                val c = videoColors(f, aspect)
                main.post {
                    if (colorsFor != key) return@post
                    colors = c
                    notifyColors()
                }
            }, "wallpaper-colors").start()
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            prefs.registerOnSharedPreferenceChangeListener(this)
            player = ExoPlayer.Builder(this@VideoWallpaperService).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                setVideoSurface(holder.surface)
            }
            applySettings()
            load()
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
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            if (visible) load() // pick up a video downloaded while we were hidden
            player?.playWhenReady = visible
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            prefs.unregisterOnSharedPreferenceChangeListener(this)
            player?.release()
            player = null
            loadedPath = null
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

        class VideoColors(val colors: WallpaperColors?, val darkText: Boolean)

        /**
         * Looks at a few frames of [video], each center-cropped to the screen's [aspect] (what the
         * wallpaper actually shows): their overall colors (for system theming) and whether the
         * area behind the status bar is light enough to need dark text.
         */
        fun videoColors(video: File, aspect: Float): VideoColors? {
            val mmr = MediaMetadataRetriever()
            return try {
                mmr.setDataSource(video.absolutePath)
                val durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                val times = if (durationUs > 0) listOf(0.1, 0.4, 0.7).map { (durationUs * it).toLong() } else listOf(0L)
                val frames = times.mapNotNull {
                    mmr.getScaledFrameAtTime(it, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
                }.map { cropToAspect(it, aspect) }
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
                VideoColors(WallpaperColors.fromBitmap(strip), topLuminance >= DARK_TEXT_LUMINANCE)
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
                val primary = base?.primaryColor ?: Color.valueOf(if (darkText) Color.WHITE else Color.BLACK)
                var hints = base?.colorHints ?: 0
                hints = if (darkText) {
                    (hints or WallpaperColors.HINT_SUPPORTS_DARK_TEXT) and WallpaperColors.HINT_SUPPORTS_DARK_THEME.inv()
                } else {
                    hints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT.inv()
                }
                return WallpaperColors(primary, base?.secondaryColor, base?.tertiaryColor, hints)
            }
            val solid = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            solid.eraseColor(if (darkText) Color.WHITE else Color.BLACK)
            return WallpaperColors.fromBitmap(solid)
        }

        private fun cropToAspect(b: Bitmap, aspect: Float): Bitmap {
            if (b.width <= 0 || b.height <= 0) return b
            return if (b.width.toFloat() / b.height > aspect) {
                val w = (b.height * aspect).toInt().coerceIn(1, b.width)
                Bitmap.createBitmap(b, (b.width - w) / 2, 0, w, b.height)
            } else {
                val h = (b.width / aspect).toInt().coerceIn(1, b.height)
                Bitmap.createBitmap(b, 0, (b.height - h) / 2, b.width, h)
            }
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

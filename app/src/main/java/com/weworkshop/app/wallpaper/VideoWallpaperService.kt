package com.weworkshop.app.wallpaper

import android.app.WallpaperManager
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
            p.setMediaItem(MediaItem.fromUri(Uri.fromFile(f)))
            p.prepare()
            p.playWhenReady = isVisible
        }

        override fun onSharedPreferenceChanged(sp: SharedPreferences?, key: String?) {
            when (key) {
                WallpaperPrefs.KEY_PATH -> load()
                WallpaperPrefs.KEY_MUTED -> applySettings()
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

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("video_wallpaper", Context.MODE_PRIVATE)

    fun select(ctx: Context, video: File) {
        prefs(ctx).edit().putString(KEY_PATH, video.absolutePath).commit()
    }

    fun selectedPath(ctx: Context): String? = prefs(ctx).getString(KEY_PATH, null)
    fun muted(ctx: Context) = prefs(ctx).getBoolean(KEY_MUTED, true)
    fun setMuted(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_MUTED, v).apply()

    /** Selected video if it still exists, otherwise the most recently downloaded video. */
    fun resolveVideo(ctx: Context): File? {
        selectedPath(ctx)?.let { File(it) }?.takeIf { it.exists() }?.let { return it }
        DownloadManager.init(ctx)
        return downloadedVideos().firstOrNull()?.second
    }

    /** (workshop id, largest video file) for every downloaded item, newest first. */
    fun downloadedVideos(): List<Pair<Long, File>> =
        (DownloadManager.rootDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name.toLongOrNull() != null }
            .sortedByDescending { it.lastModified() }
            .mapNotNull { dir ->
                val id = dir.name.toLong()
                DownloadManager.contentFiles(id)
                    .filter { it.extension.lowercase() in VideoWallpaperService.VIDEO_EXTENSIONS }
                    .maxByOrNull { it.length() }
                    ?.let { id to it }
            }
}

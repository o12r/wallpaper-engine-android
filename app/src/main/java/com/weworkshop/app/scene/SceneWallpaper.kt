package com.weworkshop.app.scene

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.weworkshop.app.download.DownloadManager
import java.io.File

/** Settings and helpers for the native (GLES) Wallpaper Engine scene renderer. */
object SceneWallpaper {
    private const val PREFS = "scene_wallpaper"
    const val KEY_PROJECT = "project_dir"
    const val KEY_FPS = "fps"
    const val KEY_TILT = "tilt"
    const val KEY_TILT_STRENGTH = "tilt_strength"

    const val FPS_MIN = 10
    const val FPS_MAX = 60
    const val FPS_DEFAULT = 30
    const val STRENGTH_MIN = 25
    const val STRENGTH_MAX = 300
    const val STRENGTH_DEFAULT = 100

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun fps(ctx: Context) = prefs(ctx).getInt(KEY_FPS, FPS_DEFAULT).coerceIn(FPS_MIN, FPS_MAX)
    fun tilt(ctx: Context) = prefs(ctx).getBoolean(KEY_TILT, true)
    fun tiltStrength(ctx: Context) = prefs(ctx).getInt(KEY_TILT_STRENGTH, STRENGTH_DEFAULT).coerceIn(STRENGTH_MIN, STRENGTH_MAX)

    /**
     * Wallpaper Engine's own `assets` folder (shaders, effects, materials). It is copyrighted so it is never
     * bundled: the user copies it here or the app fetches it from their own Steam library.
     */
    fun assetsDir(ctx: Context): File {
        val external = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "we_assets")
        val internal = File(ctx.filesDir, "we_assets")
        // Files that adb pushed into the external folder can be unreadable for the app; the private copy always works.
        return if (!File(external, "shaders").isDirectory && File(internal, "shaders").isDirectory) internal else external
    }

    /** The folder only counts when it holds what the shaders need. */
    fun assetsReady(ctx: Context): Boolean = File(assetsDir(ctx), "shaders").isDirectory

    /** The scene project (folder with project.json / scene.pkg) of a downloaded item, or null if not a scene. */
    fun projectDir(id: Long): File? {
        val dir = DownloadManager.contentDir(id)
        return dir.takeIf { File(it, "scene.pkg").isFile || File(it, "scene.json").isFile }
    }

    fun component(ctx: Context) = ComponentName(ctx, SceneWallpaperService::class.java)

    fun prepare(ctx: Context, project: File): Intent {
        prefs(ctx).edit().putString(KEY_PROJECT, project.absolutePath).commit()
        return Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
            .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(ctx))
    }
}

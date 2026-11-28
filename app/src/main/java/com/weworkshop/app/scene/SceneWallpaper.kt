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

    /** How a desktop-shaped scene is framed on the phone, and which of its layers are left out. */
    data class Framing(val clamp: Boolean, val dx: Int, val zoom: Int, val hidden: Set<String>)

    private fun key(project: String, field: String) = "p.${File(project).name}.$field"

    fun hasOverride(ctx: Context, project: String) = prefs(ctx).getBoolean(key(project, "own"), false)
    fun manualHidden(ctx: Context, project: String): Set<String> =
        prefs(ctx).getStringSet(key(project, "hidden"), emptySet()) ?: emptySet()

    /** Global values, or the wallpaper's own when it overrides them. */
    fun framing(ctx: Context, project: String, own: Boolean = hasOverride(ctx, project)): Framing {
        val p = prefs(ctx)
        fun k(field: String) = if (own) key(project, field) else "g.$field"
        return Framing(
            clamp = p.getBoolean(k("clamp"), true),
            dx = p.getInt(k("dx"), 0).coerceIn(-100, 100),
            zoom = p.getInt(k("zoom"), 100).coerceIn(50, 200),
            hidden = manualHidden(ctx, project),
        )
    }

    fun setOverride(ctx: Context, project: String, on: Boolean) {
        val e = prefs(ctx).edit()
        if (on) {
            // Start from what is in force now, so switching on changes nothing by itself.
            val g = framing(ctx, project, false)
            e.putBoolean(key(project, "clamp"), g.clamp)
                .putInt(key(project, "dx"), g.dx).putInt(key(project, "zoom"), g.zoom)
        }
        e.putBoolean(key(project, "own"), on).apply()
    }

    /** Writes one framing value to the global slot or, when [project] overrides, to the wallpaper's own. */
    fun put(ctx: Context, project: String?, field: String, value: Any) {
        val name = if (project != null && hasOverride(ctx, project)) key(project, field) else "g.$field"
        val e = prefs(ctx).edit()
        when (value) { is Boolean -> e.putBoolean(name, value); is Int -> e.putInt(name, value) }
        e.apply()
    }

    fun setHidden(ctx: Context, project: String, names: Set<String>) {
        prefs(ctx).edit().putStringSet(key(project, "hidden"), names).apply()
    }

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

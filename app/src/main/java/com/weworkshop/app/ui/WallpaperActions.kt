package com.weworkshop.app.ui

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.weworkshop.app.R
import com.weworkshop.app.wallpaper.VideoWallpaperService
import com.weworkshop.app.wallpaper.WebWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The three "put this wallpaper on the phone" actions, shared by the detail screen.
 *
 * There is no in-app preview: the system live-wallpaper picker previews the live ones itself
 * (it runs the real [android.service.wallpaper.WallpaperService] before the user applies it),
 * and a static image needs no preview at all.
 */

fun setWebWallpaper(ctx: Context, page: File, preset: File? = null) {
    val intent = WebWallpaper.prepare(ctx, page, preset)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(ctx, ctx.getString(R.string.pick_in_list), Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.live_unsupported), Toast.LENGTH_LONG).show()
        }
    }
}

fun setVideoWallpaper(ctx: Context, video: File) {
    val intent = VideoWallpaperService.prepare(ctx, video)
    try {
        ctx.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            Toast.makeText(ctx, ctx.getString(R.string.pick_in_list), Toast.LENGTH_LONG).show()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, ctx.getString(R.string.live_unsupported), Toast.LENGTH_LONG).show()
        }
    }
}

suspend fun setStaticWallpaper(ctx: Context, image: File) {
    val ok = withContext(Dispatchers.IO) {
        runCatching {
            image.inputStream().use {
                WallpaperManager.getInstance(ctx).setStream(
                    it, null, true, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                )
            }
        }.isSuccess
    }
    Toast.makeText(ctx, ctx.getString(if (ok) R.string.wallpaper_set else R.string.wallpaper_set_failed), Toast.LENGTH_SHORT).show()
}

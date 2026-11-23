package com.weworkshop.app.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import com.weworkshop.app.R
import com.weworkshop.app.download.DownloadManager
import com.weworkshop.app.wallpaper.VideoWallpaperService
import com.weworkshop.app.wallpaper.WebWallpaper
import java.io.File

/** Opens / shares a downloaded wallpaper's main file with other apps. */
object ExternalOpen {
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp", "gif")

    /** The file that best represents the wallpaper: video, else web entry page, else image, else the biggest file. */
    fun mainFile(id: Long): File? {
        val files = DownloadManager.contentFiles(id)
        return files.filter { it.extension.lowercase() in VideoWallpaperService.VIDEO_EXTENSIONS }.maxByOrNull { it.length() }
            ?: WebWallpaper.entryFile(id)
            ?: files.filter { it.extension.lowercase() in IMAGES }.maxByOrNull { it.length() }
            ?: files.maxByOrNull { it.length() }
    }

    private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    fun open(ctx: Context, id: Long) = launch(ctx, id) { uri, f ->
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(f))
    }

    fun share(ctx: Context, id: Long) = launch(ctx, id) { uri, f ->
        Intent(Intent.ACTION_SEND).setType(mime(f)).putExtra(Intent.EXTRA_STREAM, uri)
    }

    private fun launch(ctx: Context, id: Long, build: (android.net.Uri, File) -> Intent) {
        val f = mainFile(id)
        if (f == null) {
            Toast.makeText(ctx, R.string.open_no_file, Toast.LENGTH_SHORT).show(); return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
            val intent = build(uri, f).apply {
                clipData = ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, R.string.open_no_app, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Toast.makeText(ctx, ctx.getString(R.string.open_failed, t.message ?: t.javaClass.simpleName), Toast.LENGTH_LONG).show()
        }
    }
}

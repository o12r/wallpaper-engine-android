package com.weworkshop.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.WallpaperManager
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weworkshop.app.R
import com.weworkshop.app.scene.NativeScene
import com.weworkshop.app.scene.SceneWallpaper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun setSceneWallpaper(ctx: Context, project: File) {
    val intent = SceneWallpaper.prepare(ctx, project)
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

/** Explains what the native renderer needs (assets folder) and what it cannot draw for this scene yet. */
@Composable
fun SceneNativeNotes(ctx: Context, project: File) {
    val issues by produceState<List<String>?>(null, project) {
        value = withContext(Dispatchers.IO) {
            if (!NativeScene.available) emptyList()
            else NativeScene.check(project.absolutePath, SceneWallpaper.assetsDir(ctx).takeIf { it.isDirectory }?.absolutePath)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val small = MaterialTheme.typography.bodySmall
        val dim = MaterialTheme.colorScheme.onSurfaceVariant
        if (!NativeScene.available) {
            Text(stringResource(R.string.scene_native_unavailable), style = small, color = MaterialTheme.colorScheme.error)
            return@Column
        }
        Text(stringResource(R.string.scene_native_note), style = small, color = dim)
        if (!SceneWallpaper.assetsReady(ctx)) {
            Text(
                stringResource(R.string.scene_assets_missing, SceneWallpaper.assetsDir(ctx).absolutePath),
                style = small, color = MaterialTheme.colorScheme.error,
            )
        }
        val list = issues.orEmpty()
        val err = list.firstOrNull { it.startsWith("ERROR:") }
        if (err != null) {
            Text(stringResource(R.string.scene_cannot_open, err.removePrefix("ERROR:").trim()), style = small, color = MaterialTheme.colorScheme.error)
        } else if (list.isNotEmpty()) {
            Text(stringResource(R.string.scene_unsupported, list.joinToString(", ")), style = small, color = dim)
        }
    }
}

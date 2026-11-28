package com.weworkshop.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.app.WallpaperManager
import android.widget.Toast
import android.content.Intent as AndroidIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.weworkshop.app.R
import com.weworkshop.app.scene.NativeScene
import com.weworkshop.app.scene.SceneAssetsImporter
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
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    runCatching {
                        ctx.contentResolver.takePersistableUriPermission(uri, AndroidIntent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    SceneAssetsImporter.import(ctx, uri)
                }
            }
            val importState by SceneAssetsImporter.state.collectAsState()
            OutlinedButton(
                onClick = { picker.launch(null) },
                enabled = importState !is SceneAssetsImporter.State.Running,
            ) { Text(stringResource(R.string.scene_assets_import)) }
            when (val st = importState) {
                is SceneAssetsImporter.State.Running ->
                    Text(stringResource(R.string.scene_assets_importing, st.done), style = small, color = dim)
                is SceneAssetsImporter.State.Done ->
                    Text(stringResource(R.string.scene_assets_imported, st.count), style = small, color = dim)
                is SceneAssetsImporter.State.Failed ->
                    Text(stringResource(R.string.scene_assets_import_failed, st.msg), style = small, color = MaterialTheme.colorScheme.error)
                else -> {}
            }
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


/** Framing controls: the global ones (project == null) or one wallpaper's own, with its layer list. */
@Composable
fun SceneFrameControls(ctx: Context, project: File?) {
    val path = project?.absolutePath
    var rev by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val own = path != null && androidx.compose.runtime.remember(rev) { SceneWallpaper.hasOverride(ctx, path) }
    val f = androidx.compose.runtime.remember(rev, own) { SceneWallpaper.framing(ctx, path ?: "", own) }
    val names by produceState<List<String>>(emptyList(), path) {
        value = if (path == null) emptyList() else withContext(Dispatchers.IO) {
            NativeScene.layerNames(path, SceneWallpaper.assetsDir(ctx).takeIf { it.isDirectory }?.absolutePath)
        }
    }
    fun set(field: String, v: Any) { SceneWallpaper.put(ctx, path, field, v); rev++ }
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (path != null) {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(androidx.compose.ui.Modifier.weight(1f)) {
                    Text(stringResource(R.string.frame_own))
                    Text(stringResource(R.string.frame_own_info), style = small, color = dim)
                }
                androidx.compose.material3.Switch(checked = own, onCheckedChange = { SceneWallpaper.setOverride(ctx, path, it); rev++ })
            }
        }
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(androidx.compose.ui.Modifier.weight(1f)) {
                Text(stringResource(R.string.frame_clamp))
                Text(stringResource(R.string.frame_clamp_info), style = small, color = dim)
            }
            androidx.compose.material3.Switch(checked = f.clamp, onCheckedChange = { set("clamp", it) })
        }
        FrameSlider(R.string.frame_shift, f.dx, -100..100, 0, own) { set("dx", it) }
        FrameSlider(R.string.frame_zoom, f.zoom, 50..200, 100, own) { set("zoom", it) }
        if (path != null && names.isNotEmpty()) {
            var open by androidx.compose.runtime.remember(path) { androidx.compose.runtime.mutableStateOf(false) }
            androidx.compose.material3.TextButton(onClick = { open = !open }) {
                Text(stringResource(R.string.frame_layers) + " (" + names.count { it in f.hidden } + "/" + names.size + ")" + if (open) "  ▲" else "  ▼")
            }
            if (open) names.forEach { n ->
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(n in f.hidden, onCheckedChange = {
                        SceneWallpaper.setHidden(ctx, path, if (it) f.hidden + n else f.hidden - n); rev++
                    })
                    Text(n, style = small)
                }
            }
        }
    }
}

/** A labelled slider whose value can also be tapped and typed, with a reset to [default]. */
@Composable
private fun FrameSlider(label: Int, value: Int, range: IntRange, default: Int, key: Any, onCommit: (Int) -> Unit) {
    var live by androidx.compose.runtime.remember(value, key) { androidx.compose.runtime.mutableFloatStateOf(value.toFloat()) }
    var editing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        androidx.compose.material3.TextButton(onClick = { editing = true }) {
            Text(stringResource(label, live.toInt()))
        }
        androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.weight(1f))
        androidx.compose.material3.TextButton(onClick = { live = default.toFloat(); onCommit(default) }, enabled = live.toInt() != default) {
            Text(stringResource(R.string.frame_reset))
        }
    }
    androidx.compose.material3.Slider(live, { live = it }, valueRange = range.first.toFloat()..range.last.toFloat(),
        onValueChangeFinished = { onCommit(live.toInt()) })
    if (editing) {
        var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(value.toString()) }
        val parsed = text.trim().toIntOrNull()
        val valid = parsed != null && parsed in range
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.frame_enter_value, range.first, range.last)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true, isError = !valid,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(enabled = valid, onClick = { live = parsed!!.toFloat(); onCommit(parsed); editing = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { editing = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}
